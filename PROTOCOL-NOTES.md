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
