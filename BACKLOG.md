# Backlog

Status marks:
- `[ ]` ready
- `[~]` in progress
- `[x]` done
- `[!]` blocked
- `[?]` needs a user decision

Id prefixes:
- `B-` backend (Java)
- `F-` frontend (React JS)
- `C-` data tools (Python)
- `I-` infra
- `S-` research and design

Acceptance values come only from user decisions, external sources, raw fixtures and the new design docs. Nothing from the discarded TypeScript attempt is used (D-031, D-032).

## Ready

- [x] **B-001** backend: Spring Boot bootstrap
  - `backend/`: Spring Boot 4.1.x on Java 17, the Gradle wrapper committed, JUnit 5. No database.
  - Acceptance:
    - `GET /health` returns 200 `application/json` `{"ok":true}`.
    - Unknown paths return 404 JSON, even with `Accept: text/html`.
    - `POST /health` returns 405 JSON.
    - The HTTP tests run on a random port.
    - `songrush.data-dir` defaults to `../data`.
    - From `backend/`, `gradlew.bat test` and `gradlew.bat build` exit 0, and they also pass `--offline`.
  - The lead updates the CLAUDE.md Commands row. Spec: `docs/cycles/012-backend-bootstrap.md`.
- [x] **I-002** data: Python bootstrap
  - `data/`: package `songrush_data/`, a `tests/` folder run by pytest, `requirements.txt` with pinned versions, and a `.venv` created by a documented command.
  - Acceptance:
    - One smoke test passes.
    - `.venv\Scripts\python.exe -m pytest` exits 0 from `data/`.
    - The Stop hook runs the `data` area.
- [ ] **S-001** design: song data and catalog (new research for this stack)
  - Re-research from the sources:
    - which songs and anime exist, and how to fetch them (AnimeThemes API);
    - popularity and series relations (AniList);
    - accepted titles in Korean, English, Japanese and romaji (D-015, D-020, D-022);
    - each source's terms and rate limits.
  - Decide how the Python tools build the catalog file, and what the backend reads.
  - Output: a design doc plus C- items.
- [ ] **S-002** design: audio delivery (new research for this stack)
  - Re-research how to fetch, cache and serve the songs (D-009, D-018, D-019, D-021, D-026, D-027), including what browsers can play and how every player starts at the same moment.
  - Output: a design doc plus C-, B- and F- items.
  - Real-device checks need the user (Needs decision).
- [ ] **S-003** design: game protocol and rounds (new research for this stack)
  - Design the realtime connection between the Spring Boot backend and the React frontend, the room flow, the round timing, chat matching, skip and scoring. These follow D-011..D-017, D-024 and D-025.
  - Output: a design doc plus B- and F- items.

- [ ] **F-001** frontend: React JavaScript bootstrap
  - `frontend/`: React with JSX, Vite, Vitest with jsdom and Testing Library, ESLint with a flat config. No `.ts` or `.tsx` files, and no TypeScript dependency.
  - Acceptance:
    - The app renders an `h1` "SongRush". A test pins it.
    - `npm test`, `npm run lint` and `npm run build` exit 0 from `frontend/`.
    - A check fails if a `.ts` or `.tsx` file appears under `frontend/`, outside `node_modules`.

## Roadmap

Milestones in order. Planning cycles break the first `[ ]` one into Ready items.

- [ ] **M-1** Solo prototype: one player opens the site, hears a song from the start (at most 90 s), guesses the anime by chat, sees right/wrong and the reveal, and plays the next round.
- [ ] **M-2** Rooms: create a room, share a code or link, others join with a nickname, lobby shows players, host starts the game.
- [ ] **M-3** Synchronized multiplayer rounds: every player hears the same song at the same server-announced time, the first correct answer scores, players can vote to skip, round results and a final ranking are shown.
- [ ] **M-4** Deploy: a public URL friends can use, with the credit line (D-023). Hosting (D-010) needs a user decision first. Revisit D-025 before planning.
- [ ] **M-5** Anime-specific modes: OP/ED filter, year range, popularity difficulty.

## Needs decision

- [?] **D-010** Hosting. It is decided before M-4, after S-002 settles what the server needs (a JVM, disk for the audio cache, ffmpeg or not). Options are researched then.

