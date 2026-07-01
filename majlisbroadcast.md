# CLAUDE.md — MajlisBroadcast

> Build brief for Claude Code. This file is the single source of truth for the project.
> Read it fully at the start of every session. Build strictly in the order in **Build Plan**.
> Do not skip ahead. Do not build the whole app in one pass. One phase per working block.

---

## 0. Decisions to confirm before Phase 1 (defaults chosen — change if wrong)

- **Bilingual:** Urdu + English from day one. **RTL layout** for Urdu, LTR for English. All
  user-facing text goes through a string/resource system from the first screen — never hardcode
  display strings. (Retrofitting localization later is expensive; doing it now is nearly free.)
- **Server details screen:** **view-only** read-only panel showing the configured host/mount
  (never the password). Config stays immutable and baked in. No editing UI.
- **Live listener count:** **deferred to the end** (Phase 7). It is a separate read from the
  AzuraCast now-playing API and touches nothing in the streaming pipeline.

If the user wants any of these changed, adjust before starting Phase 1.

---

## 1. What this app is

A **personal** Android app (Kotlin) that live-broadcasts audio from the **phone's built-in
microphone** to a **self-hosted AzuraCast station** (Liquidsoap harbor). The owner uses it to
broadcast a **weekly spiritual lecture (majlis)** to listeners who hear it via the AzuraCast
player embedded on a website.

- Single-station, single-user, **no redistribution**, **not** for the Play Store.
- Built-in mic only. No USB/line-in.
- Usage conditions the app MUST handle (these are real, not hypothetical):
  - Phone is **unplugged** during lectures (battery/doze resilience required).
  - Owner may **switch networks** mid-broadcast (Wi-Fi <-> cellular). A **~5 second** self-healing
    reconnect gap is acceptable — do NOT attempt seamless zero-gap handover (out of scope).
  - Owner may use **Bluetooth**.
  - Owner **receives phone calls** during lectures and wants listeners to hear **silence**, not a
    disconnect.

### Core reframe
This is primarily a **streaming-protocol client**, not an audio app. Capture + AAC encoding are the
easy parts. The real work is ADTS framing, Icecast protocol correctness, AzuraCast/Liquidsoap
compatibility, realtime pacing, TLS/SNI, and background survivability on OEM Android.

---

## 2. Secrets & Git hygiene (do this FIRST, before any commit)

- The repo is **PUBLIC**. Server host, mount, and the source password **MUST NEVER** be committed.
- Store real credentials in a **local, git-ignored** file (e.g. `local.properties` or a dedicated
  `secrets.properties`), read at build time. Committed code references them; never contains them.
- Ensure `.gitignore` excludes: Android build output, `local.properties`, any `secrets*` file,
  keystores (`*.jks`, `*.keystore`), and IDE files.
- If a credential is ever committed by accident: stop, tell the user, and advise rotating the
  AzuraCast source password (history scrubbing alone is not sufficient).

---

## 3. Architecture

Foreground `Service` (typed `microphone`) running this pipeline:

```
AudioRecord (PCM, UNPROCESSED source, blocking reads, oversized buffer)
  -> MediaCodec (AAC-LC, CBR, 64 kbps, mono, 44.1kHz; SYNCHRONOUS mode, dedicated thread)
  -> consume codec-config buffer, wrap each frame in ADTS
  -> bounded drop-oldest queue of WHOLE ADTS frames
  -> coalesced writes
  -> raw SSLSocket (explicit SNI) -> AzuraCast harbor
```

### Threading & capture
- **Synchronous** `MediaCodec` (`dequeueInputBuffer`/`dequeueOutputBuffer`) on a **dedicated
  encoder thread**. Do NOT use async `MediaCodec.Callback` (three-way thread sync is a time sink).
- Audio source: `MediaRecorder.AudioSource.UNPROCESSED` (fallback `CAMCORDER`). **Never `MIC`**
  (its telephony AGC/high-pass makes voice sound thin).
- Capture reads: `AudioRecord.read(buf, size, AudioRecord.READ_BLOCKING)`. Never busy-loop.
- `AudioRecord` buffer: multiply `getMinBufferSize()` by **2-3x** for a real-time cushion.
- Capture/encode thread at `THREAD_PRIORITY_AUDIO`, with structured cancellation and clean shutdown.
- **Encoder/socket decoupling:** the encoder drain loop must release output buffers promptly
  regardless of socket state — a stalled socket must never block `releaseOutputBuffer`
  (MediaTek/Unisoc output-pool exhaustion). The bounded queue absorbs backpressure.

### Config (immutable, baked in)
Host, port, mount, username, password, TLS mode stored once (from the git-ignored secrets file).
No editable settings UI. Startup path: open app -> tap Go Live -> stream.

---

## 4. Protocol (hard requirements — all verified in Phase 0 before app code)

