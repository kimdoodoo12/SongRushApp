# SongRushApp

A multiplayer anime OP/ED guessing website. Players join a room by code, hear the same song at the same moment, guess the anime by chat, and the first correct answer scores.
- **Monorepo** (D-029, D-030; design 011):
  - `backend/`: Java Spring Boot 4.1.x on JDK 17, Gradle wrapper, JUnit 5. Owns rooms, rounds, answers, scoring and audio delivery. Reads the data directory from `songrush.data-dir` (default `../data`).
  - `frontend/`: React in plain JavaScript (JSX), Vite, Vitest, Testing Library, ESLint. TypeScript is not used anywhere.
  - `data/`: Python 3.12 tools (package `songrush_data/`, pytest, `data/.venv`). They build the catalog, committed as `data/catalog.json`, and the audio cache in `data/cache/audio/` (gitignored, encoded with `imageio-ffmpeg`).
  - `contracts/`: cross-language contracts, such as `normalize-vectors.json`, run by both JUnit and pytest.
- D-031 fresh start: the earlier Node + TypeScript implementation, its cycle records and its git history were discarded. Designs 001-010 (game rules, data, audio) stay in force.
- **Data**: the catalog is built from the AnimeThemes GraphQL API (design 007) plus AniList (popularity, titles, relations), with Korean titles from anime-offline-database, Wikidata and AniDB (design 008). The user-provided `nonlooped/anime-op-ed-dataset` is not used (D-006, user). **Audio comes from AnimeThemes.moe** (the dataset's upstream). Our backend fetches the file and serves it through an opaque per-round URL; clients download it and play it from the start (D-009, D-018, like songspot.co). The popular top ~1500 are cached ahead of time (D-019). A room draws its songs from that top 1500 when it is created, and any not yet cached are fetched while the lobby waits (D-026, D-027).
- Reference product: songspot.co. Borrow mechanics, not its UI, branding, or copy. Our angle is anime OP/ED.

## Product decisions (user, 2026-11-02)

- **Players guess the anime title by chat, like the StarCraft use-map quiz** (D-015). Every chat message is compared on the server with the accepted strings of the anime's series group: every title and alias from the sources, in Korean, English, Japanese and romaji (D-020, D-022). Spaces, punctuation, case and width are ignored, but spelling must match. Attempts are unlimited, within a flood limit of burst 5 and 2 per second (D-024). Wrong guesses show as normal chat to everyone; a correct message is hidden and announced as "<name> 정답!". No autocomplete.
- **Round = the whole TV-size song from the start, at most 90 s** (D-018, D-021). It ends at the first correct answer, a passed skip vote, or the end of the song. Everyone hears it from the same moment. The reveal shows the entry's own title.
- **Only the first correct answer scores, by server arrival order** (D-011, D-016). Skip passes with a majority of connected players (D-012, D-017). The host sets the number of rounds when creating the room (D-014). No hints for now (D-013).
- UI language: Korean.

## How work happens here

Development runs as autonomous cycles. Run them with `/vibe-cycle`, or `/loop /vibe-cycle` for continuous work.

- **The user supervises; they do not approve steps.** When a decision is needed and the user is in the conversation, ask at once. Record the answer as a `D-NNN` in BACKLOG "Decided" (D-028). **Never block on the answer**: until it arrives, and whenever the user is absent (for example in `/loop`), record the question in `PROGRESS.md` under "Needs decision", mark the item `[?]`, and move on. Items that need the user's own devices or accounts (such as I-001) go to Needs decision rather than being run.
- **The work queue is `BACKLOG.md`.** Take the first unchecked item under "Ready". When Ready is empty, the next **Roadmap** milestone is planned into Ready (see the skill), so work keeps flowing without the user feeding items one by one.
- **The audit trail lives in two places.** Every cycle writes `docs/cycles/NNN-slug.md` (design and planning cycles write `docs/design/NNN-slug.md`) and adds one entry at the top of the Log section in `PROGRESS.md`.
- **Design is part of the cycle, not a separate human step.** `S-` items investigate, write a design doc, and append the resulting items to Ready. Only policy, legal, cost, external-account, or product-direction choices go to "Needs decision".
- **The main session is the lead.** It specifies, delegates, and integrates. It does not write product code itself.
- **Agent definitions live in `.claude/agents/`.** They override the global ones.
- **The system design is documented in `docs/vibe-coding-system.md`.** It is the user's portfolio record of the vibe-coding process, so keep it factual (commit SHAs, command output).

## Branches

- `main` is release. **Only the user merges into it**, via the PR from `dev` to `main`.
- `dev` is integration. Merge a cycle here only after it passes the verifier and critic.
- `cycle/NNN-slug` is one branch per cycle, cut from `dev`.
- **Never commit on `main`, force-push, or rewrite pushed history.** A hook enforces this.

## Evidence rules

- **A claim counts only with command output behind it.** "Done" or "looks fine" from any agent is not evidence.
- **Whoever writes the code never verifies it.** The `verifier` agent runs the checks, and the `critic` agent reviews the diff.
- **Tests are written and committed before the implementation, then locked.** Use `python -I .claude/hooks/lock.py lock <paths>` (this exact form is pre-allowed). The implementer must make them pass without editing them.
- **Retry cap: 3 implementation attempts per cycle.** After that, mark the item BLOCKED and continue with the next one.

## Commands (verified ones only; add each area when it is bootstrapped)

| Area | Test | Lint / typecheck |
|---|---|---|
| backend (verified once bootstrapped) | `backend\gradlew.bat test` (from the repo root; `./gradlew test` off Windows) | compile is part of `test` |
| frontend (verified once bootstrapped) | `npm test` (from `frontend/`) | `npm run lint`; build `npm run build` (from `frontend/`) |
| data (verified once bootstrapped) | `.venv\Scripts\python.exe -m pytest` (from `data/`; `.venv/bin/python` off Windows) | none yet |

None of these areas exists yet; drop "(verified once bootstrapped)" from a row only after its bootstrap cycle has run that command successfully.

The Stop hook runs the tests of each area whose files changed: `frontend` (`npm test --silent`, when `package.json` and `node_modules` exist), `backend` (`gradlew test --console=plain -q`, when the wrapper exists), `data` (`.venv` python `-m pytest -q`, when the venv and `data/tests/` exist). A change under `contracts/` runs both `backend` and `data`. An area not bootstrapped yet is reported UNVERIFIED, never as a pass.

## External source rules

- **No media files in git** (repo size). Cached audio (`.m4a`, at most 90 s) and its manifest live under `data/cache/audio/` (gitignored), are built locally by the Python cache builder (`imageio-ffmpeg`) and copied to the host's persistent disk. The backend also fetches and encodes, in the lobby, any drawn song that is not cached yet (D-026), running ffmpeg through `ProcessBuilder` (`songrush.ffmpeg-path`, default `FFMPEG_PATH` or the `imageio-ffmpeg` binary), so the host needs an ffmpeg binary too. The repo stores metadata only (ids, titles, URLs).
- **Audio host `a.animethemes.moe`**: downloads happen in two places: the cache builder (C-007, dev machine) and the backend's single server-wide lobby queue (B-008). Both fetch one file at a time, at least 3 s apart, with the SongRushBot UA, and honor 429 `Retry-After` (design 010). There is no documented limit, so stay at this pace.
- **Do not download audio from YouTube** (YouTube's terms). Not needed anyway: AnimeThemes is the audio source.
- **AnimeThemes API**: use the **GraphQL** API (`graphql.animethemes.moe`; the JSON:API is deprecated, design 007). User-Agent `SongRushBot/<version> (+repo contact)`, 90 requests/min documented; keep at least 3 s between requests and a timeout of at least 60 s (requests take 15-30 s). AniList: header limit 30; keep at least 2 s between requests. Honor 429 `Retry-After`, and cache responses in `data/cache/` (gitignored) so a catalog rebuild does not refetch.
- **Korean title sources** (design 008 decision 7): download the AniDB titles dump at most once per 24 h; reuse the anime-offline-database release and the Wikidata SPARQL result for 7 days. All use the SongRushBot UA and atomic cache writes. aod and Wikidata use up to 3 attempts. AniDB is attempted at most once per 24 h, with an attempt marker written before the request and no retry of any kind, 429 included. The marker is `data/cache/korean-anidb-attempt.json`. The last request was 2026-11-02 18:34 KST, so the next one is allowed from **2026-11-03 18:34 KST**. A source that fails is skipped, or its stale cache is used.
- **Credit line** (D-023, ODbL): the UI shows "데이터 출처: AnimeThemes, AniList, anime-offline-database, AniDB, Wikidata".
- Live requests to external sources happen only in design cycles, the catalog build and the audio cache builder (Python tools in `data/`), and the backend's lobby queue (B-008); never in tests. Tests use saved fixtures, so they run offline and deterministically.

## Multiplayer rules

- **Transport**: raw Spring WebSocket at `/ws` with a JSON envelope `{type, id?, payload}`; event names are design 009's, and a reply with the same `id` is the ack (design 011). **Each room runs on its own single-thread executor**: every inbound message and every timer for that room runs on it, which enforces server arrival order and keeps a round deadline and a correct answer from interleaving.
- **The server is the authority.** The backend owns the answer, the round clock, and scores. Never send the answer or the media URL's anime title to clients before the round ends. Audio URLs sent to clients are opaque (no anime name, slug or AnimeThemes basename), including prefetched next-round files.
- Round sync: clients download the whole file, then play from 0 `delayMs` after `round:start` (design 009 D1, design 010 decision 3). Chat is compared only from the server's `roundStartTime`; the first match by server arrival order wins (D-016). Players not loaded at round start sit the round out (`missed`) and cannot chat until the reveal. Fairness bugs (one client hearing earlier) are High severity. The one exception: listening ahead to the prefetched next-round file with a modified client is accepted for now (D-025; revisit before M-4).

## Environment pitfalls

- Windows: use Python's `-I` flag, and call `sys.stdout.reconfigure(encoding="utf-8")` for Korean/Japanese output, because the console uses cp949.
- `C:\Users\GRAM` (home) is itself a git repo with no commits. This project has its own repo; run git only inside it.
- Dev notifications go to Discord through `.claude/hooks/notify_discord.py` (`stop` mode only here: new Needs decision or BLOCKED). The webhook comes only from `BA_DEV_DISCORD_WEBHOOK`; never write it to a file. `notification`, `failure` (API errors), `halt` (every idle stop) and `end` (session closed) are wired globally; do not wire them here too, or every alert arrives twice. An alert that cannot be sent (network down) is queued in `~/.claude/state/discord-outbox.jsonl` and resent by a detached retrier every 30 s for up to 12 h.

## Pitfalls learned (cycles append here, one line each)
- guard.py checks the branch *before* a command runs, so `git switch X && git commit` on main is blocked. Switch in one command, commit in the next.
- The Stop hook counts every lead stop while tests are locked and red, even while an implementer is still working. Do not end a turn between lock and green: wait in-turn, for example with a poll loop until the area's test command passes, or a 3rd stop writes a false BLOCKED entry.
- File-writing tools (Write, Edit, and heredocs passed as tool parameters) turn backslash-u escapes in the text into the real characters. For invisible or combining characters in source or docs, build the backslash with `chr(92)` in a Python script, or build the character from its code point in the test (`String.fromCharCode`, `Character.toChars`, `chr`), then check that the file is pure ASCII.
- guard.py regex-matches the whole command text; a test command that merely contains a forbidden string is blocked. Put hook test inputs in a script file.
- Backticks inside a double-quoted bash string are executed as commands (for example in a `git commit -m "..."` or `gh pr create --body "..."`). Pass such text through a quoted heredoc (`<<'EOF'`) or a file.
- npm and Gradle wrappers swallow flags meant for the script: `npm run x --limit 3` loses `--limit`. Put `--` before script arguments (`npm run x -- --limit 3`), and make CLIs reject a bare positional argument.
