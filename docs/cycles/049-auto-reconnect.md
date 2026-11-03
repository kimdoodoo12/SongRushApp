# 049 auto-reconnect (F-006)

## Goal
When a room session's connection drops, the browser reconnects on its own within the server's 30 s grace period (cycle 042). It rejoins with its private token and returns to the lobby or to the running song. A replaced tab, a refused rejoin, or a drop longer than the grace ends cleanly with a message.

## Protocol used (cycle 042)
- After `create` or `join`, the server sends `token{token}`, which only this session receives.
- After a drop, a new socket sends `rejoin{token}`. The server answers with one of:
  - `joined{code, name}`, then `snapshot{phase, players, song, scores}`, then `roster` to everyone;
  - `error{code:"rejoin-failed"}`.
- A socket replaced by another tab is closed with status `4001`.
- After a reconnect the player's skip vote is cleared on the server (cycle 045).

## Behaviour (room sessions; solo keeps the cycle 039/041 behaviour)
1. **Token.**
   - The session keeps the last `token{token}` in memory.
   - It also stores `{token, code}` in `sessionStorage` under `songrush.rejoin`. The write is wrapped in `try`/`catch`, so a blocked storage changes nothing.
   - The storage entry is cleared on `나가기`, on `rejoin-failed`, on a `4001` close, and when the session ends on purpose.
   - Using the stored token after a page reload is out of scope (follow-up).
2. **Unexpected close with a token.**
   - "Unexpected" means a close that the session did not start itself, with a status other than 4001.
   - The screen keeps its content and shows `연결이 끊겨 다시 연결하는 중...`.
   - Any playing source is stopped, and the remaining-time timer stops.
   - The session tries new sockets with `socketFactory(url)`. The waits before each attempt are 500 ms, 1 s, 2 s, 4 s, 8 s, then 10 s each.
   - It stops trying when 30 s have passed since the drop. Then it shows `서버와 연결이 끊어졌습니다.` and `처음으로`, as before.
   - Each new socket sends `rejoin{token}` on open.
   - If an attempt's socket closes, or gets no `joined` within 5 s of opening, that socket is closed (handlers cleared first) and the next attempt follows.
   - **Without a token** (none received yet), an unexpected close keeps the old behaviour: the disconnect message appears at once. This keeps every locked earlier test valid, because none of them sends `token`.
3. **Successful rejoin.**
   - On `joined` the reconnect notice disappears, and the session runs the cycle 047 `burst(8)` again on a fresh clock sync (old samples are discarded).
   - On the `snapshot`:
     - `phase:"lobby"`: the lobby screen.
     - `phase:"playing"` with `song` null: the game screen, waiting for the next `prepare`.
     - `song` with `startAt` (PLAYING): load the `audioUrl`, then schedule a late start once a clock estimate exists, exactly as the cycle 048 watcher does.
     - `song` with only `audioUrl` (PREPARE): load it, then send `ready{index}`. The player is a Room player; the server ignores this for a watcher.
   - The skip button, if shown, starts as `스킵`.
   - A late joiner's watching notice is not shown after a rejoin.
4. **`rejoin-failed`.** The session ends: the socket closes with handlers cleared and the storage entry is cleared. The start screen shows `다시 연결하지 못했습니다. 방에 다시 참가해 주세요.`
5. **Close status 4001**, on any socket of the session: no reconnect. The screen shows `다른 창에서 접속해 이 연결이 끊겼습니다.` with `처음으로`.
6. **Sends while not open.** `chat`, `skipVote` and `ready` are dropped while the current socket is not open (`readyState !== 1`). This also covers the cycle 039 review nit about chatting while connecting.

## Acceptance criteria (from `frontend/`)
1. **`src/App.reconnect.test.jsx`**. It uses the locked fakes unchanged and fake timers for the backoff, driven with `advanceTimersByTimeAsync` inside `act` as in earlier tests.
   - **Token storage.** After `token{token:"T1"}` in a room session, `sessionStorage['songrush.rejoin']` holds `{token:"T1", code}`. `나가기` clears it.
   - **Reconnect into the lobby.**
     - Close the socket with status 1006: `연결이 끊겨 다시 연결하는 중...` shows.
     - No new socket appears before 500 ms. At 500 ms a new socket appears, and on its open it sends exactly `rejoin{token:"T1"}`.
     - `joined` plus a lobby `snapshot` show the lobby screen with the roster from the snapshot, and the notice is gone.
   - **Backoff.** Failed attempts (sockets that close at once) happen at 0.5, 1.5, 3.5, 7.5, 15.5 and 25.5 s after the drop. After 30 s no further socket appears, and `서버와 연결이 끊어졌습니다.` shows.
   - **Silent server.** An attempt that opens but gets no `joined` within 5 s is closed, and the next attempt follows.
   - **Reconnect into a song.**
     - A PLAYING `snapshot` with `startAt` loads the audio and starts the source late, after a `pong`.
     - A PREPARE `snapshot` loads the audio and sends `ready{index}`.
     - A `song:null` snapshot shows the game screen, and the next `prepare` plays normally.
   - **Failures.**
     - `rejoin-failed` shows its message on the start screen and clears the storage entry.
     - A close with 4001 shows the replaced message and opens no new socket within 30 s.
   - **Without a token.** An unexpected close shows the disconnect message at once, and no new socket appears.
   - **Solo.** A solo session never reconnects.
   - **Sends while not open.** While reconnecting, submitting chat sends nothing.
2. All locked earlier tests pass unchanged. `npm test`, `npm run lint` and `npm run build` exit 0.

## Test list
- `frontend/src/App.reconnect.test.jsx` covers criterion 1.

## File ownership
- **Test writer:** `src/App.reconnect.test.jsx`.
- **Implementer:** `src/game/*`, `src/components/*`, `src/App.jsx`, `src/net/*` (non-test files) and `src/App.css`.
- **Locked and unchanged:** the earlier tests and `src/testing/fakes.js`. If the fakes cannot express a needed case (for example a close status), report it instead of editing them.

## Out of scope
- Using the stored token after a page reload (follow-up).
- Server changes.
