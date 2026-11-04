# 069 game-core (B-014)

## Goal
Move everything that is not the raw WebSocket transport out of `GameSocketHandler` into a transport-independent `GameService`. The STOMP endpoint (B-015, B-016) and the current raw handler can then share one implementation and the same lobbies (design 068, review M1). Behaviour does not change.

## Behaviour
1. **`com.songrush.ws.GameConnection`** (new interface): one client connection as the game sees it.
   - `String id()`: unique per connection.
   - `void send(Map<String, Object> message)`: queue one message to this connection. It must not block, and it keeps order.
   - `void close(int code, String reason)`: used for 4001 `replaced` and for 1008 backpressure.
2. **`com.songrush.ws.GameService`** (new): owns today's state and logic from `GameSocketHandler`:
   - the seat, token (`logins`) and binding registry;
   - `Hosted` lobbies and their per-lobby `ExecutorGameScheduler`;
   - `MAX_LOBBIES`, nickname and song-count validation, and filter parsing from `JsonNode`;
   - rejoin, replace and disconnect handling.

   Public methods, one per client action. Each takes the `GameConnection` and the parsed `JsonNode` fields where today's handler reads them:
   - `ping(conn, JsonNode)`;
   - `create(conn, JsonNode)`, `join(conn, JsonNode)`, `start(conn, JsonNode)`;
   - `ready(conn, JsonNode)`, `chat(conn, JsonNode)`, `skipVote(conn, JsonNode)`;
   - `leave(conn)`, `rejoin(conn, JsonNode)`;
   - `disconnected(conn)`.

   Error replies, the `pong`, `token` and `joined` sequence, and every rule stay exactly as today. A constructor parameter `Function<String, GameScheduler>` supplies a lobby's scheduler from its code; the default creates an `ExecutorGameScheduler`. The dependencies are otherwise the same as the handler's today: `Catalog`, `AudioTokens` and `Clock`.
3. **`GameSocketHandler`** becomes only the raw transport:
   - The 4096-byte check, JSON parse, `type` dispatch and `bad-message`/`too-large` stay in the handler. It then calls `GameService`.
   - Its `GameConnection` wraps the session and the existing `OutboundQueue`.
   - `afterConnectionClosed` calls `disconnected`.
   - The Spring bean wiring provides one `GameService` and the handler built on it.
4. Logs keep never printing tokens.

## Acceptance criteria (JUnit 5, from `backend/`)
1. **`GameServiceTest`** (new) drives `GameService` directly, through a fake `GameConnection` that records sent maps and close calls, and `ManualScheduler`-backed lobbies via the scheduler function. It covers:
   - **Create.** `create` with a nickname gives that connection exactly `joined`, then `token`, then `roster`, in that order.
   - **Join.** `join` with the code from another connection gives `joined` and `token`, and both connections get the updated `roster`.
   - **Errors.** Each of these goes to the sender only: `bad-nickname`, `room-not-found`, `already-in-room`, and `bad-filter` (for a bad `types` on a solo `create`).
   - **Replace.** `rejoin` with the token from a second connection closes the first with 4001 `replaced` and gives the second `joined` and `snapshot`.
   - **Disconnect and rejoin.** `disconnected` followed by `rejoin` within the grace keeps the seat.
   - **Leave.** `leave` frees the seat; a later `rejoin` with that token gives `rejoin-failed`.
   - **Ping.** `ping{id:3}` gives `pong{id:3, serverTime}` at once, without going through the lobby scheduler.
2. **Earlier tests.** All earlier backend tests pass unchanged, including every raw socket test. `.\gradlew.bat test` exits 0.

## Test list
- `backend/src/test/java/com/songrush/ws/GameServiceTest.java`, with helpers inside it or a new `FakeConnection` in the same package.

## File ownership
- **Test writer:** the test list.
- **Implementer:** `ws/GameSocketHandler.java`, `ws/WebSocketConfig.java`, `ws/SocketOutbox.java`, the new `ws/GameService.java` and `ws/GameConnection.java`.

## Constraint
A live upload is running from this PC. Run only `.\gradlew.bat test`, never `build`, `bootJar` or `assemble`, and do not touch `deploy/` or `frontend/`.

## Out of scope
- STOMP (B-015, B-016).
