# 068 STOMP realtime protocol (S-005)

Owner decision D-076: STOMP over WebSocket, using Spring's `@MessageMapping` and destinations, for both the web client and the Flutter app. The game rules stay the same: rooms, scores, answer checks and server arrival order. Per D-084, the lead writes all code for this change.

## Questions
1. How does each current message map to STOMP destinations?
2. How do we keep "the first correct answer by server arrival order" and the per-player message order?
3. How do private messages work, and what replaces the custom close codes? Private messages include the token, the rejoin snapshot, rate-limit and skip-refusal errors, and the sender-only early chat.
4. How do reconnect, the 30 s grace, the replaced-tab case and clock sync work?
5. How do we migrate the 48 raw-socket backend tests and the about 150 frontend tests that script raw frames, without losing coverage?

## Findings
**The current protocol** (explorer survey, `path:line` evidence in the cycle record).
- One `/ws` endpoint carries JSON frames with a `type` field.
- Client to server: `ping`, `create`, `join`, `start`, `ready`, `chat`, `skipVote`, `leave` and `rejoin`.
- Server to client: `pong`, `token`, `error`, `joined`, `snapshot`, `settings`, `roster`, `prepare`, `start`, `chat`, `correct`, `skipState`, `reveal`, `scores` and `gameOver`.
- Audience: messages go either to one player or to every connected member. The `chat` echo goes to the sender only before `startAt` and for a watcher's guess.

**The seam.**
- The game layer talks only to `Outbox` (`toPlayer`, `toAll`), from one single-thread scheduler per lobby. Everything in `game/` is independent of the transport, and so are its about 240 tests.
- The transport layer is what changes: `GameSocketHandler`, `OutboundQueue`, `SocketOutbox`, `WebSocketConfig`, plus the frontend's `session.js` and the `FakeSocket` in `testing/fakes.js`.

**Spring STOMP facts** (Framework 7 reference).
- **Concurrency.** `@MessageMapping` methods run on the `clientInboundChannel` pool. Messages from one session may be handled out of order unless `setPreserveReceiveOrder(true)` is set. Sessions are handled concurrently.
- **Outbound order.** Outbound order per session needs `setPreservePublishOrder(true)`.
- **Simple broker and heartbeats.** The simple broker is in memory and fits one JVM. Heartbeats need a `TaskScheduler`.
- **User destinations.** These are `/user/queue/...` with `convertAndSendToUser`. A per-connection Principal can come from a custom `HandshakeHandler`, without Spring Security.
- **Send limits.** `setSendBufferSizeLimit` and `setSendTimeLimit` close a slow session, replacing `OutboundQueue`'s 256-frame cap and its 1008 close.
- **Allowed origins.** `setAllowedOriginPatterns` on the endpoint. The default is same-origin.

**Client libraries.**
- `@stomp/stompjs` 7.5.0. You subscribe in `onConnect`, `reconnectDelay: 0` disables its own retry, and heartbeats default to 10 s.
- `stomp_dart_client` 3.0.1 for Flutter.

## Options
**A. How the server sends to clients**
- **A1. Room topics plus a private queue.**
  - Room-wide messages go to `/topic/room.{code}`. Private ones go to `/user/queue/game`.
  - This is the textbook Spring layout.
  - Two parts are not yet built:
    - A SUBSCRIBE interceptor that only lets current members subscribe. Anyone who knows a room code could otherwise listen in: wrong guesses, roster, start times.
    - A join handshake that closes the gap between joining and subscribing. Broadcasts sent in that gap are lost, and today's frontend already guards against a similar race.
  - Ordering between the topic and the queue for one session holds only because both are published from the lobby thread with `setPreservePublishOrder`.
- **A2. Private queue only.**
  - Every server-to-client message goes to `/user/queue/game` of each recipient. The outbox sends one copy per member, which is what `toAll` does today.
  - There is no topic to guard and no subscribe gap: the client subscribes once on connect.
  - Per-session order is exactly today's order.
  - The cost: N copies per broadcast, which is fine with at most 16 members. It is also less "textbook", since topics go unused.

