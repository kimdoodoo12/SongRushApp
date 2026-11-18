# 147 · F-M3 mobile audio playback

## Goal
The Flutter client plays each song at the same moment as everyone else: a `Player` interface, a round controller in core that drives it from the server's messages exactly as session.js drives Web Audio, and a flutter_soloud implementation in the app (web first), with the in-song drift correction and a manual offset from cycle 142.

## Acceptance criteria
1. Core `Player` interface (no engine import): `load(bytes)`, `play({when, offset, stopAt})` in the player's own clock (seconds), `stop()`, `setStopAt`, `positionSec`, `nowSec`, `outputLatencySec`, `dispose`. A `FakePlayer` in `package:songrush_core/testing.dart` records calls and lets tests set position and clock.
2. Core `RoundController` (pure Dart, driven by `Session.messages` and `SessionState.clock`): on `prepare` downloads the audio (and covers: `choices[].coverUrl`, plus reveal `nextChoices` preloaded and reused) through an injectable `Fetcher`, loads it, then `sendReady`; `ready` only after audio and covers. On `start` uses `startOffsetFor` and `planPlayback` (with output latency and the manual offset); a late start seeks in; a provisional start (no estimate yet) is corrected by stop-and-reschedule when `correctedStartOffset` says so. `reveal` keeps the music to `revealWindowEnd(min(playUntil, deadlineAt))`; `holdMs` keeps the old reveal while the next song loads (as session.js). Snapshot rejoin mid-song starts late. Leaving or a new prepare stops the old sound.
3. In-song check: while playing, every 1 s it compares `expectedPositionMs` with the player's position and seeks by `checkDrift` (40 ms, at most once per 2 s), recording the last correction time.
4. Manual offset: a value in ms (default 0, range -500..+500) kept through an injectable settings store; changing it applies from the next plan.
5. Tests for 2-4 on fakes with fake_async: prepare → ready ordering, cover preload reuse, start on time, late start, provisional correction, reveal window stop, holdMs, snapshot late start, drift correction both ways and rate limit, manual offset.
6. App: `SoloudPlayer implements Player` following spikes/s009-audio (loadMem of Ogg Opus, playScheduled / seek, getHardwareOutputLatency) and an `HttpFetcher` (follows redirects, no cache). `flutter analyze` clean and `flutter build web` succeeds. If flutter_soloud's native build hook makes the app's `flutter test` impossible on this PC (no MSVC), the app's widget tests keep running in CI (Linux) and the local check is analyze + build web; this is written in CLAUDE.md's mobile row.

## Out of scope
Screens and the sound-unlock button (F-M4/F-M5), reconnection rules beyond the snapshot start (F-M5).

## Result
- Core: `SongPlayer` (named so because `Player` is the roster row; has `seek` for the drift check), `Fetcher`, `PlaybackSettingsStore` (manual offset -500..+500 ms), and `RoundController` mirroring session.js 280-1075 (prepare, cover preload and reuse, ready after audio and covers, start, provisional correction, late start, reveal window, holdMs, snapshot, abandon, game over, reconnect). One player per round so a held reveal keeps playing while the next song loads. The drift check runs every 1 s.
- App: `SoloudEngine`/`SoloudPlayer` (spike's engine-clock method over 300 ms, `playScheduled` for a future start, paused play + seek for a late one, `stopScheduled`), `HttpFetcher` (plain GET, redirects followed, no cache, 60 s timeout), soloud's `init_soloud.js` in web/index.html.
- Verifier: core `dart analyze` no issues, `dart test` +171 all passed; app `flutter analyze` no issues, `flutter build web` built; app `flutter test` fails only on flutter_soloud's native hook ("No compiler configured on host 'windows_x64'"), so app tests run in CI. Fairness: nothing plays on prepare; the only `play` call needs a loaded round with a start.
- Not verified by running: the soloud player on a real browser or phone (F-M4a's manual run). A start less than one mix buffer away may land a buffer late; the drift check corrects it within 1 s.
