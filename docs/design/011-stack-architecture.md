# 011 Stack and architecture: Spring Boot + React (JS) + Python (S-012)

## Questions
D-029 (user, 2026-11-02) fixes the stack:
- the backend is Java Spring Boot on JDK 17;
- the frontend is React in plain JavaScript, with no TypeScript;
- data processing is Python.

The folders are `backend/`, `frontend/` and `data/`. This design answers:
- how the three parts split the work;
- what contracts join them;
- which realtime transport the game uses;
- how behaviour stays identical across Java and Python;
- in what order to build.

Designs 001-010 hold the game rules and the data and audio decisions. They are stack-neutral and stay in force.

## Findings
- **F1. Parts.**
  - Offline data tools run on a developer machine: the AnimeThemes GraphQL crawl, AniList enrichment, Korean title sources, series groups, the catalog build, and the audio cache builder (designs 007, 008 and 010).
  - The runtime game server owns rooms, rounds, chat matching, scoring and audio delivery (designs 009 and 010).
  - The browser client plays audio and handles chat.
- **F2. One cross-language rule.**
  - The data build writes the normalized accepted strings of each series group into `catalog.json`. The game server normalizes each chat message and compares it with those strings. The two normalize functions must agree.
  - The rule comes from design 008 decisions 3-4, plus the hardening added later:
    1. NFKC
    2. lowercase
    3. NFD
    4. remove U+0300-U+036F
    5. NFC
    6. remove every character in Unicode categories P*, S*, Z* and Cf, plus whitespace, the Hangul fillers U+115F, U+1160 and U+3164, and the variation selectors U+FE00-U+FE0F.
- **F3. Language pitfalls for that rule.**
  - **Java:**
    - `\s` is ASCII-only unless `UNICODE_CHARACTER_CLASS` is set.
    - Lowercasing needs `Locale.ROOT`; the Turkish locale maps `I` to a dotless `ı`.
  - **Python:**
    - `re` has no `\p{}`.
    - `\s` also matches U+001C-U+001F and U+0085.
  - **Unicode versions:** Java 17 uses Unicode 13, while Python 3.12 uses 15.0.0 (`unicodedata.unidata_version`, checked by the critic).
  - The user chose Java 17 knowing this (D-030). A character that gained a P, S or Z category after Unicode 13 can therefore normalize differently. This is rare and is **detected, not prevented**, by shared vectors.
- **F4. Versions and tools on this machine.**
  - Spring Boot 4.1.2 is current and needs Java 17 or later (docs.spring.io system requirements, 200). Boot 3.5's open-source support has ended.
  - Installed: JDK 17.0.12, Python 3.12.10 and pip. Not installed: Gradle, Maven, pytest and ffmpeg.
  - PyPI `zstandard` 0.25.0 handles aod's `.zst` (the standard library has no zstd before Python 3.14). `imageio-ffmpeg` 0.8.0 bundles an ffmpeg binary.
