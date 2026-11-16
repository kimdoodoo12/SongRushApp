# 140 leave-game (F-041, from D-138)

## Goal
A player can leave during a game: a 나가기 button asks once, then takes them back to the start screen.

## Plan
- The server already handles `leave` during a game (Lobby.java:245-272: the member is marked disconnected, the room goes on, pauses if nobody is left to play). The lobby's 나가기 (LobbyScreen.jsx:231, App.jsx `handleLeave` :403-409) sends `leave` and resets to the start screen.
- Client: the game screen (GameScreen.jsx, its header or the panel row; follow the existing layout and buttons, `.btn`) gets a 나가기 button for players and watchers. A click opens an in-app confirmation dialog (`role="dialog"`, modal, the same pattern as NicknameDialog/FeedbackDialog; never `window.confirm`): text "게임을 나갈까요?" with "나가기" (confirm) and "취소" (cancel), Escape cancels, focus goes to the dialog and back to the button on cancel. Confirm calls the same `handleLeave`. i18n keys in ko, en, ja. Any style goes in an approved stylesheet, not `room.css` (D-124).
- The 대기실로/나가기 on the game-over screen stay as they are.

## Acceptance criteria (Vitest)
1. During a game the 나가기 button is shown; a click opens the dialog and sends nothing.
2. 취소 (or Escape) closes the dialog; nothing is sent; the game goes on and focus returns to the button.
3. 나가기 in the dialog sends `leave` and shows the start screen.
4. The dialog text and buttons are translated in en and ja.
5. A watcher can leave the same way.
6. `npm test`, lint, build pass; `room.css` unchanged.

## Out of scope
- Server changes; leaving the lobby (exists).
