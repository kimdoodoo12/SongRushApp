# 148 · F-M4a mobile start and lobby screens

## Goal
The Flutter client has its first real screens: start (create or join, nickname dialog, credit line) and the lobby (host settings, guest view, chat, leave), in Korean, English and Japanese, phone-first, with the sunset colours and bundled fonts, connected to a real server through a WebSocket adapter.

## Acceptance criteria
1. `WebSocketGameSocket implements GameSocket` (web_socket_channel) in the app; URL from the page origin (`ws(s)://<host>/ws`) on web, overridable by `--dart-define=SONGRUSH_SERVER=...` for local runs.
2. Start screen: title, create button, join by room code (also from the URL path `/r/<CODE>` like the web), the nickname dialog (D-123), the credit line of D-023 in each language, a language switcher (ko/en/ja; default from the device locale, D-141).
3. Lobby screen: room code with copy, player list (host mark, own name), host's settings (answer format 채팅/객관식 with tile count by difficulty, game mode 선착순/순위전, song count 5-100 typed, D-137, and every other setting the web lobby has), guests see the host's settings read-only, lobby chat, start button for the host (disabled with the server's reason when it refuses, e.g. `not-enough-songs`), leave. Behaviour and wording mirror the React lobby (frontend/src/ui/… LobbyScreen.jsx, StartScreen etc.); texts come from frontend/src/i18n/{ko,en,ja}.json copied into ARB files (gen-l10n), keys kept the same where they exist.
4. Look: phone-first layout (360-430 px wide), the sunset palette from frontend/src/room.css `:root` copied as Dart constants (room.css itself untouched), fonts Jua, Gowun Dodum, IBM Plex Sans KR and JetBrains Mono bundled as assets from the official Google Fonts repository (OFL; licence files included).
5. State with Flutter built-ins only (ChangeNotifier / ValueListenableBuilder); the app owns one `Session` and its `RoundController` (cycle 147), created together, and a sound-unlock step on the first tap (web) before audio is needed.
6. Widget tests per screen with the core's `FakeGameSocket` (run in CI; this PC cannot run app tests, cycle 147). `flutter analyze` clean, `flutter build web` succeeds.
7. Manual run: backend + React web locally, the Flutter web build served on the same origin, a room created in the Flutter app is joined from the React web and vice versa; chat goes both ways; screenshots in the cycle doc folder `docs/cycles/148/`.

## Out of scope
Game and game-over screens (F-M4b), reconnect UI and lifecycle (F-M5), moods (later), serving to phones on the live site (I-012).

## Result
- App: `WebSocketGameSocket`, server URL from the page (`SONGRUSH_SERVER` override), sessionStorage token store in the web's `songrush.rejoin` format, start screen, nickname dialog, lobby (ticket, settings, seats, chat, start, leave), ko/en/ja via gen-l10n (95 keys copied from the web's dictionaries by `mobile/tool/sync_l10n.py`), sunset palette, bundled fonts (google/fonts commit recorded in `mobile/assets/fonts/README.md`). One `AppController` (ChangeNotifier) owns the Session and its RoundController.
- Verifier: core `dart test` +171; `flutter analyze` no issues; the 31 widget tests passed in a copy of the app without flutter_soloud (`+31: All tests passed!`); frontend and room.css untouched.
- Manual run (criterion 7), backend jar with `--spring.web.resources.static-locations=file:<repo>/mobile/build/web/` on 8080 and the React dev server on 5173, Playwright Chromium, phone viewport 390x844 for the app:
  - Room made in the app (code EPXSS), joined from the React web: both see both players; chat both ways (`app sees web chat: True`, `web sees app chat: True`).
  - Room made in the React web (code ACVQB), joined by the app through `/r/ACVQB`: the app shows the host's settings read-only and follows a change (song count 11).
  - Screenshots: `docs/cycles/148/1-app-start.png` … `8-app-lobby-guest-en.png`.
- Lead fix: the page title was the template's `songrush_mobile`; now `SongRush`.
- Follow-ups: the bundled fonts are 18 MB (Gowun Dodum 7.2 MB) and load at start, so subset them before phones use the live site; the web dictionaries still say 5~30 in `error.bad-song-count` (D-137 is 5-100).
