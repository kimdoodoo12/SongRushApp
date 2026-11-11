# 119 nickname-dialog (D-127)

## Decision (owner, 2026-11-11)
"닉네임은 들어갈때나 방만들 때 입력하는 걸로 바꾸고싶어". Asked how, the owner chose "버튼을 누르면 닉네임 창": the start screen shows only 방 만들기 and the room code with 참가. Pressing either opens a small nickname dialog.

## Behaviour
1. **Start screen.** The card holds:
   - the marquee;
   - `.btn.primary.go` 방 만들기;
   - "또는 코드로 참가";
   - the room-code input with `.btn` 참가.
   There is no nickname field any more. 참가 stays disabled until the code has 5 characters, as today's rule is.
2. **Dialog.** Pressing 방 만들기 or 참가 opens a modal `dialog` (`role=dialog`, `aria-modal`, labelled by its title) styled with the mockup's classes (`.opts` card, `.ol` label, `.start-input`, `.btn.primary`, `.btn`). It holds:
   - the title: "닉네임" with "방 만들기" or "<code> 방 참가";
   - the nickname input, focused on open, `maxLength` 16;
   - the buttons 확인 (`.btn.primary`) and 취소 (`.btn`).
3. **Closing the dialog.**
   - Enter submits.
   - Escape, 취소 or a click outside the card closes it without sending anything.
   - The nickname is trimmed. A blank one keeps 확인 disabled, with today's server rule as the backstop (1–16 characters).
4. **Confirm.** 확인 sends exactly what today's create or join sends, with that nickname.
   - The dialog closes when the room is joined.
   - A server error (`bad-nickname`, `room-not-found`, `room-full` and the rest) shows in the dialog as `p.warn` with today's text. The dialog stays open, so the player can fix the name or cancel.
5. **Remembering.** The last confirmed nickname is stored in `localStorage` (`songrush.nickname`, wrapped in try/catch) and prefilled the next time the dialog opens.
6. **Invite link** (`/r/<code>`): the start screen opens with the code filled in, and the join dialog opens at once.
7. **Unchanged:** reconnect and reload rejoin (they never ask for a name), the lobby and the game.
8. **i18n:** every new text in ko, en and ja.

## Acceptance
1. **`App.nickname.test.jsx`** (new, jsdom):
   - the start screen has no nickname input;
   - 방 만들기 opens the dialog with the nickname focused;
   - 확인 sends `create` with the trimmed name;
   - a blank name keeps 확인 disabled;
   - Escape and 취소 send nothing;
   - 참가 is disabled with a short code; with a full code it opens the dialog, and 확인 sends `join` with the code;
   - a `bad-nickname` error shows in the open dialog;
   - the stored name is prefilled;
   - `/r/ABCDE` opens the join dialog with the code;
   - the dialog closes on `joined`.
2. **Declared changes.** A shared helper in `src/testing/lobby.js`, `createRoomAs(name)` and `joinRoomAs(name, code)`, replaces the "fill 닉네임, click 방 만들기/참가" steps in every existing test that does them (18 files today: grep `닉네임`). Each file is listed in the test commit, and the behaviour each test checks is kept.
3. **Commands:** `npm test`, `npm run lint` and `npm run build` pass.
4. **Browser check (lead):** create and join through the dialog in Edge at 1280×800 and 390×844, plus the invite link.

## File ownership
- **Test writer:** the new test, the helper, and the declared changes.
- **Implementer:** `src/components/StartScreen.jsx`, a new `src/components/NicknameDialog.jsx`, `src/App.jsx` (wiring only), `src/index.css` (dialog layout, on classes `room.css` does not define), and `src/i18n/{ko,en,ja}.json`.

## Out of scope
- Server changes.
- Changing the nickname inside a room.
