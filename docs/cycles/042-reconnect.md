# 042 reconnect (B-005b)

## Goal
A player whose connection drops gets 30 s to come back and keep their seat and score. A reconnect with a private token restores their view through an allow-listed snapshot, and a second socket with the same token replaces the first (design 034 M3, M4).

## Scope
- This cycle covers tokens, the 30 s grace period, `rejoin`, socket replacement, the snapshot, and the cycle 040 review fix for a stale frame after `leave`.
- **Moved to a new item B-005c:** "with zero connected players the game pauses and re-prepares the current song on the first reconnect". It needs a pause/resume in `Room`. Until then, a song keeps playing while everyone is away, and a returning player rejoins mid-song through the snapshot.
- Ready and skip counting only connected players is B-006b (D-061).

## Interface changes
- **`Room`** (cycle 036; its locked tests must still pass) gains `Map<String, Object> currentSong()`.
  - It returns `null` unless the phase is PREPARE or PLAYING.
  - Otherwise it returns `{index, total, audioUrl}`, plus `startAt` and `deadlineAt` once the song has started (PLAYING).
  - The `audioUrl` is the one already sent in that song's `prepare`. The token is still valid, because tokens are not consumed.
  - It is called only on the room thread.
- **`Lobby`** (cycle 040; its locked tests must still pass) gains these methods:
  - `void disconnect(String playerId)` marks a connected member `connected:false` and starts a `GRACE = 30 s` timer.
    - When the timer fires, the member is treated as having sent `leave`: in LOBBY they are removed, and in a game they stay disconnected until `gameOver`.
    - The host rule (earliest-joined connected player) applies at once.
    - Everyone gets `roster`. The empty-lobby rule counts connected players, so it may start the `EMPTY_TTL`.
  - `boolean reconnect(String playerId)` succeeds only for a member who is disconnected and whose grace timer has not fired.
    - It marks them connected, cancels the grace timer, cancels a pending `EMPTY_TTL`, and sends that player `joined{code, name}` and then `snapshot`.
    - Then everyone gets `roster`.
    - It returns `false` otherwise, and sends nothing.
  - `leave(String playerId)` keeps its cycle 040 meaning, an immediate leave. It also works for a disconnected member and cancels their grace timer.
  - `static final Duration GRACE = Duration.ofSeconds(30)`.
- **`snapshot`** has exactly these keys: `type`, `phase` (`"lobby"` or `"playing"`), `players` (the same rows as `roster`), `song` (`null`, or `currentSong()` with exactly the keys above), and `scores` (`[{name, score}]` from the current `Room` in player order, or `[]` in LOBBY). It never contains a player id, a token, or any catalog field.

## Socket protocol (`ws/GameSocketHandler`)
1. **Token.**
   - After a successful `create` or `join` (in both lobby and solo mode), the handler sends that session `token{token}`.
   - The token is 128 random bits from `SecureRandom`, base64url without padding.
   - The handler keeps `token -> (lobby, playerId)`. A token is never put in any other message or log.
2. **Socket close** (not `leave`).
   - The seat is freed, and the player's queue is removed from the lobby's player map at once, on the I/O thread.
   - Then `lobby.disconnect(playerId)` runs on the lobby thread. The token stays valid for `rejoin`.
3. **`leave`.**
   - The queue is removed at once on the I/O thread (cycle 040 review M2).
   - Then `lobby.leave(playerId)` runs, and the token is dropped.
4. **`rejoin{token}`.**
   - A non-string `token` gives `bad-message`.
   - A session that already has a seat gets `already-in-room`.
   - An unknown token, or one whose lobby has closed, gets `error{code:"rejoin-failed"}`.
   - Otherwise:
     - If another open session still holds that player's seat, that session's seat is released first, without calling `disconnect`. That socket is then closed with status `4001` ("replaced"), and its later close callback does not touch the player.
     - The new session takes the seat and its queue is put in the lobby's player map. Then `lobby.reconnect(playerId)` runs on the lobby thread.
     - If `reconnect` returns `false` (grace over, or the member is gone), the seat and queue are removed, the token is dropped, and the session gets `rejoin-failed`.
     - A replaced player is still connected, so for replacement the handler calls `lobby.disconnect(playerId)` and then `lobby.reconnect(playerId)` in one lobby task. The new socket gets the snapshot.
