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
