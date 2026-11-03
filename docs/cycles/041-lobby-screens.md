# 041 lobby-screens (F-003)

## Goal
Friends can play in one room from the browser. One creates a room and shares its code or link, the others join with the code or by opening the link, everyone sees the roster with the host marked, the host picks the song count and starts, and after the game everyone is back in the lobby. This is the screen half of M-2 (D-046, D-047, D-048, D-058, D-062; cycle 040 protocol).

## Server protocol used (cycle 040)
- **Client to server:**
  - `create{nickname}` makes a lobby.
  - `create{nickname, songCount}` is the solo path from cycle 039, kept unchanged.
  - `join{code, nickname}`, `start{songCount}`, `leave`, plus the cycle 039 messages.
- **Server to client:**
  - `joined{code, name}` goes to the joiner only. `name` is the assigned name, which may be `Name (2)`.
  - `roster{phase:"lobby"|"playing", players:[{name, score, host, connected}]}` goes to everyone.
  - Then the cycle 039 game messages.
  - New error codes: `room-not-found`, `room-full`, `in-game`, `not-host`, `server-full`, `room-closed`.

## Behaviour
1. **Start screen.**
   - The cycle 039 elements stay unchanged: `닉네임`, `곡 수`, `시작` (solo), the Opus notice and the credit line. The locked cycle 039 tests must pass as they are.
   - New elements:
     - a `방 만들기` button;
     - a text input labelled `방 코드` (upper-cased as typed, at most 5 characters);
     - a `참가` button.
   - `방 만들기` and `참가` are disabled when `canPlayOpus` is false, like `시작`. `참가` is also disabled while the code is empty.
   - If the page path is `/r/<code>`, read once at mount from `window.location.pathname`, the code field starts with that code, upper-cased.
2. **Create and join.**
   - Like `시작`, both buttons unlock audio synchronously in the click: the AudioContext is created on first use and `resume()` is called before any `await`.
   - Each opens a socket and, on open, sends `create{nickname}` with no `songCount`, or `join{code, nickname}`.
   - `joined{code, name}` shows the lobby screen and remembers `name` as the player's own name.
3. **Lobby screen.**
   - It shows `방 코드: <code>` and the share link `<location.origin>/r/<code>`. The link is plain text; a `복사` button copies it with `navigator.clipboard.writeText` when available.
   - It shows the roster in server order. Each row shows the name, `(방장)` for the host, `(나)` for the player's own row, and `(연결 끊김)` when `connected` is false.
   - **Host** (the own row has `host:true`): a `곡 수` select (5-30, default 10) and a `게임 시작` button that sends `start{songCount}` with a number.
   - **Others:** the text `방장이 게임을 시작하기를 기다리는 중...`.
   - A `나가기` button sends `leave`, clears the socket handlers, closes the socket and returns to the start screen.
   - Host changes follow the next `roster`.
4. **Game in a room.**
   - `prepare`, `start`, chat, `correct` and `reveal` behave as in cycle 039.
   - The shown score `점수: <n>` comes from the `scores` row whose `name` equals the player's own name. Solo play keeps `scores[0]`, since its first row is always the player.
5. **After a game in a room.**
   - `gameOver` shows `최종 점수: <n>점` from the own row of `ranking`, and the ranking as `<rank>위 <name> <score>점` lines in server order.
   - The next `roster{phase:"lobby"}` keeps that result visible and adds a `대기실로` button that shows the lobby screen again. There the host can start a new game (D-062).
   - Solo play keeps cycle 039's `최종 점수` and `다시 하기`.
   - **Solo socket close.** After a solo `gameOver`, the socket is closed at once with its handlers cleared (cycle 040 review). The server's solo lobby then empties, and no `room-closed` can reach the final-score screen.
