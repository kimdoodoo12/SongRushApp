# Backlog

Status marks:
- `[ ]` ready
- `[~]` in progress
- `[x]` done (merged to dev)
- `[!]` blocked
- `[?]` needs the user's decision

Cycles take the first `[ ]` under **Ready**. When Ready is empty, a planning cycle turns the first `[ ]` milestone under **Roadmap** into Ready items.

- Only the user reorders or deletes items, and only the user adds Roadmap milestones. The user may also edit anything an agent added.
- Design and planning cycles append the items they produce to the **end** of Ready, marked `(from S-NNN)` or `(from M-x)`.
- Review nits go under **Follow-ups**, which cycles never pick. The user promotes them to Ready if wanted.

Id prefixes: `B-` backend (`backend/`, Spring Boot), `F-` frontend (`frontend/`, React JS), `C-` catalog and data tools (`data/`, Python), `I-` infra, `S-` design (investigation; output is a design doc plus new items), `M-` roadmap milestone, `D-` user decision.

The repo restarted with a fresh history (D-031). Ready below is design 011 "Resulting items", in that order. Item ids reuse the original numbers so designs 001-010 still match. The acceptance criteria carry over design 011 "Lessons carried over", so the bugs found in the discarded implementation are pinned from the first test.

Shared conventions for every item:
- Tests never touch the network. Data tests use the saved fixtures in `data/tests/fixtures/`. Clocks, sleeps, randomness, HTTP and the ffmpeg runner are injected.
- The SongRushBot User-Agent is `SongRushBot/<version> (+https://github.com/kimdoodoo12/SongRushApp)`.
- File contracts between `data/` and `backend/` are design 011 decision 4. The normalize contract is design 011 decision 3.

## Ready

### Backend first (blocks M-1)

- [ ] **B-001** backend: Spring Boot bootstrap (from design 011)
  - `backend/`: Spring Boot 4.1.x on Java 17, the Gradle wrapper committed (`gradlew`, `gradlew.bat`, `gradle/wrapper/`), JUnit 5. No database (design 011 decision 6).
  - Acceptance:
    - `GET /health` returns 200, `Content-Type: application/json`, and the body `{"ok":true}`.
    - Any other path (for example `GET /nope`) returns 404 with a JSON body, not the Whitelabel HTML page.
    - The tests start the app on a random port (`@SpringBootTest(webEnvironment = RANDOM_PORT)`) and call it over HTTP.
    - `gradlew.bat test` and `gradlew.bat build` exit 0 from `backend/`, with no network access needed after the first wrapper download.
    - The data directory is read from `songrush.data-dir`, which defaults to `../data` (design 011 decision 1). A test pins the default.
    - The lead adds the `backend` row to the CLAUDE.md Commands table and makes the Stop hook run `gradlew.bat test` when files under `backend/` changed.

- [ ] **B-006** backend: chat answer matcher with the shared normalize vectors (from design 011 / design 008)
  - A pure `normalize(text)` and `isCorrect(text, accepted)` in the backend's `game` package. Design 008 decisions 3-4, design 011 F2-F3 and decision 3.
  - Rule, in order: NFKC; lowercase with `Locale.ROOT`; NFD; remove U+0300-U+036F; NFC; remove every character in categories P*, S*, Z* and Cf, the explicit whitespace set, U+115F, U+1160, U+3164 and U+FE00-U+FE0F.
  - The explicit whitespace set is written out character by character, never `\s` (Java's `\s` is ASCII-only without `UNICODE_CHARACTER_CLASS`, and Python's also matches U+001C-U+001F and U+0085). The spec lists the set and records, for U+001C-U+001F and U+0085, whether they are removed; the vectors then pin that choice for both languages.
  - This item creates `contracts/normalize-vectors.json`: a list of `{input, expected}` pairs. Inputs that need invisible or combining characters are stored as JSON escapes, and the file is pure ASCII (checked by a test).
  - Acceptance:
    - The vectors include every design 008 example: "귀멸의 칼날" and "귀멸의칼날" give "귀멸의칼날"; "SPY×FAMILY" and "spy family" give "spyfamily"; "Ōkami-san" gives "okamisan"; "ガールズ＆パンツァー" gives "ガールズパンツァー"; "ｶﾞｰﾙｽﾞ" gives "ガールズ"; "Re:ゼロから始める異世界生活" gives "reゼロから始める異世界生活"; "【최애의 아이】" gives "최애의아이"; full-width "ＡＢＣ１２３" gives "abc123"; "Lucky☆Star" gives "luckystar"; "Gintama°" gives "gintama".
    - The vectors include one case per edge character in design 011 decision 3: U+001C-U+001F, U+0085, U+00A0, U+3000, U+200B, U+00AD, U+FEFF, U+115F, U+1160, U+3164, U+FFA0 (NFKC gives U+1160), U+FE0F after ☆, U+0130 `İ` (gives "i"), and a word ending in capital sigma (final sigma).
    - The vectors include at least one title per script present in the catalog (Hangul, kana, kanji, Latin with diacritics, Cyrillic or Greek if present). Every vector character is assigned in Unicode 13, so Java 17 and Python 3.12 should agree (D-030).
    - A JUnit test runs every vector through `normalize` and fails with the vector index on a mismatch.
    - `isCorrect`, with accepted = {normalize("진격의 거인")}: "진격의 거인!!" and "  진격의거인 " are true; "진격의 거" (prefix), "진격의 거인 2기" (extra text), "" and "!!! ..." are false. With accepted = {normalize("귀멸의 칼날")}, "귀멸의 칼남" is false. An accepted set containing "" never matches an empty or punctuation-only message.
    - `isCorrect` does not modify the accepted set.
    - The Java source holds no raw invisible or combining characters: a test reads the source file and asserts it is pure ASCII.
    - Lowercasing does not depend on the default locale: the vectors pass with `Locale.setDefault(new Locale("tr"))`.

- [ ] **B-002** backend: ranking, pure (from design 011 / design 009)
  - Design 009 D3.
  - Acceptance:
    - `rank([{A,3},{B,5},{C,3},{D,0}])` gives B 1, A 2, C 2, D 4.
    - Three players with 0 points share rank 1.
    - The class exposes no time-based score function, because design 009 removed the speed formula.

