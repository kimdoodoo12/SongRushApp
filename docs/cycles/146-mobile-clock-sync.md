# 146 · F-M2 mobile clock sync and playback planning

## Goal
Port the web's clock sync (frontend/src/net/clockSync.js) and playback planning (frontend/src/audio/playback.js) to pure Dart in songrush_core, giving the same results on the same inputs, and add the in-song drift check that cycle 142's measurement called for.

## Acceptance criteria
1. `ClockSync` in core: the same sampling, filtering (8 samples, median of the 3 lowest RTT) and guards as clockSync.js; every case in clockSync.test.js and clockSyncGuards.test.js is ported with the same inputs and expected numbers.
2. Playback planning in core: the same `when / offset / stop` plan as playback.js (output latency subtracted, late start seeks in, provisional start and correction, reveal window to `min(playUntil, deadlineAt)`), as pure functions over numbers (no audio engine); every case in playback.test.js and playbackLatency.test.js ported with the same expectations.
3. In-song drift check (new, cycle 142): a pure function that, given the expected song position (from server time and the clock offset) and the player's reported position, returns whether to correct and the seek target. It corrects only past a threshold (default 40 ms, constant in core) and not more often than once per 2 s; a manual offset setting (ms, positive = play later) is applied to every plan. Tests for: within threshold, past threshold both ways, rate limit, manual offset.
4. Session wiring: the session starts a ping burst on joined and on rejoin as session.js does (the bursts after each load and every 30 s / on drift are listed and wired where session.js does them, or left to F-M3 with a note if they depend on audio load), feeds pongs to `ClockSync`, and exposes the offset in `SessionState`. Tests on the fake socket with fake_async.
5. `dart analyze` clean, `dart test` passes; `flutter analyze` and `flutter test` in mobile/ pass.

## Out of scope
The audio engine (F-M3), screens.

## Result
- Core: `ClockSync` and `estimateClock` (clockSync.js), `planPlayback` (playback.js), the session.js helpers `startOffset`, `correctedStartOffset`, `revealWindowEnd` as pure functions, and the new `expectedPositionMs` and `checkDrift` (corrects past 40 ms, at most once per 2 s, never before the start; manual offset positive = later, applied in both the plan and the expected position). The session bursts 8 pings on joined and 3 every 30 s, clears the clock on a drop, and offers `sendReady` (ready + 3 pings) and `startOffsetFor` (resync on drift) for F-M3 to call.
- Verifier: core `dart analyze` no issues, `dart test` +143 all passed; app `flutter analyze`, `flutter test` pass. Every JS case of clockSync.test.js (12), clockSyncGuards.test.js (7), playback.test.js planPlayback (5) and playbackLatency.test.js (6) has a Dart twin; 5 spot-checked with identical inputs and expectations. canPlayOggOpus and loadTrack (audio engine) are left to F-M3. No logic mismatch found line by line.
- Dart detail: `List.sort` is not stable, so rtt ties are broken by input order to keep the JS result.
