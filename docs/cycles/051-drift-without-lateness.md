# 051 drift-without-lateness (F-007)

## Goal
A `start` frame that reaches one player late no longer makes that player start late. The song is first scheduled as today, never early. When the re-sync burst returns a fresh estimate, the start is corrected to the true time (cycle 048 review M2).

## Why a correction, not a deferral
The backlog item suggests holding the source until the burst answers. The locked cycle 047/048 tests instead expect a source the moment a drifted `start` arrives (`App.sync.test.jsx` drift cases, `App.multi.test.jsx` drift-clear test). The correction below leaves those tests valid. It also keeps the "never early" guarantee: the song is scheduled first with `min(receipt, estimate)` as today, and moves only on a fresh measurement.

## Behaviour (`frontend/src/game/session.js`; room sessions only)
1. **Drift as today.** At `start`, `receipt = serverTime - now()`. When `|receipt - estimate| > rtt + 50`, the session does what cycle 048 does: clear the samples, send 3 pings, and schedule with `min(receipt, estimate)`.
2. **Provisional start.** When the drift path used the receipt (`receipt < estimate.offsetMs`), the round is marked provisional with that offset. The cause can be a late frame or a forward clock jump. The other direction (`receipt > estimate`) is not marked and keeps today's behaviour; it is out of scope.
3. **Correction (spec review H1, M1).**
   - **Lower bound.** For every pong after the drift `clear()`, the session takes that sample's lower bound `serverTime - t1`. By causality this is never above the true offset, so a song scheduled from it is never early. `clockSync` exposes it, for example as `lowerBound()`: the largest `serverTime - t1` over the stored samples, or `null`.
   - **Stale pongs.** The drift `clear()` also forgets pending pings, so a pong for a ping sent before the clear is ignored. Without this, a ping sent before a forward clock jump leaves a high-RTT sample whose median offset is too high. The next song's `current()` would then use it without seeing drift, and start early (spec review round 2, H1).
   - **When to move.** On each such pong, while the round is current, not revealed and provisional: if the lower bound exceeds the offset in use by more than 20 ms, the start moves earlier. Each move:
     - sets `round.start.offsetMs` to the lower bound, on the same object the remaining-time ticks read, so the bar follows;
     - stops any source already created, with `stop()` and no argument;
     - schedules a new source with `planPlayback`; if the song is already sounding, it continues from the corrected position (`offset > 0`).
   - The offset only ever increases, so the start only moves earlier, and never past the true time.
4. **No new estimate.** If no pong arrives, the provisional start simply plays; that is today's behaviour. A reveal, the next `prepare`, a snapshot or a reconnect ends the provisional state.
5. Solo is unchanged.