- `HTTP/1.0`, **no chunked transfer** — raw continuous bytes.
- Header terminator exactly `\r\n\r\n`.
- Content-Type `audio/aac` (not `aacp`).
- Auth: `Authorization: Basic base64(user:password)` — exact form confirmed empirically.
- Verb (`SOURCE` vs `PUT`) confirmed empirically, not assumed.
- **Handshake response validation:** read + validate the server's initial status line
  (`HTTP/1.0 200 OK` or equivalent) BEFORE entering the stream loop. Never stream into a dead socket.
- **TLS SNI set explicitly:** raw Android `SSLSocket` does not send the SNI hostname by default,
  so a Caddy/Let's-Encrypt server presents the wrong cert and the handshake fails. Set
  `SSLParameters.setServerNames` (or `setHostname`) and run hostname verification manually.
  **This is the most fiddly part of the build — expect it to take real time.**

---

## 5. Correctness-critical details (mandatory)

- **Partial-write loop:** every socket write loops until the full buffer is sent. Add an escape
  hatch: after ~3 consecutive `0`-byte-progress writes, force an `IOException` to trigger reconnect
  (avoids an infinite CPU-spinning loop when a socket silently stalls).
- **Half-open detection:** explicit socket **write timeout (`SO_SNDTIMEO`)**, not just connect
  timeout (a cached socket on a network handover hangs indefinitely rather than throwing).
- **Encoder-priming-aware arming:** AAC-LC needs 1-2 priming frames before first output. Do NOT
  arm zero-progress/half-open detection until after the first encoded frame.
- **ADTS header construction:** build the 7-byte header from the `MediaCodec` config buffer.
  Sampling frequency is a **4-bit index** (44100 -> `0x4`, 48000 -> `0x3`), not the integer.
  Set `protection_absent` bit = `1` (no CRC) so no 2-byte CRC is appended. Correct channel-config
  and profile bits. A wrong index/bit is accepted at handshake then rejected the instant audio flows.
- **Sample-clock pacing:** master clock = `samples_sent / sample_rate`. Use
  `AudioRecord.getTimestamp()` for drift correction only. Use monotonic
  `SystemClock.elapsedRealtime()` (never `currentTimeMillis()`) for reconnect/backoff only.
- **Unified discontinuity policy:** any clock gap past a threshold — AudioRecord short read /
  timestamp jump, OR device suspend/resume — is a discontinuity: **flush, mark, resync the pacing
  baseline. Never burst-to-catch-up.**
- **ADTS frame-boundary integrity:** the queue holds only WHOLE ADTS frames. Every drop, flush, and
  recovery happens at frame boundaries. Never cut mid-frame (corrupts `frame_length`, desyncs the
  decoder, drops the mount).
- **Codec-config handling:** `BUFFER_FLAG_CODEC_CONFIG` output is consumed to build ADTS headers,
  then dropped — never streamed as audio.
- **Buffer reuse:** pre-allocate/reuse PCM, AAC, ADTS, socket buffers to avoid GC churn over a
  long broadcast.
- **Backpressure:** on queue overflow, drop oldest in **burst units** (drain until queue < ~70%),
  at frame boundaries. Surface drop count.
- **Frame coalescing:** multiple ADTS frames per socket write, capped at **100-250 ms** of audio.
- **Encoder flush on stop** to avoid tail truncation.
- **Reconnect within the running service** — never restart the service from the background
  (avoids `ForegroundServiceStartNotAllowedException` on Android 14+). If the service itself dies,
  recovery is user-initiated.
- **Harbor-timeout-calibrated back-off:** Liquidsoap holds the slot ~10-30s after an unclean drop
  and returns 403/409 (mount occupied). Back-off expects this window; don't spam or exhaust retries.
- **Service teardown order:** call `stopForeground(STOP_FOREGROUND_DETACH/REMOVE)` BEFORE releasing
  the mic. Releasing the mic while still an active `microphone` FGS can trip the Android 14+ watchdog.

---

## 6. Interruption / focus / route handling

- **Route-change checks sample rate, not just presence.** A Bluetooth headset can switch capture to
  the SCO path (forced 8/16 kHz); feeding that into a 44.1k encoder pitches/scrambles the audio.
  **v1 policy: refuse the SCO route and stay on the built-in mic** rather than reconfigure live.
- **Silence generator on focus loss.** On a phone call / focus loss the mic returns zeroed or
  errored frames. Instead of disconnecting, stream **perfectly-timed silent ADTS frames** to hold
  the harbor slot until focus returns (avoids the reconnect dance on every call). Silent frames are
  still traffic, satisfying any harbor inactivity timeout.
- **HAL micro-mute is expected, NOT a discontinuity.** Alarms/notifications/Assistant can make the
  HAL feed valid-but-zeroed PCM for 500-1000 ms with no exception/callback. Timing stays intact, so
  this must NOT trigger discontinuity/resync — only the level meter dips. Expect it during testing.

---

## 7. Background survivability

