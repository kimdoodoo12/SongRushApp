# SongRushApp

A multiplayer anime OP/ED guessing website. Players join a room by code, hear the same song at the same moment, guess the anime by chat, and the first correct answer scores.
- **Stack** (D-029, D-030):
  - `backend/`: Java Spring Boot 4.1.x on JDK 17, Gradle wrapper, JUnit 5.
  - `frontend/`: React in plain JavaScript (JSX). TypeScript is not used anywhere.
  - `data/`: Python 3.12 data tools, with pytest.
- **Fresh start** (D-031, D-032): the earlier Node + TypeScript attempt, its history, its outputs and every design and research document were discarded. Only the user's game-rule decisions (BACKLOG "Decided") carry over. Research and design restart for this stack (S- items in BACKLOG).
- Reference product: songspot.co. Borrow mechanics, not its UI, branding, or copy. Our angle is anime OP/ED.

## Product decisions (user, 2026-11-02)

- **Players guess the anime title by chat, like the StarCraft use-map quiz** (D-015). A chat message is correct when it matches one of the accepted titles of the anime's series, in Korean, English, Japanese or romaji (D-020, D-022). Spaces, punctuation, case and width are ignored, but spelling must match. Attempts are unlimited, within a flood limit of burst 5 and 2 per second (D-024). Wrong guesses show as normal chat to everyone; a correct message is hidden and announced as "<name> 정답!". No autocomplete.
- **A round is the whole TV-size song from the start, at most 90 s** (D-018, D-021). It ends at the first correct answer, a passed skip vote, or the end of the song. Everyone hears it from the same moment. The reveal shows the entry's own title.
- **Only the first correct answer scores, by server arrival order** (D-011, D-016). A skip passes with a majority of connected players (D-012, D-017). The host sets the number of rounds when creating the room (D-014). No hints for now (D-013).
- **Songs:** AnimeThemes is the audio source (D-009). The top ~1500 OP/ED themes by popularity are cached ahead of time, and rooms draw from them (D-019, D-027). Uncached draws are fetched while the lobby waits (D-026).
- UI language: Korean. Credit line: "데이터 출처: AnimeThemes, AniList, anime-offline-database, AniDB, Wikidata" (D-023).

## How work happens here

Development runs as autonomous cycles. Run them with `/vibe-cycle`, or `/loop /vibe-cycle` for continuous work.

- **The user supervises; they do not approve steps.** When a decision is needed and the user is in the conversation, ask at once, and record the answer as a `D-NNN` in BACKLOG "Decided" (D-028).
  - **Never block on the answer.** Until it arrives, and whenever the user is absent (for example in `/loop`), record the question in `PROGRESS.md` under "Needs decision", mark the item `[?]`, and move on.
  - Items that need the user's own devices or accounts go to Needs decision rather than being run.
- **The work queue is `BACKLOG.md`.** Take the first unchecked item under "Ready". When Ready is empty, the next **Roadmap** milestone is planned into Ready (see the skill).
- **The audit trail.** Every cycle writes `docs/cycles/NNN-slug.md`, and design or planning cycles write `docs/design/NNN-slug.md`. `PROGRESS.md` is the owner's progress report in plain Korean, not notes for the AI.
- **Design is part of the cycle.** `S-` items investigate, write a design doc, and append the resulting items to Ready. Only policy, legal, cost, external-account, or product-direction choices go to "Needs decision".
- **Confirm before choosing.** Stack, folder layout and any product-direction choice are the user's; ask before deciding them.
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

| Area | Test | Lint / build |
|---|---|---|
| backend (verified once bootstrapped) | `.\gradlew.bat test` (from `backend/`; `./gradlew test` off Windows) | compile is part of `test`; build `.\gradlew.bat build` |
| frontend (verified once bootstrapped) | `npm test` (from `frontend/`) | `npm run lint`; build `npm run build` |
| data (verified once bootstrapped) | `.venv\Scripts\python.exe -m pytest` (from `data/`) | none yet |

Drop "(verified once bootstrapped)" from a row only after its bootstrap cycle has run that command successfully.