Decided:
- **D-001** GitHub remote: private repo `kimdoodoo12/SongRushApp`.
- **D-003** Answer target: the anime title.
- **D-006** Dataset: do not use `nonlooped/anime-op-ed-dataset`. Build our own catalog from AnimeThemes and AniList, with Korean titles from public sources.
- **D-007/D-008** Answer matching: a full title counts in Korean, English or Japanese, including romaji (D-015). Season and sequel suffixes are not required, so any title of the same series counts. The reveal shows the exact entry's title, season included.
- **D-009** Audio: the server fetches the audio from AnimeThemes and serves it to players through opaque URLs, like songspot.co. Players never get a URL that reveals the song.
- **D-011** Scoring: only the first correct answer in a round scores.
- **D-012** Skip: players can vote to skip a round.
- **D-013** Hints: none for now; revisit at feature expansion.
- **D-014** Rounds per game: the host sets it when creating the room.
- **D-015** Answer input:
  - Answers come by chat, like the StarCraft use-map quiz. Every chat message is compared on the server with the titles in Korean, English, Japanese and romaji.
  - Spaces, punctuation, case and width are ignored; spelling must match.
  - Attempts are unlimited. Wrong guesses are visible as chat. The correct message is hidden and announced. No autocomplete.
- **D-016** "First" means server arrival order. No latency compensation.
- **D-017** A skip vote passes with more than half of the connected players.
- **D-018** Playback: the whole TV-size song from the start. The round ends at the first correct answer, a passed skip, or the end of the song.
- **D-019** Caching: pre-fetch the popular top ~1500 themes.
- **D-020** Series grouping: automatic, from the sources' series and sequel/prequel relations, with no manual splits. The revealed answer is the entry's own title, but the accepted range is the whole series.
- **D-021** Long files: play at most the first 90 s; if nobody answers, reveal.
- **D-022** Aliases: accept every alias from the sources for now. Filtering rules come later.
- **D-023** Attribution: one line in the footer or an about page: "데이터 출처: AnimeThemes, AniList, anime-offline-database, AniDB, Wikidata". Update it if S-001 changes the sources.
- **D-024** Chat flood limit: a token bucket with a burst of 5 messages and 2 per second.
- **D-025** Prefetch fairness: a modified client listening ahead to the next prefetched song is accepted for now (friends-only play). Revisit before M-4.
- **D-026** Uncached songs: a room's songs are drawn when the room is created, and redrawn when the host changes the number of rounds. Uncached ones are fetched while the lobby waits, and the game starts only when every drawn song is ready.
- **D-027** Draw range: rooms draw from the top 1500 OP/ED themes by popularity, the pre-cached set. Widening the range is a later mode or difficulty setting.
- **D-028** Working mode: when a decision is needed and the user is present, ask at once and never block. When the user is absent, record it under Needs decision.
- **D-029** Stack: a Java Spring Boot backend, a React frontend in plain JavaScript with no TypeScript, and Python data processing. The same stack as BlueArchiveBriefingApp.
- **D-030** Layout: the folders are `frontend/`, `backend/` and `data/`. The backend uses Java 17.
- **D-031** Fresh start: discard the TypeScript attempt, its git history and every artifact it produced.
- **D-033** Order (user, 2026-11-02): data first. The data bootstrap, the song data design and its tools, then the audio design and the top-1500 audio run come before game-protocol design, backend features and the frontend. The backend bootstrap (B-001) was already done.
- **D-032** Fresh documents (user, 2026-11-02): delete every earlier design, research and planning document, because their standards came partly from the TypeScript attempt. Keep only the user's game-rule decisions above, the raw API fixtures (`data/tests/fixtures/`) and the vibe-cycle tooling. Research and design restart as S-001..S-003.

## Follow-ups
- Data test network guard (critic, cycle 013):
  - Let loopback (`127.0.0.1`, `::1`, `localhost`) through, because on Windows `socket.socketpair()` and `asyncio.new_event_loop()` connect over loopback and currently fail.
  - Install the guard at session start (`pytest_configure` or conftest module level), not per test function, so fixtures and import-time code are guarded too.
  - Add tests that pin the coverage of `create_connection` and ssl.
- Backend bootstrap (critic, cycle 012):
  - `SongrushPropertiesTest` runs without Tomcat, so only HealthTest catches the `../data` binding failure. Run it on `RANDOM_PORT`, or bind a String and expose a `Path` accessor.
  - The `StringToPathConverter` applies to every `@ConfigurationProperties` Path. Narrow it if a library or Boot property ever needs `classpath:` paths.
  - `../data` resolves against the working directory: `backend/` under Gradle, the repo's parent under `java -jar` from the root. Log the absolute data dir at startup.
  - `JsonErrorController` turns non-standard status codes into 500; use `HttpStatusCode.valueOf`. A direct `/error` call returns 500; return 404 when there is no status attribute.
  - The Stop hook runs areas one after another, each up to 850 s, so two changed areas can exceed the 900 s cap. Share one deadline across runners.
