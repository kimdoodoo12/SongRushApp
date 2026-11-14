# 128 window-snapshot (B-036, from S-008 design 125, D-129, issue #44 request 1)

## Goal
With the play-to-limit switch on, a player who reloads or joins while the music plays on after the reveal gets a snapshot that carries the song, the reveal and the skip count.

## Background
- Cycles 126 and 127 added the reveal window (`playUntil`) and skips in it, behind `songrush.game.play-to-limit` (default off). This is step 3 of 7.
- Today `Room.currentSong()` returns null in `REVEAL` (Room.java:310-320), so a reload in the window gets `song: null` and no reveal; and since a reload gets no `skipState`, it cannot show the skip count (cycle 127 review).
- The `reveal` frame is built in `endSong` (Room.java:980-1002) and is not kept; only `playUntil` is.
- `Lobby.sendJoinedAndSnapshot` (Lobby.java:676-696) puts `room.currentSong(m.id)` in the snapshot. For a member returning from grace the snapshot is built before `markRoomConnection` (Lobby.java:319-325), so the Room still counts that player as disconnected.

## Design
All of this is with the switch on. With it off nothing changes.

**When.** Exactly when `skipInWindow()` holds (switch on, not paused, phase `REVEAL`, `now < playUntil`). Before the reveal, when paused, at or after `playUntil`, after a deadline or skip reveal (`playUntil == now`), and once the next `prepare` is out (phase `PREPARE`), `currentSong` behaves as today.

**What.** `currentSong(playerId)` in the window returns:
- the fields a playing song has: `index`, `total`, `audioUrl`, `startAt`, `deadlineAt`, plus the per-mode fields `currentSong(playerId)` adds today (choice: `choices`, `picked`, `pickState`; ranked: `answered`, `correct`), with the values the room holds now;
- `reveal`: a map equal to the `reveal` frame that was broadcast, without its `type` key (so with `playUntil`, and `correctChoice` or `ranking` when the broadcast had them). The room keeps a copy of that frame at the reveal (never the map passed to the outbox) and drops it when it leaves `REVEAL`;
- `skipState: {votes, needed}`: `votes` is the number of current skip votes; `needed` is the majority of connected Room players, computed exactly as the `skipState` broadcast computes it (`connectedCount()/2 + 1`). A player returning from grace is not yet counted when its snapshot is built (Lobby.java:319-325), the same as `pickState.total` today; that is accepted here and recorded as a follow-up.

`currentSong()` (no argument) stays as today, and the no-argument form is not used by the Lobby snapshot.

## Acceptance criteria
Switch on unless said otherwise; a song of at least 90 s decided at 10 s; three connected players.
1. In the window, `currentSong(id)` has `index`, `total`, `audioUrl`, `startAt`, `deadlineAt` equal to the values `currentSong(id)` had while that song played, and `reveal` equal to the broadcast `reveal` frame minus `type` (chat 선착순, choice 선착순 with `correctChoice`, ranked with `ranking`). Choice and ranked keep their per-mode keys.
2. `skipState` in the window: `{votes: 0, needed: 2}` at the reveal; `{votes: 1, needed: 2}` after one vote.
3. A watcher's `currentSong` in the window, and that of an id the Room does not know (the Lobby builds a late joiner's snapshot before `addPlayer`), has `reveal` and `skipState` too, with `needed: 2` (not counting that id), no `picked` value and `answered: false`.
   - After any `currentSong` call, the recorded broadcast `reveal` frame still has its `type` key (the stored frame is a copy).
