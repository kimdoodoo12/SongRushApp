# 078 spin-and-no-solo (F-013)

## Goal
- **Solo mode leaves the client (D-093).** The start screen keeps nickname, the 분위기 picker, 방 만들기, and 방 코드 with 참가.
- **The record visibly spins in every theme.** Today play, sakura, chalk and star draw concentric rings and a plain label, so the spin is invisible.

## Behaviour
1. **Start screen.**
   - The "혼자 하기" card, its 곡 수 and filter controls, and the 시작 button are removed.
   - The remaining layout: a 닉네임 field, the 분위기 picker, 방 만들기, then the 방 코드 field with 참가.
   - Nickname and code rules are unchanged.
   - Song count and filters stay host controls in the lobby.
   - The App-level selection (song count and filters) still exists and is used by the lobby.
2. **App.** The solo start path (`handleStart`, the `create` with `songCount`) is removed from the client. Every game is a room game: create the room, then `start`. The server's solo protocol is untouched.
3. **Record.**
   - Every theme shows a rotation cue on the spinning record: an asymmetric layer, for example a conic sheen or a dot or stripe on the label, drawn by shared rules.
   - Colours come from theme tokens. Size and position do not change.
   - Rule 2 of cycle 076 still holds, and `themeLayout.test.js` stays green.
   - `prefers-reduced-motion` still stops the spin.

## Tests (declared rewrite)
- Locked tests that start games through the solo form, about 60 places in 9 files, move to "create a room as host, then `start`".
  - The scripted server frames change accordingly: `joined`, `roster`, then `prepare` and `start` after `start`.
  - Each assertion keeps its meaning.
  - Tests whose subject is solo itself are removed and listed with the reason. Examples: the solo form's validation, "no 자리 list in solo", solo-only skip absence.
- **New test:** the start screen has no 시작 button and no 곡 수 select, and does have 방 만들기, 방 코드 and 참가.
- **The test writer lists, per file:** kept, changed (and how), removed (and why). The critic reviews the rewritten tests against the originals.

## Acceptance
1. `npm test`, `npm run lint` and `npm run build` exit 0.
2. The browser check (lead):
   - Two screenshots of the record 400 ms apart differ inside the record box in all six themes.
   - The box equality from cycles 076 and 077 is still 0 differences.
   - The start screen shows no solo controls.

## File ownership
- **Test writer:** `frontend/src/**/*.test.jsx` and `*.test.js` (declared rewrite), and `frontend/src/testing/fakes.js` only if a helper is needed.
- **Implementer:** `frontend/src/App.jsx`, `frontend/src/components/*.jsx`, `frontend/src/index.css`, and `frontend/src/game/*.js` only for removing the solo client path.

## Out of scope
- Removing the server's solo code (a follow-up).

## Red evidence (lead, live site before the change)
Playwright took two clips of `.record` 400 ms apart in a room game and counted the share of pixels whose brightness changed by more than 24.

| Theme | Changed pixels |
|---|---|
| play | 0.5% |
| neon | 2.0% |
| sakura | 0.0% |
| chalk | 0.9% |
| star | 0.0% |
| arcade | 9.9% |

Target: at least 5% in every theme.

## Test rewrite record (declared; test writer's table, checked by the critic)
| File | Tests before → after | Removed (why) |
|---|---|---|
| difficulty | 12→10 | The start-screen 난이도 select (the screen lost it; the host-side test stays). The solo create default message shape (the solo protocol itself; the host default test covers the meaning). |
| filters | 24→21 | The start-screen filter options (the host-side test stays). The solo create defaults. The reversed-range solo form submit (the solo form itself; the host test "button disabled, no start" stays). |
| flow | 27→27 | None. An `it.each` row moved into its own test. |
| layout | 6→5 | "Solo game shows no 자리 list." |
| lobby | 25→23 | Three solo session tests: the socket closes at gameOver, joined/roster are ignored, lobby frames do not affect a solo game. A new start-screen test was added. |
| multi | 26→25 | "Solo shows no 스킵." |
| reconnect | 20→19 | "A solo session never reconnects." |
| sync | 28→27 | "A solo session sends no ping." The solo rows became "alone" (a one-player room) rows. |
| theme | 11→11 | None. The check button changed from 시작 to 방 만들기. |

The total went from 295 to 284: 12 removed, 1 added. The meaning changes the critic accepted:
- Errors that come after `start` now appear in the lobby, with the socket kept open.
- `bad-nickname` and an unknown code stay on the start screen.
- "다시 하기" paths became "대기실로, then 나가기, then a new room".

## Result
- **Commits.**
  - Tests: `efa173b`.
  - Implementation: `03f584e`.
  - Right-aligned round counter: attempt 2.
- `npm test` passed 284 tests; `npm run lint` and `npm run build` exited 0 (implementer's runs). The test commit is locked and unchanged.
- **Lead's browser check, local jar plus the built client.** In 400 ms the record changes visibly in every theme:

  | Theme | Changed pixels |
  |---|---|
  | play | 22.9% |
  | neon | 13.9% |
  | sakura | 22.3% |
  | chalk | 13.5% |
  | star | 18.1% |
  | arcade | 23.2% |

  Before, it was 0.0–9.9%. Box differences are 0 at 1280 and 390, and clippings are 0. The start screen has no solo controls.
- **Code review.** No Blocker or High.
  - The Medium (the missing test table) is resolved above.
  - The Nit (the sheen paints over the record's inset ring) is moved to Follow-ups.
