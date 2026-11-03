# 029 Audio delivery (S-002)

## Questions
1. What are the AnimeThemes audio files (codec, length, size), and how may we fetch them?
2. Which browsers can play them, and what does that mean for transcoding and hosting (D-010)?
3. How does every player hear the song from the same moment (CLAUDE.md multiplayer rules)?
4. What could reveal the answer through the audio, and how is it served opaquely (D-009)?
5. How is the 90-second cap (D-021) enforced?
6. How long and how large is the top-1,500 prefetch (D-019, D-044)?

## Findings (explorer, 2026-11-03; 3 live audio requests with the SongRush User-Agent and 3 s gaps)
- **F1. Format.** The three sampled draw-set files are **Ogg Opus**, stereo, 48 kHz (first packet `OpusHead`), 88.9-92.0 s long, 193-304 kbps. All 1,500 draw-set themes are `.ogg` at `https://a.animethemes.moe/<basename>` (`data/catalog.json`). The catalog's `audio.size` equals the downloaded byte count for all three samples, so it can verify a finished download.
- **F2. HTTP.** `HEAD` returns **403** (Cloudflare in front); `GET` with `Range: bytes=0-99` returns **206** with `Accept-Ranges: bytes`, `Content-Type: audio/ogg` and an ETag; a full `GET` returns the file. The responses carry `content-disposition: inline; filename="ShingekiNoKyojin-OP1.ogg"`, which names the series and slot. No 429 was seen. The API documents 90 requests/min for GraphQL (api-docs.animethemes.moe/graphql/intro/ratelimiting/); no published statement about bulk audio download was found (the terms pages returned 404).
- **F3. Sizes.** Draw set: 5.416 GB total; average 3.61 MB, median 3.58 MB, p95 4.96 MB, max 16.7 MB.
- **F4. Browser support.** Ogg Opus plays in Chrome 33+, Firefox 15+, Edge 14+, and in Safari on macOS 15.4 / iOS 18.4 and later (Apple's Safari 18.4 release notes, 2025-03-31: "Added support for Ogg Opus and Ogg Vorbis"); older Safari plays Opus only inside CAF (MDN). Whether `decodeAudioData` decodes Ogg Opus in Safari 18.4 is not confirmed; it needs a real-device check.
- **F5. Sync.** The standard approach: the client estimates its clock offset from the server with repeated ping/pong over the realtime connection (NTP-style, keep the lowest-RTT samples); the server announces `startAt` in server time with enough lead for every client to finish loading; each client fetches and decodes the whole file into an `AudioBuffer` and schedules it on the `AudioContext` clock, compensating `outputLatency`. `<audio>.play()` has variable start latency. Autoplay rules require a user gesture before audio, so the client unlocks audio on a join or ready click. Expected skew: tens of ms (unmeasured), dominated by device output latency.
- **F6. Leaks.** The sampled files' Opus tags hold only an encoder string (and once `DURATION`); no title, artist or anime. The leaks are the file name, `Content-Disposition`, our URL and anything derived from the basename (ETag). **Size is a strong fingerprint** (design review H1): the catalog's sizes come from the public AnimeThemes API, and 1,405 of the 1,500 draw-set files (93.7%) have a size no other draw-set file has. A client downloads the whole file before `startAt`, so a modified client could identify the song from the byte count before it plays, with no listening at all. That is not the D-025 case (listening ahead) and breaks the "nothing that reveals the song" rule; the user chose to prevent it (D-055).
- **F7. 90-second cap.** Files are already about 90 s; a few are larger (max 16.7 MB). Audio after the round ends reveals nothing new, because the answer is revealed when the round ends.
- **F8. Prefetch time.** 1,500 requests with a 3 s gap is 75 minutes of waiting plus transfer: about 1.5-2.5 hours. 5.4 GB is under D-044's 10 GB threshold.

## Options
1. **Old Safari (before 18.4).**
   - (A) Support only browsers that play Ogg Opus; show an "update iOS" notice elsewhere. No transcoding, no ffmpeg on the server.
   - (B) Transcode all 1,500 to AAC once (≈1.5 GB more) and serve AAC to clients that fail `canPlayType('audio/ogg; codecs=opus')`. Needs ffmpeg on the build PC and the host.
   - **User decision D-053: (A).**
2. **90-second cap.**
   - (A) The server ends the round at `min(duration, 90 s)`; the client stops playback then. No file changes.
   - (B) Trim files to 90 s with ffmpeg.
   - **Choose (A):** listening past the end gives no advantage (F7), and it needs no ffmpeg.
3. **Opaque serving.**
   - (A) The backend keeps the cached files under their AnimeThemes basenames (server-side only) and serves them at `/audio/<token>`, where the token is a random 128-bit value issued per round with a short lifetime, with neutral headers.
   - (B) Rename the files on disk to random names. It adds a mapping file and gains nothing, since the disk is not visible to clients.
   - **Choose (A).**
4. **Playback.**
   - (A) Web Audio: fetch, decode, schedule at `startAt` on the `AudioContext` clock.
   - (B) `<audio>` with `play()` at `startAt`.
   - **Choose (A)** for start accuracy; the realtime protocol and the clock-offset exchange are designed in S-003.

## Decision
- Serve the original Ogg Opus files; support browsers that play Ogg Opus (Safari/iOS 18.4+) (D-053).
- The data tools prefetch the draw set into `data/audio-cache/` (gitignored), verified by the catalog size and the `OpusHead` header. The prefetch starts as soon as the tool passes review (D-044, D-054).
- ~~After a file is verified, the prefetch rewrites its OpusTags packet with a padding tag of random length (D-055).~~ Dropped by D-057: a client can strip in-file padding and recover the exact size, and the packet-size pattern identifies the file anyway. Fingerprinting is accepted for friends-only play and revisited before M-4.
- The prefetch also records each file's duration (last Ogg granule position minus pre-skip, at 48 kHz) in `data/audio-cache/index.json`, which the backend uses to end the round at `min(duration, 90 s)` (design review H2).
- Only themes with a verified cached file are draw candidates; a failed download shrinks the draw set rather than leaving a hole at round time (D-056). D-026's lobby fetch of uncached draws does not arise while the draw range equals the cached set (D-027); it moves to M-5, where the range widens (design review H3).
- The backend serves cached files only through per-round opaque tokens, as a full `200` response without Range support (a `206`'s `Content-Range` would also expose the size), with an allow-list of headers: `Content-Type`, `Content-Length`, `Cache-Control: no-store`; never `Last-Modified` (the download order follows popularity), `ETag` or `Content-Disposition` (design review M3, M4).
- The round ends at `min(duration, 90 s)` on the server (D-021); no trimming.
- Hosting (D-010) needs a JVM and about 5.5 GB of disk for the audio cache; no ffmpeg.
- The client sync design (clock offset, `startAt`, Web Audio scheduling, audio unlock) goes to S-003, which defines the realtime protocol.

## Resulting items
- **C-015** data: audio prefetch tool with verification and the duration index, then the real prefetch run.
- ~~**C-016** data: size padding of the cached files (D-055).~~ Dropped (D-057).
- **B-002** backend: catalog loader (reads `data/catalog.json`).
- **B-003** backend: opaque audio endpoint.
- **Needs decision (user's device):** a real-device check on the owner's iPhone (iOS 18.4+) that a decoded Ogg Opus file plays through Web Audio, once a test page exists (after F-001).

## Requests made
Three `GET` (one `Range: bytes=0-99`, two full) and three `HEAD` (403) against `a.animethemes.moe`, 3 s apart, with the SongRush User-Agent; five documentation pages fetched. No fixture saved; the downloaded samples stay outside the repo (`%TEMP%\audio-probe\`).

## Design review (critic)
- **H1 (user decision D-055).** Size identifies 93.7% of the draw set; padding the OpusTags packet changes it without touching the audio.
- **H2.** No duration existed anywhere; the prefetch now records it.
- **H3 (user decision D-056).** Failed downloads leave the draw set; D-026's lobby fetch moves to M-5.
- **M1.** The prefetch stops at once on a local write error and after 5 consecutive failed downloads, instead of requesting all 1,500.
- **M2.** Leftover `.part` files are overwritten, and a lock left by a killed run is reported with a clear message to delete it.
- **M3, M4.** No Range support; an allow-list of response headers.
- **Nit.** Token lifetime is set by S-003; expired tokens are removed.

## Addendum (cycle 031, 2026-11-03)
The cycle 031 spec review showed that OpusTags padding cannot hide the size: the padding is visible to the client, which can subtract it (reproduced on `ShingekiNoKyojin-OP1.ogg`: padded 3,516,260 bytes, recovered 3,494,723 = the public size), and the Opus packet-size sequence and last granule fingerprint the file regardless. A fixed per-file padding would also become a new fingerprint after one reveal. The user chose to accept the risk for now and revisit before M-4 (D-057).