**B. Client-to-server destinations**
- **B1. One destination per message type**: `/app/create`, `/app/join`, `/app/start`, `/app/ready`, `/app/chat`, `/app/skip`, `/app/leave`, `/app/rejoin`, `/app/ping`. This is idiomatic `@MessageMapping`, and the body carries only the fields.
- **B2. One `/app/game` destination with today's `type` field.** It is a smaller change but uses STOMP only as a pipe.

## Decision (lead's design; A needs the owner, D-085)
- **B1.** Each `@MessageMapping` method only:
  1. parses and validates its input;
  2. checks the 4096-byte payload limit, as today;
  3. submits the action to the lobby's single-thread scheduler.

  The arrival-order rule is therefore unchanged.
- **Handling ping.** `/app/ping` is answered at once on the inbound thread, so pings never wait behind game tasks. The reply is `pong` with `serverTime`.
- **Ordering settings.** `setPreserveReceiveOrder(true)` and `setPreservePublishOrder(true)`.
- **Message bodies.** Server-to-client bodies keep today's JSON objects exactly, including `type`. So `Room`, `Lobby` and their tests stay, and so do the frontend reducer and most frontend tests.
- **Principal.** A random connection id is assigned at the handshake. The existing registry maps token to player and player to the current connection.
- **Disconnect.** `SessionDisconnectEvent` starts today's 30 s grace.
- **Replaced connection.** The old connection gets `{type:"replaced"}` and is then closed by the server. The client treats it as terminal, as it treats 4001 today.
- **Errors.** Bad input or an exception in a handler becomes `{type:"error", code:"bad-message"}` on the private queue, through `@MessageExceptionHandler`, and never an ERROR frame that drops the connection.
- **Heartbeats.** 10 s both ways. They also keep idle connections alive through the owner's Nginx (design 067, review H3).
- **Same-origin.** The default stays. `setAllowedOriginPatterns` is left for the owner's deployment.
- **Frontend.**
  - `session.js` moves to `@stomp/stompjs`, pinned. It subscribes to `/user/queue/game` in `onConnect`.
  - Its own reconnect ladder stays: `reconnectDelay: 0`, `rejoin` sent from `onConnect`.
  - `clockSync.js` only changes its send call.
- **Tests.**
  - Backend socket tests are ported to a `WebSocketStompClient`-based `StompTestClient` with the same assertions. These are declared rewrites.
  - The frontend `FakeSocket` is replaced by a `FakeStompClient`. It keeps the same `receive(msg)` and `sent` interface, so App tests change only in setup. This is a declared change to `fakes.js`.
  - The raw `/ws` endpoint stays until both sides have moved, then it is removed.

## Resulting items
1. **B-014 STOMP endpoint, part 1.**
   - Config, connection Principal, `/app/ping`, `/app/create`, `/app/join` and `/app/leave`.
   - A user-queue `Outbox` (or a topic outbox, per D-085), and the ordering flags.
   - `SoloSocketTest` and `LobbySocketTest` ported onto `StompTestClient`, plus an ordering test: concurrent correct answers from several connections get one winner, in server arrival order.
   - The raw `/ws` endpoint stays live.
2. **B-015 STOMP endpoint, part 2.**
   - The handlers: `start`, `ready`, `chat`, `skip` and `rejoin`.
   - Connection lifecycle: replaced, the disconnect grace, `@MessageExceptionHandler` and heartbeats.
   - Tests: the Rounds, LateJoin, Reconnect, Filter and Tier socket tests and the proxy-handshake test ported. A leak test checks that nothing private reaches another connection.
3. **F-010 Frontend on STOMP.**
   - `session.js` on `@stomp/stompjs`, with the `FakeStompClient` (a declared change to `fakes.js`) and App tests adapted only in setup.
   - Acceptance: all frontend tests pass, and a real-jar smoke check over STOMP plays a solo game.