6. **Errors.**
   - Korean texts, added to the cycle 039 map:

     | Code | Text |
     |---|---|
     | `room-not-found` | `방을 찾을 수 없습니다.` |
     | `room-full` | `방이 가득 찼습니다 (최대 8명).` |
     | `in-game` | `게임이 진행 중입니다. 끝난 뒤에 참가해 주세요.` |
     | `not-host` | `방장만 시작할 수 있습니다.` |
     | `server-full` | `서버가 가득 찼습니다. 잠시 후 다시 시도해 주세요.` |
     | `room-closed` | `방이 닫혔습니다.` |

   - Where each error shows:
     - An error before `joined` (create or join failed) closes the socket with handlers cleared and shows the text on the start screen, as cycle 039 does for start errors.
     - `room-closed`, in any screen of a room, closes the socket and shows the text on the start screen.
     - Other errors in the lobby, such as `not-host` or `bad-song-count`, show on the lobby screen, and the socket stays open.
   - An unexpected close in the lobby shows `서버와 연결이 끊어졌습니다.` and `처음으로`, as in cycle 039.

## Acceptance criteria (from `frontend/`)
1. `src/App.lobby.test.jsx`, using the locked cycle 039 fakes (`src/testing/fakes.js`, not edited) and `fireEvent`, `waitFor` and real timers, as the cycle 039 timer rule requires:
   - **Create.** The start screen shows `방 만들기`, `방 코드` and `참가`. `방 만들기` calls `resume()` synchronously and sends `create` with exactly the keys `type, nickname`.
   - **Join.** Typing `abcde` shows `ABCDE`, and `참가` sends `join{code:"ABCDE", nickname}`. `참가` is disabled with an empty code. With `window.history.pushState({}, '', '/r/abcde')` before render, the code field starts as `ABCDE`. The test restores the path afterwards.
   - **Lobby.** After `joined{code:"ABCDE", name:"Hana (2)"}` and a roster of 3, the screen shows:
     - the code and the link ending in `/r/ABCDE`;
     - the rows with `(방장)` on the host and `(나)` on `Hana (2)`;
     - `(연결 끊김)` for a disconnected player;
     - for a non-host, the waiting text and no `게임 시작`.
   - **Host.** A roster that makes the player host shows `곡 수` and `게임 시작`, which sends `start{songCount:10}` (a number). Changing the select to 15 sends `songCount:15`.
   - **Room game.** `prepare` leads to `ready` and the cycle 039 flow. `scores` with the own row second shows `점수: <own score>`. `gameOver` shows the own final score and the ranking lines. `roster{phase:"lobby"}` then shows `대기실로`, which returns to the lobby screen.
   - **Leave.** `나가기` sends `leave`, closes the socket, returns to the start screen, and a later `onclose` from that socket shows no disconnect message.
   - **Errors.** `room-not-found` and `room-full` after `join` show their texts on the start screen and close the socket. `not-host` in the lobby shows its text and keeps the lobby screen. `room-closed` in the lobby returns to the start screen with `방이 닫혔습니다.`
   - **Solo socket close.** A solo `gameOver` closes the solo socket, and a later `error{code:"room-closed"}` or `onclose` on it changes nothing on the final-score screen.
2. The locked cycle 038 and 039 tests pass unchanged.
3. `npm test`, `npm run lint` (`--max-warnings 0`) and `npm run build` exit 0.

## Test list
- `frontend/src/App.lobby.test.jsx` covers criterion 1. It may add helpers inside the test file, or a new non-test helper file `src/testing/lobbyHelpers.js`.

## File ownership
- **Test writer:** `src/App.lobby.test.jsx` and the optional `src/testing/lobbyHelpers.js`.
- **Implementer:** `src/App.jsx`, `src/game/*`, `src/components/*`, new components, `src/App.css`.
- **Unchanged:** all test and fake files from cycles 038 and 039, `vite.config.js` and `package.json`.

## Out of scope
- Skip, live scores of others, reconnect and clock sync (M-3: F-004, F-005, B-005b, B-006b).
- Lobby chat.
- Production hosting of `/r/<code>`. The Vite dev server already falls back to `index.html`; deploying it is M-4.
