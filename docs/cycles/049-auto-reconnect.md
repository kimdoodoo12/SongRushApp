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
   - **Timing (spec review M1).** Each attempt has a 5 s timer started when its socket is created: if no `joined` has arrived by then (whether the socket is still connecting or open and silent), the socket is closed with handlers cleared and counted as failed. After a failed attempt (closed or timed out) the next backoff wait applies before the next socket. The 30 s limit is one `setTimeout` armed at the drop; when it fires it cancels every reconnect timer, closes any attempt in progress (handlers cleared) and shows the disconnect message.
   - **Without a token** (none received yet), an unexpected close keeps the old behaviour: the disconnect message appears at once. This keeps every locked earlier test valid, because none of them sends `token`.
3. **Frames before the snapshot (spec review H1).** At the drop the session forgets that it joined. On a new socket, until a `snapshot` arrives, it handles only `joined`, `snapshot`, `pong`, `token` and `error`, and ignores `prepare`, `start`, `reveal`, `chat`, `correct`, `scores`, `skipState`, `roster` and `gameOver` (the server may flush queued broadcasts to the new socket before `joined`). Every `snapshot` first stops the current source and timer and drops the current round, then rebuilds from the snapshot, so a repeated `joined`/`snapshot` pair gives the same result.
4. **Successful rejoin.**
   - On `joined` the reconnect notice disappears, and the session runs the cycle 047 `burst(8)` again on a fresh clock sync (old samples are discarded).
   - On the `snapshot`:
     - `phase:"lobby"`: the lobby screen.
     - `phase:"playing"` with `song` null: the game screen, waiting for the next `prepare`.
     - `song` with `startAt` (PLAYING): load the `audioUrl`, then schedule a late start once a clock estimate exists, using the cycle 048 late-start rule; the round is a **played** round, not a watching one (spec review M2).
     - `song` with only `audioUrl` (PREPARE): load it, then send `ready{index}`. The player is a Room player; the server ignores this for a watcher.
     - The snapshot's `players` update the roster and its `scores` (when not empty) update `점수판` and the own score (spec review M3).
     - Accepted: a rejoiner who was in grace at `start` (a watcher on the server) sees a skip button whose clicks the server ignores.
   - The skip button, if shown, starts as `스킵`.
   - A late joiner's watching notice is not shown after a rejoin.
5. **`rejoin-failed`.** The session ends: the socket closes with handlers cleared and the storage entry is cleared. The start screen shows `다시 연결하지 못했습니다. 방에 다시 참가해 주세요.`
6. **Close status 4001**, on any socket of the session: no reconnect. The screen shows `다른 창에서 접속해 이 연결이 끊겼습니다.` with `처음으로`.
7. **Sends while not open.** Every send (`chat`, `skipVote`, `ready`, `start`, `leave`, `ping`) is dropped while the current socket is not open (`readyState !== 1`); a real `WebSocket.send` on a connecting socket throws. This also covers the cycle 039 review nit about chatting while connecting.
8. **Leaving while reconnecting (spec review H2).** `나가기`, `처음으로` and unmount during a reconnect cancel every reconnect timer, close the attempt in progress (handlers cleared), clear the storage entry and return to the start screen; no socket is created afterwards.

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
   - **Frames before the snapshot.** A `start` for the old index sent on the new socket before `joined`, then `joined` and a PLAYING `snapshot`, give exactly one started source; a second `joined` + `snapshot` pair does not add another.
   - **Snapshot data.** A playing snapshot with `scores` shows `점수판` from it.
   - **Leaving while reconnecting.** `나가기` during a reconnect returns to the start screen, no new socket appears within 30 s, and the storage entry is gone.
   - **Timing.** An attempt that stays connecting for 5 s is dropped and the next one follows after the next wait; at exactly 30 s after the drop the disconnect message appears even if an attempt is in progress.
   - The test file clears `sessionStorage` in `beforeEach` (jsdom keeps it between tests in one file).
   - **Solo.** A solo session never reconnects.
   - **Sends while not open.** While reconnecting, submitting chat sends nothing.
2. All locked earlier tests pass unchanged. `npm test`, `npm run lint` and `npm run build` exit 0.

## Review changes (spec round 1)
- **High.** Frames that reach the new socket before the snapshot are ignored and the snapshot always rebuilds; leaving or unmounting during a reconnect cancels everything and sends nothing on a connecting socket.
- **Medium.** The 5 s attempt timer starts at socket creation, failed attempts wait for the next backoff step, and one 30 s timer ends the reconnect; a rejoined round is a played round; snapshot players and scores are applied.
- **Nit.** The storage entry is write-only in this cycle (the reload follow-up must handle duplicated tabs copying `sessionStorage`).

## Test list
- `frontend/src/App.reconnect.test.jsx` covers criterion 1.

## File ownership
- **Test writer:** `src/App.reconnect.test.jsx`.
- **Implementer:** `src/game/*`, `src/components/*`, `src/App.jsx`, `src/net/*` (non-test files) and `src/App.css`.
- **Locked and unchanged:** the earlier tests and `src/testing/fakes.js`. If the fakes cannot express a needed case (for example a close status), report it instead of editing them.

## Out of scope
- Using the stored token after a page reload (follow-up).
- Server changes.

## Result
- Tests `8700ec1` (locked); implementation `22afeb4` (attempt 1).
- Red phase (verifier): 18 of 20 new tests failed on missing reconnect behaviour; the 2 passing ones guard the no-token and solo paths.
- Verifier: `npm test` twice (11 files, 169 tests), `npm run lint`, `npm run build` exit 0; no test or fake changed after `8700ec1`. Real-server smoke: B's socket terminated mid-game, a new socket's `rejoin` got exactly `joined` (name `Bo`), `snapshot` (`phase:"playing"`, song `index/total/audioUrl`), `roster`; a bogus token got only `rejoin-failed`.

## Code review (critic)
No Blocker, High or Medium. Confirmed: old sockets' events are ignored and handlers are detached before every intentional close; every timer stops on success, give-up, leave, unmount, 4001 and `rejoin-failed`; pre-snapshot frames are filtered (the server really flushes broadcasts to the new socket first); the late start after a rejoin waits for a fresh estimate; the token is never logged and is cleared on every end.
- **Nits (follow-up).** A drop on the final-ranking screen loses the ranking; a drop during REVEAL loses the reveal card.
