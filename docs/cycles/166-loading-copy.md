# 166 · A friendlier mobile loading screen

The Flutter web's first visit takes a while on a phone (about 10 s on LTE); the owner asked for wording that shows we know (approved the lead's recommendation).
- `mobile/web/index.html`: one language from the browser (ko/en/ja). Big and small lines change at 0, 5, 15 and 30 s ("🎵 무대 준비 중… / 처음 한 번만 조금 오래 걸려요" → "거의 다 왔어요 / 게임 엔진을 내려받는 중이에요. 다음부터는 금방 열려요" → "조금만 더 기다려 주세요 / 인터넷이 느리면…" with the PC 버전 link → "오래 걸리고 있어요" with 다시 시도 and the diagnostics, moved from 10 s to 30 s). A progress bar with a percentage from the finished startup files' real bytes over estimates, creeping between files, never past 95 % before the first frame. One game tip that changes every 5 s (5 per language).
- A viewport meta tag so the loading screen is laid out at phone width before Flutter starts (Flutter replaces it with the same content on start).
- D-144 note: the PC link is in the markup (works if the script fails) and is shown from 15 s.
- Evidence (implementer, Chromium 390x844, ko/en/ja, release build under /m/): main.dart.js held 35 s → the four stages at 1/6/16/31 s, bar 78→90 % monotonic, 5 tips, overlay gone at 36 s; throttled 400 KB/s → steady 6→75 %, gone at 30 s; fast load gone at 0.6 s; `?debug=1` still works. Screenshots `docs/cycles/166/`. en/ja tip translations to be checked by the owner.
