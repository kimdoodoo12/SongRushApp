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
  - `boolean reconnect(String playerId)`:
    - For a member **in grace** (an explicit flag set by `disconnect` and cleared by the grace expiry, `leave` or `reconnect`): marks them connected, cancels the grace timer, cancels a pending `EMPTY_TTL`, sends that player `joined{code, name}` and then `snapshot`, then everyone gets `roster`.
    - For a member who is **connected** (socket replacement, spec review B1): no state or roster change; it only sends that player `joined{code, name}` and then `snapshot`.
    - Otherwise (unknown, grace expired, or left, including a `leave` during a game): returns `false` and sends nothing.
    - A member who was in grace when a game started is not in that game's `Room`; after a reconnect they get the `playing` snapshot and the game's messages but cannot score until the next game (their chat is ignored by the `Room`). Accepted until B-006b (late joiners, D-059).
  - `leave(String playerId)` keeps its cycle 040 meaning, an immediate leave.
  - **Grace expiry** does not re-arm `EMPTY_TTL` (the empty timer runs from the moment the last connected player disconnected) and does not re-arm the idle timer.
  - **Back to the lobby** after `gameOver` removes only disconnected members whose grace has expired; members still in grace stay, and their grace timer keeps running (spec review M1).
  - `static final Duration GRACE = Duration.ofSeconds(30)`.
- **`snapshot`** (a reconnect during REVEAL gets `song:null` and sees the next `prepare`; the reveal card is missed, accepted) has exactly these keys: `type`, `phase` (`"lobby"` or `"playing"`), `players` (the same rows as `roster`), `song` (`null`, or `currentSong()` with exactly the keys above), and `scores` (`[{name, score}]` from the current `Room` in player order, or `[]` in LOBBY). It never contains a player id, a token, or any catalog field.

## Socket protocol (`ws/GameSocketHandler`)
1. **Token.**
   - After a successful `create` or `join` (in both lobby and solo mode), the handler sends that session `token{token}`.
   - The token is 128 random bits from `SecureRandom`, base64url without padding.
   - The handler keeps `token -> (lobby, playerId)`. A token is never put in any other message or log.
2. **Bindings (spec review B1, H3).** The handler keeps `sessionId -> WebSocketSession`, and per lobby a `playerId -> sessionId` binding updated atomically (`compute`). Queues are removed from the lobby's player map only conditionally (`remove(playerId, queue)`), so an old socket's late cleanup never removes a new socket's queue.
3. **Socket close** (not `leave`).
   - The seat is freed and, only if the player's binding still points to this session, the binding is cleared, the queue is removed conditionally, and `lobby.disconnect(playerId)` runs on the lobby thread. The token stays valid for `rejoin`.
   - If the binding already points to another session (replaced), nothing is done for the player.
4. **`leave`.**
   - The queue is removed at once on the I/O thread (cycle 040 review M2).
   - Then `lobby.leave(playerId)` runs, and the token is dropped.
5. **`rejoin{token}`.**
   - A non-string `token` gives `bad-message`.
   - A session that already has a seat gets `already-in-room`.
   - An unknown token, or one whose lobby has closed, gets `error{code:"rejoin-failed"}`.
   - Otherwise:
     - The handler rebinds the player to the new session atomically. If the binding pointed to another open session, that session's seat is released, its queue is stopped (`OutboundQueue.stop()`, package-private) and removed conditionally, and its socket is closed with `new CloseStatus(4001, "replaced")` through the session map; its later close callback finds the binding moved and does nothing.
     - The new session's queue is put in the lobby's player map, then `lobby.reconnect(playerId)` runs on the lobby thread. For a still-connected member this is the replacement case (no roster change); for a member in grace it is a normal reconnect.
     - If `reconnect` returns `false` (grace over, or the member is gone), the binding, seat and queue are removed, the token is dropped, and the session gets `rejoin-failed`.
6. **Lobby close.** When the lobby closes (`onClosed`), its tokens are dropped along with its seats.

