# 127 skip-in-window (B-035, from S-008 design 125, D-129, issue #44 request 1)

## Goal
With the play-to-limit switch on, players can vote to skip while the music plays on after the reveal, and a passed vote moves the room on at once.

## Background
- Cycle 126 stretched the reveal of a decided song to the end of its music (`playUntil`), behind `songrush.game.play-to-limit`. In that window a skip vote is ignored, so the only way on is to wait.
- D-129: "a passed skip vote is the way to move on early". Design 125, server point 4.
- The switch is still off by default. This is step 2 of 7.

## Design
All of this is with the switch on. With it off nothing changes.

**When a skip is possible after the reveal.** The room is in `Phase.REVEAL`, not paused, and `now < playUntil`. That is exactly: the song was decided by answers and its music is still playing, and (in prefetch mode) the next `prepare` has not gone out, because that step moves the phase to `PREPARE`. A deadline or skip reveal has `playUntil == now`, so it is never skippable. `Room` stores `playUntil` at the reveal for this test; `deadlineAt` must not be used in its place, because after a skip reveal `deadlineAt` is still in the future. `skip`, the connection-change branch and `clearSkipVote` all use this one condition.

**At the reveal.** Votes are cleared, as today. When a skip is possible at that moment, the server sends `skipState{index, votes: 0, needed}` to everyone right after `scores`, so clients reset their own vote and show the count. `needed` is the majority of connected Room players, as in `PLAYING`. No `skipState` is added to a reveal that is not skippable.

**A vote** (`skip(playerId, index)`), when a skip is possible: the same rules as in `PLAYING`. The sender must be a connected Room player and `index` the current song; it costs a chat token and can be refused with `rate-limited`; it toggles; everyone gets `skipState`. When the votes reach the majority the room moves on at once: it does what the end of the window does (`afterReveal`: the next `prepare` without `holdMs`, or `gameOver` after the last song), in the same turn, with no second `reveal` or `scores`. (`sendSkipStateAndPass` ends the song today; in `REVEAL` it must move on instead.)

**Not possible:** paused, another index, a watcher or a disconnected player, at or after `playUntil`, after the delayed prefetch `prepare`, or with the switch off. Such a vote is ignored and spends no chat token, as a vote in `REVEAL` is today: the refusal sits before the token is taken.

**Connection changes while a skip is possible.** As in `PLAYING`, in a separate branch of `setConnected` (so a disconnect that itself decides a 순위전 song is not evaluated twice, and with no `pickState` and no `endIfAllAnswered`): a disconnect removes that player's vote and, if there were votes before it, sends `skipState` with the new numbers and can pass the vote. A return re-sends the count when other players have votes. `clearSkipVote` (a replaced socket) removes a standing vote and sends the lower count; it never moves the room on. When a skip is not possible, none of these sends anything.

