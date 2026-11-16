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
