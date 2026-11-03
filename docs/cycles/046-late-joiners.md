# 046 late-joiners (B-006c)

## Goal
Someone can join while a game is running. They watch the current song and join the game at the next `prepare` with 0 points (D-059). This covers both a new player and a member who was in grace when the game started (the cycle 042 spectator).

## Interface (package `com.songrush.game`)
- **`Room`**
  - `void addPlayer(PlayerRef player)`.
    - Adds a **pending** player. A known id, or an id that is already pending, is ignored.
    - A pending player is not in the ready set or the skip count, and gets no row in `scores`, `scores()` or the ranking.
    - At the next `prepare(index)` every pending player becomes a full player: score 0, connected (or not, as last set), a full chat bucket, and placed after the existing players in player order.
    - `prepare` runs for the next song, for a `resume`, and for a new game's first song.
    - In LOBBY, `addPlayer` is ignored, because the lobby builds a new `Room` per game.
  - `setConnected` also accepts pending ids. It records their state, which takes effect when they are promoted.
  - **Chat from a pending player (watcher).**
    - It spends a token from its own bucket, starting full.
    - It never scores.
    - A message that matches the current song before its `reveal` reaches only the sender, as `chat{name, text}`. It is never broadcast, so a watcher cannot announce the answer to the players. This holds in PREPARE and in PLAYING before or after `startAt`.
    - A miss follows the player rules: before `startAt` only the sender gets it, and from `startAt` everyone does. In REVEAL everyone gets every message.
    - A watcher's `ready` and `skip` are ignored.
- **`Lobby`**
  - **`join` during a game** is now allowed. The checks run in this order: closed, then nickname, then room full (8 members, D-046).
    - The joiner becomes a member, connected and not in grace.
    - The joiner gets `joined{code, name}` and then the `snapshot` (cycle 042 shape, `phase:"playing"`). Then everyone gets `roster`.
    - Then `room.addPlayer(PlayerRef(id, name))` is called.
    - The `in-game` error remains only for `start` during a game.
  - **A grace `reconnect` of a member who is not a Room player** (the cycle 042 spectator) while a game runs calls `room.addPlayer` the same way, after `joined`, `snapshot` and `roster`.
  - **Room players.** Promotion turns pending players into Room players. The lobby learns of it from the `prepare` message passing through its `RoomOutbox`: every pending id joins `roomPlayers` at that moment.
  - **Until promotion.** A pending player does **not** count for the cycle 044 pause, resume and abandon rules. Those rules stay as locked: a connected watcher neither keeps a game running nor resumes it.
- **Snapshot and roster.** These are unchanged in shape. A pending player appears in `roster` like any member, but not in `scores` until promoted.

## Changes to locked earlier tests (declared; the test writer changes only these)
- **`LobbyTest.startAndJoinDuringAGameGetInGame`.**
  - Before: a `join` during a game gets `in-game` and `names()` stays two.
  - After: that join succeeds. P3 gets `joined` and `snapshot`, and `names()` lists the three players.
  - The `start`-during-game `in-game` part stays.
  - The nickname-before-anything check stays.
  - The test may be renamed.
- **`LobbyReconnectTest.memberInGraceAtStartSpectatesAndCannotScore`.**
  - Before: the spectator's correct-title chat after `startAt` produces no message.
  - After: it reaches only the spectator, as `chat{name, text}`. Nobody else gets anything, and nobody scores.
  - The rest stays.
- No other locked test may change.

## Acceptance criteria (JUnit 5, from `backend/`)
1. **`RoomLateJoinTest`** (cycle 036 fakes, unchanged):
   - **Joining mid-song.** A player added in PLAYING of song 0 is not counted in `skipState.needed`. Their `ready` and `skip` are ignored. A correct chat from them reaches only them, both before and after `startAt`, and does not score. Their miss after `startAt` is broadcast. They have their own rate limit.
   - **Promotion.** At the next `prepare` they are in the ready set (the song waits for their `ready`, if connected), in `skipState.needed`, and in `scores` with 0 points, after the existing players. They score from then on, and the ranking includes them.
   - **Disconnected while pending.** A pending player marked disconnected before promotion is promoted as disconnected and not waited for.
   - **Ignored adds.** `addPlayer` of a known or already pending id, and `addPlayer` in LOBBY, do nothing.
   - **Last song.** A player added during the last song's PLAYING is never promoted. `gameOver` ranks only the players.
   - **Pause.** A player added while paused is promoted by `resume`'s `prepare`.
2. **`LobbyLateJoinTest`** (cycle 036 fakes, unchanged):
   - **Joining mid-game.** A `join` during a game sends that player `joined`, then `snapshot` with `phase:"playing"` and the current `song`, then a roster to everyone with the new name. At the next song's `prepare` they become a Room player: their `ready` is waited for, and their correct answer scores.
   - **Room full.** The 9th member gets `room-full` even during a game.
   - **Spectator reconnect.** A member in grace at `start` who reconnects during song 0 becomes a Room player at song 1's `prepare`.
   - **Watcher and pause.** A connected late joiner does not prevent the pause when every Room player has disconnected. While paused, that joiner's `reconnect` cannot resume.
   - **No `toAll`.** The no-`toAll` `@AfterEach` check is included.
3. **Socket** (`LateJoinSocketTest`, reusing the cycle 037 `TestClient` and `WsFixture` unchanged):
   - A third client joins with the code after the host started a game. It gets `joined` and a `snapshot` with `phase:"playing"`, then the next `prepare`.
4. **No regression.** All earlier tests pass with only the declared changes. `.\gradlew.bat test` and `.\gradlew.bat build` exit 0.

## Test list
- `backend/src/test/java/com/songrush/game/RoomLateJoinTest.java`: criterion 1.
- `backend/src/test/java/com/songrush/game/LobbyLateJoinTest.java`: criterion 2.
- `backend/src/test/java/com/songrush/ws/LateJoinSocketTest.java`: criterion 3.
- The two declared assertion changes in `LobbyTest` and `LobbyReconnectTest`.

## File ownership
- **Test writer:** the three new files and the two declared changes.
- **Implementer:** `game/Room.java` and `game/Lobby.java`. The socket handler needs no change, because `join` already forwards.

## Out of scope
The client UI for watching (F-005).
