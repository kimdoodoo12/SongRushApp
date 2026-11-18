# songrush_mobile

The SongRush Flutter client (web target first; Android later).

- `lib/`: the app (screens, Flutter-only code).
  - `main.dart` wires the real adapters; `app.dart` is the MaterialApp.
  - `app/`: the `AppController` (one `ChangeNotifier`: screen, nickname dialog, language, lobby selection, the room `Session` and its `RoundController`).
  - `ui/`: the start screen, nickname dialog, lobby and chat, and the sunset palette and fonts (`ui/palette.dart`).
  - `net/`: `WebSocketGameSocket` (web_socket_channel), the server URL, `HttpFetcher`.
  - `store/`: browser storage (localStorage, sessionStorage) behind `Prefs`, with a memory fallback; the rejoin entry and the manual sound offset (`songrush.soundOffset`) over it.
  - `platform/`: the page's own APIs on the web (reload check, Screen Wake Lock), no-ops elsewhere.
  - `audio/`: the flutter_soloud player (cycle 147).
  - `l10n/`: ARB files copied from the web's dictionaries, and the gen-l10n output.
- `packages/songrush_core/`: pure-Dart game logic, tested with `dart test` (no Flutter, no native toolchain).
- `assets/fonts/`: Jua, Gowun Dodum, IBM Plex Sans KR, JetBrains Mono (OFL); see its README.
- `tool/sync_l10n.py`: copies the needed keys from `frontend/src/i18n/*.json` into `lib/l10n/*.arb`.

Flutter lives at `C:\src\flutter\bin` on the dev PC (not on PATH):

```
cd packages/songrush_core && C:\src\flutter\bin\dart.bat test
C:\src\flutter\bin\flutter.bat analyze
C:\src\flutter\bin\flutter.bat test
C:\src\flutter\bin\flutter.bat build web --release --base-href /m/ --no-web-resources-cdn
```

The build flags match the `Dockerfile`: the site serves the build at `/m/`,
and `--no-web-resources-cdn` bundles CanvasKit in `build/web/canvaskit/`
rather than loading it from `www.gstatic.com` (cycle 155). In Git Bash, set
`MSYS_NO_PATHCONV=1` first, or `/m/` is rewritten into a Windows path.

`flutter test` needs the flutter_soloud native build hook, which does not run
on the dev PC (no MSVC, cycle 147); the app tests run in CI on Linux. The
widget tests use the core's fakes and never import flutter_soloud.

## Texts

The screens' texts come from the web's dictionaries with the same keys in
camelCase (`lobby.songCount` is `lobbySongCount`); each key's `@description`
in `lib/l10n/app_ko.arb` names the web key. To add keys, list them in
`tool/sync_l10n.py`, then from `mobile/`. Texts the web has no key for (the
sound offset setting, cycle 150) are in the script's `APP_KEYS`, in ko, en
and ja, so a rerun keeps them:

```
python -I tool/sync_l10n.py ../frontend/src/i18n lib/l10n
C:\src\flutter\bin\flutter.bat gen-l10n
```

## Running against a local server

The app opens its socket at `/ws` on the page's own origin (`ws://` on http,
`wss://` on https), as the web client does. The backend accepts WebSocket
connections from its own origin only, so the simplest local run serves the
Flutter build from the backend itself:

1. Build: `C:\src\flutter\bin\flutter.bat build web --no-web-resources-cdn` (from `mobile/`). The output is `mobile/build/web/`.
2. Start the backend as usual, adding a static location for that folder (an absolute `file:` path ending in `/`), for example from `backend/`:

   ```
   .\gradlew.bat bootRun --args="--spring.web.resources.static-locations=file:<repo>/mobile/build/web/"
   ```

   with `<repo>` the repository's absolute path in forward slashes (such as `C:/src/songrushapp`).

   or `java -jar <backend jar> --spring.web.resources.static-locations=file:<repo>/mobile/build/web/`.
3. The Flutter app is then at `http://localhost:8080/`, and a room link `http://localhost:8080/r/<CODE>` opens it with the code filled in (the backend forwards `/r/<code>` to `index.html`).
4. The React web runs as usual with `npm run dev` in `frontend/` (`http://localhost:5173/`); its dev server proxies `/ws` to the same backend, so a room made in one client can be joined from the other.

`--dart-define=SONGRUSH_SERVER=ws://<host>:<port>/ws` at build time points the
socket elsewhere (and `/audio/`, `/cover/` to the same host over http). The
backend's same-origin check refuses a socket from a page on another origin,
so this works only against a server that accepts that origin.

The first tap on 확인 in the nickname dialog also starts the audio engine
(the browser's sound unlock); the host's start tap retries it if that failed.

## Reconnect and lifecycle (cycle 150)

- A dropped socket reconnects with the token (the core's back-off, 30 s);
  the lobby and game show the reconnecting line meanwhile. A lost room (no
  rejoin within 30 s, or a 4001 from another window) shows the disconnected
  screen with 처음으로; `room-closed` and `rejoin-failed` go to the start
  screen with their text, as on the web.
- A page reload rejoins the room of the `songrush.rejoin` sessionStorage
  entry (the web's format); any other page start drops it.
- Visibility comes through Flutter's `AppLifecycleListener` (on the web the
  engine reports `visibilitychange` as the app lifecycle). Hiding changes
  nothing; on return a reconnecting session tries at once, and a song still
  playing is put right by the drift check.
- After a reload no tap has unlocked the sound: the game shows 탭하여 소리
  켜기, and the tap loads the current song again and joins it where it is.
- The screen is kept awake during a game where the browser has the Screen
  Wake Lock API.
- The header's tune button opens the manual sound offset (-500..+500 ms,
  steps of 10), stored in localStorage and used from the next song.
