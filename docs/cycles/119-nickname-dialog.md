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

## Spec review (critic, round 1) and resolutions
- **Blocker: the dialog cannot survive an error as written.** Pressing 확인 dispatches `connecting` (`App.jsx:289`), which sets `screen:'game'` (`state.js:168-170`) and unmounts the start screen. A following `startError` resets to `initialState` (`state.js:171-172`).
  - **Resolution.** The dialog state (open, mode, code, name) lives in `App`, not in `StartScreen`.
  - While the dialog's attempt is connecting, the start screen stays rendered under the dialog with the dialog's busy state (확인 disabled). A dialog-started attempt does not switch to the game frame until `joined`.
  - `startError` keeps the dialog open with the error as `p.warn`.
  - `state.js` may change for this: the `connecting` action gains a flag that keeps `screen:'start'` for dialog attempts.
- **High: existing tests check the error on the start screen** (`App.flow.test.jsx:504-516`, `App.lobby.test.jsx:456-467`). Declared changes: they now expect the error inside the open dialog.
- **High: the invite link reopens the dialog.** The invite dialog opens **once per page load**. A later return to the start screen (leave, error, rejoin-failed) does not reopen it. This is pinned by a test.
- **Medium: connection failures.** If the socket closes before `joined`, the dialog shows today's connection-failure text as `p.warn` and stays open (instead of the `disconnected` screen) for dialog attempts. Reload rejoin keeps today's path.
- **Medium: opus.** When `canPlayOpus` is false, 방 만들기 and 참가 are disabled and `start.noOpus` shows on the start card, as today (`App.flow.test.jsx:179`, `App.lobby.test.jsx:190`).
- **Medium: focus.** The dialog is a `div role="dialog" aria-modal="true"`, not a native `<dialog>`: jsdom lacks `showModal`. It follows `FeedbackDialog.jsx`'s pattern: focus moves into it, Tab is trapped, Escape closes, and on close the focus returns to the button that opened it.
- **Nit: size.** Two steps in one cycle. First the test writer adds the helpers `createRoomAs`/`joinRoomAs`, written against today's UI, and switches the 18 files to them, with all tests green. Then the new tests and the helper internals change for the dialog.
- **Nit: mobile.** The 390×844 browser check opens the dialog with the on-screen keyboard emulated by a shorter viewport (390×500). The input and 확인 must stay visible.

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
- **Implementer:** `src/components/StartScreen.jsx`, a new `src/components/NicknameDialog.jsx`, `src/App.jsx` (dialog state and wiring), `src/game/state.js` (the dialog-attempt flag on `connecting`, and error routing), `src/game/session.js` (close before `joined` for dialog attempts only), `src/index.css` (dialog layout, on classes `room.css` does not define), and `src/i18n/{ko,en,ja}.json`.

## Out of scope
- Server changes.
- Changing the nickname inside a room.

## Pre-PR review fixes (verifier and critic on cycles 118 and 119)
- **Verifier.** `npm test` passed in 2 of 4 runs. The failures were tests that go through the new dialog, which timed out at the default 5 s (`App.flow.test.jsx:157`, `App.prefetch.test.jsx:458`). Each full run takes 50–65 s on this PC with the local servers running.
  - **Fix.** Vitest's `testTimeout` becomes 15000 in `vite.config.js`, a declared test-config change.
- **Critic High: fixed header heights.** `scroll.css` uses 67/114 px and `choice.css` uses `--oh`, both measured only in ko. In en and ja the longer mood names wrap the mood bar, so the header becomes about 114 px even at 1300 px and wider. The chat input then ends 47 px below the screen.
  - **Fix.** `Frame` measures the header with a ResizeObserver and sets `--hd` (px) on `.app`.
  - `scroll.css`: `.chat{align-self:start;height:calc(100vh - var(--hd,67px))}` inside the 901 px media query, replacing the two fixed heights.
  - `choice.css`: `--oh` becomes `calc(var(--hd,67px) + 175px)`. 175 = 242 − 67 = 290 − 115, as measured.
- **Critic Medium: drag-release cancels.** The backdrop cancels only when both the mousedown and the click start on the backdrop itself.
- **Critic Nit: focus after cancelling the invite dialog.** Focus goes to the 참가 button.
- **Tests (declared).**
  - `scrollCss.test.js` and `choiceCss.test.js`: the new exact rules.
  - A new `App.headerheight.test.jsx`: `.app` gets `--hd` from the header's height, with ResizeObserver and `offsetHeight` stubbed.
  - `App.nickname.test.jsx`: a drag that ends on the backdrop does not cancel; cancelling the invite dialog focuses 참가.
