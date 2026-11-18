# 159 · Covers keep their size while typing; the first screen waits for less

Owner on a real phone (Android Chrome 157, LTE): the app takes about 10 s to appear, and in a choice room the covers shrink drastically when the keyboard opens. The Flutter web at /m/ is a test channel; the product is the Android app (owner, 2026-11-18).

- Keyboard: in a choice room with the keyboard up the chat box is hidden and the tiles keep their keyboard-down size (at 390x844 exactly; on small screens as large as fits, the disc/hint row hidden first, covers at least 48 px where possible). Two tests that required chat lines with the keyboard up were replaced by checks of the new rule; new tests at 390x844 and 360x640 for 4/6/8 tiles.
- First load: the SoLoud script and wasm are no longer loaded by index.html; they load on the first touch (or at the 확인 tap) through `mobile/lib/platform/soloud_script*.dart`, and the engine starts after them; if the browser keeps the sound context suspended, the cycle-150 sound prompt stays. IBM Plex Sans KR and JetBrains Mono load after the first frame (`late_fonts.dart`).
- Measured (implementer, release build under /m/, Pixel 7 profile): before the app runs, 18 files 13.9 MB raw → 11 files 11.5 MB raw (about −950 KB compressed: soloud −807 KB, fonts −144 KB); after 확인 the engine starts and its context is running, also with the script held back 8 s.
- Lead: app tests +145 all passed in a copy without flutter_soloud; `flutter analyze` no issues.
- Not verified here: whether the late engine start keeps Chrome's audio gesture on a real phone (Playwright's Chromium never suspends audio); the sound prompt is the fallback.
