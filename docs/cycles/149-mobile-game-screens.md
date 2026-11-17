# 149 · F-M4b mobile game and game-over screens

## Goal
A full game can be played in the Flutter client: the game screen (time bar, chat answers, choice tiles, skip, reveal card, scores, leave) and the game-over screen, behaving and worded like the React web, phone-first.

## Acceptance criteria
1. Game screen, mirroring the React GameScreen and its parts (frontend/src: GameScreen.jsx, ChoiceGrid.jsx, LeaveDialog.jsx, state.js for what is shown when):
   - song n / N, a time bar to the song's deadline from the server clock (the core's clock estimate), the held previous reveal while the next song loads (`holdMs`), the reveal window ("music plays on") with its end;
   - chat answers: input, wrong guesses shown as chat to everyone, `✔ <name> 정답!` system line and the winning message after `correct` (D-094), ranked mode not echoing a correct chat, chat after the reveal is ordinary chat;
   - choice rooms: 4/6/8 cover tiles from the RoundController's cover bytes, one pick per song, the picked/locked state, pick count, correct/wrong marks after the reveal (translated marks, cycle 138), chat never scores (D-135);
   - skip vote with count (majority of connected players), closed when the server says so;
   - reveal card: the entry's own title, titles in ko/en/ja/romaji (D-095), cover, artists and theme type/slug as the web shows them, the winner or the ranked 3/2/1 points;
   - scores list; 나가기 with a confirm dialog (D-138).
2. Game-over screen: final ranking, back to the lobby when the server returns the room to the lobby (D-062).
3. Texts in ko/en/ja from the web's dictionaries via `mobile/tool/sync_l10n.py` (same keys).
4. Widget tests per screen part on the core fakes (FakeGameSocket, FakePlayer, FakeFetcher) with fake time: chat answer flow, choice flow, skip, reveal card in each language, ranked points, held reveal, leave confirm, game over → lobby, 360 px fit. Run on this PC in a copy without flutter_soloud (as in cycle 148), and in CI.
5. `flutter analyze` clean, `flutter build web` succeeds, core `dart test` passes.
6. Manual run (lead): a full short game (5 songs) app vs React web on the local server, chat mode and choice mode, screenshots in `docs/cycles/149/`.

## Out of scope
Reconnect UI and lifecycle (F-M5), moods, volume control, the manual offset slider UI (F-M5 with the settings store).

## Result
- App: `GameModel` (ChangeNotifier mirroring state.js and the game part of session.js, fed by `Session.messages` and `RoundController.events`), `GameScreen` with the time bar, chat answers, choice tiles and marks, skip, pick count, reveal card (ko/en/ja/romaji, winner or ranked points, cover after the reveal only), seats with points, in-screen leave confirm, and the game-over view with 대기실로 once the lobby roster arrives. 62 keys synced from the web's dictionaries (157 in all).
- Verifier: core `dart test` +171; `flutter analyze` no issues; app widget tests +48 all passed in a copy without flutter_soloud; ARBs regenerate identically from frontend/src/i18n; the fairness checks (nothing identifying the song before the reveal, no early correct mark, ranked no echo, choice chat never scores, one pick per song) hold by reading.
- Manual run (lead), backend on 8080 serving the new build, React web host on 5173, app guest at 390x844, 5 songs:
  - Chat mode: the web answered song 1 from the local catalog's titles; the app showed `✔ 정답` and the reveal card (`docs/cycles/149/chat-2-app-reveal-correct.png`); songs 2-5 were skipped by both votes (each in about 5 s); the final ranking, then 대기실로, back in the lobby; no console errors.
  - Choice mode: 6 tiles; the app picked tile 1 (wrong, `✕ 오답`), the web tile 2 (right); the reveal marked `✔ 정답` on tile 2 (`choice-3-app-reveal.png`); the game ran to the final ranking and back to the lobby; no console errors.
- Found: choice tiles have no semantic label, so a screen reader (and the test script) cannot find them as buttons; the script tapped by position. Follow-up.
