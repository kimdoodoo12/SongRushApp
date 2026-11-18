# 142 audio-spike (S-009, from M-6, design 141, D-139/D-140)

## Goal
Find out whether a Flutter web client with `flutter_soloud` can load our Ogg Opus songs, seek into them and start them at the server's moment, before building the mobile client on it.

## What was built
A throwaway Flutter web app in `spikes/s009-audio/` (commit `e25a494`): it joins a room like the web client (join, token, ping/pong clock sync ported from `clockSync.js`, prepare, ready, start), downloads the song, loads it with `loadMem`, and starts it with `playScheduled` at `startAt - offset - output latency`, seeking for a late start, stopping at `deadlineAt`. It logs every step. `serve.vite.config.mjs` serves the build with the frontend's `/ws`, `/audio`, `/cover` proxy.

## Results
- flutter_soloud 5.3.0 on the web: `init`, Opus from memory (`loadMem`), `seek`, `playScheduled` and `stopScheduled` all work; `getHardwareOutputLatency` gives the browser's output latency.
- Desktop Chrome (headless), fake server: start error 2.5 ms on the engine clock (scheduled mode), 18.9 ms with a timer; a late start seeks correctly. A dart2js bug in the spike (`1 << 40` is 32-bit) was found and fixed.
- Local server, React web host + spike in desktop Chrome in the same room: join, prepare, ready (the server waited for both), start scheduled at the server's moment; offset 0.6 ms, RTT 1 ms.
- Android Chrome 136 on the emulator (`Medium_Phone_API_36`), controlled from the PC over CDP, React host on the PC: joined, clock offset −2220.7 ms found (the emulator's clock), song downloaded in 31 ms and decoded in 50 ms, start scheduled; the engine-clock check read −59.3 ms (the emulator's software audio, output latency ~395 ms).
- The owner's phone (Galaxy S25 Edge, Chrome) joined over USB (`adb reverse`), synced its clock (offset −2183 ms, RTT 2.3 ms), then lost the socket after a minute with the screen off before the song started. The owner preferred the emulator to phone tests.

## Decision
flutter_soloud (option A of design 141) is kept; the MediaCodec fallback (B-M3) is not needed. Not verified: the acoustic offset on a real phone (the speaker latency a phone adds beyond the engine clock). It moves to the release check of the mobile web (the owner plays a game with the web and a phone together), with the spike's `adj` parameter for tuning.

## Notes for the next items
- `flutter test` in a project that depends on flutter_soloud runs its native build hook, which needs the MSVC C++ build tools on this PC (missing). Keep the pure logic (protocol, session, clock sync) in a Dart package without soloud so its tests run anywhere, or install the VS Build Tools. The Linux CI runner has a toolchain.
- The single-threaded web build of SoLoud uses ScriptProcessor on the main thread; the AudioWorklet build needs COOP/COEP and HTTPS (an I-012 hosting question).
- A browser always sends `Origin`; the Flutter web build must be served from the same origin as `/ws` (the server rejects a cross-origin Origin).
- Chrome on Android may drop the socket when the screen is off or the tab is in the background: the real client's reconnect (F-M5) matters on phones.

## Measured by sound on the emulator (2026-11-17)
The owner asked the lead to check the sound itself rather than the engine's clock. Method: every local test song was rewritten as the same 70 s click track (a 3 ms 3 kHz burst at 72 irregular times); the React web (PC Chrome) and the spike (emulator Chrome) played it in the same room; the PC's speaker output was recorded by WASAPI loopback and the clicks were told apart by level (the emulator's output is about 40 times quieter: peak 0.02 against the PC's 0.5-0.85).
- A first loopback attempt and a microphone attempt found only the PC: the emulator's session (qemu, on the Realtek speaker, volume 100%) is that quiet. Reading its level meter showed it.
- Emulator only: the recorded gaps between clicks match the click pattern to 7.7 ms on average, so the faint output is the song.
- Both, no adjustment: emulator minus PC was +346 ms about 4 s into the song, growing to +467 ms 11 s later.
- Both, the spike started 300 ms earlier (`adj=300`): -4 ms, growing to +141 ms over the same span.
- So the start itself lands where it is aimed (the 300 ms shift moved it by about 300 ms), the emulator's true output delay is about 0.35 s more than it reports (`getHardwareOutputLatency` 395 ms), and its audio clock runs about 1.2 % slow (about +12 ms per second). Both are traits of the emulator's software audio; a phone's audio clock error is typically thousands of times smaller.

Consequences for F-M3: the player checks its playback position against the server-derived expected position during the song and corrects (seek) past a threshold, which also covers a device that misreports its output latency; and a manual offset setting (the spike's `adj`) is kept as a calibration option. The React web client corrects neither; the same check could be ported to it later.
