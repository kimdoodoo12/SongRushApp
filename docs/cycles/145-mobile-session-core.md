# 145 · F-M1 mobile net and session core

## Goal
The pure-Dart core (`mobile/packages/songrush_core`) can talk the SongRush protocol: typed models for every message in `contracts/protocol.md`, a socket abstraction with a fake for tests, and a session that handles create, join, rejoin and leave, the roster, lobby settings, chat, errors, the rejoin token and the 4001 rule, the way the React client does (frontend/src/game/session.js, state.js).

## Acceptance criteria
1. Models: one Dart type per client and server message in `contracts/protocol.md`, decoded from and encoded to JSON. A test decodes every `contracts/messages/*.json` (read from the repo at test time) and re-encodes it without losing a field the contract lists. An unknown `type` decodes to an `UnknownMessage`, never throws.
2. `GameSocket` interface (connect, send, incoming stream, close with code) and a `FakeGameSocket` for tests. The real adapter (web_socket_channel) is in the app, not in core; it sends no Origin header from the app on non-web targets (web cannot control it; noted).
3. `Session` (a ChangeNotifier-free plain Dart class exposing a `Stream`/listener of immutable `SessionState`): create and join (nickname, room code), the `token` stored through a `TokenStore` interface (in-memory fake in tests), rejoin with back-off 0.5 s doubling to 10 s, giving up after 30 s (the server's grace), never reconnecting after close code 4001, leave (sends `leave`, clears the token). Roster, host flag, lobby settings, lobby and game chat lines, and `error` codes are reflected in the state. Game messages (prepare, start, reveal, …) are exposed as typed events for later items, not yet acted on.
4. Tests on the fake socket for each flow: create, join, duplicate nickname suffix shown as the server sends it, chat in and out, lobby settings from host, an error code, rejoin after a drop (token sent, snapshot accepted), back-off timings (with an injectable clock/timer), 4001 stops reconnecting, leave clears the token.
5. `dart analyze` clean and `dart test` passes in the core; `flutter analyze` and `flutter test` still pass in `mobile/`.

## Out of scope
Clock sync and playback planning (F-M2), audio (F-M3), screens (F-M4), app lifecycle (F-M5). A manual run against a local server is deferred to F-M4a, where the app first has screens.

## Result
- Core: typed models for the 12 client and 20 server messages (sealed classes, `UnknownMessage` for unknown types, `raw` kept), `GameSocket` interface, `FakeGameSocket` in `package:songrush_core/testing.dart`, `Session` with create, join, resume, rejoin back-off (0.5, 1, 2, 4, 8, 10 s; 5 s per attempt; give-up at 30 s), 4001, leave, token cleared as in session.js. Game frames go out on `messages` untouched.
- Verifier: core `dart analyze` no issues, `dart test` +79 all passed; app `flutter analyze` no issues, `flutter test` +1. Reconnect logic compared line by line with session.js (waits 47/1085, attempt limit 1096-1101, give-up 1080/1115-1122, 4001 1127-1132, error close 406-409): no mismatch. The contract test decodes all 32 examples and requires `toJson()` to equal the file exactly.
- Moved to F-M4a: the real `web_socket_channel` adapter (criterion 2's app half), built when the app first has screens to run against a local server.
- Minor difference: a `token` before `joined` (not sent by the server) is kept in memory only, where session.js stores it with a null code.