- [ ] **B-013** backend: realtime core (from design 011)
  - Raw Spring WebSocket handler at `/ws`. Every frame is a JSON envelope `{type, id?, payload}`; event names are design 009 D7's. A request that carries `id` gets one reply with the same `id` (the ack), shaped `{type, id, payload: {ok: true, ...}}` or `{type, id, payload: {ok: false, error}}`.
  - Each room owns a single-thread executor. Every inbound message for the room and every timer of the room runs on it, in submission order (design 011 decision 2, design 009 F2). A message's arrival order is the order in which it was submitted to the room executor.
  - Sends to one session are serialized (Spring's `WebSocketSession` is not safe for concurrent sends; use a decorator such as `ConcurrentWebSocketSessionDecorator` or an equivalent).
  - The core provides the token binding that B-003's join uses: one open session per `playerToken`.
  - Acceptance (integration tests with real WebSocket clients on a random port, plus unit tests on the executor):
    - Malformed JSON, a missing `type`, or a non-object `payload` gets `{ok:false, error:"bad_request"}` (with the `id` when one was parseable) and the session stays open. An unknown `type` gets `unknown_type`.
    - A frame above the size limit fixed in the spec closes the session with status 1009.
    - **Arrival order.** Two sessions in one room send a correct answer to a test handler at the same moment, released together by a `CountDownLatch`. Repeated 100 times: every run has exactly one winner, and the winner is the message submitted first to the room executor. The loser's message is handled after the round ended.
    - **Deadline vs answer.** A round deadline timer and a correct answer are scheduled for the same instant, repeated 100 times: the round ends exactly once per run (either `timeout` or `correct`, never both, never neither), and exactly one end event is broadcast.
    - **Replace.** A second session presenting a `playerToken` already bound to an open session receives the binding; the old session receives `room:replaced` and is then closed. A later close of the old session does not mark the player disconnected.
    - A session close removes its binding and is delivered to its room's executor as a disconnect.
    - A room's executor is shut down when the room is deleted; tasks submitted after that are dropped without an exception reaching the caller.
    - An exception inside one handler is logged, answers that request with `{ok:false, error:"internal"}`, and the room's executor keeps running the next task.

- [ ] **B-003** backend: room registry, pure, with injected clock and random (from design 011 / design 002, 009)
  - Design 002 D4, design 009 D5. No WebSocket and no timers inside; time and randomness are injected. The registry stores no score: the engine owns points, keyed by a `playerId` that is stable across reconnects.
  - Acceptance:
    - **Codes.** 4 characters from `ABCDEFGHJKLMNPQRSTUVWXYZ` (no I or O), each `ALPHABET[floor(random * 24)]`. With `random` scripted as `[0, 0.5, 0.99, 0.25]` the code is "ANZG". 200 seeded codes all match `^[A-HJ-NP-Z]{4}$`. A forced collision retries.
    - **Join errors.** The 13th distinct token gets `full` (disconnected players count). An unknown code gets `not_found`. An unknown token gets `in_game` in each of `loading`, `playing`, `reveal` and `paused`, and is accepted in `lobby` and `finished`. An empty or 17-character trimmed nickname gets `bad_nickname` on create and join.
    - **Reconnect.** After a disconnect, rejoining with the same token returns the same `playerId` with `reconnected:true`, also when the room holds 12 players.
    - **Replace.** The same token from a new socket while the old one is connected returns `replaced` = the old socket id; a later `disconnect(oldSocketId)` returns null and the player stays connected.
    - **Host.** The host is the connected player with the earliest join. When the host leaves, the next earliest connected player becomes host; a disconnected earlier joiner is skipped and gets the host back on reconnect. With nobody connected, `hostId` is null. `leave` returns the new `hostId`.
    - **Empty rooms.** With 0 connected players, `sweep()` keeps the room at +59 999 ms and lists it in `deletedRooms` at +60 000 ms. A reconnect at +30 000 ms prevents the deletion. A lobby room whose last player was removed as a ghost survives until 60 s after it became empty.
    - **Ghosts.** In `lobby` or `finished`, a player disconnected for 30 000 ms is listed in `removedPlayers` and leaves the snapshot. The same player in `playing` is kept.
    - **Snapshot.** Shape `{code, state, settings:{rounds}, hostId, players:[{playerId, nickname, connected, isHost}]}` in join order. Serialized to JSON it contains neither the keys `playerToken` or `socketId` nor any token or socket id value used in the test.
    - **Settings.** Without `settings`, `rounds` is 10. Rounds 4, 31, 10.5 and the string "10" give `bad_settings`; 5 and 30 are accepted. The host can change `rounds` in `lobby`; a non-host gets `not_host`; in `playing` the call gets `not_lobby`.

- [ ] **B-009** backend: room registry hardening (from design 011)
  - Pins the registry defects found in the discarded implementation. New tests go in their own test class; B-003's tests stay unedited.
  - Acceptance:
    - **Ghost timer.** It restarts only on a move from a non-sweepable state (`loading`, `playing`, `reveal`, `paused`) to a sweepable one (`lobby`, `finished`). Scenario: disconnected for 45 s during `playing`, then `finished`: an immediate `sweep` keeps the player and a sweep 30 s later removes them; a rejoin in between keeps the same `playerId`. A move from `lobby` to `finished` (sweepable to sweepable) does not restart it.
    - **Reconnect resets the ghost timer.** Disconnect, reconnect after 10 s, disconnect after another 15 s: the player is still kept 31 s after the first disconnect.
    - **One room per socket.** A socket moves rooms only after the new room accepts it: `create` or `join` with a `socketId` that is current in another room first succeeds in the new room, then marks the old membership disconnected, and the result reports the old room as `left: {code, playerId}`. A failed join (`full`, `not_found`, `in_game`, `bad_nickname`) leaves the old membership untouched and reports no `left`. Afterwards `disconnect(socketId)` affects only the new room.
    - **`bad_request` first.** These return `{ok:false, error:"bad_request"}`, never throw, and win over every other error (an unknown code with a null token gets `bad_request`, not `not_found`): null or non-object `settings`; an empty or non-string `playerToken` or `socketId`; a null settings argument to `setSettings`. Two joins with an empty token never share an identity.
    - Re-joining from the same socket gives no `replaced`.
    - **Code retry limit.** It is 20 retries, which is 21 draws. Scripted randomness where the first 20 draws collide and the 21st is fresh succeeds with the 21st code (84 values, then a fresh 4-value block). Where all 21 draws collide, `create` fails with a clear error (the spec fixes the exception or error code) and makes no 22nd draw.

- [ ] **B-007** backend: audio store, tokens and route (from design 011 / design 010)
  - Design 010 decisions 1-2; the manifest contract is design 011 decision 4.
  - Acceptance:
    - **Manifest.** `<data-dir>/cache/audio/manifest.json` is `{themeId: {cacheKey, durationMs}}`. With a manifest listing themes 3373 and 3374 where only 3373's `<cacheKey>.m4a` exists, `cached(3373)` gives its `durationMs`, `cached(3374)` is empty, and an unlisted theme is empty. A missing manifest gives an empty store. A corrupt manifest fails startup with a message naming the file. `add(themeId, entry)` makes a theme cached at once, after checking its file exists.
    - **Tokens.** `issue(themeId, roomId)` returns 32 lowercase hex characters from 16 random bytes; with injected fixed bytes the token is exactly their hex, so it is not derived from the key or the id. Two issues give different tokens. Issuing for an uncached theme throws.
    - **Route** (real HTTP on a random port, a temp dir with a 1000-byte fake `.m4a`):
      - GET returns 200 with `Content-Type: audio/mp4`, `Cache-Control: no-store`, `Accept-Ranges: bytes`, `Content-Length: 1000`, no `ETag` and no `Last-Modified`, and a body equal to the file.
      - Range is single only, per RFC 9110: `bytes=0-99` returns 206, 100 bytes and `Content-Range: bytes 0-99/1000`; `bytes=990-` returns 10 bytes; `bytes=-10` returns the last 10; `bytes=0-1999` is clamped to `bytes 0-999/1000`; `bytes=-2000` returns the whole file as 206.
      - `bytes=1000-`, `bytes=5000-` and `bytes=-0` return 416 with `Content-Range: bytes */1000`. Any range on a 0-byte file returns 416 `bytes */0`.
      - A malformed header (`bytes=50-10`, `items=0-1`, `bytes=abc`) or a multi-range request returns 200 with the whole file.
      - HEAD returns the same status and headers with an empty body and does not open the file.
    - **Expiry.** An unknown token returns 404. After `expireAfterRound(token)` the token still serves at +59 s on the injected clock and returns 404 at +60 s. `revokeRoom` makes that room's tokens 404 and leaves another room's token serving. `sweep()` with 2 expired tokens and 1 live one returns 2, and the live token still serves; `issue` also sweeps.
    - **Traversal.** `/audio/..%2Fmanifest.json`, `/audio/../manifest.json`, `/audio/<32 hex>/../x` and an uppercase-hex token return 404.
    - **Resilience.** A file deleted after `issue` returns 404 and `/health` still returns 200. Through an injected file opener that counts open streams: a client that aborts after the first chunk of an 8 MB file leaves 0 open streams within 1 s; 50 clients that abort immediately (including while the file lookup is pending) leave 0 open streams and log no ERROR; the server keeps serving afterwards.

### Frontend

- [ ] **F-001** frontend: React JavaScript bootstrap (from design 011)
  - `frontend/`: React with JSX in plain JavaScript (no `.ts` or `.tsx` files and no TypeScript dependency), Vite, Vitest with jsdom and Testing Library, ESLint with a flat config (`eslint.config.js`).
  - Acceptance:
    - One smoke test renders the app and finds `getByRole("heading", { level: 1, name: "SongRush" })`.
    - The test setup cleans up between tests (`afterEach(cleanup)` or Vitest globals).
    - `npm test` runs once with no watch; `npm run lint` runs ESLint over `src` and `tests` with zero warnings allowed; `npm run build` writes `frontend/dist/` (gitignored). All three exit 0 from `frontend/`.
    - A test or lint rule fails if any `.ts` or `.tsx` file appears under `frontend/src`.
    - The lead adds the `frontend` row to the CLAUDE.md Commands table and makes the Stop hook run `npm test --silent` when files under `frontend/` changed and `node_modules` exists.
    - The credit line (D-023) is not required yet; it lands with the first real page.

### Data

- [ ] **I-002** data: Python data bootstrap (from design 011)
  - `data/`: Python 3.12, package `songrush_data/`, `tests/` with pytest, `requirements.txt` pinning `pytest`, `zstandard` and `imageio-ffmpeg`, and a local `data/.venv` (gitignored).
  - Acceptance:
    - `py -3.12 -m venv data/.venv` then `data/.venv/Scripts/python.exe -m pip install -r data/requirements.txt` succeeds on this machine, and `data/.venv/Scripts/python.exe -m pytest -q` exits 0 from `data/`.
    - `songrush_data/common.py` defines the SongRushBot `USER_AGENT`, the gaps (AnimeThemes 3 s, AniList 2 s, audio host 3 s), the request timeout (at least 60 s), and the paths `data/cache/` and `data/catalog.json` resolved from the module file, not the working directory. A smoke test pins each value and checks the UA format.
    - CLIs call `sys.stdout.reconfigure(encoding="utf-8")`; a test runs one CLI entry point with `--help` and decodes its output as UTF-8.
    - `.gitignore` covers `data/.venv/`, `data/cache/` and `__pycache__/`, and does not ignore `data/catalog.json`.
    - `.gitattributes` puts the `-text` rule for `data/tests/fixtures/` **last**, after `*.json text eol=lf` and `*.graphql text eol=lf` (design 011 decision 4). A test reads `git check-attr` output, or the file itself, to confirm the fixture rule wins.
    - The lead adds the `data` row to the CLAUDE.md Commands table and makes the Stop hook run `data/.venv/Scripts/python.exe -m pytest -q` when files under `data/` changed and the venv exists. A change under `contracts/` runs both the backend and the data tests.

- [ ] **C-012** data: HTTP infrastructure (from design 011)
  - `songrush_data/http.py`, `filecache.py` and `lock.py`. Every later data item uses these; there is no second retry loop anywhere. Clock, sleep, the HTTP transport and the timeout factory are injected.
  - Acceptance, retry:
    - Up to 3 attempts, backoff 10 s then 20 s. A 5xx, a thrown request and a body-read failure (the body raises after the headers arrived) are retried.
    - A 4xx other than 429 fails at once after exactly 1 request; the message names the status and the caller's label (for example `page 3`).
    - A 429 counts as an attempt and waits `Retry-After` seconds (fallback 30 s when missing or not a number, cap 300 s): `Retry-After: 5` sleeps 5000 ms, `99999` sleeps 300000 ms, `abc` sleeps 30000 ms.
    - A 429 on the final attempt still records its `Retry-After` as a not-before time in the shared gap state, so the **next** request (another page, another theme, another caller sharing the state) waits for it.
    - `max_attempts=1` and `retry_server_errors=False` options exist (AniDB uses `max_attempts=1`, so even a 429 is not retried).
  - Acceptance, gap and timeout:
    - The gap counts from the end of the previous response body. With a fake transport whose body takes 30 s, every request after the first is preceded by a sleep of the full gap (3000 ms for AnimeThemes).
    - Each attempt gets a fresh timeout of at least 60 s created **after** the gap sleep: the recorded call order is sleep, then timeout creation, then request.
    - Every request sends `USER_AGENT`.
  - Acceptance, file cache:
    - Writes are atomic (temp file, then rename); a write interrupted mid-body leaves no entry.
    - `get`, `put`, `delete(key)` and `clear(prefix)`; `clear("")` raises `ValueError` and deletes nothing. A refresh clears only the given prefix: other prefixes survive.
    - Entries carry their write time so callers can apply a TTL on the injected clock.
    - A helper `get_or_fetch` deletes a corrupt entry (invalid JSON, or rejected by the caller's validator) and fetches once more; if the refetch is also invalid, the error names the cache key and says to delete it.
  - Acceptance, lock:
    - `with_lock(path, tool, fn)` creates parent directories, writes `{pid, startedAt}` exclusively, runs `fn` and removes the file, also when `fn` raises and on `KeyboardInterrupt` (Ctrl+C).
    - A held lock (the current pid) rejects without running `fn`; the message names the tool, the path, the pid and `startedAt`, and says to delete the file if no run is active.
    - A lock with a pid that is not alive (for example 999999), or with unreadable or non-JSON content, is stale: it is removed and the lock taken. Before removing, the content is re-read and compared, so two processes that find the same stale lock cannot both take it.
    - The liveness probe uses `OpenProcess` through `ctypes` on Windows (`os.kill(pid, 0)` is not a probe there); a test checks that the current pid is alive and 999999 is not.

- [ ] **C-002** data: AnimeThemes GraphQL crawler (from design 011 / design 001, 007)
  - POSTs the pinned query in `data/animethemes.graphql` to `https://graphql.animethemes.moe/` with `first:100` and `page` = 1.., following `paginatorInfo.hasMorePages`. Uses C-012. Design 007 decision 3.
  - Acceptance:
    - A test asserts the query file equals the query in `data/tests/fixtures/gql_page_request.json`.
    - Requests use the AnimeThemes gap (3 s), a fresh 60 s timeout per attempt and the SongRushBot UA, asserted on the injected clock and transport.
    - With `gql_page1.json` as page 1 and a synthetic last page with `hasMorePages:false`, it makes exactly 2 requests and returns both pages.
    - Pages are cached under a key that includes a hash of the query text: a second run with a full cache makes 0 requests; changing the query text fetches again. `refresh=True` clears only this query's prefix.
    - A 200 with a non-empty `errors` array fails at once with `page N` in the message, after exactly 1 request, and caches nothing.
    - A page item missing `animethemes` or `id` fails and names the page. A cached page that fails this check is deleted and fetched once more.
    - Page ids must ascend: page k+1's first id is greater than page k's last id; otherwise one warning names both pages. A duplicate id across pages fails and names both pages.
    - **Page shift on resume.** When resuming from cache, the last cached page is refetched and compared; a mismatch clears the query's cache and restarts from page 1 with one warning.
    - A total different from `paginatorInfo.total` logs one warning.
    - Progress is logged per page: `AnimeThemes page N/lastPage (cached|fetched)`.

- [ ] **C-001** data: AnimeThemes anime to catalog transform, pure (from design 011 / design 001, 007, 008)
  - A pure function from one GraphQL `anime` item (the shape of `gql_page1.json` items) to `{anime, themes}`. Design 001 decisions 2 and 5, design 007 decision 2.
  - Acceptance (over `data/tests/fixtures/`):
    - `gql_bakemonogatari.json` gives exactly 6 themes: theme ids 3373-3378, audio ids OP1 2563, OP2 2564, OP3 2565, OP4 2566, OP5 2567, ED1 2562. OP5 (all nsfw) is kept. OP1 has `estDurationSec` 90 (informational; the game reads `durationMs` from the audio manifest). The anime has AniList id 5081, `seriesIds` [116], season `summer` and `anidbIds` [6327] (every ANIDB resource).
    - In `gql_page1.json`: `akuma_no_riddle` OP1 (theme 5705) yields audio 4743, the lowest version; `akage_no_anne` ED1 (1,415,172 B) is excluded by the 1.5 MB floor and `ajin` ED1 (2,254,676 B) is kept, so exactly 349 of the page's 358 themes are kept; `aishen_qiaokeli_ing` OP1 (`song` null) is kept with an empty song title and no artists.
    - A synthetic theme with no audio is excluded. An anime whose themes are all excluded is still returned with zero themes (it is needed for grouping).
    - A theme whose `type` is not OP, ED or IN is dropped; across a page, one warning gives the count and the distinct values (a synthetic `type: "XX"` produces one warning containing "XX").
    - No output field contains the audio basename or link except `audioUrl`.

- [ ] **C-003** data: AniList enrichment (from design 011 / design 001, 008)
  - Batches AniList ids into `media(id_in: [...])` queries of at most 50, selecting `id popularity title{romaji english native} synonyms relations{edges{relationType(version:2) node{id type}}}`. Adds a nested `anilist: {popularity, titles {romaji, english, native}, synonyms, relatedIds} | null`, so AnimeThemes `synonyms` stay intact. `relatedIds` are ANIME nodes reached by SEQUEL, PREQUEL or SUMMARY edges. Uses C-012 with a 2 s gap. Design 008 decisions 1-2.
  - Acceptance:
    - With `anilist_id_in.json` as the response, anime 101922 gets popularity 1010700 and the synonym "귀멸의 칼날", and anime 5081 gets popularity 347014.
    - With `anilist_titles_sample.json` (`top50`), anime 16498 gets romaji "Shingeki no Kyojin", english "Attack on Titan" and native "進撃の巨人".
    - With `anilist_relations_franchises.json`, anime 356 (Fate/stay night) has 11741 in `relatedIds`, and CHARACTER or ALTERNATIVE edges are not included.
    - Anime without an AniList id keep `popularity: null` and empty titles. 120 ids produce 3 requests. The request text contains `relationType(version:2)`.
    - Retry paths through C-012: a thrown request is retried; two 503s then success sleep 10000 and 20000; a 403 fails after 1 request; `{"data":{}}` fails with `chunk 1` in the message.
    - `refresh=True` clears only the AniList prefix. A corrupted cached chunk is deleted and fetched once more.
    - Progress is logged per chunk: `AniList chunk k/n`.

- [ ] **C-005** data: Korean title sources (from design 011 / design 008)
  - Parsers and fetchers for anime-offline-database (aod), Wikidata and AniDB, using C-012. Design 008 decisions 2 and 7.
    - aod: decompress `.zst` with `zstandard` and parse JSON Lines; map `https://anilist.co/anime/<id>` to its strings that contain Hangul.
    - Wikidata: one SPARQL query by P8729 for `ko` labels and altLabels.
    - AniDB: gunzip `anime-titles.xml.gz` and parse it with a real XML parser (`xml.etree.ElementTree`), so self-closing tags, CDATA and comments are handled; keep `xml:lang="ko"` titles with their type, keyed by aid. AniList ids are joined through C-001's `anidbIds` (all of them).
  - Acceptance:
    - `wikidata_ko_labels_sample.json` (`top50`) gives AniList 5114 "강철의 연금술사 BROTHERHOOD". A synthetic response with a `ko` altLabel "강철의 연금술사 FA" for 5114 adds it too.
    - A synthetic aod line (compressed in the test with `zstandard`) with source `https://anilist.co/anime/16498` and synonyms ["Attack on Titan","진격의 거인"] gives 16498 only ["진격의 거인"].
    - A synthetic AniDB XML (gzipped in the test) with aid 9541, titles ko "진격의 거인" (official) and ja "進撃の巨人", plus a self-closing title, a comment and a CDATA title, yields [["official","진격의 거인"]] for aid 9541 (plus the CDATA title if it is `ko`).
    - **TTL on the injected clock.** AniDB is not refetched at 23 h and is refetched at 25 h; aod and Wikidata are not refetched at 6 days and are refetched at 8 days; a second run inside the windows makes 0 requests.
    - **AniDB once per 24 h.** `data/cache/korean-anidb-attempt.json` `{attemptedAt: <epoch ms>}` is written **before** the request (asserted by call order). With an attempt less than 24 h old there is no AniDB request at all. AniDB is never retried: a 500, a thrown request or a 429 each make exactly 1 call and give `anidb: null` (or the stale cache).
    - **Failures.** aod and Wikidata use up to 3 attempts. A source that fails is skipped and logs `korean source <name> unavailable`; with an expired cache entry present, that entry is used and `korean source <name> stale` is logged. An interrupted download leaves no cache file.
    - Every request sends the SongRushBot UA.

- [ ] **C-006** data: series groups and accepted strings, pure (from design 011 / design 008)
  - Python `normalize` in `songrush_data/` plus grouping. Design 008 decisions 1-3 and 5, D-020, D-022.
  - Acceptance:
    - Python `normalize` passes every vector in `contracts/normalize-vectors.json` (the same file JUnit runs). It uses `unicodedata.category` per character and the explicit whitespace set, never `re`'s `\s`. A test asserts the module source is pure ASCII.
    - Synthetic anime for AniList 4654, 8937, 6213 and 16049, all with `seriesIds` [170] and `relatedIds` from `anilist_relations_franchises.json`: 4654 and 8937 share a group, and no Index entry shares a group with a Railgun entry.
    - AniList 10087, 11741 and 356, all with `seriesIds` [42], share one group.
    - An anime with empty `seriesIds` is alone, even if `relatedIds` link it to others. An anime without an AniList id is alone.
    - Chain through a missing node: A (relatedIds [B]) and C (relatedIds [B]), B not in the catalog, A and C sharing series 1, are united; with no shared series they are not.
    - `groupId` is the smallest AnimeThemes anime id in the group. Accepted strings per group are sorted, de-duplicated and equal to `normalize(raw)`; an empty normalized string is never stored.
    - "Rurouni Kenshin: Meiji Kenkaku Romantan (2023)" also adds the string without " (2023)". "나루토 (애니메이션)" also adds "나루토".
    - Reveal titles: 16498 with AniDB official "진격의 거인" and english "Attack on Titan" reveals "진격의 거인"; an anime whose only Korean is the Wikidata label "나루토 (애니메이션)" reveals "나루토"; with no Korean it falls back to english, then romaji.
    - Every alias from the sources is kept (D-022).

- [ ] **C-004** data: catalog build CLI (from design 011 / design 001)
  - Chains C-002, C-001, C-003, C-005 and C-006 and writes `data/catalog.json` in the design 011 decision 4 format.
  - Acceptance:
    - **Format.** Keys sorted at every depth; one compact record per array element on its own line; LF line endings; trailing newline; `version: 1`; `anime` records have exactly `{anidbIds, anilistId, groupId, id, name, popularity, revealTitle, season, seriesIds, slug, year}`, `themes` exactly `{animeId, artists, audioId, audioSize, audioUrl, estDurationSec, id, slug, song, type}`, `groups` exactly `{accepted, groupId}`; arrays sorted by id or groupId. The file parses back as JSON.
    - **End to end with fixtures.** AnimeThemes `gql_page1.json` (`hasMorePages:false`, `total:100`, `lastPage:1`); synthetic AniList media for page 1's ids (popularity = 1000 + id; two anime sharing a series linked by a SEQUEL edge; other ids missing); Wikidata `random50` from `wikidata_ko_labels_sample.json`; a small zstd aod; a small gzip AniDB. Then 100 anime and 349 themes; the group count equals 100 minus the merges the test set up; the linked anime share a `groupId`; an anime with a Wikidata ko label reveals it; every anime's `groupId` appears in `groups`; every theme has `audioId`.
    - **Determinism.** Two runs with fresh fakes produce byte-identical output.
    - A Korean source returning 500 still gives a successful build.
    - Output above 8 MB fails with a message containing "8 MB" and the actual size. The run logs anime, theme and group counts and the byte size.
    - The CLI writes atomically (temp file, then rename), holds `data/cache/catalog-build.lock` through C-012 `with_lock` with the tool name, accepts `--refresh`, and rejects bare positional arguments and unknown flags with a usage message.
  - After merge (lead): one real build under the External source rules. AniDB may be requested only from **2026-11-03 18:34 KST**; a build before that runs with AniDB skipped by the attempt marker, or waits. Record counts, size, duration and whether page ids ascended in PROGRESS.md, and commit `data/catalog.json`.

- [ ] **C-007** data: audio cache builder (from design 011 / design 010)
  - Downloads the top N (default 1500) themes from `a.animethemes.moe` one at a time and encodes each to `data/cache/audio/<cacheKey>.m4a`. Design 010 decision 1, D-019, D-027, design 011 decision 4. Uses C-012; ffmpeg comes from `imageio-ffmpeg` and runs through an injected runner. The single-theme step `cache_one` is a public function with typed errors (`fetch`, `encode`, `fs`).
  - Acceptance:
    - **Selection** counts themes, not anime: OP or ED only, with non-null `audioId` and `audioUrl`, sorted by the anime's popularity (null last), ties by anime id then theme id. Synthetic anime with popularity 100, 300, 200 and null, 2 themes each, N = 6: 300's pair, then 200's, then 100's; the null anime's themes come last. IN themes and themes without audio are never selected.
    - **cacheKey** is the first 16 hex of `sha256("themeId:audioId:audioSize")`: stable, and different when only the size changes. One fixed triple and its expected key are pinned here and reused verbatim by B-008's test.
    - **ffmpeg args** are exactly `-y -i src -t 90 -vn -c:a aac -b:a 96k -movflags +faststart out`, output under `<dir>/tmp/` then renamed after exit 0. The ffmpeg call has a timeout (5 min) that kills the process. The duration is parsed from `Duration: HH:MM:SS.cc` (`"  Duration: 00:01:30.02, start: 0.000000"` gives 90020; none gives null, which is an encode failure).
    - **Etiquette.** Every request sends the UA and `Accept-Encoding: identity`; with responses taking 5000 ms each request starts at least 3000 ms after the previous body ended; `Retry-After: 7` sleeps 7000 then succeeds; a 404 makes exactly 1 request and the theme is `failed`; a final 429's `Retry-After` delays the next theme's request.
    - **Body checks.** A body that yields one chunk and then stalls for 60 s without bytes fails that attempt, and the attempt's connection is closed before the next attempt's request starts. A `Content-Length: 1000` response with 500 bytes fails the attempt. An `audioSize` mismatch only warns.
    - **Failures.** A corrupt upstream file (ffmpeg nonzero) fails only that theme: no rename, no manifest entry, the next theme is cached. A theme that fails its fetch 3 times is `failed` and the run continues. An fs error (simulated ENOSPC), 3 encode failures in a row, or 10 fetch failures in a row stop the run with no further requests.
    - **Crash safety.** After every cached theme the manifest is written to `manifest.json.tmp` and renamed; a run that dies on theme 3 of 4 keeps 2 entries. The `.src` download is deleted in every case.
    - **Re-run and sharing.** A second run with every file present makes 0 requests; a missing file is refetched. Two selected themes with the same `audioId` and size are fetched and encoded once and share a `cacheKey`.
    - **CLI.** `--limit N` (also `--limit=N`), default 1500; bare positional arguments and unknown flags are rejected with a usage message; it holds `data/cache/audio-cache.lock` with the tool name, removed on Ctrl+C; a missing ffmpeg binary exits with a message naming `imageio-ffmpeg`.
    - **Real ffmpeg smoke test** with no media in git: generate a 120 s sine as `.ogg` with `-f lavfi -i sine=frequency=440:duration=120` in a temp dir, encode it, and check `ftyp` at bytes 4-8 and a parsed duration between 89900 and 90100 ms.
  - After merge (lead): run `--limit 3` and record sizes, durations and time per file, then the full top-1500 run in the background (about 25.6 s per theme, about 11 h).

### Then

- [ ] **B-008** backend: catalog load, room theme draw and lobby prefetch (from design 011 / design 010, D-026, D-027)
  - Loads `data/catalog.json`, draws a room's themes, and fetches and encodes uncached ones in one server-wide queue, in Java (design 011 decision 5: `ProcessBuilder` ffmpeg, `songrush.ffmpeg-path` defaulting to `FFMPEG_PATH` or the `imageio-ffmpeg` binary). Depends on B-003, B-007 and B-013.
  - Acceptance:
    - **Catalog contract.** A fixture in the design 011 decision 4 shape loads; a file with a `version` other than 1, or with a missing required key, fails startup with a message naming the field. On load every `accepted` string is re-normalized with B-006's `normalize`.
    - **acceptedForAudio.** For a theme whose `audioId` also appears under another anime, the accepted set is the union of both anime's groups (D-020). An empty union is logged as a warning naming the theme.
    - **Draw.** On `room:create` with `rounds` 10, 10 themes plus 3 spares are drawn without repetition from the top 1500 OP/ED themes by popularity (the C-007 selection rule, pinned with C-007's synthetic case). A seeded random gives a deterministic list. The range size is a parameter (M-5). A room never draws two themes with the same `audioId`.
    - `room:settings {rounds: 12}` in the lobby redraws 12 + 3 and cancels queued fetches no longer needed.
    - **Queue.** Uncached drawn themes go to one server-wide queue: one upstream fetch at a time, at least 3 s after the previous body ended, SongRushBot UA, a fresh timeout per attempt created after the gap, 429 `Retry-After` honored and delaying the next request even after a final failed attempt. A theme queued by two rooms is fetched once. A room's queued (not started) fetches are dropped when the room is deleted.
    - **Encode contract.** The cacheKey of C-007's pinned triple matches exactly; the ffmpeg arguments equal C-007's; the stall, `Content-Length` and ffmpeg-timeout rules match C-007.
    - `room:prep {ready, total}` is emitted as themes become ready. `game:start` before `ready == total` gets `not_ready`. A drawn theme that fails 3 times is replaced by a new draw, which is queued.
    - After a lobby encode, B-007's `cached` reports the theme with its `durationMs` and `issue` serves it without a restart. The manifest on disk is rewritten via temp file and rename, under `data/cache/audio-cache.lock` with the `{pid, startedAt}` format; a stale lock is detected with `ProcessHandle.of(pid)`, and a lock held by a live process makes the queue wait rather than write.
    - No event or snapshot field contains a drawn theme's id, title or cache key; only counts.

- [ ] **B-004** backend: round lifecycle, with injected clock and random (from design 011 / design 002, 009, 010)
  - Runs on the room executor (B-013). Uses the drawn list (B-008) and `durationMs` (B-007). Design 009 D1, D6 and D7; design 002 D1 for the quorum and swap.
  - Acceptance (each is a test):
    - `round:prepare` for round n+1 is sent when round n starts, with `round`, `prepareId` and an opaque `audioUrl`, and no anime id, name, slug or `offsetSec`.
    - Only a ready with the latest `prepareId` counts. After a swap the ready set starts empty.
    - Ready timeout 15 s on the injected clock: with 4 connected and 1 ready the theme is swapped; the 3rd consecutive timeout for a round ends the game with `audio_unavailable`; with 0 connected the state is `paused`. A swap with no unused spare ends the game with `audio_unavailable`.
    - Players not ready at round start are `missed`.
    - `playDurationMs` is `min(durationMs, 90000)`: 293000 gives 90000, 66000 gives 66000. At `roundStartTime + playDurationMs` the round ends with `timeout` and no winner.
    - `endRound(correct, winnerId)` and `endRound(skipped)` move to `reveal` at once; the reveal lasts 6 s.
    - **Stale timers.** Round 1 ends `correct` at +2 s and round 2 starts after the reveal. At round 1's start +90 s, round 2 is still `playing` and no second reveal for round 1 is sent (every timer checks a `roundSeq`).
    - After `settings.rounds` reveals (tested with 5 and 12) the state is `finished` with a B-002 ranking.
    - Themes come from the drawn list in order; a swap takes the next spare.
    - Every room in `registry.sweep().deletedRooms` gets `audio.revokeRoom(roomId)` and its executor shut down. A round's token gets `expireAfterRound` on **every** end path: correct, skip, timeout, swap and game abort; a swapped-out prepare token is expired too.

- [ ] **B-005** backend: chat, skip, reveal and snapshot (from design 011 / design 009)
  - Runs on the room executor. Uses B-004 and B-006. Design 009 D2, D4 and D7.
  - Acceptance (each is a test):
    - **Chat.** In `playing`, a matching message from an active player ends the round `correct` with that `winnerId`, gives 1 point, and is **not** broadcast; "<name> 정답!" is announced. A wrong message is broadcast as `chat:message`. Two matching messages handled back to back: only the first wins; the second arrives in `reveal`, is broadcast as normal chat and gives no point. During the lead-in (after `round:start`, before `roundStartTime`) a matching message neither wins nor is broadcast, and a non-matching one is broadcast.
    - **Restricted senders.** During `playing`, any message from a `missed` player gets `{error:"missed"}` and is neither compared nor broadcast, including "답 귀멸의칼날임". In `reveal` a missed player's message is broadcast.
    - **Limits.** Text over 100 characters, or empty after trimming, is rejected. Token bucket burst 5, 2 per second (D-024): six messages at one injected instant, the 6th gets `rate_limited` and is not compared, and a rejected message uses no token; at +0.5 s one message is accepted and a second at the same instant rejected.
    - **Skip.** With 5 connected, `needed` is 3 and the 3rd `on` vote ends the round `skipped` with no winner. With 2 connected, one vote does not pass and two do. An `off` vote removes the vote. A disconnect removes that player's vote and recomputes `needed`: with 4 connected and 2 votes, a non-voter disconnecting passes the skip. A vote for a stale round is ignored.
    - `round:reveal` has `anime {revealTitle, year}`, `theme {slug, song, artists}`, `reason`, `winnerId` (when there is one) and `scores`.
    - **Snapshot** during `playing` is built per player with `elapsedMs`, `playDurationMs`, `myStatus`, `skip {votes, needed, mine}`, `nextPrepare` and `settings`; it contains no `playerToken`, no anime id, name or group, no accepted string and no hidden message, and `audioUrl` is opaque.

- [ ] **F-002** frontend: WebSocket client wrapper (from design 011)
  - `frontend/src/net/`. The `WebSocket` constructor, timers and storage are injected, so it runs under Vitest without a server. Envelope `{type, id?, payload}` (design 009 D7).
  - Acceptance:
    - `request(type, payload)` sends an envelope with a fresh `id` and resolves with the reply that carries the same `id`; replies arriving out of order resolve the right requests. A reply with an unknown `id` is ignored.
    - A request with no reply within the timeout (fixed in the spec) rejects with `timeout`, and a late reply for it is ignored.
    - Server events without `id` go to subscribers by `type`; unsubscribing stops delivery.
    - When the socket closes, pending requests reject with `disconnected`. The client reconnects with backoff on the injected timer and, once open, re-sends the stored `playerToken` and room code (`room:join`) before any queued request.
    - `room:replaced` stops reconnecting and is surfaced to the app.
    - The `playerToken` lives in `sessionStorage` and is created once per tab.

- [ ] **F-003** frontend: audio loader controller (from design 011 / design 010)
  - `frontend/src/audio/`. Design 010 decision 3. The media element, fetch and timers are injected.
  - Acceptance:
    - `unlock()` on the first tap calls `play()` then `pause()` on the single element.
    - On prepare it fetches the URL once and sends ready with the latest `prepareId` when the Blob is complete; no media event is needed.
    - A newer `prepareId` aborts the older fetch and drops its Blob, and no ready is sent for the old one.
    - The next Blob is set as `src` when the previous reveal starts, or at once for round 1.
    - On start it calls `play()` after `delayMs` on the injected timer, from `currentTime` 0. It pauses on `round:reveal` or at `playDurationMs`, and revokes the Object URL.
    - On resume with `elapsedMs` 12000, a missing Blob is fetched again; if the fetch plus overlay took 3000 ms on the injected clock, it sets `currentTime` 15 before `play()`.

- [?] **I-001** spike: playback on real browsers (from design 010), before F-003 ships to players. **Needs the user's devices** (iPhone, Android); agents prepare the spike page and the user runs it
  - Using a minimal static page, play one 90 s AAC/M4A file via fetch, then Blob, then one `<audio>` unlocked by a tap (design 010 decision 3) on desktop Chrome, Firefox and Safari, and on iOS Safari and Android Chrome. Record OS and browser versions, whether it played, and the audible start, in PROGRESS.md. Any failure sends design 010's format choice back for review.

## Roadmap

Milestones in order. Planning cycles break the first `[ ]` one into Ready items.

- [ ] **M-1** Solo prototype: one player opens the site, hears a song from the start (at most 90 s, designs 009 and 010), guesses the anime by chat, sees right/wrong and the reveal, and plays the next round.
- [ ] **M-2** Rooms: create a room, share a code or link, others join with a nickname, lobby shows players, host starts the game.
- [ ] **M-3** Synchronized multiplayer rounds: every player hears the same song at the same server-announced time, the first correct answer scores, players can vote to skip, round results and a final ranking are shown.
- [ ] **M-4** Deploy: a public URL friends can use, with the credit line (D-023). Hosting (D-010) needs a user decision first. Revisit D-025 (prefetch fairness) before planning.
- [ ] **M-5** Anime-specific modes: OP/ED filter, year range, popularity difficulty.

## Needs decision

- [?] **D-010** Hosting choice (design 005). There is no recommendation, because it depends on which of these the user accepts:
  - a credit card (required on Fly, Koyeb and Cloud Run; practically required on Oracle; likely on Lightsail);
  - a cold start (Render: sleeps after 15 min, about 1 min to wake; a JVM start adds to it);
  - idle reclamation (Oracle);
  - a region outside Seoul or Tokyo (Render, Railway and Hetzner reach Singapore at best; Koyeb's free tier is EU/US only).

  Constraints from the stack (D-029, design 011) and design 010: the host must run a long-lived JVM (Java 17) process with WebSocket support, a persistent disk of at least 2 GB for the audio cache, and an ffmpeg binary for the lobby fetch (D-026). This rules out Cloudflare Workers, Render free, Railway free, Koyeb free and Cloud Run's in-memory filesystem. Free tiers with little memory may be tight for a JVM; that is unmeasured. Seoul or Tokyo is available on Oracle, Lightsail, Cloud Run and Fly (Tokyo). Egress cost is at most about $5/mo even under D-009 option C. Non-blocking until M-4.
- [?] **I-001** Real-device playback check (see Ready). Needs the user's iPhone and Android phones.

Decided:
- **D-001** GitHub remote: private repo `kimdoodoo12/SongRushApp` (user, 2026-11-02).
- **D-002** Audio source: AnimeThemes direct playback (user, 2026-11-02). *Superseded by D-009.*
- **D-003** Answer target: anime title (user, 2026-11-02).
- **D-004** Round format: fixed clip + time limit (user, 2026-11-02). *Replaced by D-018/D-021.*
- **D-005** Answer leak: resolved by D-009 (opaque URLs).
- **D-006** Dataset (user, 2026-11-02): do not use `nonlooped/anime-op-ed-dataset`; build our own catalog from AnimeThemes + AniList (C-001..C-004), linked by AniList id. AnimeThemes stays the audio source. Korean titles come from C-005 (aod, Wikidata, AniDB). Evidence: `docs/research/game-mechanics-2026-11-02.md`, `docs/research/animethemes-scope-2026-11-02.md`.
- **D-007/D-008** Answer matching (user, 2026-11-02): a guess is correct only if it is a full official title (Korean, English or Japanese; no nicknames or abbreviations). Season and sequel suffixes ("Season 2", "Final Season", etc.) are not required: any title of the same series counts. The reveal shows the exact entry's title, season included. Open at the time: whether romaji counts as "Japanese" (assumed yes, since it is the AnimeThemes primary name), how series are grouped, and where Korean official titles come from (design 008). Resolved later: romaji counts (D-015), grouping (D-020), Korean sources (design 008). "No nicknames" is relaxed by D-022 (all source aliases accepted for now).
- **D-009** Audio delivery (user, 2026-11-02): the server fetches audio from AnimeThemes and serves only the round's highlight clip through opaque URLs, like songspot.co. (The highlight clip was later replaced by the whole song from the start: D-018, D-021, design 010.) Reverses D-002 after designs 003/004 measured direct playback as unusable. Pipeline: design 010.
- **D-011** Scoring (user, 2026-11-02): only the first correct answer in a round scores (like the StarCraft use-map quiz). Replaces design 002's speed formula; details in design 009.
- **D-012** Skip (user, 2026-11-02): players can vote to skip a round. Details in design 009.
- **D-013** Hints (user, 2026-11-02): none for now; revisit at feature expansion.
- **D-014** Rounds per game (user, 2026-11-02): the host sets it when creating the room. Details in design 009.
- **D-015** Answer input (user, 2026-11-02): chat, like the StarCraft use-map quiz. Every chat message is compared on the server with the full official titles (KO/EN/JA/romaji), ignoring spaces, punctuation, case and width; spelling must match. Unlimited attempts, no cooldown. Wrong guesses are visible as chat; the correct message is hidden and announced. Replaces autocomplete (design 002 D3).
- **D-016** "First" = server arrival order (user, 2026-11-02). No latency compensation.
- **D-017** Skip vote passes with more than half of connected players (user, 2026-11-02).
- **D-018** Playback (user, 2026-11-02): the whole AnimeThemes file (TV size, ~90 s) from the start; the round ends at the first correct answer, a passed skip, or the song's end. The round ends immediately on a correct answer. Replaces the fixed-clip/highlight plan; no cutting.
- **D-019** Caching (user, 2026-11-02): pre-fetch the popular top ~1500 themes; fetch the rest on demand. (The on-demand part was refined by D-026 and D-027.)
- **D-020** Series grouping (user, 2026-11-02): the automatic rule as-is (AnimeThemes `series` intersected with AniList SEQUEL/PREQUEL/SUMMARY chains); no manual splits, so Fate/Zero and Fate/stay night stay together. Principle: the revealed answer is the entry's own title, but the accepted range is wide.
- **D-021** Long files (user, 2026-11-02): play at most the first 90 s; if nobody answers, reveal.
- **D-022** Aliases (user, 2026-11-02): keep every alias from the sources for now; filtering rules come later (Follow-ups).
- **D-023** Attribution (user, 2026-11-02): yes, one line in the footer or an about page: "데이터 출처: AnimeThemes, AniList, anime-offline-database, AniDB, Wikidata".
- **D-024** Chat flood limit (user, 2026-11-02): keep the token bucket (burst 5, 2/s) from design 009 D2.
- **D-025** Prefetch fairness (user, 2026-11-02): accept for now (friends-only); revisit before M-4.
- **D-026** Uncached themes (user, 2026-11-02): the room's themes are drawn when the room is created (and redrawn when the host changes `rounds`), and uncached ones are fetched and encoded while the lobby waits. Start is enabled only when every drawn theme is ready. Replaces design 010's "cached only" pool; see design 010 "Decision update".
- **D-027** Draw range (user, 2026-11-02): rooms draw from the top 1500 OP/ED themes by popularity (the pre-cached set). Widening the range is an M-5 mode or difficulty setting. Uncached draws, such as failed or new entries, still use the lobby prefetch (D-026). This keeps lobby waits short and bounds disk growth.
- **D-028** Working mode (user, 2026-11-02): when a decision is needed and the user is present, ask at once and never block. When the user is absent, use Needs decision. CLAUDE.md and the vibe-cycle skill updated.
- **D-029** Stack (user, 2026-11-02): **Spring Boot + React (JS, no TypeScript) + Python**: a Java Spring Boot backend, a React frontend in plain JavaScript (JSX), and Python data processing. The user also wants no TypeScript anywhere unless it is truly needed, for consistency; nothing needs it. The user had assumed this stack (the same one as BlueArchiveBriefingApp) from the start. The lead had chosen Node.js + TypeScript in design 001 without asking, which was a mistake. Plan: design 011. Game rules and decisions D-001..D-028 carry over unchanged. What happens to the earlier implementation and its outputs is D-031.
- **D-030** Layout and Java version (user, 2026-11-02): the folders are `frontend/`, `backend/` and `data/`, like BlueArchive. The backend uses Java 17. The user accepted that Java 17 (Unicode 13) and Python 3.12 (Unicode 15) can normalize a rare character differently; the shared normalize vectors detect such drift rather than prevent it (design 011 F3).
- **D-031** Fresh start (user, 2026-11-02): discard the TypeScript implementation, its cycle records, the old git history and every artifact it produced (`catalog.json`, the HTTP caches and the 124 cached audio files); the repo restarts with a new history. Kept: the decisions, the designs (rewritten for the stack), the research, the raw API fixtures and the vibe-cycle tooling. The AniDB titles dump was last requested 2026-11-02 18:34 KST, so the next AniDB request is allowed from **2026-11-03 18:34 KST** (design 011 F6).

## Follow-ups
- Alias filtering rules (short aliases, chat stop-words, digit-only, cross-series collisions) deferred by the user (D-022); evidence and proposed rules in `docs/research/title-db-audit-2026-11-02.md`.
- Korean title gaps for popular anime (54 of the top 300 groups had none in the audit): curated file proposal pending; see the same audit.
- Hints (e.g. initial-consonant hints) deferred by the user to feature expansion, because titles are multilingual (D-013).
- Wikidata SPARQL has no ORDER BY, so the first string for an id without a ko label may differ between refreshes and change `revealTitle`. Add `ORDER BY ?id ?ko ?alt` or keep labels and alts apart.
- `HEAD /health`: confirm it returns 200 if a host health checker needs it (Spring MVC normally answers HEAD for GET mappings; pin it with a test if a checker depends on it).
- Revisit D-025 (prefetch fairness: a modified client can listen ahead to the prefetched next-round file) before M-4 planning.
