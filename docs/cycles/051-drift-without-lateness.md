# 051 drift-without-lateness (F-007)

## Goal
A `start` frame that reaches one player late no longer makes that player start late. The song is first scheduled as today, never early. When the re-sync burst returns a fresh estimate, the start is corrected to the true time (cycle 048 review M2).

## Why a correction, not a deferral
The backlog item suggests holding the source until the burst answers. The locked cycle 047/048 tests instead expect a source the moment a drifted `start` arrives (`App.sync.test.jsx` drift cases, `App.multi.test.jsx` drift-clear test). The correction below leaves those tests valid. It also keeps the "never early" guarantee: the song is scheduled first with `min(receipt, estimate)` as today, and moves only on a fresh measurement.

## Behaviour (`frontend/src/game/session.js`; room sessions only)
1. **Drift as today.** At `start`, `receipt = serverTime - now()`. When `|receipt - estimate| > rtt + 50`, the session does what cycle 048 does: clear the samples, send 3 pings, and schedule with `min(receipt, estimate)`.
2. **Provisional start.** When the drift path used the receipt (`receipt < estimate.offsetMs`), the round is marked provisional with that offset. The cause can be a late frame or a forward clock jump. The other direction (`receipt > estimate`) is not marked and keeps today's behaviour; it is out of scope.
3. **Correction.**
   - The first `pong` after the drift `clear()` makes `sync.current()` non-null again from fresh samples only. If the round is still the current one, not revealed and provisional, the round stops being provisional.
   - If the fresh `offsetMs` exceeds the provisional offset by more than 20 ms:
     - `round.start.offsetMs` becomes the fresh offset, so the remaining-time bar follows.
     - Any source already created is stopped.
     - A new source is scheduled with `planPlayback` from the fresh offset. If the song is already sounding, it continues from the corrected position (`offset > 0`).
   - Otherwise nothing changes. The fresh offset never moves the start later.
4. **No new estimate.** If no pong arrives, the provisional start simply plays; that is today's behaviour. A reveal, the next `prepare`, a snapshot or a reconnect ends the provisional state.
5. Solo is unchanged.

## Acceptance criteria (from `frontend/`)
1. **`src/App.drift.test.jsx`** uses the locked fakes and fake timers, with the helpers copied from `App.sync.test.jsx`.
   - **Late frame, corrected before the start.**
     - Setup: synced to `SYNC` (2500); `ctx.currentTime = 10`; a `start` whose receipt offset is `SYNC - 1500` (the frame arrived 1.5 s late), with `startAt` 3000 ms away by the true clock.
     - The first source starts at `when` 14.5, offset 0.
     - Answering the drift burst's first ping with offset `SYNC` stops that source (its `stop` is called with a time at or before 10.5, or `stop` is called a second time). A second source starts at `when` 13 minus the elapsed clock time, within 0.01. Its `stopAt` matches the true deadline.
     - The bar's `aria-valuemax` stays 90000.
   - **Late frame, corrected while playing.** As above, but the frame is 3.5 s late and `startAt` is 3000 ms away. The first source is at `when` 10.5. After the pong (at 10.02 or later), the second source plays at once (`when` equals `ctx.currentTime` at scheduling) with `offset` of about 0.5 s plus the elapsed time, within 0.05.
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

## Test list
- `frontend/src/App.drift.test.jsx` (new).
- The comment-only change in `frontend/src/App.sync.test.jsx`.

## File ownership
- **Test writer:** the test list.
- **Implementer:** `frontend/src/game/session.js`; `frontend/src/net/clockSync.js` and `frontend/src/audio/playback.js` if needed.

## Out of scope
- The earlier direction (backward clock jump). Today it starts late and never early; that stays.
- Server changes.