The Stop hook runs the tests of each area whose files changed: `frontend` (`npm test --silent`), `backend` (`gradlew test`), and `data` (`.venv` python `-m pytest`). It runs an area only once it is bootstrapped; otherwise the area is reported UNVERIFIED, never as a pass. A change under `contracts/` runs both `backend` and `data`. settings.json caps the hook at 900 s, so each runner is limited to 850 s.

## External source rules

- **No media files in git** (repo size). Cached audio lives in gitignored folders.
- **Do not download audio from YouTube** (YouTube's terms).
- **Be gentle with every external source.**
  - Send a `SongRushBot/<version> (+repo contact)` User-Agent.
  - Make one request at a time, with a gap of at least 2-3 s.
  - Use timeouts of at least 60 s, honor 429 `Retry-After`, and cache responses so a rebuild does not refetch.
  - The research cycles (S- items) confirm each source's exact limits and write them here.
- **AniDB:** download the titles dump at most once per 24 h. The last request was 2026-11-02 18:34 KST, so the next is allowed from **2026-11-03 18:34 KST**.
- Live requests to external sources happen only in design cycles and the data tools, and later in the backend's lobby fetch; never in tests. Tests use saved fixtures (`data/tests/fixtures/`, raw API responses), so they run offline and deterministically.

## Multiplayer rules

- **The server is the authority.** The backend owns the answer, the round clock, and scores. Never send the answer, or anything that reveals the song, to clients before the round ends; audio URLs are opaque.
- **Arrival order is enforced on the server** (D-016). The first correct answer by server arrival order wins, including when messages arrive concurrently.
- **Fairness bugs**, such as one client hearing the song earlier, are High severity. The one accepted exception: a modified client can listen ahead to the prefetched next-round file (D-025; revisit before M-4).

## Environment pitfalls

- Windows: use Python's `-I` flag, and call `sys.stdout.reconfigure(encoding="utf-8")` for Korean/Japanese output, because the console uses cp949.
- `C:\Users\GRAM` (home) is itself a git repo with no commits. This project has its own repo; run git only inside it.
- **Discord notifications** go through `.claude/hooks/notify_discord.py`.
  - Only `stop` mode is wired here; it reports new Needs decision or BLOCKED entries.
  - The other modes are wired globally, so do not wire them here too: `notification`, `failure` (API errors), `halt` (every idle stop) and `end` (session closed).
  - The webhook comes only from `BA_DEV_DISCORD_WEBHOOK`; never write it to a file.
  - An alert that cannot be sent is queued in `~/.claude/state/discord-outbox.jsonl` and resent every 30 s for up to 12 h.

## Pitfalls learned (cycles append here, one line each)
- Hooks must read stdin as UTF-8 bytes (`sys.stdin.buffer.read().decode("utf-8")`). Windows decodes `sys.stdin` as cp949, which garbled Korean in Discord alerts (fixed 2026-11-02 in every hook).
- guard.py checks the branch *before* a command runs, so `git switch X && git commit` on main is blocked. Switch in one command, commit in the next.
- The Stop hook counts every lead stop while tests are locked and red, even while an implementer is still working. Do not end a turn between lock and green: wait in-turn, for example with a poll loop until the area's test command passes, or a 3rd stop writes a false BLOCKED entry.
- File-writing tools (Write, Edit, and heredocs passed as tool parameters) turn backslash-u escapes in the text into the real characters. For invisible or combining characters, build them from their code points in code (`chr`, `Character.toChars`, `String.fromCharCode`), then check that the file is pure ASCII.
- guard.py regex-matches the whole command text; a test command that merely contains a forbidden string is blocked. Put hook test inputs in a script file.
- Backticks inside a double-quoted bash string are executed as commands. Pass such text through a quoted heredoc (`<<'EOF'`) or a file.
- npm and Gradle wrappers swallow flags meant for the script (`npm run x --limit 3` loses `--limit`). Put `--` before script arguments, and make CLIs reject a bare positional argument.
- Spring Boot 4 moved classes: `TestRestTemplate` is in `org.springframework.boot.resttestclient` (and needs `@AutoConfigureTestRestTemplate`), `ErrorController` is in `org.springframework.boot.webmvc.error`, and JSON is Jackson 3 (`tools.jackson.*`). Run `.\gradlew.bat` from `backend/`: a bare `gradlew.bat` is not found here (`NoDefaultCurrentDirectoryInExePath` is set).
