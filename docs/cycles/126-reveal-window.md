# 126 reveal-window (B-034, from S-008 design 125, D-129, issue #44 request 1)

## Goal
Behind a server switch that is off by default, a song that is decided by answers keeps its reveal up until the music ends instead of for 5 s.

## Background
- D-129: a song no longer stops when it is decided. Design 125 chose to stretch the reveal: the decision still sends `reveal` and `scores` and enters `Phase.REVEAL`; only the reveal's length changes.
- This cycle is the first of seven. Skip during the window is B-035 and the snapshot is B-036. Until the last item the switch stays off, so nothing changes for players.

## Design
**The switch**
- `songrush.game.play-to-limit` (env `SONGRUSH_GAME_PLAYTOLIMIT`), a boolean, default `false`, in `GameProperties` beside `prefetchOnReveal`.
- It reaches `Room` the way `prefetchOnReveal` does (GameSocketHandler, Lobby, Room). Every existing constructor and factory keeps its signature and means off.
- Off: the room behaves exactly as today, and `reveal` has no `playUntil` key.

**On: the reveal window**
- Every `reveal` carries `playUntil`, server epoch milliseconds:
  - the song was **decided by answers** (선착순: the first correct chat or pick; 순위전: `endIfAllAnswered`, from an answer, a pick, a disconnect or a start): `playUntil = deadlineAt`. It can lie in the past (a disconnect after `deadlineAt` before the deadline task has run); the window is then 5 s. When a resumed 순위전 song is decided as it starts, the reveal comes before `startAt` and the window is the lead plus the whole song;
  - a **passed skip vote**, or the **deadline** (the deadline task, or a chat or pick at or after `deadlineAt`): `playUntil` = the time of the reveal.
- `windowEnd = max(playUntil, revealTime + REVEAL_PAUSE)`.
- The frames at the reveal and their order are unchanged (`reveal`, `scores`, after whatever `correct` and chat frames precede them today). The phase is `REVEAL`.
- **Without prefetch:** at `windowEnd` the room does what `afterReveal` does today (the next `prepare`, or `gameOver` after the last song). Nothing is sent between `scores` and then.
- **With prefetch** and a next song: at `windowEnd - REVEAL_PAUSE` the room does what it does today right after `scores`: it clears `picks` and the ranked list, enters `PREPARE` for the next song, sends that `prepare` with `holdMs` = 5000, and is `revealing` for 5 s. Until then the phase stays `REVEAL` and `picks` and the ranked list are kept. For a deadline or skip reveal that moment is the reveal itself: the step runs inline in the same turn, not through the scheduler, so those behave exactly as today. The last song has no next `prepare` and ends with `gameOver` at `windowEnd`.
- In the window, as in any `REVEAL` today: chat is broadcast and never answer-checked, a pick is ignored, a skip vote is ignored (until B-035), a connection change sends no `pickState`.
- The earlier deadline task must not fire a second `reveal`.
- A pause in the window and the resume are as today (the resume moves on at once).

## Acceptance criteria
All with the switch on unless said otherwise. "Decided at 10 s" means 10 s after `startAt`, on a song of at least 90 s.
1. 선착순 chat, no prefetch: a correct chat at 10 s gives the frames of today (`correct`, `chat`, `reveal`, `scores`) and `reveal.playUntil == deadlineAt`. No frame follows until `deadlineAt`; then the next `prepare` comes.
2. The same with a song decided 2 s before `deadlineAt`: the next `prepare` comes 5 s after the reveal, not at `deadlineAt`.
3. A reveal at the deadline and a reveal by a passed skip have `playUntil` equal to the reveal time, and the next `prepare` comes 5 s later, as today. The deadline cases are the deadline task, and a chat and a pick at `deadlineAt` with the clock set and the scheduler not advanced.
4. 선착순 choice: a correct pick at 10 s opens the window in the same way (`pickState`, `correct`, `reveal`, `scores`).
5. 순위전 (chat and choice): a correct answer by one of two players reveals nothing; when the second has answered or picked, the reveal has `playUntil == deadlineAt`. The ranking in the reveal is as today. The same when the last unanswered player disconnects at 10 s. A resumed 순위전 song whose connected players have all answered reveals as it starts with `playUntil == deadlineAt`, and the next `prepare` comes at `deadlineAt`.
6. In the window: a chat line with the song's exact title is broadcast as chat and changes no score; a pick is ignored; a skip vote is ignored; `phase()` is `REVEAL`; advancing past the original `deadlineAt` produces exactly one move to the next song and no second `reveal`.
7. Prefetch on: after a decision at 10 s nothing is sent until `deadlineAt - 5 s`; then the next `prepare` has `holdMs` 5000; the next song can start no earlier than `deadlineAt`. `currentSong(playerId)` just before that moment is not the next song's (the phase is `REVEAL`). With a deadline or skip reveal the next `prepare` follows `scores` at once, as today. Also with prefetch on:
   - a `ready` for the next index before the delayed `prepare` is ignored; one after it starts nothing until the hold is over;
   - 순위전: the delayed `prepare` has no `correct` key; choice: `currentSong(id).picked` is null after it;
   - the spoiler guard: in the window before the delayed `prepare`, a line with the next song's title is broadcast; after it, the same line goes to its sender only;
   - a player who joins in the window is promoted by the delayed `prepare` and receives it with `holdMs` 5000.