5. **Lobby close.** When the lobby closes (`onClosed`), its tokens are dropped along with its seats.

## Acceptance criteria (JUnit 5, from `backend/`)
1. **`LobbyReconnectTest`** (`com.songrush.game`, reusing the cycle 036 fakes unchanged):
   - **Disconnect.** `disconnect` in LOBBY sends `roster` with that player `connected:false`, and the host moves on if they were host. `reconnect` within 30 s returns true and sends that player `joined` and then `snapshot`. The snapshot has `phase:"lobby"`, `song:null`, `scores:[]` and exactly the specified keys. Everyone then gets a roster with them `connected:true`.
   - **Grace expiry.** After `GRACE`, `reconnect` returns false and sends nothing. In LOBBY the player is gone from `names()`.
   - **During a game.** A player disconnected in PREPARE who reconnects within 30 s gets a snapshot with `phase:"playing"`, `song` holding exactly `index, total, audioUrl` (the same `audioUrl` as that song's `prepare`), and `scores`. In PLAYING the `song` also has `startAt` and `deadlineAt` equal to the `start` message's values.
   - **Grace in a game.** After the grace period in a game, the player stays `connected:false` until `gameOver` and is then removed (cycle 040 rule).
   - **Empty lobby.** When the last connected player disconnects, `EMPTY_TTL` starts. A `reconnect` within the grace period cancels it, so the lobby is still open after `EMPTY_TTL`.
   - **No leaks.** No snapshot or roster JSON contains a player id or any title, song, artist or basename of the current song.
   - **Explicit leave** of a disconnected player removes them at once in LOBBY.
2. **`RoomCurrentSongTest`** (`com.songrush.game`): `currentSong()` is null in LOBBY and REVEAL, and has the specified keys in PREPARE and PLAYING. The values match `prepare` and `start`.
3. **`ReconnectSocketTest`** (`com.songrush.ws`, `RANDOM_PORT`, reusing the cycle 037 `TestClient` and `WsFixture` unchanged):
   - **Tokens.** After `create` and `join`, each client gets `token{token}`, a 22-character base64url string, and the two tokens differ. No `roster` or `joined` frame contains a token.
   - **Drop and rejoin.** Closing B's socket makes A get a roster with B `connected:false`. A new socket sending `rejoin{token}` gets `joined` with B's name, then a `snapshot` with `phase:"lobby"`. A gets a roster with B `connected:true`.
   - **Replacement.** While B's second socket is open, a third socket sends `rejoin` with the same token. The second socket is closed with status 4001, and the third gets a `snapshot`. A sees B stay in the roster, with no `connected:false` roster in between from the replacement.
   - **Errors.** An unknown token gives `rejoin-failed`, and a `rejoin` from a seated session gives `already-in-room`. After `leave`, rejoining with the old token gives `rejoin-failed`.
4. **No regression.** The locked cycle 036, 037 and 040 tests pass unchanged. `.\gradlew.bat test` and `.\gradlew.bat build` exit 0.

## Test list
- `backend/src/test/java/com/songrush/game/LobbyReconnectTest.java`: criterion 1.
- `backend/src/test/java/com/songrush/game/RoomCurrentSongTest.java`: criterion 2.
- `backend/src/test/java/com/songrush/ws/ReconnectSocketTest.java`: criterion 3.

## File ownership
- **Test writer:** the three new test files, plus new helpers in those test packages. No existing test file is edited.
- **Implementer:** `game/Room.java` (the `currentSong()` addition only, without behaviour changes), `game/Lobby.java`, `ws/GameSocketHandler.java`, and new package-private helpers.

## Out of scope
- The pause with nobody connected (B-005c, new).
- Connected-only ready and skip (B-006b).
- The client-side token storage and automatic reconnect (F-005).