- **F5. Realtime transport.** Design 009's needs are:
  - per-room broadcast;
  - per-player private events (each player's snapshot differs);
  - acks;
  - token reconnect with `room:replaced`;
  - a **total order by server arrival** for chat (D-016).

  Spring delivers each WebSocket session's messages on its own thread, so arrival order has to be enforced explicitly. The options:
  - Raw Spring WebSocket handler with a JSON envelope.
  - STOMP with `@stomp/stompjs`. It brings user destinations and a Principal set at handshake.
  - netty-socketio. Its last release was 2.0.13 on 2025-03-19; it has a single maintainer and its own Netty server (GitHub API, 200).
- **F6. External source state at the reset.**
  - AniDB's titles dump was last requested **2026-11-02 18:34 KST**. The once-per-24-hours rule allows the next request from **2026-11-03 18:34 KST**.
  - Everything the earlier TypeScript tools produced is discarded at the reset (D-031): the catalog, the caches and 124 audio files.

## Options
1. **Transport.**
   - (A) Raw Spring WebSocket with `{type, id?, payload}`.
   - (B) STOMP.
   - (C) netty-socketio.

   A maps design 009's event names one to one and keeps snapshots, acks and `replaced` as plain code. B adds broker and Principal machinery that 12-player rooms do not need. C adds a second server stack on a lagging library.
2. **Ordering.**
   - (A) Each room owns a single-thread executor. Every inbound message and every timer for that room runs on it.
   - (B) A lock per room.

   A also serializes the timers with chat, so a round deadline and a correct answer can never interleave.
3. **The lobby fetch (B-008).**
   - (A) Java runs ffmpeg through `ProcessBuilder` under the shared lock and manifest contract.
   - (B) Java calls the Python tool as a subprocess.

   A keeps one runtime process with typed errors. B needs Python on the host.

## Decision
1. **Layout.**
   - `backend/`: Spring Boot 4.1.x, Java 17, the Gradle wrapper, JUnit 5.
   - `frontend/`: React (JSX), Vite, Vitest, Testing Library, ESLint.
   - `data/`: Python package `songrush_data/`, `tests/` with pytest, `requirements.txt`, a `.venv`.
   - `data/catalog.json` is committed. `data/cache/` is gitignored and holds the HTTP caches, the locks, and `audio/` (manifest plus `.m4a` files).
   - The backend reads the data directory from `songrush.data-dir`, which defaults to `../data`.
2. **Transport: raw Spring WebSocket at `/ws`, JSON envelope** (option A).
   - Event names are design 009's.
   - A request carrying `id` gets a reply with the same `id`, which serves as the ack.
   - **Each room runs on its own single-thread executor** (option 2A).
3. **Normalize contract.**
   - `contracts/normalize-vectors.json` holds input and expected pairs written from the rules in F2. The sources are design 008's examples, the edge characters (U+001C-001F, U+0085, U+00A0, U+3000, U+200B, U+00AD, U+FEFF, U+115F, U+1160, U+3164, U+FFA0, U+FE0F, U+0130 `İ`, final sigma) and the scripts in the catalog.
   - Both JUnit and pytest run it.
   - The Stop hook runs both areas when `contracts/` changes.
   - Java uses `Locale.ROOT`, and its whitespace set is explicit rather than `\s`. Python uses `unicodedata.category` per character, also with the explicit whitespace set.
   - The game server re-normalizes `accepted` when it loads the catalog.
4. **File contracts** (data tools to backend). The backend has a test for each.
   - **`catalog.json`:** keys sorted recursively, one compact record per array element on its own line, LF line endings, `version: 1`, at most 8 MB.
     - `anime` holds `{anidbIds, anilistId, groupId, id, name, popularity, revealTitle, season, seriesIds, slug, year}`.
     - `themes` holds `{animeId, artists, audioId, audioSize, audioUrl, estDurationSec, id, slug, song, type}`.
     - `groups` holds `{accepted, groupId}`.
   - **Audio:**
     - `data/cache/audio/manifest.json` is `{themeId: {cacheKey, durationMs}}`, written to a temp file and renamed.
     - `cacheKey` is `sha256("themeId:audioId:audioSize")`, first 16 hex characters. The file is `<cacheKey>.m4a`.
     - The ffmpeg arguments are `-y -i src -t 90 -vn -c:a aac -b:a 96k -movflags +faststart out`.
   - **Lock files** are `{pid, startedAt}`. A lock is stale when its pid is not alive. Python checks with `OpenProcess` through ctypes on Windows; Java with `ProcessHandle.of(pid)`.
   - **The AniDB attempt marker** is `data/cache/korean-anidb-attempt.json` `{attemptedAt: <epoch ms>}`. It is written before the request, and there is no retry of any kind.
   - **`.gitattributes`:** the `-text` rule for fixtures comes **last**, after `*.json text eol=lf` and `*.graphql text eol=lf`, so fixture bytes are never converted.
5. **Lobby fetch in Java** (option 3A). `songrush.ffmpeg-path` defaults to `FFMPEG_PATH`, or to the binary that `imageio-ffmpeg` installed for the data tools.
6. **No database.** Rooms live in memory and the catalog is a file. This differs from BlueArchive's JPA and MySQL on purpose and can be revisited.
7. **Process.** The vibe-cycle is unchanged: spec, critic, tests first and locked, implement, verifier, critic. Each item below is one cycle.

## Lessons carried over
These come from the earlier implementation, which was discarded at the reset. They are requirements for the new specs.
- **HTTP.**
  - Each attempt gets a fresh timeout of at least 60 s, created after the gap sleep, never before it.
  - The gap counts from the end of the previous response body.
  - A 429 `Retry-After` is honored even on the final failed attempt, and it delays the **next** request too: AnimeThemes, the audio host and the lobby queue all share the gap state.
  - A 4xx other than 429 fails at once.
  - Body-read failures are retried.
  - A corrupted cache entry is deleted and fetched once more.
  - A refresh clears only the prefix of the query.
  - `clear("")` is rejected.
- **Crawl.**
  - A page shift on resume restarts the crawl from page 1.
  - Ascending ids across pages are checked. The order held on the one real crawl, with 0 warnings.
  - Unknown theme types are dropped with one warning.
  - The catalog keeps anime with zero themes, for grouping.
  - Selection counts themes.
- **Audio cache.**
  - A stall of 60 s without bytes aborts the connection before the retry.
  - `Content-Length` is checked against the bytes received; a truncated file encodes to a short song.
  - A corrupt upstream file fails only that theme.
  - An fs error, 3 encode failures in a row, or 10 fetch failures in a row stop the run.
  - The CLI rejects a bare positional argument, because `npm run x --limit 3` loses the flag. The same caution applies to Gradle and pip wrappers.
- **Locks.**
  - A stale lock is detected by its pid.
  - Ctrl+C removes the lock.
  - Each lock message names its tool.
- **Audio route.**
  - The token is 32 lowercase hex characters. It expires 60 s after its round, and on room revoke.
  - Range is single only, following RFC 9110: 416 for an unsatisfiable range, and 416 for a 0-byte file.
  - Responses carry `no-store`, with no ETag and no Last-Modified.
  - A client abort before or during streaming must not leak the file handle or log an error. A file deleted after load returns 404, and the server stays up.
- **Rooms.**
  - The ghost timer restarts only on a move from a non-sweepable state to a sweepable one.
  - A socket moves rooms only after the new room accepts it, and the result reports `left`.
  - `bad_request` is checked before every other check.
  - The code retry limit is 20 retries, which is 21 draws.
- **Totals from the one real run.**
  - Catalog: 5,021 anime, 13,899 themes and 3,592 groups, 5.2 MB, built in 13 minutes.
  - Audio: about 25.6 s per theme, so the top 1500 take about 10.7 h.

## Resulting items (in order; ids reuse the original numbers so designs 001-010 still match)
- **Backend first** (it blocks M-1):
  1. B-001 backend bootstrap
  2. B-006 matcher with the vectors
  3. B-002 scoring
  4. B-013 realtime core
  5. B-003 room registry
  6. B-009 registry hardening
  7. B-007 audio store and route
- **Frontend:**
  8. F-001 frontend bootstrap (JS)
- **Data:**
  9. I-002 data bootstrap
  10. C-012 HTTP infrastructure
  11. C-002 AnimeThemes crawler
  12. C-001 transform
  13. C-003 AniList
  14. C-005 Korean sources
  15. C-006 groups
  16. C-004 catalog build
  17. C-007 audio cache
- **Then:** B-008, B-004, B-005, F-002 (WebSocket client), F-003, and I-001.
- **Real runs (lead).** The catalog build runs after C-004, with AniDB only after 2026-11-03 18:34 KST. The top-1500 audio run starts after C-007 and takes about 11 h.

The acceptance criteria for each item are in BACKLOG.md.