## Acceptance criteria (from `frontend/`)
1. **`src/App.drift.test.jsx`** uses the locked fakes and fake timers, with the helpers copied from `App.sync.test.jsx`.
   - Note: `advance()` moves the injected clock and the fake timers but not `ctx.currentTime`; the tests set `ctx.currentTime` themselves. Pongs come back after `RTT` 20 ms with the stated offset, as in `answerPings`.
   - **Late frame, corrected before the start.**
     - Setup: synced to `SYNC` (2500); `ctx.currentTime = 10`; a `start` whose receipt offset is `SYNC - 1500` (the frame arrived 1.5 s late), with `startAt` 3000 ms away by the true clock.
     - The first source starts at `when` 14.5, offset 0.
     - Answer the drift burst's first ping with offset `SYNC`. The lower bound is 2490 (`SYNC - RTT/2`). Then:
       - the first source's last `stop` call has no argument;
       - a second source starts at `when` 12.99 (within 0.005) with offset 0, and its `stopAt` is 102.99 (within 0.005).
   - **Late frame, corrected while playing (spec review B1).**
     - Setup: the frame is 1000 ms late (receipt `SYNC - 1000`) and the song began 500 ms ago by the true clock (`startInMs: -500`).
     - The first source is at `when` 10.5.
     - Set `ctx.currentTime = 10.6`, then answer the first burst ping with offset `SYNC`. The second source starts at `when` 10.6 (within 0.005). Its `offset` is `(now() + 2490 - startAt) / 1000` at the pong, within 0.005, computed in the test from its own values (about 0.51 s). After one more 250 ms advance, the bar's `aria-valuenow` is 89240 (`deadlineAt - 2490 - now()`); without the correction it would be 90000 (round 2, B1).
   - **Stale pong after a jump (round 2, H1).**
     - Setup: `joinRoomFake`, 8 answered pings, `prepareFake`, 2 answered pings of the ready burst; advance 130 so the third ping goes out; then `clock.t += 1500` (a forward jump); then `start` for index 0 with receipt 1000 and `startAt` 3000 ms away by the new offset 1000.
     - Answer that third (stale) ping now, with `serverTime` from the true server clock. Leave the drift burst unanswered. There is no second source for song 0.
     - Then `reveal`, `prepare(1)`, `ready`, and an on-time `start` for index 1 (receipt 1000, `startAt` 3000 ms away by offset 1000), with `ctx.currentTime` set before it. Song 1's `when` is not earlier than its true start (`ctxTime + 3`, within 0.005).
   - **Later pongs move it again** (the setup of the first case: provisional offset 1000).
     - The first fresh pong has RTT 400, so its lower bound is 2300 (it moves the start, because 2300 is more than 20 ms above 1000).
     - The burst's second ping is answered with `serverTime = t1 + 2490` (an asymmetric path, valid by causality): a third source appears.
     - The third ping is answered with `serverTime = t1 + 2480`: nothing changes.
     - Here `t1` is the clock at the pong.
   - **Real forward clock jump.**
     - The client clock jumps +1500 ms (`clock.t += 1500` with no timer advance), then `start` arrives with an on-time frame.
     - The receipt is smaller and the song is first scheduled by the receipt.
     - The drift burst is answered with offset `SYNC - 1500`, which is the true new offset. No second source appears, and the first source's `when` is unchanged.
   - **No fresh pong.** A late frame with no burst answered keeps one source at `when` 14.5. No second source appears within 4 s.
   - **Earlier-direction drift is unchanged.** With receipt `SYNC + 2000`, answering the burst with `SYNC + 2000` creates no second source for this song.
   - **No-drift case.** With a receipt equal to `SYNC`, no ping is sent and exactly one source plays at `when` 13.
   - **Reveal ends it.** A late frame, then `reveal` before any pong, then a pong: no new source.
2. **Declared change to a locked earlier test: comments only.** In `src/App.sync.test.jsx`, the comments of "start schedules source.start from the synced offset..." and "a song 1 s late by the synced clock..." are fixed to say that the receipt offset is 4500, i.e. a +2000 drift, and that the synced one wins as the smaller. Only comment lines change.
3. All other locked tests pass unchanged. `npm test`, `npm run lint` and `npm run build` exit 0.

## Review changes (spec round 1)
- **Blocker.** The "while playing" numbers were impossible; the case now uses a 1 s late frame on a song that began 0.5 s ago.
- **High.** A single median estimate could start the song early, through an asymmetric path or a pong for a ping sent before a clock jump. The correction now uses only per-sample lower bounds `serverTime - t1`, and the drift clear forgets pending pings.
- **Medium.**
  - The correction follows every fresh pong, moving only earlier.
  - The bar check uses `aria-valuenow` after a tick.
  - The stop check requires an argument-less `stop()`.

## Review changes (spec round 2)
- **Blocker.** The bar check moved to the "while playing" case, because before `startAt` the bar is clamped at its maximum.
- **High.** Forgetting pending pings protects the next song's median estimate. The stale-pong case now checks the next song.
- **Medium.** The later pongs are given as `serverTime = t1 + bound`.

## Test list
- `frontend/src/App.drift.test.jsx` (new).
- The comment-only change in `frontend/src/App.sync.test.jsx`.

## File ownership
- **Test writer:** the test list.
- **Implementer:** `frontend/src/game/session.js` and `frontend/src/net/clockSync.js`; `frontend/src/audio/playback.js` if needed.

## Out of scope
- The earlier direction (backward clock jump). Today it starts late and never early; that stays.
- Server changes.
