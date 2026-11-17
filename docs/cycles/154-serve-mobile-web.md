# 154 · I-012 serve the Flutter web build to phones at /m/

## Goal
The live site serves the Flutter mobile web build at `/m/` beside the React web, sends phones there, and offers a switch link both ways (D-140). It ships through the existing image and deploy (merge to main is the owner's); no server-side setting changes.

## Acceptance criteria
1. Docker: a `mobile` stage builds `flutter build web --release --base-href /m/` with Flutter 3.50.0 (a pinned image or a pinned git tag) and the runtime stage copies it to `/app/web/m/`; `.dockerignore` lets `mobile/` in (minus build output). `docker build .` succeeds locally and the image serves `/m/` (checked by running the image).
2. Backend: `/m` redirects to `/m/`; `/m/` and `/m/r/{code}` (with or without a trailing slash) serve `/m/index.html`, like `/r/{code}` does for the React web (`RoomPageController`); tests for each.
3. React web: a phone (coarse pointer and a short screen side of at most 600 px) opening `/` or `/r/CODE` goes to `/m/` or `/m/r/CODE` before React renders, unless the viewer chose the PC version (`localStorage songrush.ui = "pc"`); `/admin` is never redirected; desktops and tablets stay. A "모바일 버전" link in the shared footer (ko/en/ja) sets `songrush.ui = "mobile"` and goes to the matching `/m/` path; tests for the decision function and the link.
4. Flutter: a "PC 버전" link on the start screen and lobby footer (ko/en/ja) sets `songrush.ui = "pc"` and goes to `/` (or `/r/CODE` in a room); on the web only.
5. No immutable caching for `/m/*` (Caddy's `/assets/*` rule is root-only; the backend sends no-cache).
6. Tests pass: backend `.\gradlew.bat test`, frontend `npm test` and `npm run lint`, mobile `flutter analyze` and the app tests (copy without flutter_soloud); `flutter build web --base-href /m/` succeeds.

## Out of scope
The Android APK (later item), the deploy itself (owner merges to main).

## Result
- Dockerfile `mobile` stage (debian bookworm-slim, Flutter git tag 3.50.0, `flutter build web --release --base-href /m/`) copied to `/app/web/m`; `.dockerignore` lets `mobile/` in without build output and `tool/fonts-src`.
- `RoomPageController`: `/m` → 302 `/m/`; `/m/`, `/m/r/{code}(/)` → `/m/index.html` (`MobilePageTest`, 4 tests).
- React: `frontend/src/mobileRedirect.js` run in `main.jsx` before React renders: a phone (`(pointer: coarse)` and the screen's short side ≤ 600 px) on `/` or `/r/<code>` goes to `/m/` or `/m/r/<code>` with `location.replace`, query and hash kept, unless `localStorage songrush.ui = "pc"`; `/admin` and other paths never. Footer link 모바일 버전 / Mobile version / モバイル版 stores `songrush.ui = "mobile"`.
- Flutter: footer link PC 버전 / PC version / PC版 on the start screen and lobby (web only) stores `songrush.ui = "pc"` and goes to `/` or `/r/<code>`.
- Evidence: implementer `docker build -t songrush-local .` OK and the image checked with curl (`/m` 302 to `/m/`, `/m/` and `/m/r/ABCDE` 200 html with `<base href="/m/">` and no-cache, `/m/main.dart.js` 200 text/javascript no-cache, `/`, `/r/ABCDE`, `/admin` React); app tests +136 in a copy without flutter_soloud. Verifier: backend 1310 tests 0 failures (MobilePageTest 4/4), frontend 932 passed, lint clean, `flutter analyze` no issues, room.css untouched.
- Not covered: the old non-Docker upload path (`deploy/upload.sh`, `songrush.service`) does not ship `/m/`; the live site deploys the Docker image (docker.yml). A phone downloads the React bundle before it is redirected (the redirect runs in main.jsx, not inline).
