# 037 solo-websocket (B-007)

## Goal
A browser can play a solo game over a raw WebSocket at `/ws`: it creates a one-player room that starts at once, and the cycle 036 `Room` runs on its own thread with every message to the client going through a queue that never blocks or throws into the room (design 034; cycle 036 review M2).

## Protocol (JSON text frames, each an object with `"type"`)
- **Client to server:**
  - `create{nickname, songCount}`. The nickname is trimmed and must be 1-16 characters (`String.length()`); otherwise the reply is `error{code:"bad-nickname"}`. A session that already has a room gets `error{code:"already-in-room"}`. A `songCount` outside 5-30 is answered by the handler with `error{code:"bad-song-count"}` without building a room. Otherwise the server builds a `Room` with one `PlayerRef` (a random id, the nickname) and calls `start(songCount)` on the room thread. If the room is still in LOBBY afterwards (`not-enough-songs`), the room is discarded.
  - `ready{index}` and `chat{text}` are passed to the room. With no room the reply is `error{code:"no-room"}`.
  - `ping{id}` gets `pong{id, serverTime}` (epoch ms). `id` is echoed as given and may be absent. A ping works with or without a room.
  - `leave` discards the room. The socket stays open, and a new `create` is allowed.
- **Server to client:** whatever the `Room` sends (`prepare`, `start`, `chat`, `correct`, `reveal`, `scores`, `gameOver`, `error`), plus `pong` and the `error` codes above. After `gameOver` the room is discarded, so the next game is a new `create`.
- **Bad input:**
  - A frame that is not a JSON object, has no string `type`, or has an unknown `type` gets `error{code:"bad-message"}`.
  - Wrong field types (for example a non-integer `songCount` or `index`, or a non-string `text` or `nickname`) also get `bad-message`.
  - A text frame longer than 4096 bytes in UTF-8 (`TextMessage.getPayloadLength()`) gets `error{code:"too-large"}` and is not parsed. No container setting is added: Tomcat's default text buffer (8192 characters) already lets such frames reach the handler, and a `ServletServerContainerFactoryBean` would break the existing MOCK-environment tests (spec review B1).
- **The handler never throws.** Every handler method catches its exceptions (Spring would otherwise close the socket with 1011). A message for a room whose executor has been shut down (a submit rejected with `RejectedExecutionException`) is answered with `error{code:"no-room"}`.
- **Closing the socket** discards its room.

## Server structure
- **Room thread.** Each room gets its own single-thread `ScheduledExecutorService` wrapped as a `GameScheduler` (`ExecutorGameScheduler`). Every inbound message for the room is submitted to it, and the room's timers run on it, so every `Room` call happens on that one thread.
  - Each task is wrapped so that an exception is logged and does not kill the thread.
  - Discarding a room shuts its executor down (`shutdownNow`), and nothing more is sent from that room.
- **Session to room.** The handler keeps a concurrent map from session id to its room. A room removes its own entry with a conditional remove (`map.remove(sessionId, room)`), so a late discard of an old room never removes a newer room.
- **Outbound queue.** Each session gets an `OutboundQueue` holding up to 256 unsent messages; the one being sent counts until its send returns. Every outbound frame of the session, including `pong` and the handler's own `error`s, goes through this queue; only the queue's drain calls `WebSocketSession.sendMessage`.
  - `offer(String json)` never blocks and never throws.
  - A shared sender pool drains each queue in order, one frame at a time per session.
  - When the queue is full, or a send fails, the session is closed with status 1008 (policy violation) and later offers are dropped. The close may happen on the offering thread or a sender thread. Each failure is logged once, without the message content.
- **Outbox.** The `Outbox` given to the room serializes each message map to JSON with Jackson and offers it to the right queues. It catches every exception, so the room never sees one (cycle 036 review M2).
- **Logging.** Logs carry session ids and error codes only, never chat text, titles or tokens.

## Acceptance criteria (JUnit 5, from `backend/`)
Tests start the app with `@SpringBootTest(webEnvironment = RANDOM_PORT)` and `songrush.data-dir` pointing at a temp folder, written before the context loads as `AudioEndpointTest` does. The folder holds a catalog with at least 6 drawable candidates whose `durationMs` is 3000 and audio files of the listed sizes with content distinct per theme (as `AudioEndpointTest.audioBytes`). `GameFixture` is package-private, so the test writer copies what it needs into the `ws` test package. Tests connect with `StandardWebSocketClient`, and their handler pushes each received text frame into a `BlockingQueue`. Each wait has a timeout of at least 15 s.
1. **Solo game:** `create{nickname:"Tester", songCount:5}` leads to `prepare{index:0,...}`.
   - For each of the 5 songs, the test downloads `prepare.audioUrl` over HTTP (200, with the bytes of the right file), sends `ready{index}`, waits for `start`, and after `startAt` sends a correct answer, except song 2, which it leaves unanswered. It finds the answer by resolving the token with the `AudioTokens` bean and reading the catalog.
   - Each answered song yields `correct`, `reveal` with `winner:"Tester"`, and `scores`; song 2 ends at its deadline with `reveal.winner` null.
   - After the last song comes `gameOver` with rank 1 and score 4.
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
4. **Leave and close:** after `leave` sent while a song is PLAYING, no `reveal` or `prepare` arrives until 1 s after that song's `deadlineAt`, a `chat` gets `no-room`, and a new `create` works. After a socket closes mid-song, the server keeps running and a new connection can play.
5. **OutboundQueue unit tests** (no server). The sink is a fake that blocks until released.
   - With the sink blocked on the first message, 256 offers in total return immediately (the blocked message counts); offer 257 returns false and the session is closed (the test waits up to 5 s for the fake to record status 1008).
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

## Review changes (spec round 1)
- **Blocker.** The 8 KiB container setting would have broken three existing MOCK-environment tests and is unnecessary; dropped.
- **High.** Queue capacity now counts the message being sent, so the overflow test is deterministic; the handler never throws and answers a rejected submit with `no-room`.
- **Medium.** Short songs make the leave test meaningful and let one song end at its deadline (as B-007 asks); the fixture is copied into the `ws` tests; every frame goes through the queue; the handler validates the song count itself and the session map uses conditional removes.
- **Nit.** Waits are at least 15 s.

## Out of scope
Room codes, multiple players, the lobby (B-005a), reconnect (B-005b), rate limits and skip (B-006b), the frontend (F-001, F-002), and a limit on the number of rooms (friends-only play; revisit with D-010 before M-4).
