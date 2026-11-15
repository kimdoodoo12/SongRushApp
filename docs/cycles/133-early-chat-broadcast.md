# 133 early-chat-broadcast (B-038, from D-132)

## Goal
A chat line a player sends before the song starts is seen by everyone, except a line that matches the song's answer, which only its sender sees.

## Background
- The owner noticed that chat sent before a song starts sometimes shows only on the sender's screen. The lead reproduced it with two players on a local server with the production settings: a line sent right after the game starts, and one sent after a song's music ended and before the next song started, reached only the sender; lines at every other point reached both.
- Cause: `Room.chat` (backend/src/main/java/com/songrush/game/Room.java:772-775) sends every line from a Room player in `PREPARE` or before `startAt` to its sender only, match or miss (cycle 045, from design 034 M5: a line that matches the answer is never broadcast before the reveal; misses were hidden too so the hiding itself told nothing).
- D-132 (owner, 2026-11-15): only the line that matches the answer is hidden; the rest is ordinary chat. The owner: "정답 유출을 막는것은 정답인 채팅만 안보이게 하는거지." The same rule already applies to the next song's titles during a reveal (D-120).
- An experiment by the lead (the branch changed in the working tree, `gradlew test`, reverted): 4 cases in 3 classes pin the old rule; nothing else fails.

## Design
In `Room.chat`, for a Room player (not a watcher) while the song is not playing yet (`phase == PREPARE`, or `PLAYING` with `now < startAt`):
- A line whose normalized text is one of the song's accepted titles goes to its sender only. It never scores and sends no `correct`.
- Any other line goes to everyone, as during the song.
- The early check is its own: the normalized text against the song's accepted set only, ignoring `answerMode` and `rankedIds`. It must not reuse `match` (Room.java:769-771), which is false for a ranked player who already answered: a resume from `PLAYING` re-prepares the same song and keeps `rankedIds` (Room.java:565; `RoomRankedTest` R2), so in that `PREPARE` an answered player's title would reach everyone after others heard part of the song. In a choice room chat never scores, but a matching line is still hidden: it would name the answer.
- A line that normalizes to empty (punctuation only) never matches, so it goes to everyone.
Watchers, the reveal and the window, the D-120 guard, and chat from `startAt` on are unchanged. During a hold (the last 5 s of a window with prefetch) the phase is already `PREPARE`, but the `revealing` branch runs first (Room.java:745), so D-120 applies there, as today. After the hold the room stays in `PREPARE` with the next song's audio reachable until its start; the new rule hides a match there.

What is given up: with misses broadcast and matches hidden, someone with two accounts can tell which early guess is the title (the one the other account never sees). D-132 accepts this, as D-120 does during a reveal; nobody scores before `startAt`.

## Acceptance criteria (JUnit)
1. Chat room, 선착순, two players, in `PREPARE`: a miss reaches both players; a line matching the song's title reaches only its sender, and no `correct` is sent, no score changes.
2. The same in `PLAYING` before `startAt` (after `start`, before the start time).
3. From `startAt` on, behaviour is as today (a miss to both; a match scores and ends the song in 선착순).
4. Choice room: before the start, a miss reaches both; a matching line reaches only its sender.
5. 순위전 chat room: before the start, a miss reaches both; a matching line reaches only its sender and does not rank the sender.
6. A punctuation-only line before the start reaches both.
7. A watcher's chat before the start reaches only the watcher (unchanged).
7a. 순위전 chat room: a player answers correctly, the room is paused and resumed (a re-prepare of the same song); in that `PREPARE` the same player's title reaches only its sender, and a miss reaches both.
7b. Prefetch on, a song decided early: after the hold ends and before the next song's `start`, a line matching the next song's title reaches only its sender, and a miss reaches both.
8. The four cases that pinned the old rule are revised to the new rule, and nothing else in them changes: `RoomPunctChatTest.punctuationOnlyChatsBeforeStartAtReachOnlyTheSender` and `punctuationOnlyChatsInPrepareReachOnlyTheSender` (now: to both), `RoomRoundsTest.beforeStartAtAMatchAndAMissReachOnlyTheSenderAndFromStartAtAMissReachesEveryone` and `RoomTest.chatBeforeStartAtReachesOnlyTheSenderAsPlainChat` (now: the miss to both, the match to its sender only). Their names change to say what they now pin.
9. `./gradlew test` passes from `backend/`.
10. Browser check (lead), local server with production settings, two players: a line typed right after the game starts and one typed between songs both show on the other screen.

## Test list
- New `backend/src/test/java/com/songrush/game/RoomEarlyChatTest.java`: criteria 1 to 7b.
- `RoomPunctChatTest.java`, `RoomRoundsTest.java`, `RoomTest.java`: only the four cases of criterion 8, and the header comments that describe the old rule (`RoomPunctChatTest.java:19`, `RoomRoundsTest.java:24`).

## File ownership
- Test writer: the four test files above.
- Implementer: `backend/src/main/java/com/songrush/game/Room.java` (the early branch of `chat` and its comment). Also `docs/design/068-stomp.md:17` (the sentence on the sender-only early echo) is updated to the new rule; design 034 M5 and CLAUDE.md already agree with it.

## Out of scope
- Watchers' chat before the reveal, the reveal and window rules, any client change (the client shows whatever chat frames arrive).

## Spec review
One round. No Blocker; one High, one Medium and two Nits, all taken.
- High: the draft said a ranked player who already answered cannot exist before the start. A resume from `PLAYING` re-prepares the same song and keeps `rankedIds`, so reusing `match` would broadcast that player's title. The early check is now its own (accepted set only), with criterion 7a.
- Medium: the design now states what showing misses gives up (a two-account check of early guesses), accepted by D-132 as D-120 accepts it.
- Nits: the hold case is stated (the `revealing` branch runs first) and the time after the hold gets criterion 7b; design 068 and two test headers that describe the old rule are in the file lists.
- Checked by the critic: choice rooms hide a match regardless of `answerMode`; the four declared cases exist; the other early-chat tests send titles, which stay hidden.

## Result
- Tests first: commit `48b54a8`. `RoomEarlyChatTest` (new, 10 cases: criteria 1 to 7b, with 7b run once with prefetch and once with prefetch and play-to-limit, the latter also pinning D-120 during the hold) and the four declared cases in `RoomPunctChatTest`, `RoomRoundsTest`, `RoomTest` (renamed, misses now to everyone, matches still to the sender). Red, run by the lead: every failure an assertion that a miss before the start reached only its sender.
- Implementation: commit `02221ca`, first attempt (+12, -3): the early branch of `Room.chat` checks the accepted titles on its own (not `match`) and broadcasts anything else; `docs/design/068-stomp.md` states the new rule.
- Green, run by the verifier with `--rerun-tasks`: 1259 tests, 0 failures, 0 errors, 1 skipped; the four locked classes all pass. The locked tests are unchanged since `48b54a8`. Criteria 1 to 9 confirmed.
- Review of the diff: no finding. Checked: the early check uses the same normalization and accepted set as the answer check, so no matching line is broadcast before `startAt` (a ranked player after a resume, a choice room); watchers, pauses and the reveal path are unchanged. Raised for the owner, not changed: from `startAt` on, a ranked player who already answered can type the title and it goes to everyone (cycle 101 item 3).
- Criterion 10, by the lead in Chromium against the local server with the production settings, two players, the same check that reproduced the report: a line sent right after the game started and one sent between songs now show on the other screen; all six points of a song (before the start, playing, the window, its last 5 s, between songs, the next song) reach both players.
