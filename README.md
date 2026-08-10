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
low-battery confirmation prompt before going live, a self-monitor toggle
(hear your own mic through the earpiece, off by default -- use
headphones), optional Bass Boost / Echo voice effects with 0-100 knobs
(off by default, applied to the raw mic signal before the existing
fixed-gain clamp so clipping protection still holds), and a static
"Go Live" home-screen shortcut (long-press the app icon) that still goes
through the same login gate, just auto-starts the broadcast once you're
in.

Phase 9b, at the user's request: the bottom nav is now shared by all four
screens (Broadcast / Recordings / History / Settings), not just the
Broadcast screen -- each of those Activities is `launchMode="singleTask"`
so switching tabs reuses the same running instance instead of piling up
copies. Recordings and Session History each got a delete action (per-row
delete for a recording, a "Clear All" for session history), and the
Broadcast screen's stray second header (the system ActionBar showing the
app name above the custom green one) is gone -- it was only ever missing
`supportActionBar?.hide()`, which every other screen already had.
