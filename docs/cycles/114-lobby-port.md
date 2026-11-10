# 114 lobby-port (D-124, D-123; needs 113 merged)

## Goal
The lobby is the mockup's `#lobbyView` (`docs/design/108-ui-design-reference.html`, Version 21, markup at lines 663-700, script `SKIN`, `renderLobby`, `toLobby`, `lobbyChat`, `showView`), inside the same `.app` > `header` + `.main` (`.stage` + `aside.chat`) frame as the game room, styled by the unchanged `room.css`. It also has lobby chat (cycle 113's server path).

The start screen, which the mockup does not have, is built from the mockup's own classes in the same frame. After this cycle the interim rules and aliases in `index.css` that served the lobby and start screens are removed, and the dev→main release is unblocked.

## Behaviour
1. **One frame for start, lobby and game.**
   - `body.lobby-on` is set on the lobby, and only there.
   - `window.scrollTo(0,0)` and `.stage` `scrollTop = 0` run when the view switches, as the mockup's `showView` does.
   - The header is the game room's: `.logo`, `.roominfo` (`.code` and `.settings`, empty on the start screen), `.vol`, and `.moodbar`.
   - **The mood picker is always the header `.moodbar`.**
     - Start screen: the own pick, enabled.
     - Lobby: the room theme; enabled for the host. For guests it is disabled, and its `.ml` label reads today's guest text "분위기 (방장이 고름)".
     - There is no second picker in any card.
   - **Placement of today's other lines:**
     - the reconnect banner: a `p.warn` at the top of `.stage .inner`;
     - the lobby's `notice` (for example ABANDONED) and `error`: `p.warn` lines directly under `.start-row`;
     - the footer (language switcher, feedback, data credit): stays after `.app`, as today.
   - **Lobby `.settings`:** the header line shows today's line for the current lobby settings.
