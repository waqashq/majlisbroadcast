# Protocol Notes — Phase 0.5 Gate 1 findings

Confirmed empirically against the live AzuraCast/Liquidsoap harbor, per the
protocol requirements in `majlisbroadcast.md` section 4. This is the contract
Phase 2 (raw-socket uploader) implements against.

## Confirmed contract

- **Transport:** plain HTTP — no TLS on the source-ingest port.
- **Verb:** `PUT`
- **Path:** `/` (root — no named mount)
- **Auth:** `Authorization: Basic base64(username:password)`, credentials from
  local `secrets.properties`
- **Content-Type:** `audio/aac`
- **Transfer mode:** HTTP/1.0-style, non-chunked, raw continuous body (no
  `Transfer-Encoding: chunked`)
- **Encoding proven:** AAC-LC, 44.1kHz, mono, 64kbps CBR — matches the
  architecture in section 3 exactly. No need for the MP3 fallback.

## How this was verified

1. `curl` handshake probes across TLS x {`SOURCE`, `PUT`} x {`audio/mpeg`,
   `audio/aac`} against the live mount, using a tiny dummy payload to check
   handshake-level accept/reject and response codes.
2. `ffmpeg` end-to-end streaming test: 30 seconds of AAC-LC ADTS (a 440Hz
   test tone), `PUT` over plain HTTP to the root path. Confirmed live and
   audible on the AzuraCast dashboard/public stream — proves ADTS framing,
   content-type, and continuous transfer all work together, not just the
   handshake.

## Notable deviation from the original brief

Section 4 assumed TLS with explicit SNI handling would be required (a
Caddy/Let's-Encrypt front end). Empirically, the source-ingest port speaks
plain HTTP only — no TLS observed on this port (a TLS ClientHello to it just
hangs). This simplifies Phase 2: the raw-socket uploader can use a plain
`Socket`, not `SSLSocket`, and the SNI/hostname-verification work described
in section 4 does not apply to this connection. If that ever changes (e.g.
the source port moves behind TLS in the future), this file should be updated
and Phase 2 revisited.

## Reference command (redacted)

```
ffmpeg -re -f lavfi -i "sine=frequency=440:sample_rate=44100" -t 30 \
  -ac 1 -c:a aac -b:a 64k -ar 44100 -profile:a aac_low -f adts \
  -method PUT -content_type "audio/aac" -chunked_post 0 \
  "http://USERNAME:PASSWORD@HOST:PORT/"
```

Real host, port, username, and password live only in the local, git-ignored
`secrets.properties` — never in this file or anywhere in the repo.

Verified: 2026-07-01.

## Update — Phase 6 long-run testing (2026-07-02)

Under repeated rapid network handovers (Wi-Fi toggled off/on several times
in a short window), the mount-hold window after an *unclean* drop (network
simply vanishes -- no FIN/RST reaches the server, which only notices via
its own read timeout) proved to run closer to **45-50 seconds** in
practice, not the 10-30s originally estimated from clean-disconnect
testing in Gate 1. A reconnect attempt inside that window is rejected with
`HTTP/1.0 403 Mountpoint already taken`.

The app's backoff ceiling and its network-handover fast-path were both
adjusted in response (see `BroadcastEngine.kt`: `BACKOFF_MAX_MS`,
`RECONNECT_GRACE_MS`). If 403s during reconnect still show up in the debug
log after this fix, the more durable fix is lowering the source/read
timeout on the AzuraCast/Liquidsoap harbor side so it notices a dead
connection sooner.

**2026-07-03 follow-up:** a second long-run test (Wi-Fi -> mobile data
handover) needed the *full* ~40s backoff step to succeed -- even a ~20s
attempt was still rejected with 403. Liquidsoap's `harbor.timeout` defaults
to 30s; AzuraCast's actual configured value (and any of its own supervisor
overhead on top) may run longer than that default. This is adjustable via
Station -> Broadcasting -> "Edit Liquidsoap Configuration" (custom code box
at the bottom of that page) but requires hand-written Liquidsoap and hasn't
been attempted yet -- flagged as a possible future server-side change, not
done as of this note.

**2026-07-03, whistling investigation:** a persistent pitch-shifted
"whistle" was reported starting right after a reconnect and continuing
for the rest of that connection (not a momentary blip). Acoustic feedback
was ruled out by testing at 1ft and 10m from any listening speaker with
the same result either way. Working theory: on reconnect, the writer was
draining whatever backlog had piled up in the `FrameQueue` during the
outage in one rapid burst (a network write is far faster than the audio's
real-time rate), handing the server several seconds of audio much faster
than real-time -- plausible trigger for a decoder-side clock-recovery
assumption getting stuck. Fix: `BroadcastEngine.kt` now clears the queue
on every successful (re)connect instead of draining the backlog, so the
writer only ever sends audio at the pace it's actually being captured.
Not yet confirmed on-device -- retested after this fix, whistle still
present, so it isn't (solely) the burst-drain mechanism. Current
hypothesis has shifted to the manual gain boost's hard clamp on loud
peaks; `GAIN_FACTOR` dialed back from 4.0x to 3.0x as a first, modest
experiment (see `BroadcastEngine.kt`). Not yet confirmed either -- next
log will tell us whether this had any effect.

App-side, `BroadcastEngine.kt` now special-cases a "Mountpoint already
taken" rejection specifically: instead of doubling up through the backoff
ladder (which we now know just wastes 1-2 guaranteed-to-fail cycles once
the server has already told us the mount is still held), it jumps straight
to `BACKOFF_MAX_MS` on that specific error.

