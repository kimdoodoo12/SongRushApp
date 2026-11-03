# 037 solo-websocket (B-007)

## Goal
A browser can play a solo game over a raw WebSocket at `/ws`: it creates a one-player room that starts at once, and the cycle 036 `Room` runs on its own thread with every message to the client going through a queue that never blocks or throws into the room (design 034; cycle 036 review M2).

## Protocol (JSON text frames, each an object with `"type"`)
- **Client to server:**
  - `create{nickname, songCount}`. The nickname is trimmed and must be 1-16 characters (`String.length()`); otherwise the reply is `error{code:"bad-nickname"}`. A session that already has a room gets `error{code:"already-in-room"}`. Otherwise the server builds a `Room` with one `PlayerRef` (a random id, the nickname) and calls `start(songCount)` on the room thread. If the room is still in LOBBY afterwards, because `Room` rejected the count with its own `error`, the room is discarded.
  - `ready{index}` and `chat{text}` are passed to the room. With no room the reply is `error{code:"no-room"}`.
  - `ping{id}` gets `pong{id, serverTime}` (epoch ms). `id` is echoed as given and may be absent. A ping works with or without a room.
  - `leave` discards the room. The socket stays open, and a new `create` is allowed.
- **Server to client:** whatever the `Room` sends (`prepare`, `start`, `chat`, `correct`, `reveal`, `scores`, `gameOver`, `error`), plus `pong` and the `error` codes above. After `gameOver` the room is discarded, so the next game is a new `create`.
- **Bad input:**
  - A frame that is not a JSON object, has no string `type`, or has an unknown `type` gets `error{code:"bad-message"}`.
  - Wrong field types (for example a non-integer `songCount` or `index`, or a non-string `text` or `nickname`) also get `bad-message`.
  - A text frame longer than 4096 bytes in UTF-8 gets `error{code:"too-large"}` and is not parsed. The container's text buffer is set to 8 KiB, so frames up to that size reach the handler.
- **Closing the socket** discards its room.

## Server structure
- **Room thread.** Each room gets its own single-thread `ScheduledExecutorService` wrapped as a `GameScheduler` (`ExecutorGameScheduler`). Every inbound message for the room is submitted to it, and the room's timers run on it, so every `Room` call happens on that one thread.
  - Each task is wrapped so that an exception is logged and does not kill the thread.
  - Discarding a room shuts its executor down (`shutdownNow`), and nothing more is sent from that room.
- **Outbound queue.** Each session gets an `OutboundQueue` holding up to 256 messages.
  - `offer(String json)` never blocks and never throws.
  - A shared sender pool drains each queue in order, one frame at a time per session.
  - When the queue is full, or a send fails, the session is closed with status 1008 (policy violation) and later offers are dropped. Each failure is logged once, without the message content.
- **Outbox.** The `Outbox` given to the room serializes each message map to JSON with Jackson and offers it to the right queues. It catches every exception, so the room never sees one (cycle 036 review M2).
- **Logging.** Logs carry session ids and error codes only, never chat text, titles or tokens.

## Acceptance criteria (JUnit 5, from `backend/`)
Tests start the app with `@SpringBootTest(webEnvironment = RANDOM_PORT)` and `songrush.data-dir` pointing at a temp folder, written before the context loads as `AudioEndpointTest` does. The folder holds a catalog with at least 5 drawable candidates and audio files of the listed sizes; `GameFixture` may be reused. Tests connect with `StandardWebSocketClient`, and their handler pushes each received text frame into a `BlockingQueue`. Each wait has a timeout.
1. **Solo game:** `create{nickname:"Tester", songCount:5}` leads to `prepare{index:0,...}`.
   - For each of the 5 songs, the test downloads `prepare.audioUrl` over HTTP (200, with the bytes of the right file), sends `ready{index}`, waits for `start`, and after `startAt` sends a correct answer. It finds the answer by resolving the token with the `AudioTokens` bean and reading the catalog.
   - Each song yields `correct`, `reveal` with `winner:"Tester"`, and `scores`.
   - After the last song comes `gameOver` with rank 1 and score 5.
   - No frame received before a song's `reveal` contains that song's audio basename or any of its titles.
2. **Ping:** `ping{id:7}` gets `pong{id:7, serverTime}`, with `serverTime` between the test's clock readings before and after; it works before `create`.
3. **Errors:**
   - `chat` before `create` gives `no-room`.
   - A second `create` gives `already-in-room`.
   - Nicknames `""`, `"   "` and 17 characters give `bad-nickname`.
   - `songCount:4` gives `bad-song-count`, and a following `create` with 5 works, because the rejected room was discarded.
   - Non-JSON, a missing `type`, an unknown `type` and a string `songCount` each give `bad-message`.
   - A 5000-byte frame gives `too-large`.
   - After each error the socket stays open and a `ping` still works.
4. **Leave and close:** after `leave` (sent mid-song), no further room messages arrive within 1 s after the leave, and a new `create` works. After a socket closes mid-song, the server keeps running and a new connection can play.
5. **OutboundQueue unit tests** (no server). The sink is a fake that blocks until released.
   - 256 offers return immediately while the sink is blocked; offer 257 closes the session (the fake records status 1008).
   - Offers after the close are dropped without an exception.
   - Messages are delivered in order.
   - A sink that throws closes the session, and the next `offer` does not throw.
6. **Outbox never throws:** with a queue whose sink throws, `toAll` and `toPlayer` return normally.
7. `.\gradlew.bat test` and `.\gradlew.bat build` exit 0, and every earlier test still passes. The solo-game test takes well under 2 minutes, since each song ends at its correct answer.

## Test list
- `backend/src/test/java/com/songrush/ws/SoloSocketTest.java`: criteria 1-4.
- `backend/src/test/java/com/songrush/ws/OutboundQueueTest.java`: criteria 5-6.
- Helpers in `com.songrush.ws` test sources, owned by the test writer.

## File ownership
- Test writer: the test files above.
- Implementer: new files under `backend/src/main/java/com/songrush/ws/`, which are `WebSocketConfig`, `GameSocketHandler`, `OutboundQueue`, `SocketOutbox` and `ExecutorGameScheduler` (names may vary; each test-facing class name and constructor goes in the spec's Interface note below), plus `backend/src/main/resources/application.properties` only if a setting is needed.
- No changes to `game/Room.java` or the locked cycle 036 tests.

## Interface note (test-facing)
- `com.songrush.ws.OutboundQueue`:
  - Constructor: `OutboundQueue(Sink sink, Executor senders, int capacity)`, where `interface Sink { void send(String text) throws Exception; void close(int status); }` is nested.
  - Methods: `boolean offer(String text)` returns false when dropped, and `boolean isClosed()`.
- `com.songrush.ws.SocketOutbox`:
  - Constructor: `SocketOutbox(Map<String, OutboundQueue> queuesByPlayerId)`. The map is read at each call, so players can be added later.
  - It implements `Outbox`.

## Out of scope
Room codes, multiple players, the lobby (B-005a), reconnect (B-005b), rate limits and skip (B-006b), the frontend (F-001, F-002), and a limit on the number of rooms (friends-only play; revisit with D-010 before M-4).
