# 122 pick-count (B-033, from D-130, issue #44 request 2)

## Goal
In a choice room, everyone learns how many players have picked the current song and who picked wrong, without learning anything about the answer.

## Background
- A user asked for a pick counter in choice rooms (issue #44). The owner decided D-130: show `picked / connected players`, and announce a wrong pick by name in chat. Choice rooms only.
- Today a wrong pick sends `pick-result` to its sender only (`Room.java:636-644`), so nobody else can tell that anyone picked.
- This cycle is the server half. The screen is F-033.

## Protocol
Two new server-to-client messages. Both go to everyone (`outbox.toAll`), and only in a choice game (`AnswerMode.CHOICE`), first-come and ranked alike.

- `pickState{index, picked, total}`
  - `index`: the song index.
  - `total`: connected Room players (watchers are never counted).
  - `picked`: connected Room players who have an accepted pick for this song. A disconnected player's pick is kept, but not counted until that player reconnects.
- `wrong{index, name}`
  - `name`: the player's name. Nothing else: no choice position, no cover id.

When they are sent:
1. **An accepted pick** (a pick stored in `picks`): `pickState` first. Then, as today, `correct` for a correct pick, or `pick-result` to the sender for a wrong one; a wrong pick is followed by `wrong`. Then whatever ends the song (`reveal`, `scores`) as today.
   - Wrong pick, in order: `pickState` (all), `pick-result` (sender), `wrong` (all).
   - Correct pick, in order: `pickState` (all), `correct` (all), and in first-come `reveal`, `scores`.
2. **A Room player's connection changes during PLAYING** (`setConnected`, not paused, the value really changed): `pickState` with the new numbers is the first frame of that change, before the `skipState` of `sendSkipStateAndPass()` and before `endIfAllAnswered()`, either of which can end the song. Nothing is sent after the song has ended, and nothing when `total` is 0.
3. **Every `start` of a choice song** (`startSong`): `pickState` right after `start`, before the `endIfAllAnswered()` there. This gives each song its first count and covers everything that changes the numbers outside PLAYING: a resume (picks are kept across a pause), a disconnect or reconnect during PREPARE, and a late joiner promoted at the prepare.

Never sent: in a chat game; for a refused pick (not a choice game, paused, not PLAYING, a watcher or disconnected sender, another index, a position out of range, at or after the deadline, `too-early`, `already-picked`); for a watcher's connection change.

Snapshot: the `song` of a `snapshot` in a choice game gains `pickState: {picked, total}` with the numbers as they are when the snapshot is built. The keys `picked` (the own pick position) and `total` (the song count) already exist in that map with other meanings, so the counts are nested. A player who reconnects during PLAYING gets the snapshot first and then the rule 2 broadcast with the numbers that include them; during PREPARE the next `start` brings them (rule 3).

## Acceptance criteria
1. Three players, first-come choice, one wrong pick: `outbox.since(mark)` is exactly `pickState{index, picked: 1, total: 3}` (all), `pick-result` (sender), `wrong{index, name}` (all), in that order. The key sets are exactly `type, index, picked, total` and `type, index, name`.
2. A correct pick in first-come: exactly `pickState`, `correct`, `reveal`, `scores`.
3. Ranked choice: a correct pick while another connected player has not picked gives exactly `pickState`, `correct` (no `wrong`); a wrong pick gives `pickState`, `pick-result`, `wrong`; when the last player picks wrong the frames are `pickState`, `pick-result`, `wrong`, `reveal`, `scores`.
4. A refused pick sends neither message: `already-picked`, `too-early`, a watcher, a disconnected player, at the deadline.
5. A chat game never sends `pickState` or `wrong`, also on a disconnect and reconnect during PLAYING.
6. During a choice song, a Room player's disconnect sends `pickState` with `total` one lower, and `picked` one lower when that player had picked; the reconnect sends it with the earlier numbers. A watcher's disconnect sends nothing. In ranked, when the last unpicked player disconnects the frames are `pickState`, `reveal`, `scores`. A disconnect that passes the skip vote (4 players, 2 votes, a non-voter drops) gives exactly `pickState`, `skipState`, `reveal`, `scores`, and no `pickState` after the `reveal`.
7. `currentSong(playerId)` of a choice song carries `pickState` with the current numbers. A chat song has no `pickState` key.
8. Every `start` of a choice song is followed at once by `pickState` (`picked: 0` on a fresh song). After a pause with one kept pick, a resume where a second player reconnects during the resumed PREPARE gives `start`, then `pickState{picked: 1, total: 2}`. A chat song's `start` is followed by no `pickState`.
9. No frame before the reveal other than the sender's `pick-result` contains the picked position: in a full first-come and a full ranked choice song, every `pickState` and `wrong` frame has exactly the key sets of criterion 1.
10. Through the Lobby with real members: a wrong pick reaches every connected member as `pickState` and `wrong`, and `pick-result` reaches the sender only. Over a real socket (`ChoiceSocketTest`): the host receives `pickState` and `wrong` with the guest's name after the guest's wrong pick, and no host frame contains `pick-result`.
11. `./gradlew test` passes from `backend/`.

## Test list
New:
- `backend/src/test/java/com/songrush/game/RoomPickStateTest.java`: criteria 1 to 9 at Room level, with `RecordingOutbox`, `ManualScheduler`, `MutableClock`, built like `RoomChoiceTest` and `RoomRankedChoiceTest`.

Updated (existing exact-frame assertions that the new frames change; keep every other assertion as it is):
- `RoomChoiceTest`: `:314`, `:333-335`, `:507-508`, the snapshot key sets at `:286` and `:294-295`, and the frame scan at `:687-742` gains the two types.
- `RoomRankedChoiceTest`: `:166`, `:174`, `:181`, `:187-188`, `:210-212`, `:238`, `:251`, `:316-318`, `:384`, `:470`, `:485`, and the scan at `:495-531`; `:404-421` gains the `pickState` after the resumed `start`.
- `RoomPrefetchOnRevealTest`: `:650-652`.
- `LobbyAnswerModeTest`: `:273-275`, `:333` (becomes `joined, snapshot, settings, roster, pickState`), and the snapshot key checks at `:324`, `:337`, `:347` (criterion 10, Lobby part).
- Every exact-frame assertion around the `start` of a choice song in these classes and in the other `Room*` and `Lobby*` test classes: the test writer finds them by reading and lists each one in the report.
- `ChoiceSocketTest`: one added check in `aChoiceGameFromSettingsToTheReveal` (criterion 10, socket part).

Any other test that fails only because of the new frames is updated the same way and reported.

## File ownership
- Test writer: the test files above.
- Implementer: `backend/src/main/java/com/songrush/game/Room.java`; `Lobby.java` only if the snapshot needs it.

## Out of scope
- The screen (F-033) and its texts.
- Playing songs to the time limit (D-129, S-008).
- Any change to scoring, to `pick-result`, or to chat rooms.

## Spec review
One round. No Blocker. Two High findings, both taken:
- The count went stale after a pause and resume, and after any connection change during PREPARE. Rule 3 was "after a resume's prepare"; it is now "after every `start`".
- Rule 2 did not say where `pickState` goes relative to the skip vote, which can end the song inside the same connection change. It is now the first frame.

Also taken: the snapshot carries the plain numbers (no special case for the receiver), and the line references of the tests to update were corrected.

Not taken: leaving `pickState` out of a first-come correct pick to save test changes. One rule for every accepted pick is simpler for the client, and it is what B-033 says.

## Commands (this cycle ran on a Linux cloud container)
- `./gradlew test` from `backend/`. JDK 17.
- In this container Maven Central refused many requests (HTTP 429, and 404 for files that exist), so Gradle ran against a local read-through mirror kept outside the repo. Nothing in the repo changed for it.

## Result
- Tests first: commit `06dfb97`. One new class (`RoomPickStateTest`, 19 tests) and updated exact-frame assertions in `RoomChoiceTest`, `RoomRankedChoiceTest`, `RoomPrefetchOnRevealTest`, `LobbyAnswerModeTest` and `ChoiceSocketTest`.
- Red, run by the verifier: `945 tests completed, 37 failed, 1 skipped`, every failure in those six classes and every one an assertion about the missing frames or the missing snapshot key.
- Implementation: commit `a0bd46a`, first attempt. `Room.java` only (+48, -5): `pickState` after an accepted pick, as the first frame of a connection change during a song, and after every `start`; `wrong` after the sender's `pick-result`; `pickState` nested in the snapshot's song.
- Green, run by the verifier with a forced rerun: 945 tests, 0 failures, 0 errors, 1 skipped. `git diff 06dfb97 HEAD -- backend/src/test` is empty. Criteria 1 to 11 confirmed.
- Review of the diff: no Blocker, no High. One Medium (the snapshot count leaves out the rejoining player) and three smaller notes went to Follow-ups; the Medium is also written on F-033.
- GitHub CI on PR #46 passed on `a0bd46a`; merged to `dev` as `7b96aa9`.
- Not checked in a browser: there is no screen for it yet (F-033).