4. **B-016 Remove the raw transport.** Remove `/ws`, `GameSocketHandler`'s raw parts, `OutboundQueue`, the raw test helpers and their tests. Update `deploy/smoke.py`'s WebSocket check to STOMP; that is app-side code (D-084).

## Needs decision
- **D-085 (owner): A1 room topics plus a private queue, or A2 private queue only.**
  - **Recommended: A2.** It keeps today's exact ordering and privacy with the least risk to fairness.
  - Choose A1 if showing the standard topic layout in the portfolio matters. It adds a membership-checking SUBSCRIBE guard and a join-then-subscribe handshake, about one more cycle.

## Review changes (round 1)
- **Blocker. The items are re-split so that every item's tests can pass with that item's handlers.**
  1. **B-014: extract a transport-independent core (review M1).** Pull the seat, token, binding and lobby registry and the per-message actions out of `GameSocketHandler` into a `GameService`. The raw handler and the STOMP controller both call it and share the same lobbies. This is a pure refactor: every existing test passes unchanged.
  2. **B-015: STOMP endpoint for the game messages.** `ping`, `create`, `join`, `leave`, `start`, `ready`, `chat` and `skip` on STOMP, with the Solo, Lobby, Rounds, Filter and Tier socket tests ported. Includes the concurrent-correct-answer ordering test.
  3. **B-016: STOMP connection lifecycle.** `rejoin`, replace, disconnect and grace, and heartbeats. The Reconnect, LateJoin and proxy-handshake tests are ported, and a leak test is added.
  4. **F-010: frontend on STOMP.**
  5. **B-017: remove the raw transport.** The `deploy/smoke.py` STOMP update is proposed to the owner rather than made directly (D-083, review M5).
- **High.**
  - **H1. Disconnect.** It is handled on the ordered inbound path: the STOMP DISCONNECT message under `setPreserveReceiveOrder`. A set of closed connection ids is also checked atomically at seat reservation. Together they mean a `join` still queued after a close can never seat a dead connection. Test: send, then close at once; no ghost seat is left.
  - **H2. Replaced.** WebSocket close codes still work under STOMP, so the server closes the old session with `CloseStatus(4001)`, through a `WebSocketHandlerDecorator` that keeps each session by connection id. stompjs reports the code in `onWebSocketClose`. The 4001 assertions stay as they are. Whether `stomp_dart_client` exposes the code is checked in M-6.
  - **H3. Test porting.** Each port cycle's spec carries a per-test table with three columns: kept as is, changed (with the reason), and removed (with its replacement). The critic reviews the rewritten tests as a diff against the originals.
    - **Payloads.** Handlers take `JsonNode`, so strings, decimals and explicit nulls are still rejected exactly as today.
    - **Unknown destinations.** A catch-all `/app/**` mapping answers `bad-message`.
    - **OutboundQueue's cap.** Replaced by Spring's send-buffer and time limits, with a new test for a slow consumer being closed.
- **Medium.**
  - **M2. Size check.** The 4096-byte limit is checked in a `ChannelInterceptor.preSend` on `clientInboundChannel` before conversion. The `too-large` reply is sent and the frame is not parsed. STOMP frame errors still produce an ERROR frame and a close; this is documented.
  - **M3. Heartbeats.** Server to client only (10 s), with no client-to-server heartbeat expected, so a throttled background tab is not dropped. The server heartbeat also keeps idle connections alive through Nginx.
  - **M4. Frontend fake.** `FakeStompClient` records `{destination, body}`. A test-side helper maps it to today's `{type,...}` for the existing assertions, and a separate unit test pins the destination mapping.
  - **M5.** See item 5: the `smoke.py` change is a proposal to the owner.
- **Nit.** "16 members" means 8 players plus up to 8 watchers during a game (`Lobby.MAX_GAME_MEMBERS = 2 * MAX_PLAYERS`).
- **D-085 framing.** D-076 says "`@MessageMapping` and topics". A2 drops topics for fairness and privacy, so the question is put to the owner in those terms.

## Owner decision
- D-085 (2026-11-04): private queue only (A2).
