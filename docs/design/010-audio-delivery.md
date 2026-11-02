# 010 Audio delivery: cached full songs, opaque URLs (S-010)

Stack updated for D-029 (2026-11-02); decisions unchanged.

Researched 2026-11-02 by `explorer`. It measured 19 AnimeThemes files, ffmpeg cuts and encodes, bulk-download timings, and browser support. The lead's earlier probes are in `docs/research/game-mechanics-2026-11-02.md` and `docs/research/animethemes-scope-2026-11-02.md`. No audio was put in the repo.

User decisions that changed the question:
- **D-009.** Our server fetches from AnimeThemes and serves the audio through opaque URLs.
- **D-018.** No highlight cutting. The whole TV-size song plays from the start.
- **D-021.** At most the first 90 s plays.
- **D-019.** The popular top ~1500 are pre-fetched; the rest are fetched on demand.

The highlight questions in BACKLOG S-010 are therefore answered by a user decision, not by measurement. The findings are kept for the record.

## Questions (BACKLOG S-010, as they stand after D-018)
1. Which format plays everywhere, and how big is a file?
2. How long does the bulk fetch take, and how much disk does it need?
3. How does a file reach the players without leaking the answer, and how long does it take?

## Findings

### F1. Durations
Source: 19 files (10 OP / 9 ED, 1973-2020; ffmpeg probe).
- **18 of 19 are 66-96 s**. 7 are within 90.0-90.3 s, or 10 within 89.95-90.3 s (critic recount of `hl.json`). Shows from 1973-1994 run 66-71 s.
- One file is a 293 s full-length release (AIKa ED1, 11.5 MB). D-021's 90 s cap covers it.
- The sources are Opus at 320 kb/s, 48 kHz stereo.

### F2. Highlight methods (kept for the record; not used)
- Fixed offsets, the loudest window, the biggest energy rise, and chroma repetition rarely agree. All three non-fixed methods agree within 8 s on only 2 of 19 files.
- Apple previews (chosen by the label) start 30-40 s into 3 of 4 famous OPs.
- D-018 makes this moot.

### F3. Encoding (25 s test cut from KimetsuNoYaiba-OP1)

| Output | Size | Size per second | Encode time |
|---|---|---|---|
| `-c copy` Ogg | 1,018,694 B | — | 0.06 s |
| AAC 96k M4A | 307,028 B | 12.3 KB/s | 0.56 s |
| AAC 128k M4A | 407,742 B | 16.3 KB/s | 0.52 s |
| Opus 96k WebM | 311,833 B | — | 0.25 s |

- `-c copy` starts up to 770 ms early on Ogg page boundaries. Re-encoding is sample-accurate.

