# 152 · Take out the sound-offset button; sync the Flutter web like the React web (D-143)

## Goal
The owner asked why the app needs a manual sound-timing button and decided (D-143): take it out; the Flutter web runs in the same browser as the React web, so it should sync the same way, with no button.

## Done
- The offset button is gone from the game and lobby headers, and its overlay from app.dart. `SoundOffsetDialog`, `OffsetButton`, the controller's offset state, the localStorage store and the core manual offset are kept (a stored value still applies; nothing in the UI changes it).
- On web the player's output latency is now `AudioContext.outputLatency` of soloud's own context (finite, else 0), the React formula of frontend/src/game/session.js:240-241, read through `window.miniaudio.get_device_by_index(0).webaudio` (`mobile/lib/platform/audio_latency*.dart`). Before, soloud's web value was `outputLatency + baseLatency` (flutter_soloud-5.3.0 soloud_miniaudio.cpp:1709-1746). Non-web keeps soloud's sum.

## Evidence
- Verifier: `flutter analyze` no issues; app tests +121 all passed in a copy without flutter_soloud (absence of the button in lobby and game in ko/en/ja, the dialog tested standalone, a stored 100 ms offset still moves the next start, `finiteLatency` cases).
- Lead, the served build in headed Chromium after the 확인 tap: `{"state":"running","sampleRate":44100,"outputLatency":0.04,"baseLatency":0.01,"devices":1}`, so the app now uses 0.04 s where it used 0.05 s; no `싱크` label on the lobby screen.
- Not verified: the value on a real phone (phone check).