## Acceptance criteria
Switch on unless said otherwise; a song of at least 90 s decided at 10 s; three connected players unless said otherwise.
1. The frames at the decision are those of cycle 126 followed by `skipState{index, votes: 0, needed: 2}`. A deadline reveal, a skip reveal, and (prefetch on) a song decided in its last 5 s add no `skipState`.
2. A vote cast before the decision does not count after it: with one earlier vote, the `skipState` at the reveal has `votes: 0`, and one new vote gives `votes: 1`.
3. Two of three vote in the window: after the second vote the frames are exactly `skipState{votes: 2, needed: 2}` and the next `prepare` (no `holdMs`), with no `reveal` and no `scores`; the room is in `PREPARE` for the next song. Up to and past the original `deadlineAt` there is exactly one `prepare` of the next song, one `reveal` of this one and no `gameOver`, and the next song runs normally. The same with prefetch on (the vote passing before the delayed `prepare`): no held `prepare` appears at `deadlineAt - 5 s`.
4. On the last song a passed vote gives `skipState` and `gameOver`.
5. A vote toggles: the same player voting twice gives `votes: 1` then `votes: 0`.
6. Ignored without a frame and without spending a chat token: a vote at `playUntil` or later (no prefetch, the window still open for its 5 s minimum), a vote after the delayed prefetch `prepare` (for the old index), a watcher's vote, a disconnected player's vote, another index, a paused room, and any vote in `REVEAL` with the switch off. Also, with no prefetch and three players: after a skip passed at 10 s in `PLAYING`, the third player's vote for that index during the 5 s reveal sends nothing and the next `prepare` comes exactly 5 s after the reveal; the same for a vote 1 s after a deadline reveal. "Without spending a token" is shown at the same clock instant as the ignored vote, by a sender whose bucket was full (not the player who just spent one on the winning chat): five chat lines go through and a sixth is `rate-limited`. For the paused case the burst is shown right after the resume, with the clock not advanced.
7. A vote that the flood limit refuses gets `error{code: rate-limited, for: skipVote}` as in `PLAYING`.
8. Disconnect: three players, one has voted; a non-voter disconnects: `skipState{votes: 1, needed: 2}`; then the other non-voter disconnects: the vote passes (`needed: 1`) and the room moves on. With no votes, a disconnect sends no `skipState`.
9. Returns and replaced sockets, three players: the sole voter disconnects: `skipState{votes: 0, needed: 2}`; its return: no frame. A return while another player has a vote: `skipState{votes: 1, needed: 2}`. `clearSkipVote` for a voter: `skipState{votes: 0}` and the phase is still `REVEAL`. `clearSkipVote` at or after `playUntil`, or for a player with no vote: no frame.
10. 순위전 (chat and choice) and 선착순 choice windows accept skips the same way.
11. Switch off: every existing test passes; no `skipState` follows a reveal.
12. `./gradlew test` passes from `backend/`.

## Test list
- New `backend/src/test/java/com/songrush/game/RoomWindowSkipTest.java`: criteria 1 to 11 at Room level.
- `backend/src/test/java/com/songrush/game/RoomPlayToLimitTest.java` (cycle 126, unlocked): its tests pinned things this cycle changes on purpose, and only these change:
  - `decide()` and its frame lists: `skipState` is appended exactly when the switch is on, `now < deadlineAt`, and not (prefetch on and `deadlineAt - now <= 5000`). The switch-off and near-deadline callers of `decide()` keep their lists.
  - `theDecisionScoresAsToday`: it reads the last frame as `scores`; it reads the `scores` frame by type instead.
  - `rankedTheLastUnansweredPlayerDisconnectingAtTenSecondsOpensTheWindow` and `rankedAResumedSongWhoseConnectedPlayersAllAnsweredRevealsAsItStartsUntilItsDeadline`: their exact frame lists gain the `skipState` that follows `scores`.
  - `inTheWindowChatIsOnlyChatAndPicksSkipsAndConnectionChangesDoNothing` and `skipVotesIgnoredInTheWindowDoNotCountForTheNextSong`: the parts that say a skip vote in the window is ignored are replaced by what this spec says (the chat, pick and `pickState` parts stay).
  - Any other test of that class that this cycle breaks is reported, not changed silently.

## File ownership
- Test writer: the two test files.
- Implementer: `backend/src/main/java/com/songrush/game/Room.java` (and `Lobby.java` only if a call needs it).

## Out of scope
- The snapshot in the window (B-036), every client change, turning the switch on (B-037).
- A minimum reveal time before a skip can pass: design 125 chose none.

## Spec review
One round. One Blocker and one High, both taken.
- Blocker: the list of cycle 126 tests that this cycle changes missed three (one reads the last frame as `scores`, two pin exact frame lists after a decision by disconnect and by a resume). They are now declared, and the rule for `decide()` is stated exactly.
- High: the room did not store `playUntil`, and no criterion caught an implementation that tested `deadlineAt` instead, which would accept late votes during the 5 s reveal of a skipped song and cut it short. The design now says the room stores `playUntil`, and criterion 6 has that case.

Also taken: the connection-change section names what changes in code and criterion 9 pins the frames; criterion 3 no longer claims that nothing happens up to the old deadline; the token check states its conditions. The added socket test was dropped: the Lobby and the handler forward a skip with no phase logic, so Room-level tests cover it.
