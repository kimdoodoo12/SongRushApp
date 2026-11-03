# 048 multi-screen (F-005, part 1)

## Goal
In a room game, every player sees the same state:
- everyone's scores after each song;
- a skip button with the vote count;
- the final ranking with shared ranks;
- a "watching" notice for late joiners;
- a return to the lobby when the game is abandoned.

The client also never starts a song early after its clock jumps (cycle 047 review M1).

## Scope
- **Split.** F-005's automatic reconnect (token in `sessionStorage`, `rejoin`, a time-out, no reconnect after 4001, snapshot restore) moves to a new item, **F-006**. This cycle covers everything else in F-005.

## Behaviour (room sessions; solo keeps its cycle 039 behaviour)
1. **Scores.** On every `scores{scores:[{name, score}]}`, the game screen shows a list labelled `점수판`. Each line reads `<name> <score>점` in server order, and the own line ends with ` (나)`. The cycle 041 `점수: <own>` line stays.
2. **Skip.**
   - While the current song is between `start` and its `reveal`, the game screen shows a button `스킵`. It sends `skipVote{index}` with the current index.
   - The button reads `스킵 취소` after the player has voted in this song. The client tracks its own toggles and resets them on each `prepare`.
   - On `skipState{index, votes, needed}` for the current index, the screen shows `스킵 <votes>/<needed>`.
   - A `reveal` hides the button and the count.
   - Solo has no skip button.
3. **Final ranking.** On `gameOver{ranking:[{rank, name, score}]}`, a list labelled `최종 순위` shows `<rank>위 <name> <score>점` per row, in server order, with shared ranks as sent. The own row ends with ` (나)`. The cycle 041 `최종 점수: <n>점` line stays.
4. **Watching.**
   - A room session that receives `joined` and then a `snapshot` with `phase:"playing"` is a watcher of the current song.
   - It shows `이번 곡은 구경 중입니다. 다음 곡부터 참가합니다.`
   - It plays the snapshot's song if `song` is not null. With `startAt`, it schedules as a late start; with only `audioUrl`, it loads and waits for the next `start` with that index.
   - It sends no `ready` and shows no skip button.
   - The notice disappears at the next `prepare`, which starts a normal song.
5. **Abandoned game.** A `roster{phase:"lobby"}` that arrives while the game screen shows a song (no `gameOver` received) stops the source and the timer, and shows the lobby screen with the text `게임이 중단되어 대기실로 돌아왔습니다.`
6. **Clock-jump guard (cycle 047 review M1).**
   - In `createClockSync`, `estimate` and `current()` ignore samples with a negative RTT.
   - When the drift rule fires at `start`, the song uses `Math.min(receipt, estimate.offsetMs)` instead of the estimate. The smaller offset is never early.
   - The drift `burst(3)` also clears the stored samples first, so stale samples cannot outvote the new ones.
   - `outputLatency` is used only when `Number.isFinite`.

## Changes to locked earlier tests (declared; the test writer changes only these)
- **`App.sync.test.jsx` (cycle 047), the drift cases.** These are the `it.each([2000, -2000, 100])` that asserted "the song still uses the synced offset".
  - With drift, the scheduled `when` and `offset` now follow `Math.min(receipt, synced)`.
  - The 3 extra pings stay.
  - The cases without drift are unchanged.
- **`clockSync.test.js` (cycle 047).** It is unchanged. Negative-RTT handling and clearing are new tests.
- No other locked test may change.

## Acceptance criteria (from `frontend/`)
1. **`src/App.multi.test.jsx`** uses the locked fakes, unchanged, with real timers and `waitFor` (fake timers only where needed):
   - **Scores.** After `joined{name:"Hana (2)"}` and a game, a `scores` message with 3 rows shows the `점수판` lines, with `(나)` on `Hana (2)`.
   - **Skip.**
     - `스킵` appears after `start`, and its click sends `skipVote{index:0}`.
     - The label becomes `스킵 취소`, and a second click sends another `skipVote{index:0}`.
     - `skipState{index:0, votes:1, needed:2}` shows `스킵 1/2`.
     - A `skipState` for another index is ignored.
     - After `reveal`, the button and count are gone.
     - After the next `prepare`, the label is `스킵` again.
     - Solo shows no `스킵`.
   - **Ranking.** `gameOver` with `[{rank:1,…},{rank:1,…},{rank:3,…}]` shows the three `최종 순위` lines in order, with the own row marked.
   - **Watching.**
     - A join that receives a `snapshot` with `phase:"playing"` and a `song` with `startAt` shows the watching notice, fetches the `audioUrl` and schedules a late start.
     - It sends no `ready`.
     - It shows no `스킵`.
     - At the next `prepare` the notice is gone, and `ready` is sent after the load.
   - **Abandoned.** A `roster{phase:"lobby"}` during a song stops the source and shows the lobby screen with the abandon text.
2. **`src/net/clockSyncGuards.test.js`**
   - Negative-RTT samples are ignored by `estimate`, and by `current()` through `onPong` with a clock stepped backwards.
   - The clear on a drift burst: the session-level test in criterion 1 or 3 covers it through `App.sync` behaviour, or a unit-level `clear()` on the sync object if one is exposed.
3. **`App.sync.test.jsx` drift cases**, as declared above: with drift +2000 the plan uses the receipt offset (the smaller one), and with -2000 the synced one. This is the declared change.
4. All other locked tests pass unchanged. `npm test`, `npm run lint` and `npm run build` exit 0.

## Test list
- `frontend/src/App.multi.test.jsx` covers criterion 1.
- `frontend/src/net/clockSyncGuards.test.js` covers criterion 2.
- The declared change in `frontend/src/App.sync.test.jsx` covers criterion 3.

## File ownership
- **Test writer:** the two new files and the declared `App.sync.test.jsx` change.
- **Implementer:** `src/net/clockSync.js`, `src/game/*`, `src/components/*`, `src/App.jsx` and `src/App.css`.

## Out of scope
- Automatic reconnect (F-006).
- Restart controls beyond the cycle 041 `대기실로` flow.
