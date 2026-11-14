# 127 skip-in-window (B-035, from S-008 design 125, D-129, issue #44 request 1)

## Goal
With the play-to-limit switch on, players can vote to skip while the music plays on after the reveal, and a passed vote moves the room on at once.

## Background
- Cycle 126 stretched the reveal of a decided song to the end of its music (`playUntil`), behind `songrush.game.play-to-limit`. In that window a skip vote is ignored, so the only way on is to wait.
- D-129: "a passed skip vote is the way to move on early". Design 125, server point 4.
- The switch is still off by default. This is step 2 of 7.

## Design
All of this is with the switch on. With it off nothing changes.

**When a skip is possible after the reveal.** The room is in `Phase.REVEAL`, not paused, and `now < playUntil`. That is exactly: the song was decided by answers and its music is still playing, and (in prefetch mode) the next `prepare` has not gone out, because that step moves the phase to `PREPARE`. A deadline or skip reveal has `playUntil == now`, so it is never skippable.

**At the reveal.** Votes are cleared, as today. When a skip is possible at that moment, the server sends `skipState{index, votes: 0, needed}` to everyone right after `scores`, so clients reset their own vote and show the count. `needed` is the majority of connected Room players, as in `PLAYING`. No `skipState` is added to a reveal that is not skippable.

**A vote** (`skip(playerId, index)`), when a skip is possible: the same rules as in `PLAYING`. The sender must be a connected Room player and `index` the current song; it costs a chat token and can be refused with `rate-limited`; it toggles; everyone gets `skipState`. When the votes reach the majority the room moves on at once: it does what the end of the window does (`afterReveal`: the next `prepare` without `holdMs`, or `gameOver` after the last song), in the same turn, with no second `reveal` or `scores`.

**Not possible:** paused, another index, a watcher or a disconnected player, at or after `playUntil`, after the delayed prefetch `prepare`, or with the switch off. Such a vote is ignored and spends no chat token, as a vote in `REVEAL` is today.

**Connection changes while a skip is possible.** As in `PLAYING`: a disconnect removes that player's vote and, if there were votes, sends `skipState` with the new numbers and can pass the vote; a reconnect removes that player's vote and sends the lower count; it never moves the room on.

## Acceptance criteria
Switch on unless said otherwise; a song of at least 90 s decided at 10 s; three connected players unless said otherwise.
1. The frames at the decision are those of cycle 126 followed by `skipState{index, votes: 0, needed: 2}`. A deadline reveal, a skip reveal, and (prefetch on) a song decided in its last 5 s add no `skipState`.
2. A vote cast before the decision does not count after it: with one earlier vote, the `skipState` at the reveal has `votes: 0`, and one new vote gives `votes: 1`.
3. Two of three vote in the window: after the second vote the frames are exactly `skipState{votes: 2, needed: 2}` and the next `prepare` (no `holdMs`), with no `reveal` and no `scores`; the room is in `PREPARE` for the next song; nothing more happens at the original `deadlineAt`. The same with prefetch on (the vote passing before the delayed `prepare`).
4. On the last song a passed vote gives `skipState` and `gameOver`.
5. A vote toggles: the same player voting twice gives `votes: 1` then `votes: 0`.
6. Ignored without a frame and without spending a chat token: a vote at `playUntil` or later (no prefetch, the window still open for its 5 s minimum), a vote after the delayed prefetch `prepare` (for the old index), a watcher's vote, a disconnected player's vote, another index, a paused room, and any vote in `REVEAL` with the switch off. "Without spending a token" is shown by the sender still being able to send the full chat burst afterwards.
7. A vote that the flood limit refuses gets `error{code: rate-limited, for: skipVote}` as in `PLAYING`.
8. Disconnect: three players, one has voted; a non-voter disconnects: `skipState{votes: 1, needed: 2}`; then the other non-voter disconnects: the vote passes (`needed: 1`) and the room moves on. With no votes, a disconnect sends no `skipState`.
9. Reconnect: a voter who disconnected and returns in the window has no vote; `clearSkipVote` for a player with a vote sends the lower count and does not move the room on.
10. 순위전 (chat and choice) and 선착순 choice windows accept skips the same way.
11. Switch off: every existing test passes; no `skipState` follows a reveal.
12. `./gradlew test` passes from `backend/`.

## Test list
- New `backend/src/test/java/com/songrush/game/RoomWindowSkipTest.java`: criteria 1 to 11 at Room level.
- `backend/src/test/java/com/songrush/game/RoomPlayToLimitTest.java` (cycle 126, unlocked): its tests pinned two things this cycle changes on purpose, and only these change:
  - `decide()` and its frame lists: a decision that opens a skippable window is followed by `skipState`;
  - `inTheWindowChatIsOnlyChatAndPicksSkipsAndConnectionChangesDoNothing` and `skipVotesIgnoredInTheWindowDoNotCountForTheNextSong`: the parts that say a skip vote in the window is ignored are replaced by what this spec says (the chat, pick and `pickState` parts stay).
- `backend/src/test/java/com/songrush/ws/PlayToLimitSocketTest.java`: one added test, two clients passing a skip in the window over real sockets receive the next `prepare` well before `playUntil`.

## File ownership
- Test writer: the three test files.
- Implementer: `backend/src/main/java/com/songrush/game/Room.java` (and `Lobby.java` only if a call needs it).

## Out of scope
- The snapshot in the window (B-036), every client change, turning the switch on (B-037).
- A minimum reveal time before a skip can pass: design 125 chose none.