- Foreground `Service` typed `microphone`, persistent notification with a Stop action.
- Wake-lock + wifi-lock while live.
- `ConnectivityManager` network-change callback drives reconnect on handover.
- First-run: request `RECORD_AUDIO`, `POST_NOTIFICATIONS`, and a **battery-optimization exemption**
  (`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, or guide the user to "Don't optimize"). Without it, the
  CPU parks mid-broadcast on aggressive OEMs regardless of the foreground service.

---

## 8. UI (deliberately minimal)

Essentially **one main screen**, bilingual (Urdu/English), RTL-aware:
- Large **Go Live / Stop** button (the primary control).
- **Status line:** Offline / Connecting / Live / Reconnecting.
- **Mic level / clipping meter** (so the owner confirms the mic is registering before speaking).
- **Elapsed-time** readout while live.

Plus:
- **First-run permission prompt** (mic, notifications, battery exemption) — one-time.
- **Live notification** with Stop (control while backgrounded / screen off).
- **View-only server panel** (shows configured host/mount; never the password).
- **Listener count** — deferred to Phase 7.

All complexity lives in the streaming layer; the UI stays bare.

---

## 9. Observability (lightweight, in v1)

- **Runtime telemetry** surfaced in-app or in the notification/log: reconnect count, queue depth,
  dropped-frame bursts, current bitrate, a latency estimate.
- **Rolling local debug log:** on-device exportable ring buffer of the last N events (reconnects,
  encoder state changes, queue overflows, discontinuities, route changes, focus loss, socket/TLS
  failures). **No cloud logging.** Telemetry says *what*; the log says *why*. This is the primary
  debugging aid for the long-run test in Phase 6.

---

## 10. Encoder fallback

AAC-LC is the primary path (native, no NDK). MP3-via-LAME (JNI, prebuilt ABIs) is a **fallback only**
if AAC live-metadata interoperability proves inadequate. **Do not pivot to Opus** — Android encode
support is inconsistent.

---

## 11. Out of scope (v1) — do not build

Metadata title-update channel; USB/line-in routing; multi-station/profile support; editable config
UI; live encoder reconfiguration for alternate sample rates; seamless zero-gap network handover.

---

## 12. Build Plan (STRICT ORDER — one phase per working block)

**Phase 0 — Repo hygiene.** Create `.gitignore` (Android + secrets), a `secrets.properties`
template (git-ignored, with placeholder keys), and a short `README`. Commit. No credentials committed.

**Phase 0.5 — Gate 1: Protocol spike (NO app code).** Write `ffmpeg`/`curl`/`netcat` scripts to
prove, against the LIVE mount: verb (SOURCE vs PUT), auth format, content-type, transfer mode,
handshake response, **TLS/SNI**, and ADTS acceptance. `ffmpeg` streaming AAC to the mount is the
known-good reference. **Do not proceed to Phase 1 until this passes.** The user runs these; you
interpret the output.

**Phase 1 — Capture + encode to file.** `AudioRecord` (UNPROCESSED, blocking, oversized buffer) ->
synchronous AAC-LC -> ADTS wrap -> write to a local `.aac` file. User verifies the file plays and
the voice sounds full (not thin). Proves capture, encoder, and ADTS header correctness offline.

**Phase 2 — Raw-socket uploader.** Raw `SSLSocket` with explicit SNI, the handshake + response
validation, partial-write loop, `SO_SNDTIMEO`. Stream the ADTS frames to the mount. User confirms
audio reaches listeners.

**Phase 3 — Foreground service + robustness.** Move the pipeline into a typed `microphone`
foreground service: notification + Stop, wake/wifi locks, bounded queue + backpressure, sample-clock
pacing, discontinuity policy, reconnect within the service, harbor-timeout back-off, clean teardown.

**Phase 4 — Interruptions.** `ConnectivityManager` reconnect on network handover; audio-focus
handling with the **silence generator**; **Bluetooth SCO refusal**; battery-optimization exemption.

**Phase 5 — UI + observability.** The single main screen (bilingual, RTL-aware): Go Live/Stop,
status, mic meter, elapsed time. First-run permission prompt. View-only server panel. Telemetry +
rolling local log.

**Phase 6 — Gate 2: Long-run validation.** A 30-60 min continuous broadcast under real conditions:
unplugged, screen off, network handover, Bluetooth (SCO refused), a real phone call (silence holds),
battery saver on. Monitor the **actual public listener stream**, not just source ingest. Watch for
latency growth, queue runaway, GC jitter, memory churn.

**Phase 7 — Polish (optional/additive).** Live listener count (AzuraCast now-playing API); any
cosmetic refinement; brand + icon; generate keystore + signed release APK for sideloading.

---

## 13. Working style

- Small, targeted changes. Explain what each change does and why before large edits.
- After each phase, stop and let the user build/test on a real device — you cannot drive the phone
  (install APK, tap permission dialogs, pair Bluetooth, simulate a call, listen to output).
- Prefer clarity over cleverness. Comment only where the reasoning is non-obvious (the ADTS bit math,
  the pacing clock, the SNI reflection/verification — these deserve comments).
- Keep the whole project in Git; work so the user can review diffs and roll back.
