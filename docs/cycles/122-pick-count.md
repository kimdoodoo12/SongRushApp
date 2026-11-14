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
2. **A Room player's connection changes during PLAYING** (`setConnected`, not paused, the value really changed): `pickState` with the new numbers, before `endIfAllAnswered()`. Not sent when `total` is 0.
3. **A resume re-prepares a choice song** (`resume()`): `pickState` right after that `prepare`, because picks are kept across a pause.

Never sent: in a chat game; for a refused pick (not a choice game, paused, not PLAYING, a watcher or disconnected sender, another index, a position out of range, at or after the deadline, `too-early`, `already-picked`); for a watcher's connection change.

Snapshot: the `song` of a `snapshot` in a choice game gains `pickState: {picked, total}`. The keys `picked` (the own pick position) and `total` (the song count) already exist in that map with other meanings, so the counts are nested. The receiving player is counted as connected when it is a Room player, because the snapshot is built just before the Lobby marks a reconnecting player connected (`Lobby.java:311` vs `:315`). A watcher's snapshot counts Room players only.

## Acceptance criteria
1. Three players, first-come choice, one wrong pick: `outbox.since(mark)` is exactly `pickState{index, picked: 1, total: 3}` (all), `pick-result` (sender), `wrong{index, name}` (all), in that order. The key sets are exactly `type, index, picked, total` and `type, index, name`.
2. A correct pick in first-come: exactly `pickState`, `correct`, `reveal`, `scores`.
3. Ranked choice: a correct pick gives exactly `pickState`, `correct` (no `wrong`); a wrong pick gives `pickState`, `pick-result`, `wrong`; when the last player picks wrong the frames are `pickState`, `pick-result`, `wrong`, `reveal`, `scores`.
4. A refused pick sends neither message: `already-picked`, `too-early`, a watcher, a disconnected player, at the deadline.
5. A chat game never sends `pickState` or `wrong`, also on a disconnect and reconnect during PLAYING.
6. During a choice song, a Room player's disconnect sends `pickState` with `total` one lower, and `picked` one lower when that player had picked; the reconnect sends it with the earlier numbers. A watcher's disconnect sends nothing. In ranked, when the last unpicked player disconnects the frames are `pickState`, `reveal`, `scores`.
7. `currentSong(playerId)` of a choice song carries `pickState` with the current numbers; for a disconnected Room player it counts that player as connected. A chat song has no `pickState` key.
8. A resume of a paused choice song sends `prepare` and then `pickState` with the kept picks.
9. No frame before the reveal other than the sender's `pick-result` contains the picked position: in a full first-come and a full ranked choice song, every `pickState` and `wrong` frame has exactly the key sets of criterion 1.
10. Through the Lobby with real members: a wrong pick reaches every connected member as `pickState` and `wrong`, and `pick-result` reaches the sender only. Over a real socket (`ChoiceSocketTest`): the host receives `pickState` and `wrong` with the guest's name after the guest's wrong pick, and no host frame contains `pick-result`.
11. `./gradlew test` passes from `backend/`.

## Test list
New:
- `backend/src/test/java/com/songrush/game/RoomPickStateTest.java`: criteria 1 to 9 at Room level, with `RecordingOutbox`, `ManualScheduler`, `MutableClock`, built like `RoomChoiceTest` and `RoomRankedChoiceTest`.

Updated (existing exact-frame assertions that the new frames change; keep every other assertion as it is):
- `RoomChoiceTest`: `:314`, `:333-335`, `:507`, the snapshot key sets at `:286` and `:294-295`, and the frame scan at `:687-742` gains the two types.
- `RoomRankedChoiceTest`: `:166`, `:174`, `:181`, `:187-188`, `:210`, `:239`, `:251`, `:316`, `:384`, `:470`, `:485`, and the scan at `:495-531`; `:404-421` gains the resume `pickState`.
- `RoomPrefetchOnRevealTest`: `:650`.
- `LobbyAnswerModeTest`: `:273-275`, `:333`, and the snapshot key checks at `:324`, `:337`, `:347` (criterion 10, Lobby part).
- `ChoiceSocketTest`: one added check in `aChoiceGameFromSettingsToTheReveal` (criterion 10, socket part).

Any other test that fails only because of the new frames is updated the same way and reported.

## File ownership
- Test writer: the test files above.
- Implementer: `backend/src/main/java/com/songrush/game/Room.java`; `Lobby.java` only if the snapshot needs it.

## Out of scope
- The screen (F-033) and its texts.
- Playing songs to the time limit (D-129, S-008).
- Any change to scoring, to `pick-result`, or to chat rooms.
- `pickState` at the start of every song: the client can show `0 / n` from the roster until the first frame arrives.

## Commands (this cycle ran on a Linux cloud container)
- `./gradlew test` from `backend/`. JDK 17.
