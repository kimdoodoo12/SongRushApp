# songrush_mobile

The SongRush Flutter client (web target first; Android later).

- `lib/`: the app (screens, Flutter-only code).
- `packages/songrush_core/`: pure-Dart game logic, tested with `dart test` (no Flutter, no native toolchain).

Flutter lives at `C:\src\flutter\bin` on the dev PC (not on PATH):

```
cd packages/songrush_core && C:\src\flutter\bin\dart.bat test
C:\src\flutter\bin\flutter.bat analyze
C:\src\flutter\bin\flutter.bat test
C:\src\flutter\bin\flutter.bat build web
```