## Acceptance criteria (JUnit 5, from `backend/`)
1. **`LobbyReconnectTest`** (`com.songrush.game`, reusing the cycle 036 fakes unchanged):
   - **Disconnect.** `disconnect` in LOBBY sends `roster` with that player `connected:false`, and the host moves on if they were host. `reconnect` within 30 s returns true and sends that player `joined` and then `snapshot`. The snapshot has `phase:"lobby"`, `song:null`, `scores:[]` and exactly the specified keys. Everyone then gets a roster with them `connected:true`.
   - **Grace expiry.** After `GRACE`, `reconnect` returns false and sends nothing. In LOBBY the player is gone from `names()`.
   - **During a game.** A player disconnected in PREPARE who reconnects within 30 s gets a snapshot with `phase:"playing"`, `song` holding exactly `index, total, audioUrl` (the same `audioUrl` as that song's `prepare`), and `scores`. In PLAYING the `song` also has `startAt` and `deadlineAt` equal to the `start` message's values.
   - **Grace in a game.** After the grace period in a game, the player stays `connected:false` until `gameOver` and is then removed (cycle 040 rule).
   - **Empty lobby.** When the last connected player disconnects, `EMPTY_TTL` starts. A `reconnect` within the grace period cancels it, so the lobby is still open after `EMPTY_TTL`.
   - **No leaks.** No snapshot or roster JSON contains a player id or any title, song, artist or basename of the current song.
   - **Replacement contract.** `reconnect` for a connected member returns true, sends that player `joined` then `snapshot`, and sends no roster to anyone.
   - **Order and left members.** `disconnect` then `reconnect` at the same instant works; `leave` during a game followed by `reconnect` returns false (the handler, not the Lobby, skips a stale close from a replaced socket).
   - **Grace and gameOver.** A member who disconnects 2 s before `gameOver` is still present in LOBBY afterwards and can `reconnect` until their 30 s run out.
   - **Grace and EMPTY_TTL.** When the last connected player disconnects at time T, the lobby closes at T + `EMPTY_TTL` (not later), even though their grace expires at T + 30 s.
2. **`RoomCurrentSongTest`** (`com.songrush.game`): `currentSong()` is null in LOBBY and REVEAL, and has the specified keys in PREPARE and PLAYING. The values match `prepare` and `start`.
3. **`ReconnectSocketTest`** (`com.songrush.ws`, `RANDOM_PORT`, reusing the cycle 037 `TestClient` and `WsFixture` unchanged, plus a new helper client that records the close status, for example `ws/StatusClient.java`):
   - **Tokens.** After `create` and `join`, each client gets `token{token}`, a 22-character base64url string, and the two tokens differ. No `roster` or `joined` frame contains a token.
   - **Drop and rejoin.** Closing B's socket makes A get a roster with B `connected:false`. A new socket sending `rejoin{token}` gets `joined` with B's name, then a `snapshot` with `phase:"lobby"`. A gets a roster with B `connected:true`.
   - **Replacement.** While B's second socket is open, a third socket sends `rejoin` with the same token. The second socket is closed with status 4001, and the third gets a `snapshot`. A gets no roster at all from the replacement. The close status is read through a new test helper (the cycle 037 `TestClient` does not expose it).
   - **Errors.** An unknown token gives `rejoin-failed`, and a `rejoin` from a seated session gives `already-in-room`. After `leave`, rejoining with the old token gives `rejoin-failed`.
4. **No regression.** The locked cycle 036, 037 and 040 tests pass unchanged. `.\gradlew.bat test` and `.\gradlew.bat build` exit 0.

## Review changes (spec round 1)
- **Blocker.** Replacement no longer goes through `disconnect` + `reconnect` (that would have broadcast a `connected:false` roster); `reconnect` accepts a connected member silently, and the handler's player-to-session binding and conditional queue removal close the close-vs-rejoin race.
- **High.** B-005c is added to the backlog; the 4001 close goes through a session map and `OutboundQueue.stop()`; the close status is read through a new test helper.
- **Medium.** Members in grace survive the return to the lobby; a member in grace at `start` spectates until the next game (accepted until B-006b); `reconnect` requires the explicit in-grace flag; grace expiry never re-arms `EMPTY_TTL`; the unused "leave a disconnected member" criterion is dropped.
- **Nits.** The skip vote joins the snapshot in B-006b; a reconnect during REVEAL gets `song:null` by design.

## Test list
- `backend/src/test/java/com/songrush/game/LobbyReconnectTest.java`: criterion 1.
- `backend/src/test/java/com/songrush/game/RoomCurrentSongTest.java`: criterion 2.
- `backend/src/test/java/com/songrush/ws/ReconnectSocketTest.java` and a new helper such as `ws/StatusClient.java`: criterion 3.

## File ownership
- **Test writer:** the three new test files, plus new helpers in those test packages. No existing test file is edited.
- **Implementer:** `game/Room.java` (the `currentSong()` addition only, without behaviour changes), `game/Lobby.java`, `ws/GameSocketHandler.java`, and new package-private helpers. `ws/OutboundQueue.java` is not changed (its package-private `stop()` already exists).

## Out of scope
- The pause with nobody connected (B-005c, new).
- Connected-only ready and skip (B-006b).
- The client-side token storage and automatic reconnect (F-005).

## Result
- Tests `270cb6e` (locked); implementation `1f001d2` (attempt 1).
- Red phase (verifier): `:compileTestJava FAILED`, 62 errors, all missing `Lobby.reconnect` (23), `Lobby.disconnect` (21), `Lobby.GRACE` (9) or `Room.currentSong` (9); against stubs every test source compiled.
- Verifier: `cleanTest test` twice and `build`, exit 0; 22 suites, 0 failures (LobbyReconnectTest 24, RoomCurrentSongTest 6, ReconnectSocketTest 8; all earlier suites unchanged and passing); test sources unchanged since `270cb6e`; `OutboundQueue.java` unchanged; the `Room` diff only stores the prepared `audioUrl` and adds `currentSong()`; handler logs carry session ids and exception class names only.

## Code review (critic)
No Blocker or High. Traced by hand: token generation, delivery and disposal; no deadlock in the `compute` ordering scheme; three close-vs-rejoin interleavings and two simultaneous rejoins; spectators cannot crash the `Room`; snapshot contents; the grace, empty and idle timers.
- **M1 (follow-up).** A request landing while its lobby is closing can leave a seat with no reply.
- **Nits (follow-up / F-005).** Frames before the snapshot on a replacing socket; `leave` racing a rejoin drops the token; no concurrency test at the handler level.
