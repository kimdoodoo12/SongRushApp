# 155 · Release fixes before the first main PR with the mobile web (F-M10, D-144)

The release check's critic (origin/main...dev, 36 commits) found no Blocker. The owner chose to ship the phone redirect on (D-144), so the ways out were fixed first:
- H2 escape: `?ui=pc` keeps any device on the React web and stores the choice (`?ui=mobile` the opposite); the Flutter page shows a static "불러오는 중… / PC 버전으로 보기" fallback linking to `/?ui=pc` until Flutter's first frame; the app's PC 버전 button goes to `/?ui=pc` or `/r/<code>?ui=pc`.
- M2: the React footer's 모바일 버전 link only on the start screen and the lobby (leaving mid-game loses the seat).
- M1: `flutter build web --no-web-resources-cdn` (Dockerfile, README): CanvasKit from `/m/canvaskit/`, not www.gstatic.com. Missing glyphs (kana/kanji, rare Hangul) are still fetched by Flutter web from fonts.gstatic.com; the fonts README now says so.
- M3: deploy/upload.sh warns that the old path does not ship `/m/`.
- Follow-up kept: M4, the contract test checks type names only, not fields.

Evidence: frontend `npm test` 938 passed, lint clean, build OK (implementer); `flutter analyze` no issues; app tests +136 all passed in a copy without flutter_soloud (lead); `flutter build web --release --base-href /m/ --no-web-resources-cdn` built; a served build in Chromium: the fallback present at start with `/?ui=pc`, gone after the first frame, no request to gstatic CanvasKit, `/m/canvaskit/chromium/canvaskit.wasm` loaded; reviewer found no loop, XSS or boot-order problem. Release suites before these fixes: backend 1310 tests 0 failures, frontend 932, data 1095 passed, core +176, web build OK.

## Release (2026-11-18)
- PR #69 merged by the owner. The first main runs failed before starting: "The job was not started because recent account payments have failed or your spending limit needs to be increased." The owner made the repository public (history scanned first: no keys or tokens; the owner's email, the server IP in perf HARs and 11 older commits with a Claude co-author line become visible, accepted).
- Re-run on main: Docker build success, deploy success.
- Live checks on https://animeopguessr.com: `/m` 302 to `/m/`; `/m/` and `/m/r/ABCDE` 200 html with `<base href="/m/">` and the PC fallback; `/m/main.dart.js` 200; `/m/canvaskit/chromium/canvaskit.wasm` 200 application/wasm; `/health` 200. Desktop Chromium on `/` stays on the React web with the 모바일 버전 link; Playwright's Pixel 7 profile on `/` lands on `/m/`, the fallback disappears after the first frame (`docs/cycles/155/live-phone-start.png`); Pixel 7 on `/?ui=pc` stays on the React web.