### F4. Browser support
- Safari added Ogg Opus/Vorbis only in **18.4** (macOS 15.4, iOS 18.4). Source: https://webkit.org/blog/16574/webkit-features-in-safari-18-4/ (caniuse: iOS partial 11-18.3).
- AAC in MP4 plays in Chrome, Safari and iOS. Firefox plays it through platform codecs (MDN, https://developer.mozilla.org/en-US/docs/Web/Media/Guides/Formats/Audio_codecs).
- **None of this was tested on a real Safari or iOS device.**

### F5. Bulk fetch from `a.animethemes.moe`
Source: 16 logged downloads, run sequentially, with an overlap mistake disclosed by the explorer.
- TTFB: median 2.3 s, maximum 10 s (design 003 had measured 5-44 s on another day).
- Total time per file: median 11 s, maximum 27 s. Throughput was 141-433 KB/s.
- Headers: `nginx`, `Accept-Ranges: bytes`, `Cache-Control: no-cache, private`.
- **No rate-limit headers, and no documented limit for the audio host.** The GraphQL docs state 90/min for the API only.
- The mean file size is 3.23 MB, from 339 fixture audios.

### F6. Sizes and times for D-019's top 1500

| Item | Value |
|---|---|
| Fetch | 1500 × (~12 s + 3 s gap) ≈ 6.3 h on a good day; with design 003/004's TTFB of 5-44 s, about 25-35 s per file. So **6-15 h** once, plus about 45 min of encoding. Later runs fetch only new or changed themes |
| Stored | AAC 96 kb/s, at most 90 s → ≤ 1.1 MB each, so **≈ 1.6 GB** for 1500 |
| Originals | transient; deleted after encoding |
| Encode | ~1500 × ~2 s for 90 s of audio ≈ 1 h CPU (inferred from F3) |

### F7. Delivery time
- A ~1.1 MB file takes about 0.1 s on a LAN and about 9 s at 1 Mbps. Players download in parallel.
- Design 004 measured a skew of ≤13 ms with full prefetch and `delayMs = 3000` (localhost).

## Options
- **Format.**
  - **A: AAC-LC in M4A, one format (chosen).** It works on old Safari and iOS.
  - B: Opus/WebM. It is smaller and faster to encode, but iOS before 18.4 is unsure.
  - C: both formats plus feature detection. That doubles disk and code.
- **Bitrate.** **96 kb/s (chosen)** vs 128 kb/s. 96 kb/s keeps 1500 songs at about 1.6 GB rather than 2.2 GB. The quality difference was not listened to.
- **Where the game pool comes from.**
  - **A: cached themes only (chosen for now; superseded by the Decision update below, D-026 and D-027).**
  - B: any theme, fetched on demand at game creation.

  Under B, a cold theme costs 11-27 s or more upstream (F5), and the swap logic would hit often. D-019's "the rest on demand" is therefore deferred until a mode needs uncached themes (M-5 filters).
  - A cold theme takes 11-27 s plus about 2 s of encoding, which exceeds design 009's 15 s ready timeout.
  - This deferral was asked as D-026. The user chose the lobby prefetch instead; see the Decision update.
- **Client playback.**
  - **A: `fetch` the whole file, then a Blob URL on an `<audio>` element (chosen).** The memory cost is the compressed size, and design 004's skew measurement used `<audio>`.
  - B: `decodeAudioData` into an AudioBuffer, like songspot. That costs about 32 MB of PCM per 90 s stereo buffer (90 × 44100 × 2 × 4 B).

## Decision
1. **Cache builder** (the Python audio cache tool in `data/`, package `songrush_data`, design 011).
   - **Selection.** Sort the catalog themes by their anime's AniList popularity, highest first, then by anime id and theme id. Take the first **1500** OP/ED themes that have audio. N is configurable.
   - **Where it runs.** The builder runs on a developer machine, not on the game host. Its output (the `.m4a` files and the manifest) is copied to the host's **persistent disk**, which needs at least 2 GB. That is a constraint on D-010. Redeploys copy the cache and never re-crawl.
   - **Fetch.** One file at a time from the theme's `audioUrl`:
     - SongRushBot UA, at least 3 s between requests;
     - timeouts: 60 s without receiving any bytes, and 10 min in total. A large file at 141 KB/s needs about 80 s, so a single 60 s total timeout would fail it;
     - honor `Retry-After` on 429;
     - up to 3 attempts per file. After that the theme is marked `failed` and skipped.
   - **Encode** with the ffmpeg binary bundled by `imageio-ffmpeg`:
     `ffmpeg -y -i <src> -t 90 -vn -c:a aac -b:a 96k -movflags +faststart <out>.m4a`
     Then read the duration with ffmpeg's probe output. Delete the source file. The exact argument list shared with the backend is in design 011 decision 4.
   - **Storage.**
     - Encoded files go to `data/cache/audio/<cacheKey>.m4a`, which is gitignored.
     - `cacheKey` = the first 16 hex characters of `sha256(theme id + ":" + audio id + ":" + audio size)`. A changed upstream file gets a new key. No basename is used (design 007).
   - **Manifest.** `data/cache/audio/manifest.json` holds `{themeId: {cacheKey, durationMs}}`.
     - It is rewritten atomically (temp file, then rename) **after every file**, so a crash after hours keeps the progress made.
     - ffmpeg writes to a temp name that is renamed when it finishes, so no half-written `.m4a` is ever listed.
   - **Re-runs.** Themes already present with the same key are skipped.
   - **Errors.** A fetch failure counts toward the 3 attempts for that theme. An encode or write error (such as ENOSPC) **stops the run at once**, rather than hitting upstream 3 times for every remaining theme.
   - **Missing binary.** If the `imageio-ffmpeg` binary is missing (for example when the package is not installed in the `.venv`), the builder exits with a clear message.
   - **Cache state lives only in the manifest.** It is not merged into `catalog.json`.
     - The game server (the Spring Boot backend, reading `songrush.data-dir`, design 011 decision 1) loads the manifest at startup. A theme counts as `cached` only if it is in the manifest **and** its file exists, and it gets `durationMs` from the manifest.
     - This avoids committing a developer's cache state into git, and avoids a build cycle (catalog, then cache, then catalog again).
2. **Serving.**
   - When the server emits `round:prepare`, it creates a random 128-bit token mapped to the cache file. `audioUrl = /audio/<token>`.
   - `GET /audio/<token>` returns the file:
     - `Content-Type: audio/mp4`, `Cache-Control: no-store`;
     - with Range support;
     - **with no `ETag` and no `Last-Modified`**. File times follow build order, which is popularity order.
   - Unknown or expired tokens return 404. A token expires 60 s after its round ends, or when the room is deleted.
   - The token never contains a theme id, slug, name or cache key. The same theme gets a new token every round.
3. **Client** (replaces design 002 D5's seek-on-prepare and its two elements). This is the simplest form that avoids iOS media-load gating. It is plain JavaScript in the React frontend (`frontend/`, design 011).
   - **One `<audio>` element**, unlocked on the user's first tap (the join or start button) with `play()` then `pause()` on a silent source. iOS unlocks media per element and may hold loading and events until then.
   - **On `round:prepare`:**
     1. `fetch(audioUrl)` → Blob.
     2. **Send `round:ready {round, prepareId}` when the Blob is complete.** Readiness is the downloaded bytes, not `canplaythrough`, which iOS may never fire for an element that is not unlocked or not active.
     3. A newer `prepareId` aborts the older fetch and drops its Blob.
   - **Load.** The Blob is set as the element's `src` (Object URL) when the reveal of the previous round starts (6 s), or at once before round 1.
   - **On `round:start`:** wait `delayMs`, then `play()` from 0.
   - **Stop** on `round:reveal`, or at `playDurationMs`. Revoke the Object URL after use.
   - **Reconnect:**
     - if the Blob is gone (page refresh), fetch it again;
     - then seek to `(elapsedMs + localSinceSnapshot) / 1000`, where `localSinceSnapshot` covers the fetch time and the tap overlay (design 002 D4).
   - **I-001 runs first.** It is a small spike page that checks this path on real iOS and desktop browsers before F-003 is built.
4. **Timing** (used by design 009 D1): prepare lead of 1 round, ready timeout 15 s, `delayMs` 3000.
5. **Answer safety and threat model.**
   - Audio requests and responses carry no title information, and the catalog stays on the server.
   - **What remains:**
     - The next round's file arrives a round early, so a player with DevTools can listen ahead.
     - Exact `Content-Length` values could be matched against a cache built the same way.
   - Under first-correct scoring (D-011) that is a real advantage. It is **accepted for friends-only play for now**. The cheap fix is to encrypt each served file with a per-round AES-CTR key (WebCrypto) sent in `round:start`, which hides both the audio and the length. That fix is **D-025** (design 009), asked of the user.
   - D-005 (the anime name in the URL) stays resolved.

## Resulting items
- **C-007** (new) data: audio cache builder (Python).
- **B-007** (new) backend: audio tokens and route (Java).
- **F-003** (new) frontend: audio loader controller (JavaScript).
- **I-001** (new) manual playback check on real browsers, including iOS Safari, before F-003 (it needs the user's devices).
- **B-007** also loads the manifest and exposes `cached` and `durationMs` per theme. The C-004 extension was dropped after review.
- **B-004:** leaves `[?]` once B-007 provides `cached` and `durationMs` (design 009).
- **I-001** moved before F-003 as a spike.
- **D-010** gains the constraint "persistent disk of at least 2 GB".

## Review changes (round 1)
Critic: 0 Blocker, 4 High, 5 Medium, 4 Nit. Measured with the doc's command: 90 s files are 1,099,431 B, 1,105,686 B and 1,099,494 B (sine), each valid AAC-LC M4A of 1:30.00.

| Finding | Change |
|---|---|
| H1: where the 1.6 GB cache is built and kept | Built locally, copied to a persistent disk of 2 GB or more; added to D-010 |
| H2: manifest merged into the catalog put state in two places and made a build cycle | Merge dropped; the server joins manifest and file existence at startup (B-007) |
| H3: iOS may never fire `canplaythrough` for a locked element; two elements to unlock | One element unlocked on the first tap; ready = Blob complete; I-001 spike moved before F-003 |
| H4: D-019 deferral not recorded for the user | D-026 in Needs decision; tracked under M-5 |
| M1: a crash after hours loses progress; ENOSPC loops | Manifest written atomically after each file; temp-then-rename encode; encode and write errors stop the run |
| M2: a 60 s total timeout fails large files | 60 s idle and 10 min total |
| M3: 6.3 h relies on a good day | Stated as 6-15 h |
| M4: prefetch and Content-Length allow listening ahead | Threat model written; encryption option is D-025; no ETag or Last-Modified |
| M5: reconnect without a Blob, and elapsed-time correction | Re-fetch, then seek with `localSinceSnapshot` |
| Nits | F1 count corrected; CLAUDE.md "clips" wording fixed; missing ffmpeg binary gives a clear error; a newer `prepareId` aborts the older fetch |

## Decision update (D-026, user, 2026-11-02)
The user proposed using the lobby wait instead of deferring uncached themes. The number of rounds is known when the room is created, so the songs can be prepared before the game starts.

- **Draw.**
  - On `room:create`, the server draws `rounds` + 3 spare themes without repetition. **D-027 range:** the top 1500 OP/ED themes by popularity, the same set C-007 pre-caches. Widening the range is an M-5 setting.
  - It redraws when the host changes `rounds` in the lobby.
  - Cached themes are used as they are.
- **Lobby prefetch.**
  - Uncached drawn themes are fetched and encoded (decision 1's fetch and encode steps) in one **server-wide queue** shared by all rooms. The queue runs in the Java backend, which starts ffmpeg through `ProcessBuilder` under the shared lock and manifest contract (design 011 decisions 4 and 5). It fetches one at a time, at least 3 s apart, with the SongRushBot UA, so the etiquette is unchanged.
  - Encoded files join the cache and the manifest, so the cache grows with play.
- **Start gate.**
  - The lobby shows `room:prep {ready, total}`, and `game:start` is refused until every drawn theme is ready.
  - At 15-30 s per uncached theme, a lobby of 10 uncached themes would wait about 3-5 min. Under D-027, draws come from the pre-cached set, so a lobby fetch happens only for themes that failed or newly entered the top 1500.
  - The critic computed that a uniform draw over all ~14,600 themes would need about 12 of 13 fetched per room; that is why D-027 limits the range.
- **Failures.**
  - A drawn theme that fails 3 times is replaced by a new draw.
  - During the game, swaps take the spares, which are ready.
- **Leaks.** The drawn list stays on the server. Clients see only counts.
- **Changes elsewhere.**
  - The design 009 D6 "cached only" pool and B-004's `pool_too_small` criterion are replaced by this; B-004 now reads the drawn list.
  - New item **B-008**.
