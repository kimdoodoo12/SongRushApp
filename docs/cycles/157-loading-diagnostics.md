# 157 · The mobile loading screen explains itself; the install name is SongRush

The owner's phone (Android Chrome) stays on "불러오는 중" at https://animeopguessr.com/m/, while the emulator's Chrome 136 shows the app after 2.9 s (compressed first load about 4.4 MB, cycle 156). Chrome also offered to install "songrush_mobile" (the Flutter template's manifest name).

- `mobile/web/index.html`: errors recorded from the start (failed script/link/img loads, unhandled rejections, console.error, last 10); the overlay is removed on `flutter-first-frame` or, as a safety net, once Flutter's canvas has a size; after 10 s it says "오래 걸리고 있어요… / Still loading…", shows a 다시 시도 link and a small box with the browser, whether `flutter-view`/`_flutter` exist, `crossOriginIsolated` and the recorded errors.
- `mobile/web/manifest.json` and the iOS title: "SongRush", colours #1c0f1a.
- Evidence (implementer, local Chromium 148 serving the release build under /m/): normal load removes the overlay at 0.3 s with no errors; with main.dart.js blocked the 10 s message shows "load failed: <script> …/m/main.dart.js"; with the first-frame event swallowed the poll still removes the overlay; the canvas gets its size in the same frame as the event, so no early removal.
- Open: the cause on the owner's phone. After this deploy the screen itself will show it, or the phone can be connected over USB for Chrome's remote console.
