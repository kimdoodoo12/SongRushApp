# 141 plan-mobile (M-6, D-063, D-064, D-139)

## Milestone (verbatim)
> **M-6** Mobile app (D-063): a mobile-only client built with Flutter that plays against the same Spring Boot server and WebSocket protocol as the web client. Planning it first needs a design cycle (Flutter project layout, audio playback and clock sync on Android/iOS, store distribution). Order and platforms: after M-4, Android and iOS (D-064).

D-139 (owner, 2026-11-16) sets the order and scope: a separate Flutter client in `mobile/`; the web stays React; Android first; an APK for now (stores later); the first version is the core game (create/join, lobby settings, synchronized play, chat and choice answers, 선착순 and 순위전, skip, scores, leaving); phone-first screens with the web's colours; the eight moods later.

## Questions
1. Does the server need changes for a native client?
2. What protocol must the app speak, and how do we keep web and app in step when either changes?
3. How does the app start the same song at the same moment as everyone else (the hard part)?
4. Project layout, tests and CI.
5. How is the APK built and handed out?

## Findings
**Server (Q1): no change needed.** Raw WebSocket `/ws`, JSON text frames (backend/src/main/java/com/songrush/ws/WebSocketConfig.java:22), no Origin allow-list, no CORS, no cookies, no User-Agent checks; auth is the opaque rejoin token and opaque `/audio/<token>` and `/cover/<id>` URLs. Spring's default rejects a cross-origin `Origin` header but allows none; a Dart client must not send one (unconfirmed default of `dart:io`; checked in the first implementation item). HTTP `/audio` and `/cover` answer `no-store`, and in S3 mode a 302 to a 60 s presigned URL (AudioController.java:97-110, CoverController.java:150-165): the app must follow redirects and keep what it downloaded in memory.