8. The last song: the window ends with `gameOver`, in both modes, and no `prepare`.
9. Everyone disconnects in the window and one player returns, in both modes: the room moves on at once, as `REVEAL` does today; the `prepare` after the resume has no `holdMs`; on the last song the resume gives `gameOver`.
10. Switch off (the existing constructors): `reveal` has no `playUntil` key and the next `prepare` comes 5 s after any reveal. Every existing test passes unchanged.
11. `GameProperties` binds `songrush.game.play-to-limit`, default false; the env name works.
12. Over a real socket with the property on, in a data directory of 30 s songs written by the test itself: a song decided early gives `reveal` with `playUntil` at least 15 s after the reveal's arrival, and neither `prepare` nor `gameOver` arrives within 8 s of it. (The shared `WsFixture` songs are 3 s long, where the window is 5 s with the switch on or off.)
13. `./gradlew test` passes from `backend/`.

## Test list
- New `backend/src/test/java/com/songrush/game/RoomPlayToLimitTest.java`: criteria 1 to 10 at Room level, with `RecordingOutbox`, `ManualScheduler` and `MutableClock`, in chat and choice rooms, both game modes, with and without prefetch.
- `backend/src/test/java/com/songrush/ws/GamePropertiesBindingTest.java`: criterion 11 (added tests; the existing ones stay).
- New `backend/src/test/java/com/songrush/ws/PlayToLimitSocketTest.java`: criterion 12, with its own data directory as `FilterSocketTest` and `TierSocketTest` have, and a correct answer over the socket as in `SoloSocketTest`.
- No existing test changes.

## File ownership
- Test writer: the three test files.
- Implementer: `backend/src/main/java/com/songrush/GameProperties.java`, `game/Room.java`, `game/Lobby.java`, `ws/GameSocketHandler.java`, and whatever passes the property between them.

## Out of scope
- Skip in the window (B-035), the snapshot in the window (B-036), every client change (F-035 to F-037), turning the switch on (B-037), `CLAUDE.md`.

## Spec review
One round. No Blocker. One High, taken: the socket criterion could not pass with the shared fixture, whose songs are 3 s long, so the window is 5 s either way; the socket test now writes 30 s songs and uses bounds that fail with the switch off.

Also taken: criteria for the two decided-by-answers paths that had none (a disconnect, and a resumed song decided as it starts), for the deadline reached by a chat or a pick, and for the delayed prefetch `prepare` (no stale `correct` rows or pick, the spoiler guard, a late joiner, an early `ready`); the pause criterion names both modes.

## Result
- Tests first: commit `799204b`. `RoomPlayToLimitTest` (new, 95 cases), five tests added to `GamePropertiesBindingTest`, `PlayToLimitSocketTest` (new, 2 tests). Red, run by the verifier: the test sources did not compile, with 9 errors, all the missing 10-argument `Room` constructor or the missing `playToLimit()` accessor, in those three files; `compileJava` still passed.
- Implementation: commit `4f943a9`, first attempt. `GameProperties`, `Room`, `Lobby`, `GameSocketHandler` (+84, -23). `endSong` takes a `decided` flag from its nine call sites; the early-prepare block moved unchanged into `prepareEarly()`, which runs inline when the reveal lasts 5 s and through the one pending task otherwise.
- Green, run by the verifier with a forced rerun: 1047 tests, 0 failures, 0 errors, 1 skipped (`FeedbackStoreTest`, not from this cycle). `git diff 799204b HEAD -- backend/src/test` is empty. Criteria 1 to 13 confirmed. With the switch off the verifier found no changed behaviour.
- Review of the diff: no Blocker, no High. One Medium: this cycle's tests pin the exact frames at a decision and "a skip vote in the window is ignored", and B-035 changes both (a zero `skipState` after `scores`, and skip in the window). B-035's test step revises `decide()` and the two skip tests; that is written on B-035. Two comment nits went to Follow-ups.
- The switch is off by default, so nothing changes for players yet.
- GitHub CI on PR #52 passed; merged to `dev` as `cb035f5`.
