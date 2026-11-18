# 162 · On-screen keyboard readout (?debug=1)

Cycle 161's fix did not help on the owner's phone (Chrome 157, Samsung keyboard): after typing and hiding the keyboard the game still stays at the top. The emulator behaved differently, so the phone's own numbers are needed.
- `mobile/web/index.html`: with `?debug=1` only, a small top-right readout (no pointer events) refreshed every 250 ms and on viewport/focus events: inner size, visualViewport size/offset/scale, flutter-view height, scrollY, the focused element, the app's computed inset (`__srInset`) and the inset it applied (`__srMqInset`), and the last 6 visualViewport height changes with timestamps.
- `keyboard_inset_web.dart` / `app.dart`: publish those two values.
- Evidence: `flutter analyze` no issues; app tests +160 in a copy; web build OK; local Playwright: overlay with `?debug=1` (also `/m/r/abcde?debug=1`), none without; a viewport resize 800→500→800 logged. The React redirect keeps the query, so `/?debug=1` on a phone lands on `/m/?debug=1`.
