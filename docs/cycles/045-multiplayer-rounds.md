# 045 multiplayer-rounds (B-006b)

## Goal
With several players in a room:
- the song starts once every **connected** player is ready;
- a skip passes on a majority of connected players;
- chat is rate-limited;
- before `startAt` no chat reveals whether a guess matched.

Decisions covered: D-012, D-017, D-024, D-061 (design 034 M1 and M5; cycle 036 review M1).

## Scope
- **Split.** Late joiners (D-059) move to a new item **B-006c**: a player joining a running game spectates and joins the `Room` at the next `prepare`. It needs players added to a running `Room`, which is its own cycle.
- **Snapshot.** The skip vote in the reconnect snapshot (cycle 042 review note) is dropped. A disconnect removes the player's vote, so a reconnecting player never has a vote to restore. The locked cycle 042 tests also pin the exact snapshot keys.

## Interface changes (package `com.songrush.game`)
- **`Room`** (the locked cycle 036, 042 and 044 tests must still pass, except the two assertions listed under Test changes)
  - `void setConnected(String playerId, boolean connected)` marks a Room player's connection. All players start connected.
    - Disconnecting removes that player from the ready set and removes their skip vote.
    - It then re-evaluates the ready rule and the skip rule.
    - Reconnecting just marks them connected.
  - **Ready.** In PREPARE the song starts when every connected Room player is ready. With no connected Room player nothing starts; the lobby pauses the room in that case (cycle 044).
  - `void skip(String playerId, int index)`
    - Ignored unless the phase is PLAYING, the index is the current one, the room is not paused, and the player is a connected Room player.
    - It toggles the player's vote and sends everyone `skipState{index, votes, needed}`, where `needed = floor(connected / 2) + 1`.
    - When `votes >= needed`, the song ends at once with no winner, as at the deadline. The `reveal` gets an extra key `skipped:true`; other reveals have `skipped:false`.
    - Votes reset on every new song.
    - A connection change during PLAYING re-sends `skipState` if votes were present, and passes the skip if the threshold is now met.
  - **Chat rate limit (D-024).**
    - Each Room player has a token bucket with capacity 5 that refills at 2 tokens per second, measured with the injected clock. It starts full.
    - A chat that finds no token is dropped unjudged, and only the sender gets `error{code:"rate-limited"}`. This includes a correct answer: it does not score and is not shown.
    - The check runs before every other chat rule except unknown or `null` input.
  - **Before `startAt`** (in PREPARE, or in PLAYING with `now < startAt`), every judged chat goes only to its sender, as `chat{name, text}`, whether it matched or not. It never scores. From `startAt` on, misses go to everyone, as before.
- **`Lobby`**
  - Calls `room.setConnected(id, false)` on `disconnect` and on a connected Room player's `leave`.
  - Calls `room.setConnected(id, true)` on a grace `reconnect` of a Room player. This happens before any `resume` (cycle 044).
  - Forwards `skip(playerId, index)` for connected members.
- **Socket** (`ws/GameSocketHandler`): `skip{index}` forwards to `lobby.skip`. A non-integer or missing `index` gives `bad-message`. With no room, the reply is `no-room`.

## Test changes to locked earlier tests
- `RoomTest.matchingChatBeforeStartAtReachesOnlyTheSenderAsPlainChat` (cycle 036): the two "miss before `startAt`" assertions now expect the miss to reach only its sender, not everyone. These are the `toAll` checks for "not the answer at all" in PREPARE and "still guessing" before `startAt`.
- The test writer may change only those assertions and the test name or description. The lead unlocks for this and records it.
- No other locked test may change. If another one breaks, the implementation is wrong.

## Acceptance criteria (JUnit 5, from `backend/`)
1. **`RoomRoundsTest`**, using the cycle 036 fakes unchanged.
   - **Ready.**
     - With 3 players, after one disconnects in PREPARE, the other two `ready` and the song starts without waiting for the timeout.
     - A disconnect that leaves every remaining connected player ready starts the song immediately.
     - A player who reconnects in PREPARE is counted again.
   - **Skip.**
     - With 3 connected players: one vote gives `skipState{votes:1, needed:2}`, and a second vote ends the song with `reveal.skipped:true` and `winner:null`.
     - Voting again toggles the vote off.
     - A skip in PREPARE, before `startAt` is fine (PLAYING only), with the wrong index, from an unknown or disconnected player, or while paused is ignored.
     - Votes reset on the next song.
     - With 4 players, 2 votes is not enough (`needed` is 3). After one non-voter disconnects, `needed` becomes 2 and the skip passes. After a voter disconnects, their vote is removed and the new `skipState` is sent.
   - **Rate limit.**
     - 5 chats in an instant all go through and the 6th gets `rate-limited` to the sender only.
     - 500 ms later one more goes through.
     - A correct answer sent without a token does not score, and the song keeps playing.
     - Each player has their own bucket.
   - **Before `startAt`.** With 2 players, a match and a miss in PREPARE and in PLAYING before `startAt` each reach only the sender. From `startAt`, a miss reaches everyone.
   - **No leaks.** `skipState` has exactly `type, index, votes, needed`. The `reveal` key set equals cycle 036's plus `skipped`.
2. **`LobbyRoundsTest`**, using the cycle 036 fakes unchanged.
   - With 3 players in a game, one disconnects and the other two get the game started on their `ready`.
   - A `skip` through the lobby works.
   - A reconnect of a Room player restores them in the ready count for the next song.
   - A leaving player's skip vote is removed.
3. **Socket** (`RoundsSocketTest`, `RANDOM_PORT`, reusing the cycle 037 `TestClient` and `WsFixture` unchanged). With two clients in a lobby game, `skip{index}` from both ends the song with a `reveal` whose `skipped` is true. `skip{index:"x"}` gives `bad-message`.
4. **No regression.** All earlier tests pass, with only the listed assertion changes. `.\gradlew.bat test` and `.\gradlew.bat build` exit 0.

## Test list
- `backend/src/test/java/com/songrush/game/RoomRoundsTest.java`: criterion 1.
- `backend/src/test/java/com/songrush/game/LobbyRoundsTest.java`: criterion 2.
- `backend/src/test/java/com/songrush/ws/RoundsSocketTest.java`: criterion 3.
- The listed assertion change in `RoomTest.java`.

## File ownership
- **Test writer:** the three new files and the listed `RoomTest` change.
- **Implementer:** `game/Room.java`, `game/Lobby.java`, `ws/GameSocketHandler.java`.

## Out of scope
- Late joiners (B-006c).
- Client UI (F-005).
