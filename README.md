# MajlisBroadcast

A personal Android app that live-broadcasts audio from the phone's built-in
microphone to a self-hosted AzuraCast station, so a weekly majlis lecture can
reach listeners through the AzuraCast player on a website.

Single-user, single-station, not distributed, not on the Play Store.

## Source of truth

`majlisbroadcast.md` is the full build brief and the single source of truth
for scope, architecture, and the strict phase-by-phase build order (see its
section 12). Read it before making changes.

## Server settings (Phase 8 -- runtime, in-app)

As of Phase 8, the AzuraCast host/port/mount/username/password and the
audio sample rate/bit rate are entered and edited directly in the app
(Settings screen), not at build time. They're stored in an encrypted,
on-device-only SharedPreferences file (AndroidX Security, Android
Keystore-backed) and persist across app restarts.

`secrets.properties` (below) is still used, but only as a one-time seed
the *first* time the app runs after a fresh install -- after that, the
Settings screen is the only source of truth, and `secrets.properties` /
`BuildConfig` values are never read again. This means an existing
install's in-app settings survive a rebuild even if `secrets.properties`
changes or is missing.

The optional `azuracast.api_base_url` override (below) is no longer read
anywhere at runtime -- the now-playing/listener-count API base URL is
always derived as `https://` + whatever host is currently saved in
Settings. It's left in `secrets.properties`/`build.gradle.kts` as
harmless dead config rather than removed, to keep this diff small; safe
to ignore.

## Secrets

Server host, mount, and source password are never committed — this repo is
public. Real credentials live in a local `secrets.properties` (git-ignored);
use the placeholder keys in that file as the template. If a credential is
ever committed by accident, stop and rotate the AzuraCast source password.

`secrets.properties` keys (first-run seed only -- see above):

```
azuracast.host=your.host.here
azuracast.port=8005
azuracast.username=your-source-username
azuracast.password=your-source-password
# No longer read at runtime (Phase 8) -- see "Server settings" above.
azuracast.api_base_url=https://your.host.here
# Optional but recommended -- AzuraCast's all-stations /api/nowplaying
# endpoint only lists stations flagged "Public" and has a known bug
# returning an empty list on unauthenticated requests even when a
# station otherwise works fine. Setting this makes the app use the
# reliable per-station endpoint instead. Find it in the AzuraCast admin
# panel: Station Profile page -> the "Short Name" field, or the slug
# in the station's public page URL, e.g. the "azuratest_radio" part of
# https://your.host.here/public/azuratest_radio
azuracast.station_shortcode=your-station-shortcode
```

## Release signing (Phase 7)

Signing a release build requires a local, git-ignored `keystore.properties`
pointing at a keystore file that is *also* never committed. Until
`keystore.properties` exists, `assembleRelease` just produces an unsigned
APK -- debug builds are never blocked on this.

`keystore.properties` keys:

```
storeFile=../majlisbroadcast-release.jks
storePassword=your-keystore-password
keyAlias=majlisbroadcast
keyPassword=your-key-password
```

Generating the keystore itself and backing it up safely is a one-time,
user-driven step -- see chat history / ask for the walkthrough. Losing this
file means losing the ability to ever ship an update under the same app
identity.

## Status

Building in strict phase order per `majlisbroadcast.md` section 12. All
phases in section 12 (0 through 7) are complete -- see git history for
per-phase detail.

Post-Phase-7, at the user's request: a redesigned dark "studio" Broadcast
screen (status pill, latency, large elapsed time, live mic-level waveform,
listener count, share-listen-link), local recording (forks the same
already-encoded stream to a file on the phone, independent of and
resilient to network state), and a bottom nav (Broadcast / Settings)
replacing the old top-right Settings button. AzuraCast's own server-side
live-broadcast recording is also available (station profile setting) and
is the recommended primary copy; local recording is a phone-side backup.
Local recordings are saved to the public Music/Malfoozat e Akhtar folder
(via MediaStore on Android 10+) so they're visible in the Files app and
any music player -- an earlier version wrote them to app-private storage,
which Android 11+ hides from normal file browsing.

