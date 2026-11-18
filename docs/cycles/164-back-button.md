# 164 · Phone back button asks before leaving; small mobile fixes

Owner: the phone's back button left the site at once.
- Cause: MaterialApp keeps a single history entry on the web (`SystemNavigator.selectSingleEntryHistory`, engine `SingleEntryBrowserHistory`); a browser back becomes `popRoute`, and with one route `maybePop` returned false, so `SystemNavigator.pop()` left the page.
- Fix: a root `PopScope(canPop: false)` sends every back to `AppController.handleBack()`: close what is open first (exit or leave confirm, nickname dialog, offset setting, scores sheet, chat panel), then lobby/game-over "방에서 나갈까요?", game the existing leave dialog, start screen "SongRush를 나갈까요?" (confirm → `SystemNavigator.pop()`), rejoining/disconnected → start screen. URLs unchanged (/m/, /m/r/CODE). New texts roomLeaveAsk, exitAsk (ko/en/ja).
- Also: the choice room's floating chat bubbles inset 6 px (no clipped left edge); `error.bad-song-count` says 5~100 / 5 to 100 / 5〜100 in the web dictionaries and the app.
- Evidence: `back_button_test.dart` (10); app tests +179 (lead re-run in a fresh copy); frontend 938 passed (lead), lint clean; web build OK; Playwright in Chromium on the release build under /m/: 22/22 (back on start shows the confirm and keeps the URL, 나가기 goes to the previous page, cancel keeps the screen, room link and reload behave).
- Not verified: real Android Chrome may skip a history entry added without a user tap, so a back before the first tap might still leave.
