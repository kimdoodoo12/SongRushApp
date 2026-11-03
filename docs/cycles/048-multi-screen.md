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
   - The button reads `스킵 취소` after the player has voted in this song. The client tracks its own toggles; if an `error{code:"rate-limited"}` arrives after a `skipVote` and before the next `skipState`, that toggle is undone (the server did not count it, spec review M1). The own toggle and the `스킵 n/m` count reset on each `prepare`.
   - On `skipState{index, votes, needed}` for the current index, the screen shows `스킵 <votes>/<needed>`.
   - A `reveal` hides the button and the count.
   - Solo has no skip button.
3. **Final ranking.** On `gameOver{ranking:[{rank, name, score}]}`, a list labelled `최종 순위` shows `<rank>위 <name> <score>점` per row, in server order, with shared ranks as sent. The own row ends with ` (나)`. The cycle 041 `최종 점수: <n>점` line stays when the ranking has the own row; a watcher who joined during the last song has no row, so that line is hidden (spec review M3).
   - A new game's `prepare{index:0}` clears `점수판`, `최종 순위` and the skip state (D-062, spec review M4).
4. **Watching.**
   - A room session that receives `joined` and then a `snapshot` with `phase:"playing"` is a watcher of the current song.
   - It shows `이번 곡은 구경 중입니다. 다음 곡부터 참가합니다.`
   - It plays the snapshot's song if `song` is not null. With `startAt`, it schedules as a late start once the buffer is decoded **and** a clock estimate exists (`sync.current()` not null; the snapshot carries no `serverTime`, so it waits for the first `pong` of the `joined` burst, spec review H1); with only `audioUrl`, it loads and waits for the next `start` with that index (which has `serverTime`).
   - It sends no `ready` and shows no skip button.
   - The notice disappears at the next `prepare`, which starts a normal song.
5. **Abandoned game.** A `roster{phase:"lobby"}` that arrives in a room session while the game screen is shown and no `gameOver` was received for this game (whether or not a song is playing; in practice only a watcher receives it, spec review H2) stops any source and the timer, and shows the lobby screen with the text `게임이 중단되어 대기실로 돌아왔습니다.` in a `<p>` (not an `<li>`; the locked cycle 041 tests read every `<li>` on the lobby screen).
6. **Clock-jump guard (cycle 047 review M1).**
   - In `createClockSync`, `estimate` and `current()` ignore samples with a negative RTT.
   - When the drift rule fires at `start`, the song uses `Math.min(receipt, estimate.offsetMs)` instead of the estimate. The smaller offset is never early.
   - The drift `burst(3)` also clears the stored samples first, so stale samples cannot outvote the new ones.
   - `outputLatency` is used only when `Number.isFinite`.

## Changes to locked earlier tests (declared; the test writer changes only these)
- **`App.sync.test.jsx` (cycle 047), the drift cases.** These are the `it.each([2000, -2000, 100])` that asserted "the song still uses the synced offset".
  - In that test the receipt offset is `SYNC + drift`. With drift -2000 the receipt (500) is smaller than the synced offset (2500), so the plan uses the receipt: `when` 15, `offset` 0 (later, never earlier). With +2000 and 100 the synced offset is the smaller one and the plan stays `when` 13. Only the -2000 case's plan assertion changes (spec review B1).
  - The 3 extra pings stay in all three cases.
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
     - A `rate-limited` error after a click undoes the `스킵 취소` label.
     - After `reveal`, the button and count are gone.
     - After the next `prepare` and `start`, the label is `스킵` and no count is shown.
     - Solo shows no `스킵`.
   - **Ranking.** `gameOver` with `[{rank:1,…},{rank:1,…},{rank:3,…}]` shows the three `최종 순위` lines in order, with the own row marked. A ranking without the own row shows no `최종 점수` line. A second game's `prepare{index:0}` clears `점수판` and `최종 순위`.
   - **Watching.**
     - A join that receives a `snapshot` with `phase:"playing"` and a `song` with `startAt` shows the watching notice and fetches the `audioUrl`; no `source.start` happens before the first `pong`; after a `pong` it schedules a late start using the synced offset (the test fixes the values).
     - A `snapshot` with `song:null` (a paused or revealing game) shows the notice and plays nothing.
     - It sends no `ready`.
     - It shows no `스킵`.
     - At the next `prepare` the notice is gone, and `ready` is sent after the load.
   - **Abandoned.** (a) A watcher playing a song who receives `roster{phase:"lobby"}` stops the source and shows the lobby screen with the abandon text; (b) a watcher with a `song:null` snapshot who receives it also shows the lobby screen (and, as host, `게임 시작`).
2. **`src/net/clockSyncGuards.test.js`**
   - Negative-RTT samples are ignored by `estimate`, and by `current()` through `onPong` with a clock stepped backwards.
   - The clear on a drift burst: the session-level test in criterion 1 or 3 covers it through `App.sync` behaviour, or a unit-level `clear()` on the sync object if one is exposed.
3. **`App.sync.test.jsx` drift cases**, as declared above: with drift +2000 the plan uses the receipt offset (the smaller one), and with -2000 the synced one. This is the declared change.
4. All other locked tests pass unchanged. `npm test`, `npm run lint` and `npm run build` exit 0.

## Review changes (spec round 1)
- **Blocker.** The drift sign was reversed in criterion 3; only the -2000 case uses the receipt offset (`when` 15).
- **High.** A watcher's late start waits for the first clock estimate (the snapshot has no `serverTime`); the abandon rule no longer requires a playing song (only watchers receive it, and one may become host).
- **Medium.** A rate-limited skip is undone; the skip count resets at `prepare`; the final-score line hides without an own ranking row; a new game clears the lists; F-006 is added to the backlog by the lead.

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

## Locked-test correction (attempt 1)
- The implementer reported (and the lead confirmed from the test source) that two more cycle 047 tests in `App.sync.test.jsx` — "start schedules source.start from the synced offset, not the receipt offset (2 s apart)" and "a song 1 s late by the synced clock ..." — use `receiptMs: 500` against a synced offset of 2500. That is a drift of -2000, so under this cycle's rule the smaller (receipt) offset wins and they cannot keep `when` 13 / 10; the spec had missed them.
- Declared change: those two tests use `receiptMs: 4500` instead (still 2 s apart, now on the side where the synced offset is the smaller one), so they keep asserting that the synced offset is used; nothing else in them changes. The lead unlocked the tests for this change only and locked them again.