Phase 9, at the user's request: an in-app Recordings screen (play/share
without leaving the app or hunting through the Music folder), a Session
History screen (date, duration, peak listeners, data used for each past
broadcast, logged locally), a data-used + Wi-Fi/mobile-data readout and a
low-battery confirmation prompt before going live, optional Bass Boost /
Echo voice effects with 0-100 knobs (off by default, applied to the raw
mic signal before the existing fixed-gain clamp so clipping protection
still holds), and a static "Go Live" home-screen shortcut (long-press the
app icon) that still goes through the same login gate, just auto-starts
the broadcast once you're in. (A self-monitor toggle was also added in
this phase and later removed at the user's request -- see Phase 9d.)

Phase 9b, at the user's request: the bottom nav is now shared by all four
screens (Broadcast / Recordings / History / Settings), not just the
Broadcast screen -- each of those Activities is `launchMode="singleTask"`
so switching tabs reuses the same running instance instead of piling up
copies. Recordings and Session History each got a delete action (per-row
delete for a recording, a "Clear All" for session history), and the
Broadcast screen's stray second header (the system ActionBar showing the
app name above the custom green one) is gone -- it was only ever missing
`supportActionBar?.hide()`, which every other screen already had.

Phase 9c: fixed a real crash-on-launch seen on at least one OEM device --
`AppSettings`'s encrypted settings store (Tink/AndroidX Security) could end
up with a corrupted keyset (aggressive background-process/memory management
killing the app mid-write), which made `EncryptedSharedPreferences.create()`
throw on every single launch with no recovery except manually clearing all
app data. It now self-heals: on that failure it wipes just the one
corrupted file and rebuilds a fresh encrypted store, at the cost of needing
to re-enter server settings/app-lock once. Also removed the "Move Old
Recordings to Music Folder" manual button from Settings, at the user's
request -- the automatic migration on launch (Phase 8c) still runs.

Phase 9d, at the user's request: removed the self-monitor toggle (earpiece
playback of your own live mic) entirely -- the button, its BroadcastEngine
AudioTrack plumbing, and the BroadcastService action that toggled it are
all gone. Bass Boost / Echo and everything else from Phase 9 are unaffected.

Phase 9e: fixed a real bug -- stopping the broadcast without first tapping
"Stop Recording" left that recording stuck forever. `engine.stop()` closed
the file's bytes, but only "Stop Recording" ever cleared the MediaStore
row's `IS_PENDING` flag, so the file existed on disk but was invisible
everywhere (Recordings screen, Files app, any music player) -- it looked
like it was never saved. `stopBroadcast()` and `onDestroy()` now finalize
an in-progress recording themselves before tearing down, using the same
path "Stop Recording" already used.

Phase 9f, at the user's request: the large standalone logo at the top of
the Broadcast screen is gone -- it's now a small badge sitting beside the
"Malfoozat e Akhtar" title in the header row itself, start-aligned (reads
left-aligned in English, mirrors to right-aligned in Urdu automatically via
RTL layout direction, same as the rest of the app).

Phase 10, at the user's request: a full visual redesign ("Noor"), replacing
the old green + teal + amber mix with one flat emerald accent used
consistently everywhere (Go Live, active nav tab, section titles, slider
tint, on-air indicator). No gradients or glow -- flat bordered cards
(thin hairline border instead of a thick colored outline), a neutral
bordered status chip with a small colored dot instead of a loud solid-fill
pill, a redesigned mic level meter (flat bordered inset strip, plain mic
glyph, single-accent bar meter instead of a rainbow hue sweep), and a
floating rounded bottom nav bar with a filled chip behind the active tab.
The Broadcast screen's colored header bar and Splash screen's colored
header bar are both gone, replaced with the same flat no-chrome look used
everywhere else. Solid accent-filled buttons (Go Live, Login, Save) now
consistently pair with dark text for better contrast on the lighter
emerald fill. Recordings/History/Settings inherit the new look entirely
through the shared UiTheme/StudioUiKit palette -- no changes needed in
those screens themselves.

Phase 10b: first change verified by an actual Gradle build + Android lint
rather than manual review alone. Lint's two errors (MissingPermission on
`AudioRecord` creation in BroadcastEngine and AacFileRecorder) were
already safely caught by a generic `catch (Throwable)`; both now catch
`SecurityException` explicitly and give up immediately instead of
pointlessly retrying the fallback mic source, which lint recognizes as
handled. `lintDebug` now passes (warnings remain, none functional).

