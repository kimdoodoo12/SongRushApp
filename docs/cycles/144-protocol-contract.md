# 144 · C-020 protocol contract

## Goal
Write down the WebSocket protocol the React web and the Flutter client both speak, with real example frames captured from the backend's own socket tests, so the Flutter client is built against the contract rather than by reading session.js.

## Acceptance criteria
1. `contracts/protocol.md` lists every message type the client sends and the server sends (GameSocketHandler, Lobby, Room), each with its fields, types, when it is sent, and its rules (who receives it; what is withheld before the reveal). Close codes (4001) and the HTTP `/audio`, `/cover` endpoints are covered. `roomTheme` is marked legacy (D-136).
2. `contracts/messages/<type>.json` holds one real example per server message type, captured from the socket tests; client message examples are written by hand from the code and marked as such in protocol.md.
3. Capture is opt-in: the test socket client writes received frames only when the system property `songrush.captureFrames` (a folder) is set; normal `./gradlew test` writes nothing and is unchanged. A Gradle-passed property or a documented command reproduces the capture.
4. A backend test checks that every server message type named in protocol.md has an example file and that each file parses as JSON with a matching `type`; it fails if a type is added to the contract without an example.
5. CLAUDE.md: one line that a change to a message changes `contracts/protocol.md` and its example in the same cycle.
6. `.\gradlew.bat test` passes.

## Out of scope
Changing any message, the Dart models (F-M1).

## Result
- `contracts/protocol.md`: 12 client and 20 server message types (server `roomTheme` added; the backlog list missed it), close codes 4001 and 1008, `/audio` and `/cover`, the leak rules. 20 server examples captured from the socket tests (largest frame per type), 12 client examples hand-written (tests build client frames in code).
- Capture: `.\gradlew.bat cleanTest test "-Dsongrush.captureFrames=<absolute path to contracts/messages>"` from backend/; TestClient writes only when the property is set.
- `ProtocolContractTest` (5 tests): every heading has an example with a matching `type`, every example has a heading, and the headings equal the types the code handles and sends (regex over GameSocketHandler, Lobby, Room).
- Verifier: `.\gradlew.bat cleanTest test` BUILD SUCCESSFUL, 1306 tests, 0 failures (ProtocolContractTest 5/5); data `pytest` 1095 passed, 1 skipped; a normal run left all 32 example hashes unchanged; `git diff --stat backend/src/main` empty; no absolute paths in the examples.
- Gap: no socket test rejoins a choice room or a reveal window, so `snapshot.json` shows a chat room in PREPARE; the optional song keys are documented in the table only.
