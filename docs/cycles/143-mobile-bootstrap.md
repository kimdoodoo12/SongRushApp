# 143 · I-010 bootstrap the mobile client (web first)

## Goal
A Flutter client skeleton in `mobile/` for the web target, with its game logic in a pure-Dart package that tests without the C++ toolchain, wired into CI, the commands table and the Stop hook.

## Owner's choices (2026-11-17, all "as recommended")
- Layout: `mobile/` app + `mobile/packages/songrush_core` (pure Dart: protocol, session, clock sync, playback planning; no Flutter or flutter_soloud import). Reason: flutter_soloud's native build hook needs MSVC for `flutter test` on this PC; `dart test` on the core package does not.
- State: Flutter built-ins only (ChangeNotifier / ValueListenable).
- Fonts: bundled as assets (done with the screens, F-M4a), no runtime download.

## Acceptance criteria
1. `mobile/packages/songrush_core`: `dart test` passes (at least one real test), `dart analyze` clean.
2. `mobile/`: `flutter analyze` clean; `flutter build web` succeeds; the app depends on songrush_core by path and shows a placeholder start screen.
3. The app has no flutter_soloud dependency yet (added with F-M3), so `flutter test` passes on this PC too; at least one widget test.
4. CI: a `mobile` job in `.github/workflows/ci.yml` (Flutter stable pinned to the local 3.50.0) running core `dart test`, app `flutter analyze`, `flutter test`, `flutter build web`, limited to changes under `mobile/` or the workflow.
5. CLAUDE.md commands table has a verified `mobile` row; the Stop hook runs the core tests when `mobile/` changes.
6. Generated build output (`build/`, `.dart_tool/`) is gitignored; no platform folders other than `web/`.

## Out of scope
Android target and APK (I-011), serving to phones (I-012), any real screen, audio, networking.

## Result
Verifier (command output, this PC): core `dart analyze` no issues, `dart test` +4 all passed; app `flutter analyze` no issues, `flutter test` +1 all passed, `flutter build web` built; `git status -uall mobile` lists 25 source/config/lock files, no build output, web/ the only platform folder; ci.yml parses with three jobs (backend, frontend, mobile), the mobile steps guarded by a `mobile/**` paths filter; the Stop hook diff is additive. The CI job itself runs first on the next push of dev.

Notes: the core package's `pubspec.lock` is committed so CI resolves the same test packages; the app uses `material_ui` as Flutter 3.50's template does; the core has a `defaultSongs = 10` constant (D-047).