Phase 11, at the user's request: a website live/offline light on the
Broadcast screen, in its own chip directly under the existing ON AIR chip.
ON AIR is the app's own connection state; the new light shows what
listeners on waqashq.org actually see, so the two can disagree (e.g. the
app is connected but AzuraCast hasn't registered the live source yet). It
uses the exact rule waqashq.org's own player script uses -- AzuraCast's
`/api/nowplaying/{shortcode}` -> `live.is_live`, refreshed every 15s -- and
polls only while the Broadcast screen is in the foreground, whether or not
the app is broadcasting. Green = live, red = offline, unlit grey = unknown
(only when the phone itself has no working internet; if the phone is
online but the API is unreachable, the website would show Offline too, so
the light shows red). The lamp (StatusLightView) is a small custom-drawn
glossy bulb with bezel and glow -- a deliberate, self-contained exception
to Noor's no-gradients rule, which still applies everywhere else.
Verified on an Android 14 emulator against the live API (English + Urdu
RTL).

Phase 11b, at the user's request: both status indicators now match. The
old flat ON AIR dot is replaced by the same 3D lamp as the website light,
and each chip is [lamp][icon][state] -- a phone icon for the app's own
connection, a globe icon for waqashq.org -- instead of a "WEBSITE:" text
prefix (the chips still carry "App broadcast status" / "Website live
status" for screen readers). App lamp: green on air, amber while
connecting/reconnecting, red when offline or on error; the app's OFFLINE
label is now red too (was grey) so it agrees with its lamp. Both chips are
sized to the wider one so the lamps and icons line up in a column. The
lamp is now drawn on a CPU (software) layer -- the GPU path left faint
stray specks in the nearly-transparent edge of the glow. UiTheme's
now-unused `studioMicCircle` helper was removed.

Phase 11c, at the user's request: closed the dead space between the Voice
Effects card and the Share Event button. The listener-count and data-used
rows sit between them and are blank while not live, so they (plus their
40px margins) left a large empty gap; they're now `GONE` unless live, and
the remaining gaps are 28px to match the spacing between the cards above.
Share Event therefore sits directly under the Voice Effects card when
idle, and the two readouts reappear above it while on air.

Phase 11d, at the user's request: the bottom nav tabs are taller (26px
vertical padding, 48px icons -- the bar was under Android's 48dp minimum
touch target), and the two status chips sit side by side in one row
instead of stacked. Each chip takes an equal half of the card width, so
the website chip doesn't shift sideways as the app chip's label changes
(OFFLINE -> CONNECTING -> RECONNECTING); labels are 12sp, single line,
ellipsized as a last resort. Verified on the emulator that the longest
label (RECONNECTING) still fits uncut in English and Urdu, at both normal
and 480dpi (~360dp-wide) density.

Phase 11e, at the user's request: three Broadcast-screen changes.
(1) A disconnected icon (broadcast waves with a slash, muted grey) now
fills the card's empty middle while not on air -- the slot the elapsed
clock uses once live; exactly one of the two is ever visible.
(2) Bottom nav tabs taller again (26px -> 36px vertical padding).
(3) The mic level strip is replaced by a real frequency-bar visualizer.
`SpectrumAnalyzer` (new) runs a 2048-point FFT with a Hann window over the
newest PCM window and reports 22 log-spaced bands (100Hz-8kHz, per-band
peak) as 0-100 levels; it runs on the capture thread but only when a level
report is already due (~150ms) and reuses all buffers, so it neither
allocates nor meaningfully competes with the encoder. `SpectrumView` (new,
replacing `WaveformView`) draws bottom-anchored rounded bars with a
vertical gradient, eased per animation frame with fast attack/slow release
plus light neighbour blending so the outline flows instead of twitching,
at 240px tall instead of the old 40px strip. Unlike the old strip this is
genuinely spectral -- the previous one only had a single overall peak
level to work with.

Phase 11f, at the user's request: (1) a local mic preview -- `MicPreview`
(new) captures the mic while the Broadcast screen is in the foreground and
NOT broadcasting, runs it through the same `SpectrumAnalyzer` with the same
fixed gain as the live path, and drives the bars, so the mic can be checked
before going live (section 8's "confirm the mic is registering before
speaking"). It never touches the network/encoder/disk, stops on
onPause/onDestroy, and is stopped explicitly in `startBroadcastNow()`
before `BroadcastService` starts -- only one capture can hold the mic, so
the preview must be gone first. (2) The mic button is much bigger (36px ->
52dp with padding) and now reads as a button; it was never broken, just
tiny next to the new bars -- it is still the mute toggle while live, now
with a content description. (3) The idle disconnect icon is smaller
(86dp -> 66dp). (4) The visualizer bars are coloured by level zone, the
green/amber/red of a hardware level meter: one gradient spans the view, so
each bar only reveals the zones it reaches (quiet = all green, loud = red
tip; green to ~80%, amber ~85%, red 95%+). Colouring by frequency instead
was rejected -- it looks busier and carries no information. (5) Go Live and
Start Recording are now two round domed buttons side by side, labelled
LIVE (STOP while live) and REC, with a pressed state; `UiTheme.round3dButton`
takes the diameter in px because a radial gradient's radius set from code
is in pixels, not a fraction of the bounds. The long `btn_go_live` /
`btn_start_recording` / `btn_stop_recording` strings are now unused (lint
reports them) but kept in both languages in case the long form is wanted
back.

Phase 11g, at the user's request: (1) square bar tops instead of rounded.
(2) Nothing on the screen moves when going live or stopping any more --
the elapsed clock and the disconnected icon now share one fixed-height
`statusSlot` (FrameLayout, both centred, INVISIBLE rather than GONE), and
the listener/data readout became a single line that is only ever INVISIBLE
while idle, so its row stays reserved. `dataUsageText` was folded into that
line and removed. (3) LIVE/REC buttons 30% smaller (112dp -> 78dp).
(4) Visualizer 30% shorter (240px -> 168px). (5) More shades and earlier
colour: light green -> mid green -> deep green plus amber from ~70% and red
from ~88% of bar height (was amber ~85%/red ~95%), so normal speech shows
colour instead of only shouting. (6) Mute works while idle: since
`BroadcastService` applies mute to the running engine and there is no engine
when idle, muting idle stops the preview capture instead, turns the mic icon
red, and is carried into the next broadcast via `pendingMuteOnLive` (applied
from `pollLiveState()` once the state actually reaches LIVE, because the
service ignores mute intents until its engine exists). Stopping a broadcast
resets it to unmuted.

Verified on the emulator: the anti-shift work was checked by measuring
landmark y-positions in idle vs live screenshots (bars, Voice Effects
title, both sliders and Share Event all identical, the only differences
being in-place colour/visibility changes), and idle mute by confirming the
mic glyph flips grey -> red -> grey on tap while the screen still reads
OFFLINE. Note `dumpsys activity services <pkg>` substring-matches other
apps' services, so a "service still running" count from it can be a false
positive -- confirm against the on-screen state.

Phase 11h, at the user's request: mic noise reduction, as one On/Off
switch in the Voice Effects card -- default ON, remembered across launches
(`AppSettings.noiseReduction`, key `audio_noise_reduction`). The new
`NoiseReducer` is two conservative stages run per sample, before the voice
effects and the gain stage: (1) a 4th-order high-pass at 85Hz (two cascaded
RBJ Butterworth biquads) that removes fan/AC rumble and handling thumps;
(2) a gentle gate that tracks the room's own noise floor and fades gaps
between sentences down by at most -12dB -- never to silence -- with a 5ms
attack (word starts are not clipped) and 250ms release (no pumping). It is
shared by the live path (`BroadcastEngine`, toggled live via a new
`ACTION_SET_NOISE_REDUCTION` service action) and the idle `MicPreview`, so
the bars show what listeners will hear. Deliberately NOT used: Android's
own `NoiseSuppressor` (part of the telephony processing chain the
UNPROCESSED capture source avoids on purpose), spectral subtraction
(artifact-prone, wants tuning against a real recording of the room), and
RNNoise (needs NDK/native builds).

The DSP was checked before wiring it in, by mirroring it in JS and feeding
synthetic signals: 50Hz rumble -19.4dB (a single stage managed only
-9.7dB, hence the cascade); steady hiss -11.9dB; speech inside a talking
burst -0.2dB (i.e. untouched); a 0.5s gap between bursts ~-4dB (the slow
release is still easing down, by design); word onsets -1.7dB over the
first 20ms. Not yet verified: how it sounds on real speech in the actual
hall.

Phase 11i, at the user's request ("too aggressive -- it turns my actual
voice down"): `NoiseReducer` rewritten gentler. The user was right, and the
Phase 11h test had hidden it: it used a 200Hz voice in short bursts. Re-run
with a male-pitched (100-120Hz) voice speaking continuously, the old
version cost ~1.6dB on all speech and 3.7dB at 100Hz, because (a) its 4th-
order 85Hz high-pass reached into a male voice's fundamental, and (b) its
gate's noise floor crept upward the whole time someone talked, until soft
syllables got dipped. (The first re-test also had a bug of its own -- pitch
vibrato built as sin(2*pi*f(t)*t) sweeps the real frequency toward 0Hz --
fixed by accumulating phase.) Now: 2nd-order high-pass at 60Hz; a
minimum-statistics noise floor (quietest envelope over the last 4 x 500ms
blocks, additionally capped ~22dB below recent speech) so it cannot climb
during speech; opens at +6dB, attenuates gaps by at most -6dB, 3ms attack,
300ms hold, 400ms release. Measured on the lecture-style signal: speech
-0.2/-0.3dB, 100Hz -0.5dB, real pauses -4.7dB, 50Hz rumble -4.9dB, and a
voice 12dB quieter is still untouched (pauses then ease only -1dB, erring
towards leaving audio alone). Much less rumble/pause reduction than before,
deliberately -- a lecture should never be dipped.

Phase 11j, at the user's request: (1) noise reduction gentler again -- the
remaining voice loss was almost all the high-pass, so 60Hz -> 40Hz, plus a
500ms gate hold and opening at +4dB instead of +6dB. Measured: speech
-0.1dB at normal and 12dB-quieter voice, 100Hz -0.1dB, pauses -3.8dB, 50Hz
rumble only -1.5dB (knowingly traded away). (2) The "Clipping -- move back
or speak softer" text is removed entirely, view and strings, per request.
The engine still detects clipping and `BroadcastService.micClipping` is
still populated, just no longer shown; note the meter's red zone is a
per-band loudness indicator, not the same thing as sample clipping, so it
is a close visual proxy rather than an exact replacement. (3) The "Cuts
hum, rumble and background hiss" hint under Noise Reduction is removed,
view and strings.

Phase 11k, at the user's request: (1) the visualizer flashes red on a clip
(the removed text warning's replacement) -- every bar gets a red overlay
that fades over 350ms, restarted on each clip. Clips are latched between
the ~150ms level reports in both `BroadcastEngine` and `MicPreview`, so a
clip in a buffer that falls between two reports is no longer lost, and the
flash works idle (mic preview) as well as live. (2) "Noise reduction does
not seem to work at all" -- it very nearly didn't. Every earlier test used
perfectly steady hiss; on realistic fluctuating room noise (slow +/-4dB
swells, occasional clatters) the Phase 11j gate eased pauses only -1.9dB,
and not at all for a quiet talker, because its +4dB opening threshold sits
right at the MINIMUM of the noise envelope, so ordinary swells held it
open. Now: opens at +9.5dB, gaps down to -12dB (never silence), 300ms hold,
250ms release. Measured: pauses -10.6dB on steady hiss / -6.7dB on
fluctuating room noise, speech still -0.1dB, soft syllables untouched at
normal and 12dB-quieter voice. It was never a wiring bug (switch ->
AppSettings -> service action -> engine, and preview rebuilt, all
confirmed by code review); the debug log now records "Noise reduction
switched ON/OFF" and "Going live with noise reduction ON/OFF" so the state
can be confirmed after a session.

Phase 11l, "the noise reduction toggle sometimes works, sometimes doesn't":
two real bugs. (1) The gate's floor was capped ~22dB below "recent speech",
but with nobody talking "recent speech" is just the room noise's own peaks,
so the cap pinned the floor under the noise and the gate stayed open. And
every switch-ON reset the reducer, so right after switching ON in a quiet
room it did nothing (measured: only the -3dB rumble filter), and in any
pause longer than ~2s it stopped reducing. Whether it "worked" depended on
whether you'd just been talking. The cap is removed: on 25s of unbroken
speech it protected nothing the minimum-statistics floor didn't already.
The opening threshold went from +9.5dB to +12dB so room-noise swells don't
poke through. The reducer also now runs all the time (switch only picks
its output), so switching ON takes effect at once with an already-learned
floor. (2) While idle, the toggle stopped the mic preview and immediately
opened a new one; the old capture often hadn't released the mic yet, so
the new one failed silently. The preview is now switched in place.
Measured: silence right after switching ON -15dB (-12 gate, -3 rumble
filter) within ~2s, and held however long the pause; speech -0.1dB, soft
syllables -0.1dB, quiet voice unchanged.

Phase 11m, "more noise now than last time": first test on REAL room audio.
A temporary, uncommitted build saved 30s of raw idle-preview mic audio
(15s quiet, 15s talking; nothing broadcast) which was pulled over adb and
run through the reducer offline. Both 11k and 11l did 0dB on it: 99% of
that room's noise was rumble at 40-320Hz (fan/AC-type, no hiss at all)
whose level swung ~30dB several times a second, so a full-band detector
kept reading the swings as speech. Redesign: the voice detector listens
only to 300-3000Hz, where speech is strong and that room was near digital
silence, with an absolute minimum floor so breaths/rustles (peaks 30-80 in
16-bit units) don't count as speech (hundreds to thousands); the gate is
now two-band (Linkwitz-Riley split at 300Hz): in gaps the low band drops
-20dB and the rest -12dB, and during speech both are fully open so the
voice keeps its low body. Measured on that recording: quiet part -15dB,
speech untouched except in its real pauses (also with the recording 12dB
quieter); synthetic benches: silence after switching ON / after talking
-17dB, speech -0.1dB. Also removed a duplicated "Going live with noise
reduction" debug-log line.

Phase 11n, "once, noise reduction ON but the recording wasn't reduced;
off/on and retry fixed it": a one-off with no log to read (the debug log
is in memory and the app had since closed). One real race found and fixed:
Go Live stopped the mic preview without waiting, so the broadcast could
open the mic while the preview thread was still mid-read holding it --
risking the CAMCORDER fallback source, whose phone-side AGC can pump room
noise up past the gate's threshold. Go Live now waits (up to 500ms, in
practice ~100ms) for the preview to release the mic. For next time, the
debug log now records the mic source each session got ("Mic source:
UNPROCESSED" / "CAMCORDER (fallback)") and, every 30s while live, "Noise
reduction ON/OFF: room turned down N% of the time, noise floor F".

Phase 11o, "sometimes pressing LIVE shows no Broadcast Started dialog and
REC stays unavailable": a start-up race, not a network problem.
`BroadcastService.start()` only fired an Intent, so the static `state`
still held the previous session's STOPPED (or IDLE on a fresh launch) for
the tens of ms until the service ran. MainActivity starts its 300ms
livePoller in the same breath, and pollLiveState() treats STOPPED/IDLE as
"the session ended": isLive back to false (REC disabled, no dialog since
that watcher waits for LIVE) and startMicPreview() again -- which then
grabs the mic on top of the engine's own capture, so the connect could
fail outright. Whichever ran first decided the outcome, hence "only
sometimes". Fixed by setting state = CONNECTING synchronously inside
start(), before the service is dispatched.

Verified: clean build + lint, the dex checked for absence of the temporary
demo-feed code used for screenshots, and the colour-zone mapping checked
against bar heights. NOT verified on screen: the meter colours and the
domed buttons under real audio -- the emulator's mic is silent and its host
process kept crashing, so that check moved to the phone.

The FFT band maths was verified before wiring it up by mirroring the
algorithm in JS and feeding it known tones: at 512 points the bins were
wider than the low bands, which collapsed every bar below ~800Hz and put a
1kHz tone in the 660-800Hz bar; at 2048 points 200Hz/1kHz/4kHz each land in
the correct band and silence reads zero. The on-device look was checked by
temporarily feeding the view a synthetic moving spectrum (an emulator's mic
is silent), then reverting that. Still unverified on real audio: that the
bars track a real voice sensibly -- worth a look on the phone.

The WhatsApp/link-preview logo for the shared waqashq.org link is NOT an
app change -- link previews are built by WhatsApp from the target page's
Open Graph tags, and waqashq.org served none. Fixed in the separate
website repo (`malfoozatWebsite`): `og:*`/`twitter:*` tags added to
`src/layouts/Base.astro` plus a dedicated 630x630 ~44KB
`public/assets/og-image.jpg` (square so WhatsApp's thumbnail crop doesn't
cut the logo; WhatsApp silently drops previews for images over ~300KB, and
the existing logo.png/favicon-512.png are 397KB/377KB). Needs the site's
normal manual build + upload to take effect.

Emulator note: the `salah_test` AVD's host process repeatedly crashed
(exit 139) mid-way through reading the host's DNS configuration whenever
the app made network calls; starting it with
`-dns-server 8.8.8.8,1.1.1.1` avoids that path and it ran stably.
