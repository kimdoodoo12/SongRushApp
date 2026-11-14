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
  - the song was **decided by answers** (선착순: the first correct chat or pick; 순위전: `endIfAllAnswered`, from an answer, a pick, a disconnect or a start): `playUntil = deadlineAt`;
  - a **passed skip vote**, or the **deadline** (the deadline task, or a chat or pick at or after `deadlineAt`): `playUntil` = the time of the reveal.
- `windowEnd = max(playUntil, revealTime + REVEAL_PAUSE)`.
- The frames at the reveal and their order are unchanged (`reveal`, `scores`, after whatever `correct` and chat frames precede them today). The phase is `REVEAL`.
- **Without prefetch:** at `windowEnd` the room does what `afterReveal` does today (the next `prepare`, or `gameOver` after the last song). Nothing is sent between `scores` and then.
- **With prefetch** and a next song: at `windowEnd - REVEAL_PAUSE` the room does what it does today right after `scores`: it clears `picks` and the ranked list, enters `PREPARE` for the next song, sends that `prepare` with `holdMs` = 5000, and is `revealing` for 5 s. Until then the phase stays `REVEAL` and `picks` and the ranked list are kept. For a deadline or skip reveal that moment is the reveal itself, so those behave exactly as today. The last song has no next `prepare` and ends with `gameOver` at `windowEnd`.
- In the window, as in any `REVEAL` today: chat is broadcast and never answer-checked, a pick is ignored, a skip vote is ignored (until B-035), a connection change sends no `pickState`.
- The earlier deadline task must not fire a second `reveal`.
- A pause in the window and the resume are as today (the resume moves on at once).

## Acceptance criteria
All with the switch on unless said otherwise. "Decided at 10 s" means 10 s after `startAt`, on a song of at least 90 s.
1. 선착순 chat, no prefetch: a correct chat at 10 s gives the frames of today (`correct`, `chat`, `reveal`, `scores`) and `reveal.playUntil == deadlineAt`. No frame follows until `deadlineAt`; then the next `prepare` comes.
2. The same with a song decided 2 s before `deadlineAt`: the next `prepare` comes 5 s after the reveal, not at `deadlineAt`.
3. A reveal at the deadline and a reveal by a passed skip have `playUntil` equal to the reveal time, and the next `prepare` comes 5 s later, as today.
4. 선착순 choice: a correct pick at 10 s opens the window in the same way (`pickState`, `correct`, `reveal`, `scores`).
5. 순위전 (chat and choice): a correct answer by one of two players reveals nothing; when the second has answered or picked, the reveal has `playUntil == deadlineAt`. The ranking in the reveal is as today.
6. In the window: a chat line with the song's exact title is broadcast as chat and changes no score; a pick is ignored; a skip vote is ignored; `phase()` is `REVEAL`; advancing past the original `deadlineAt` produces exactly one move to the next song and no second `reveal`.
7. Prefetch on: after a decision at 10 s nothing is sent until `deadlineAt - 5 s`; then the next `prepare` has `holdMs` 5000; the next song can start no earlier than `deadlineAt`. `currentSong(playerId)` just before that moment is not the next song's (the phase is `REVEAL`). With a deadline or skip reveal the next `prepare` follows `scores` at once, as today.
8. The last song: the window ends with `gameOver`, in both modes, and no `prepare`.
9. Everyone disconnects in the window and one player returns: the room moves on at once, as `REVEAL` does today.
10. Switch off (the existing constructors): `reveal` has no `playUntil` key and the next `prepare` comes 5 s after any reveal. Every existing test passes unchanged.
11. `GameProperties` binds `songrush.game.play-to-limit`, default false; the env name works.
12. Over a real socket with the property on: a song decided early gives `reveal` with `playUntil` later than the reveal's arrival, and no `prepare` within the next 6 s.
13. `./gradlew test` passes from `backend/`.

## Test list
- New `backend/src/test/java/com/songrush/game/RoomPlayToLimitTest.java`: criteria 1 to 10 at Room level, with `RecordingOutbox`, `ManualScheduler` and `MutableClock`, in chat and choice rooms, both game modes, with and without prefetch.
- `backend/src/test/java/com/songrush/ws/GamePropertiesBindingTest.java`: criterion 11 (added tests; the existing ones stay).
- New `backend/src/test/java/com/songrush/ws/PlayToLimitSocketTest.java`: criterion 12, modelled on `PrefetchOnRevealSocketTest`.
- No existing test changes.

## File ownership
- Test writer: the three test files.
- Implementer: `backend/src/main/java/com/songrush/GameProperties.java`, `game/Room.java`, `game/Lobby.java`, `ws/GameSocketHandler.java`, and whatever passes the property between them.

## Out of scope
- Skip in the window (B-035), the snapshot in the window (B-036), every client change (F-035 to F-037), turning the switch on (B-037), `CLAUDE.md`.
