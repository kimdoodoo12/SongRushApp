# 151 · F-M6 mobile one-screen game layout (D-142)

## Goal
On a phone the game screen fits one screen with no page scroll, so a player can hear, see the covers and type at once: the owner's choice D-142.

## Acceptance criteria
1. Game screen (playing, reveal, held reveal, game over) never scrolls as a page at 360x640, 390x844 and 430x932, in ko, en and ja. Only the chat log (and, if needed, the reveal card's inner text) scrolls inside its own area.
2. Top bar, compact: song n/N, the time bar, seconds left, skip (with its count) and the offset button and 나가기 within the header; below it one horizontal line of players with points (own name marked, this song's solvers marked), scrolling sideways if it does not fit. The 8-seat grid is not shown in the game (it stays in the lobby).
3. Chat room: the chat log fills the middle and sticks to the newest line; the input and 보내기 are fixed at the bottom and move up with the on-screen keyboard (the log shrinks, the top bar stays visible). The hint/sub text shrinks to one line.
4. Choice room: the cover tiles fill the middle, in 2 columns for 4 and 6 tiles and 2 or 4 columns for 8 (whichever gives bigger tiles), sized to the space left so all fit without scrolling; marks, pick count and the 오답 line keep working. Above the fixed input: the latest one or two chat lines; tapping them opens the full chat as a bottom sheet (or an overlay) and closing it returns to the tiles.
5. Reveal: the reveal card takes the middle in a compact form (cover thumbnail, the title in the UI language, the other three titles in small text, OP/ED · song — artists, winner or ranked points); in a chat room the chat log stays visible below it (smaller); in a choice room the marked tiles stay visible (smaller) with the card. Game over: the final ranking replaces the middle, 대기실로 at the bottom.
6. The sound-unlock prompt, reconnecting line and loading/failed line fit in the top area without pushing the input off screen.
7. Widget tests: no overflow and no scroll at the three sizes in three languages for chat playing, chat reveal, choice playing with 4/6/8 tiles, choice reveal, game over; the input stays visible with a simulated keyboard inset (`MediaQuery.viewInsets.bottom` 300); tapping the last chat lines opens the full chat. Existing tests updated where the layout changed, not weakened.
8. `flutter analyze` clean, `flutter build web` succeeds; the lead runs a game on the local server at 390x844 and 360x640 with screenshots in `docs/cycles/151/`.

## Out of scope
The lobby layout (it scrolls; unchanged), moods, behaviour changes.

## Owner's refinements during the cycle (2026-11-17, from the mockup https://claude.ai/artifact/HMTXBgSFQwpUiFb8o4c2Ck)
- Chat room: an 84 px disc row with the hint above the chat log (no song info on the disc).
- Choice room: a 40 px disc row with the hint; tiles in at most 45% of the middle (2 or 4 columns for 4 and 8, 2 or 3 for 6, whichever gives bigger tiles); the chat box below takes all the remaining height, as many lines as fit, 전체 ▲ opens the full chat. These replace criterion 4's "2 columns" and "one or two lines".
- The offset button is an icon only; its name ("싱크") only in the tooltip and accessibility label, wording to be decided.

## Result
- `game_screen.dart` rewritten as one fixed screen (top bar with song n/N, code, offset icon, 나가기, time bar, seconds, skip; a sideways players strip; notices; middle; fixed input lifted by the keyboard). `chat_panel.dart` split into `ChatLog`, `ChatForm`, `ChatPreview`. The game no longer shows the 8-seat grid, logo, mood tag or footer.
- Verifier: `flutter analyze` no issues; core `dart test` +176; app widget tests +111 all passed in a copy without flutter_soloud (39 new layout tests at 360x640, 390x844, 430x932 in ko/en/ja, keyboard inset 300); two game_screen_test assertions moved to the new layout (choice-room last lines read from the box then the full chat; game over shows the ranking instead of the last card), none weakened; no behaviour removed; the disc shows no song info.
- Manual run (lead) at 390x844 against the local server: chat game (answer, reveal, skips, final ranking, lobby) and choice game, no console errors. Screenshots: `docs/cycles/151/`.
- Seen in the screenshots: with few chat lines they sit at the top of the log and box instead of at the bottom near the input; the choice room's input placeholder still reads "애니 제목을 입력하세요" though chat there is only talk. Follow-ups.
