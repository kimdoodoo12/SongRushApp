# 061 filter-controls (F-008)

## Goal
Solo players and room hosts can choose 곡 종류 and a 연도 range next to the song count. The client sends only the choices that differ from the defaults. Every player sees the conditions the server applied, and the host's choices survive a game. This is design 059, item 2, on top of the cycle 060 protocol.

## Behaviour
1. **A shared `FilterControls` component** (`frontend/src/components/FilterControls.jsx`), used by `StartScreen` (solo) and the host controls in `LobbyScreen`.
   - `곡 종류`, a select with these options:
     - `전체` (value `all`)
     - `오프닝만` (`OP`)
     - `엔딩만` (`ED`)
   - `연도` from, a select: `처음부터` (value empty), then 1990, 2000, 2005, 2010, 2015, 2020, 2025.
   - `연도` to, a select: `지금까지` (value empty), then 1999, 2004, 2009, 2014, 2019, 2024.
   - The year selects are labelled `연도 시작` and `연도 끝` so they can be found by label. `곡 종류` is labelled as written.
   - When both years are set and from > to:
     - the hint `시작 연도가 끝 연도보다 늦습니다.` is shown;
     - the parent's start button (`시작` or `게임 시작`) is disabled.
2. **Message fields** (`game/filters.js`, a new pure module):
   - `toFields({type, from, to})` returns only the non-default fields:
     - `types: ['OP']` or `['ED']`;
     - `yearFrom: <number>`;
     - `yearTo: <number>`.
   - With all defaults it returns `{}`.
   - `session.js` merges these fields into `create` (solo) and `start` (lobby). With the defaults the messages are exactly today's, which keeps the locked `toEqual` tests valid.
   - `session.startGame(count, fields = {})` and the solo `songCount`/`fields` options are new optional parameters.
3. **Applied conditions.**
   - On `settings{types?, yearFrom?, yearTo?}` the session dispatches `settings`. The game screen then shows one line with class `settings`. The line is made of:
     - `오프닝만` or `엔딩만` when `types` is present;
     - `yyyy~yyyy`, `yyyy~` or `~yyyy` for the years;
     - parts joined with ` · `.
     Example: `오프닝만 · 2010~2019`.
   - **When the line is cleared (review B1).** The server sends `settings` before the first `prepare`, so `prepare` never clears it. The reducer clears `settings` on `connecting`, `reset`, `gameOver`, `abandoned`, `toLobby`, `restore` (any phase) and `watching`. After a join the server sends `settings` again, right after the snapshot (cycle 060). A new game without `settings` therefore never shows the old line, whatever the message order.
   - The pre-snapshot frame filter (cycle 049) is not changed. `settings` always arrives after the snapshot (review N1).
4. **Errors.**
   - `not-enough-songs` with a numeric `available` shows `출제할 수 있는 곡이 n곡뿐입니다.`. This is neutral wording, because the server always sends `available`, also without a filter, and non-hosts receive it too (review M1).
   - Without `available`, it keeps `출제할 곡이 부족합니다.`
   - `bad-filter` shows `조건이 올바르지 않습니다.`
   - `errorText` gains an optional second argument, the error message object. The session passes the whole message.
5. **Kept across games (D-062).** Solo and host share one App-level selection, which persists for the page's lifetime and is not reset by 처음으로 or 나가기. `StartScreen`'s submit handler also refuses a reversed range (review N3).
   - Details:
   - The host's last chosen filter selections and song count live in `App` state rather than inside `HostControls`. After a game returns to the lobby, the selects show them.
   - Solo selections live in `App` state too.

## Acceptance criteria (Vitest, from `frontend/`)
`src/App.filters.test.jsx` (locked fakes) and `src/game/filters.test.js`:
1. **`filters.test.js`.**
   - `toFields` with the defaults returns `{}`.
   - Each single choice adds exactly its field.
   - A combined choice adds all of them.
   - The settings-line formatter handles every combination: types only, from only, to only, both years, everything.
2. **Solo.**
   - With the defaults, `시작` sends exactly `{type:'create', nickname, songCount:10}`.
   - With `엔딩만` and 2010 to 2019 it sends `{type:'create', nickname, songCount:10, types:['ED'], yearFrom:2010, yearTo:2019}`.
3. **Host.**
   - With the defaults, `게임 시작` sends exactly `{type:'start', songCount:10}`.
   - With `오프닝만` it sends `{type:'start', songCount:10, types:['OP']}`.
4. **Guard.** From 2010 with to 1999 disables `시작` and `게임 시작` and shows the hint. Fixing the range enables them again.
5. **Settings line.**
   - After `settings{types:['OP'],yearFrom:2010,yearTo:2019}` and `prepare`, the game screen shows `오프닝만 · 2010~2019`.
   - With no `settings`, there is no `.settings` line.
   - After `gameOver`, then `prepare{index:0}` of a new game without `settings` (while still on the result screen, and also after `대기실로`), the line is gone.
6. **Late join.** The sequence `joined`, `snapshot` (playing), `settings`, `roster` shows the line.
7. **Errors.**
   - `{type:'error',code:'not-enough-songs',available:3}` shows `출제할 수 있는 곡이 3곡뿐입니다.`.
   - Without `available`, the old text is shown.
   - `bad-filter` shows its text.
8. **Kept across games.**
   - The host picks 20 songs, `엔딩만` and 2000, then starts. After `gameOver` and the return to the lobby, the selects still show 20, `엔딩만` and 2000.
   - Starting again sends the same fields.
9. All locked earlier tests pass unchanged. `npm test`, `npm run lint` and `npm run build` exit 0.

## Review changes (spec round 1)
- **Blocker.** The settings line was cleared by the `prepare` that follows `settings`. Clearing now happens on the listed actions, and a test covers the result-screen-to-next-game path.
- **Medium.**
  - The not-enough-songs text is neutral.
  - The clearing actions are listed.
- **Nits.**
  - The pre-snapshot filter is unchanged.
  - One shared selection.
  - The submit guard.

## Test list
- `frontend/src/App.filters.test.jsx`
- `frontend/src/game/filters.test.js`

## File ownership
- **Test writer:** the test list.
- **Implementer:**
  - `src/App.jsx`
  - `src/App.css`
  - `src/components/*` (including the new `FilterControls.jsx`)
  - `src/game/session.js`
  - `src/game/state.js`
  - the new `src/game/filters.js`

## Out of scope
- Popularity tiers (D-069).
- Showing the host's choices to others before the game starts.