4. No `reveal` and no `skipState` key: while the song plays before the decision; in `PREPARE`. `currentSong` is null (as today): paused in the window; at or after `playUntil` (no prefetch, during the 5 s minimum); after a deadline reveal; after a skip reveal; with the switch off in `REVEAL`. With prefetch on, after the delayed `prepare` the snapshot is the next song's `PREPARE` shape with no `reveal`.
5. Through the Lobby (switch on, built with the Lobby constructor that takes the switch): a member who disconnects during the song and returns from grace in the window gets a `snapshot` whose `song` has `reveal` and `skipState` with `votes: 0` and the `needed` of the plain count; a replaced socket in the window gets the same. A late joiner in the window gets a snapshot with `reveal`. Switch off: the same reconnect in `REVEAL` gets `song: null`.
6. Switch off: every existing test passes unchanged.
7. `./gradlew test` passes from `backend/`.

## Test list
- New `backend/src/test/java/com/songrush/game/RoomWindowSnapshotTest.java`: criteria 1 to 4 and 6 (the switch-off null) at Room level.
- New `backend/src/test/java/com/songrush/game/LobbyWindowSnapshotTest.java`: criterion 5.
- No existing test changes (the critic checked: RoomPlayToLimitTest.java:866-870 already accepts a non-null result; the other `currentSong` calls are in `PREPARE`). A test this cycle breaks is reported, not changed.

## File ownership
- Test writer: the two new test files.
- Implementer: `backend/src/main/java/com/songrush/game/Room.java`; `Lobby.java` only if the snapshot call needs it.

## Out of scope
- Any client change (F-035, F-037), turning the switch on (B-037).
- The cycle 122 snapshot `pickState` count of a rejoining player outside the window, and the cycle 127 follow-up on `votes` after `gameOver`.
- Counting a returning player in the snapshot's `needed` or `pickState.total`: the snapshot uses the room's plain count (follow-up).

## Spec review
One round. No Blocker, one High, taken by simplifying.
- High: criterion 5 could not tell a rule that counts the returning receiver from one that does not (with three players both give `needed: 2`). The rule itself was scope the item did not ask for, and it would have shown the returner a different `needed` than the other clients and than `pickState.total` in the same snapshot (Medium). The rule is dropped: `needed` is the plain count, the same as the broadcast; the stale count for a grace return goes to Follow-ups.
- Nits taken: the late joiner's snapshot is built for an id the Room does not know yet; the stored reveal must be a copy, checked by the recorded frame keeping `type`.
- Checked by the critic: no leak (the window requires `REVEAL`; the prefetch `prepare` moves to `PREPARE`); picks and the ranked list are kept through `REVEAL`; the audio token outlives the window; no existing test breaks.

## Result
- Tests first: commit `dd304f5`. `RoomWindowSnapshotTest` (new, 50 cases) and `LobbyWindowSnapshotTest` (new, 5 cases; the grace return runs with 3 and 4 members, so `needed` tells the plain count from one that counts the returner). Red, run by the verifier: 1248 tests, 30 failed, 0 errors, only in those two classes, every failure an assertion that the song in the window is null.
- Implementation: commit `af44283`, first attempt. `Room.java` only (+27, -5): a copy of the broadcast `reveal` without `type` (`lastReveal`), taken in `endSong` before the broadcast and cleared in `enterPrepare` and at game over; `currentSong(playerId)` in `skipInWindow()` builds the playing fields (new `playingSong()`, shared with `currentSong()`), keeps the per-mode keys, and adds `reveal` and `skipState{votes, needed}`.
- Green, run by the verifier with `--rerun-tasks`: 1248 tests, 0 failures, 0 errors, 1 skipped. `git diff dd304f5 HEAD -- backend/src/test` is empty. Criteria 1 to 7 confirmed against the test bodies.
- Review of the diff: no Blocker, High or Medium. Checked: every way out of `REVEAL` clears the copy; the copy is written in the same `endSong` that sets `REVEAL` and before the broadcast, on the room's single thread; pause, the prefetch `prepare`, a skip or deadline reveal, a new game and a promoted late joiner never see it. Below threshold: the copy is shallow, and a skip or deadline reveal leaves a copy that the shut window never shows (Follow-ups).
- The switch is still off by default.