2. **Lobby markup**, following the mockup:
   - **`h1.marquee > span`:** the mood's `SKIN.title`.
   - **`.lobby-top`:**
     - `.ticket`:
       - `.tk-main` holds `.tk-tag` (the mood name · tagline), `.tk-label` (`SKIN.label`), `.tk-code` (the room code), and `.share` (`span.link`, the full room URL, plus the `.btn` 복사 with today's copy behaviour and the `.copied` state).
       - `.tk-sub` holds the hint, or the copy result.
       - `.tk-stub` holds `.tk-n` (connected players), `.tk-of` "/ 8명" and `.tk-admit` (`SKIN.admit`).
     - `.lobby-side`: `.ldisc > canvas` (320×320) with the mood's disc copied from `drawPlayer` (the mockup's `setMood` copy into `#lobbyDisc`), and `.lq` "?".
     - The mockup's `.viewas` is demo-only and is left out.
   - **`section.opts`:**
     - `.opts-hd` holds `h2` 게임 설정 and `.opts-who` (방장만 바꿀 수 있어요 for the host; for a guest, the mockup's guest text).
     - `.opt-grid` holds every existing lobby control in the mockup's control types, with today's values, limits and messages:
       - 곡 수 as the `.stepper` (− / `output` / +, 5–30, today's step). The value is handled as a number; the settings message carries it as today. The `output` has the accessible name 곡 수;
       - 곡 종류 as a `.seg`;
       - 난이도 as a `.seg`, always shown (as today);
       - 연도 as `.years` with two selects that keep today's options, and `.warn` when start > end, which disables the start button as today;
       - 정답 형식 as a `.seg`;
       - 게임 모드 as a `.seg`. `.oh` hints only where today's lobby already shows a hint, with today's texts. The mockup's demo hints are not taken.
     - Guests see every control `disabled`, as the mockup's guest view does.
   - **`.start-row`:**
     - `button.btn.primary.go` for the host, and the `p.waiting` with `.dots` for guests.
       - The visible text of `.go` is `SKIN.start`.
       - Its accessible name is that same text. Tests find it through a shared helper, not by the old name 게임 시작.
     - `.btn` 나가기.
   - **Seats** use the same `.seats` section as the game (heading `SKIN.seats`, the empty-seat word `SKIN.empty`, `SKIN.ready` where the mockup shows it). `body.lobby-on` applies the mockup's per-mood lobby seat rules.
   - **The `SKIN` table** (8 moods × title, label, admit, start, seats, ready, empty) is i18n in ko, en and ja. The ko values equal the mockup's. The English and pixel or neon strings that are English in the mockup stay English in every language.
3. **Lobby chat.** The `aside.chat` is shown in the lobby with the same `.msgs` and `form.send`. Sending uses the existing `chat` message, which cycle 113's server broadcasts.
   - **The log is cleared every time the screen becomes the lobby, by any path:** joining from the start screen, 대기실로 after a game, an abandoned game, a restore or rejoin into the lobby phase.
   - The lobby's system lines are the mockup's (for example "<code> 방에 들어왔어요…"), in i18n.
4. **Start screen** (not in the mockup), inside `.stage .inner`:
   - an `h1.marquee > span` "SongRush";
   - a `section.opts` card with the nickname input. It is styled like `.years select` (background, border, radius and padding) by a new `index.css` rule on a class that `room.css` does not define;
   - `.btn.primary.go` 방 만들기;
   - a room-code input with `.btn` 참가.
   - The mood picker is the header's.
   - The labels, validation and errors are today's. The chat aside is hidden.
5. **Cleanup.** `index.css` keeps only the Tailwind import, `@theme inline`, the rules the admin page and the feedback dialog need, and the start screen's inputs.
   - Every alias that `@theme inline` reads is kept (`--soft`, `--good`, `--good-text`, `--bad`, `--warn`, `--font-d`).
   - Other interim rules that the start and lobby screens used are removed.
   - `indexCss.test.js` stays green unchanged.

## Acceptance
1. **`App.lobbyport.test.jsx`** (new, jsdom):
   - `body.lobby-on` is on in the lobby and off in game and on start;
   - the marquee, `.tk-label`, `.tk-admit`, the start button text, and the seat heading follow `SKIN` for two moods in ko and one in en;
   - `.tk-code` is the room code, `.tk-n` is the connected count, and `.share .link` is the room URL;
   - copy shows the copied state;
   - the stepper changes the song count within 5–30 and sends today's settings message;
   - the year warning shows and disables start;
   - a guest sees disabled controls, the waiting line, and no start button;
   - lobby chat: sending emits a `chat` message; an incoming `chat` in the lobby renders as `.msg > .t`; the log is empty after 대기실로 and after entering from the start screen;
   - the view switch calls `scrollTo(0,0)`.
2. **Declared changes (critic H1).**
   - A shared helper module, `src/testing/lobby.js`, provides:
     - `startGame()`, which clicks `.start-row .go`;
     - `setSongCount(n)`, which uses the stepper;
     - the year and filter setters.
   - **Every** test file that today finds the start button by the name 게임 시작, or the 곡 수 select, switches to the helpers. Today that is 16 files; the test writer greps for `'게임 시작'` and `'곡 수'` and lists every file in the test commit.
   - `App.lobby`, `App.filters`, `App.difficulty`, `App.multi` and `App.theme` are rewritten to the new markup where they read lobby or start classes. They keep the behaviour they check: labels, roles, sent messages, the ABANDONED notice text, and the `li`-roster helper scoped to the seats.
3. **Commands:** `npm test`, `npm run lint` and `npm run build` pass. `roomCss.test.js` and `indexCss.test.js` are unchanged and pass.
4. **Browser check (lead):** the mockup's lobby and the site's lobby side by side, host and guest, in all 8 moods at 1280×800, and in sakura, pixel and manga at 390×844. Computed styles are compared for `.marquee`, `.ticket`, `.tk-code`, `.opts`, `.seg button`, `.stepper output`, `.go` and `.seat`. Captures of the start screen, the admin page and the feedback dialog are added.

## Spec review (critic, round 1)
- **H1:** a shared helper, and every affected file is listed.
- **H2:** the placement of the notice, error, banner and footer is fixed.
- **H3:** the chat is cleared on every way into the lobby.
- **M4:** there is one picker, the header's, with the guest label.
- **M5:** the aliases that `@theme` reads are kept.
- **M6:** only today's hints are shown.
- **Nits:** the settings line, the numeric count, the year options and the difficulty are all fixed.

## File ownership
- **Test writer:** the new test, `src/testing/lobby.js`, and the declared changes.
- **Implementer:**
  - `src/components/LobbyScreen.jsx`, `StartScreen.jsx`, `FilterControls.jsx`, `ThemePicker.jsx`, `Seats.jsx`, `seatList.js` (remove `SeatsAsDivs` if no longer needed), `GameScreen.jsx` (only to share the frame);
  - `src/components/drawPlayer.js` (only to export a copy helper if needed);
  - `src/App.jsx`, `src/game/state.js` (only for clearing the chat and the lobby system lines);
  - `src/index.css`;
  - `src/i18n/{ko,en,ja}.json`.
  - Not `room.css`.

## Out of scope
- `#sky` and `#viz` (cycle 115).
- The admin page and the feedback dialog layout.
- Server changes beyond 113.

## Result
- **Commits.** Spec `981eb8a`. Tests `745da60` (locked, 17 files with the shared helper `src/testing/lobby.js`). Implementation `25b1e77`. Fix after review `6e6a5c6`.
- **Verifier** (on `25b1e77`): `npm test` 664/664, lint clean, build passes. The locked-test diff is empty. `room.css`, `roomCss.test.js` and `indexCss.test.js` are unchanged. Every `@theme inline` variable resolves.
- **Lead,** after the fix: `npm test` 664/664.
- **Critic code review.**
  - **High:** a guest's locked settings card shows the guest's own local defaults, because the server never sends lobby settings to guests (`Room.java:392-395` sends `settings` only at game start). This was confirmed in code. It is fixed by cycle 116 (a server broadcast of the host's lobby selection), before any release.
  - **Medium, fixed in `6e6a5c6`:** the lobby join line stayed in a late joiner's game chat.
  - **Medium, footer placement:** the footer is the last child of `.stage .inner` on all three screens, the same frame the game used since 110. The spec line "after .app" is superseded by this.
  - **Added in the fix:** the mockup's mode hints under 정답 형식 and 게임 모드, without the mockup's demo sentence.
- **Lead browser check** (the mockup's lobby against the site's, host view, all 8 moods at 1280×800, sakura, pixel and manga at 390×844): computed styles of `.marquee`, `.ticket`, `.tk-code`, `.opts`, `.seg button`, `.stepper output`, `.go`, `.seat`, `.tk-stub` and `.share` are equal. The only differences are `border-top-style` (solid vs none) at zero width from Tailwind preflight, which is invisible. Captures: `docs/design/114/`.