**Protocol (Q2).** It exists only in code: frontend/src/game/session.js (1236 lines) and GameSocketHandler.java / Lobby.java / Room.java. Client sends `ping, create, join, rejoin, start, lobbySettings, ready, skipVote, pick, chat, roomTheme, leave`; the server sends `pong, token, joined, roster, lobbySettings, settings, snapshot, prepare(holdMs, choices), start(startAt, deadlineAt, serverTime), chat, correct, wrong, pick-result, pickState, skipState, reveal(titles, coverUrl, correctChoice, nextChoices, ranking, playUntil), scores, gameOver, error(code)`. Rejoin: token in storage, back-off 0.5-10 s up to 30 s (the server's grace), close code 4001 means "replaced, do not reconnect". `contracts/` holds only title-normalisation vectors.

**Sync (Q3).** The web client: clock offset from ping/pong (8 samples, median of the 3 lowest RTT; frontend/src/net/clockSync.js), bursts on join, after each load, every 30 s and on drift; decode the whole file before `ready`; `start(when, offset)` / `stop(stopAt)` on the Web Audio clock with the output latency subtracted (frontend/src/audio/playback.js:11-19); seek for a late start; a provisional start corrected by stop-and-reschedule; the reveal window keeps playing to `min(playUntil, deadlineAt)`; `holdMs` keeps the old reveal while the next song loads. Audio is **Ogg Opus** (data/songrush_data/audio.py), which Android decodes natively; iOS lacks it before 18.4.

Flutter options (research, sources in the cycle notes):
- `just_audio` (ExoPlayer): Opus yes, but start is play()+timer, jitter tens to hundreds of ms. Too coarse alone.
- `flutter_soloud` 5.3 (SoLoud engine, MIT, active): bundles Ogg/Opus decoders (so iOS later does not depend on the OS), engine-clock scheduling (`playClocked`/`playScheduled`). Unconfirmed: loading Opus from memory/file on the non-streaming path, seeking a loaded sound, an absolute-time start.
- A custom Kotlin channel (MediaCodec decode to PCM, AudioTrack with write-position start): full control, 1-2 weeks, Android only.

## Options and decision
**Audio (the one real choice).**
- A. `flutter_soloud` behind a small `Player` interface (load bytes, start at local time T with offset, stop at T2, output latency), after a spike that proves Opus load, seek and start error on real devices. Recommended: least code, carries to iOS.
- B. Custom MediaCodec + AudioTrack channel behind the same interface: fallback if the spike fails the 50 ms target.
- C. `just_audio`: rejected for synced play.
Decision: A, gated by the spike (item M-S1); B is the planned fallback. The `Player` interface keeps the session logic independent of the choice.

**Protocol in step (Q2).** Write `contracts/protocol.md` (every message and field, from the code; `roomTheme` marked legacy since D-136, the app ignores it) and `contracts/messages/*.json` example frames captured from the existing socket tests (the test client writes the frames it receives), so the examples are real by construction. The Dart tests decode every example. A feature that changes a message changes the contract in the same cycle; CLAUDE.md gets one line saying so.

**Layout and tests (Q4).** `mobile/` Flutter app (Android only at first), Dart with `flutter_lints`; `lib/net` (socket, protocol models), `lib/game` (session state machine as a pure Dart class with an injected clock and socket, the port of session.js), `lib/audio` (`Player` interface + soloud impl), `lib/ui` (screens). Unit tests with `flutter test` on fakes, as the web does with Vitest; widget tests for screens. `flutter analyze` as lint. CI: a GitHub Actions job for `mobile/` (`flutter test`, `flutter analyze`, `flutter build apk --debug`). CLAUDE.md commands table gets a `mobile` row; the Stop hook gets a `mobile` area.

**Fairness (CLAUDE.md Multiplayer rules).** The app follows the web's timing exactly: audio only from `prepare` (D-119: the early prepare one reveal pause before), covers from the reveal's `nextChoices` (D-133), nothing earlier; a modified app is the same accepted risk as a modified web client. Downloaded audio stays in memory (or a temp file deleted after the song), as the web keeps it in a decoded buffer (D-057/D-067).

**Lifecycle rule (F-M5).** On going to the background the app keeps the socket while it can and lets audio stop; on return (or after the OS killed the socket) it rejoins with the token and late-starts from the snapshot, as a web reload does. Audio focus loss (a call) mutes; the screen stays awake during a game.

**APK (Q5).** A release APK signed with an upload key kept outside the repo (path in an env var); the owner keeps the key. Until then, debug APKs for testing. Server URL `wss://animeopguessr.com/ws` by default, overridable at build time for a local server.

## Resulting items
1. **S-009 / M-S1** audio spike (mobile): a throwaway Flutter app that joins a room on the live or a local server, downloads the song, loads it with flutter_soloud, starts it at the server's `startAt` with a seek, and shows its own engine-clock error and the output latency. The pass test is acoustic, not the engine's own clock (which can read 0 ms while the speaker is late): on the owner's phone, the app and the web client in the same room play together and the owner reports whether they sound together (no echo), as in I-003; the lead first checks the functional parts on the emulator (Opus loads from memory or a temp file, seek works). Needs the owner. Fail: item B-M3.
2. **I-010** bootstrap `mobile/`: `flutter create` (Android), lints, one passing unit test, `flutter analyze`, a CI job limited to changes in `mobile/` (`paths:`), the CLAUDE.md commands row and the Stop hook area. Acceptance: `flutter test` and `flutter analyze` pass locally and in CI; `flutter build apk --debug` produces an APK.
3. **C-020** protocol contract: `contracts/protocol.md` and example frames captured by the existing socket tests; CLAUDE.md line on keeping it current. Acceptance: every message type in GameSocketHandler/Lobby/Room appears in the contract and has a captured example; `./gradlew test` passes.
4. **F-M1** net and session core (Dart): socket wrapper (no Origin header; checked against a local server), message models from the contract examples, the session state machine for create/join/rejoin/leave, roster, lobby settings, chat, errors, with the 4001 rule. Acceptance: unit tests on a fake socket for each flow; a manual run joins a room created in the web client.
5. **F-M2** clock sync and playback planning (Dart port of clockSync.js and playback.js, pure functions) with the web's test vectors. Acceptance: the same inputs give the same offsets and `when/offset/stop` plans as the web tests.
6. **F-M3** audio playback: the `Player` interface and the soloud implementation; prepare (download, decode, `ready` after audio and covers), start/stop, late start, provisional correction, reveal window, `holdMs`. Acceptance: unit tests with a fake `Player`; on the emulator the app logs each start's planned local time next to the server's `startAt` converted with its offset, and the lead compares it with the web client's log for the same song (within 50 ms); the acoustic check is the owner's, as in M-S1.
6a. **B-M3** (only if M-S1 fails) the fallback player: a Kotlin plugin decoding Ogg Opus with MediaExtractor/MediaCodec to PCM (B-M3a) and an AudioTrack player with a write-position start and output-latency correction (B-M3b), behind the same `Player` interface.
7. **F-M4a** start and lobby screens: start (create/join, nickname dialog, the credit line of D-023), lobby (host settings incl. song count 5-100 typed, guest view, chat, leave). Phone-first layout with the sunset colours and fonts. Acceptance: widget tests per screen; the lead creates a room in the app and joins it from the web.
7b. **F-M4b** game and game-over screens: time bar, chat answer, choice tiles with marks, skip with count, pick count, reveal card ko/en/ja/romaji, scores, 나가기 with confirm, game over with the ranking and back to the lobby. Acceptance: widget tests per screen; the lead plays a full game app vs web on the emulator.
8. **F-M5** reconnect and lifecycle, by the rule above: rejoin with back-off and the snapshot (incl. the reveal window, and a late start mid-song), the 4001 rule, background and return, audio focus loss mutes, screen awake during a game. Acceptance: unit tests for each rule on fakes; the lead toggles network and backgrounds the app on the emulator.
9. **I-011** release APK: signing config reading an owner-kept key from env, version code, app name and icon, a build script. Needs decision: the owner creates and keeps the key (or approves a lead-generated one stored outside the repo).

Later (not in M-6's first version): moods, iOS (soloud carries the decoder; needs a Mac or cloud build and an Apple account), store listing.

## Open for the owner
- The app's UI language: Korean only for the first version, or ko/en/ja like the web. Error texts and every label depend on it.

## Review
One round. No Blocker; three High, five Medium, two Nits, all taken.
- High: the spike's pass test compared the engine with its own schedule; it is now an acoustic check on the owner's phone against the web client, and F-M3's check compares logged start times with the web's.
- High: the screens item was too big; split into F-M4a (start, lobby) and F-M4b (game, game over).
- High: the UI language and the D-023 credit line were missing; the credit line is in F-M4a, the language is asked.
- Medium: a conditional fallback item B-M3 (a/b); a fixed lifecycle rule for F-M5; `roomTheme` marked legacy in the contract; the contract's examples captured from socket tests instead of a new key-checking test; a fairness section.
- Nits: the CI job is limited to `mobile/` changes; owner-device items confirmed.
