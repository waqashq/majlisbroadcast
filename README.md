# MajlisBroadcast

A personal Android app that live-broadcasts audio from the phone's built-in
microphone to a self-hosted AzuraCast station, so a weekly majlis lecture can
reach listeners through the AzuraCast player on a website.

Single-user, single-station, not distributed, not on the Play Store.

## Source of truth

`majlisbroadcast.md` is the full build brief and the single source of truth
for scope, architecture, and the strict phase-by-phase build order (see its
section 12). Read it before making changes.

## Secrets

Server host, mount, and source password are never committed — this repo is
public. Real credentials live in a local `secrets.properties` (git-ignored);
use the placeholder keys in that file as the template. If a credential is
ever committed by accident, stop and rotate the AzuraCast source password.

`secrets.properties` keys:

```
azuracast.host=your.host.here
azuracast.port=8005
azuracast.username=your-source-username
azuracast.password=your-source-password
# Optional -- only needed if the AzuraCast web panel/API is not reachable
# at https://<azuracast.host>. Used only for the Phase 7 listener count.
azuracast.api_base_url=https://your.host.here
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
