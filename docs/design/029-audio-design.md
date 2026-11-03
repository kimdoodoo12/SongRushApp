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
- **F6. Leaks.** The sampled files' Opus tags hold only an encoder string (and once `DURATION`); no title, artist or anime. The leaks are the file name, `Content-Disposition`, our URL and anything derived from the basename (ETag). Size and duration are weak fingerprints; a modified client could match sizes against public AnimeThemes data, the same class of risk D-025 accepts.
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
- The backend serves cached files only through per-round opaque tokens, with Range support and no header that names the file.
- The round ends at `min(duration, 90 s)` on the server (D-021); no trimming.
- Hosting (D-010) needs a JVM and about 5.5 GB of disk for the audio cache; no ffmpeg.
- The client sync design (clock offset, `startAt`, Web Audio scheduling, audio unlock) goes to S-003, which defines the realtime protocol.

## Resulting items
- **C-015** data: audio prefetch tool, then the real prefetch run.
- **B-002** backend: catalog loader (reads `data/catalog.json`).
- **B-003** backend: opaque audio endpoint.
- **Needs decision (user's device):** a real-device check on the owner's iPhone (iOS 18.4+) that a decoded Ogg Opus file plays through Web Audio, once a test page exists (after F-001).

## Requests made
Three `GET` (one `Range: bytes=0-99`, two full) and three `HEAD` (403) against `a.animethemes.moe`, 3 s apart, with the SongRush User-Agent; five documentation pages fetched. No fixture saved; the downloaded samples stay outside the repo (`%TEMP%\audio-probe\`).
