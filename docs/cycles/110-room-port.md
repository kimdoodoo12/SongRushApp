# 110 room-port (D-124, D-121..D-123)

## Decision (owner, 2026-11-11)
- "CSS는 내가 참고해준걸 완전히 따라가야지..."
- "저기 CSS는 완전히 따라가"
- "어 그렇게 해"

The reference is `docs/design/108-ui-design-reference.html` (mockup Version 21, artifact version 1794325903-8fce). It is data: its script is a local demo with bots and synthesized music. Only its look, its markup and its drawing code are taken.

## The plan for the port (replaces design 108's items F-026..F-031)
| cycle | content |
|---|---|
| **110** (this one) | The stylesheet and the game room's frame: header, stage top, time bar, panel states, seats, chat |
| 111 | The deck: disc (`drawPlayer`), `.mark` and arm, and the reveal frames (`showCover`, `pixelate`, caption, stamp, sfx) |
| 112 | Choice rooms: the `.choices` tiles with number badges, the marks, the fly-in |
| 113 | Backend lobby chat (B-032) |
| 114 | The lobby (ticket, settings, per-mood `SKIN` text, lobby chat) and the start screen in the same look |
| 115 | The canvases: `#sky` scenes, `#viz` visualizers (including chalk's staff and sakura's petal storm), matsuri fireworks |

The site is not usable for release between 110 and 114, because the start screen and the lobby look half-converted. The owner is asked not to merge dev into main until 114 is done (PROGRESS).

## Goal
The game room renders the mockup's markup with the mockup's stylesheet unchanged. The mood is `<html data-mood="<id>">`.

## Stylesheet
1. **`frontend/src/room.css`** is the mockup's second `<style>` block copied verbatim, rule for rule, with only these demo-only rules left out:
   - `.cheat` (and its descendants);
   - `.try`, `.trymsg`, `.vh`, `.try:focus-within label`;
   - `.note`;
   - `.rar`, `.speed` (D-123);
   - `.viewas`;
   - `.backlobby`, `.lobby-on .backlobby`;
   - `.cheat li canvas`.
   - No other rule is changed, reordered within its block, or added to `room.css`.
   - **Exclusion is per rule, at any depth.** A rule is excluded when any selector in its list names one of those classes: for example `[data-mood="manga"] .cheat`, or a rule inside `@media (max-width:900px)` that names `.cheat`. The enclosing `@media` or `@container` block is kept with its other rules.
   - Comments are not compared; they are stripped in normalization.
   - The lobby rules are copied now as well. They already apply to today's lobby markup where class names coincide (`.lobby`, `.share`, `.code`, `.panel`, `.hint`). This is accepted until 114; see the interim rule below.
   - The mockup's `@media (max-width:900px){.vol{display:none}}` is copied as is (D-124). On a phone the volume slider is therefore hidden, and the phone's own volume keys apply. This is reported to the owner.
2. **Load order.** `room.css` is imported after `index.css`.
   - `index.css` keeps only the Tailwind import and what screens outside the mockup still need: the admin page and the feedback dialog.
   - Every old look rule is deleted: the `[data-room-theme]` token blocks and their overrides, `.record`, `.eq` and the rest of the old components' looks. Nothing old may override a mockup rule.
   - Tailwind's preflight stays. The mockup's rules are unlayered, so they win over Tailwind's layers wherever both apply.
   - **Interim aliases until 114.** `index.css` defines, on `:root`, the old token names that the lobby, start, admin and feedback screens still read, as aliases of the mockup tokens:
     - `--soft: var(--dim)`, `--good: var(--ok)`, `--good-text: var(--ok)`;
     - `--bad` and `--warn` as fixed colours;
     - and every other name that `@theme inline` maps for Tailwind utilities, each pointing at the nearest mockup token.
     - The aliases are only names. They add no rule for any class.
3. **Fonts.** The `index.html` Google Fonts link is the mockup's link, byte for byte: the `<link rel="stylesheet" ...>` line of the reference.
4. **Mood.** `App` sets `document.documentElement.dataset.mood` to the room theme (the host's pick, as today; `sunset` outside a room or before a frame). The old `data-room-theme` attribute is removed.
   - `index.html` sets `<html data-mood="sunset">`, so the first paint is already in a mood.
5. **Removed tests (declared):**
   - `themeLayout.test.js`: its rule (no per-theme layout) contradicts D-124.
   - `themeTokens.test.js` (cycle 109): the token blocks it checks no longer exist.

## Markup (game room, chat game)
The structure and class names follow the mockup's `#gameView` exactly. Its parts, and where their content comes from:

- **`header`**
  - `.logo`: the `.eq` bars and "SongRush".
  - `.roominfo`: `.code` is the room code; `.settings` is today's one settings line.
  - `.hd-right`:
    - `.vol`: today's volume control, with the same label and behaviour.
    - `.moods`: the eight mood buttons, each with its `.sw` swatch and its i18n name, and `aria-pressed`. For the host a click sends `roomTheme`. For guests the buttons are `disabled`.
  - `body.playing` is set while a song is audible.
  - The language switcher and the feedback link stay in the footer as today, styled with `.btn`.
- **`.main`**: a `.stage` that holds `.inner`, and an `aside.chat`.
- **`.stage-top`**
  - `.round`:
    - `.tag` is the mood name and tagline, from i18n;
    - `.num` is `index / total`;
    - the round label.
  - `.clock`: whole seconds left, with `.low` at 10 s or less.
- **`.timebar`** with `.timebar-fill` (`role=progressbar`, name "남은 시간", as today).
- **`.now`**: in this cycle `.deck` is the mockup's element tree with today's disc logic moved in unchanged. The port of `drawPlayer` and the reveal frames is cycle 111; the full tree is listed here so 111 only fills it.
  - `.deck` holds:
    - `canvas#viz`, present but not drawn until cycle 114;
    - `.player` with `.arm`, `.disc > canvas` and `.mark`;
    - `.cover` with `.shot` (`img`, `.noimg`, `.fog`, `canvas.pix`), `.cap`, `.stamp` and `.sfx`.
  - `.deck` gets the classes `idle`, `revealed` or none, as in the mockup.
  - The disc canvas is drawn by a port of the mockup's `drawPlayer()` for the current mood, and redrawn when the mood changes. `.mark` holds the same text as in the mockup.
- **`.panel`** holds the phase content. Every state the game screen has today keeps a place:
  - **Waiting or loading:** `.hint` and `.sub`, using today's texts (connecting, loading, sound unlock button, watching).
  - **Playing:** `.hint` and `.sub` (the chat-mode texts), plus a `.row` with the skip `.btn` (`.voted` after the own vote) and `.skipcount` "스킵 n/m".
  - **Reveal:** a `.reveal` card:
    - `h3` 정답 공개;
    - `.who` "정답: <name>", or `.who.none` 정답자 없음;
    - `dl.titles` with KO/EN/JA/Romaji, where `dd.ko` is the Korean title;
    - `.theme-line`, built from the fields the reveal frame actually has (slug, song title, artist);
    - `.next`.
    - No speed line.
  - **Ranked reveal (cycle 101):** the same `.reveal` card. `.who` lists the solvers in order. An `ol.rank` holds the reveal's `ranking` rows (`.p` place, name, `.s` points). Every solver's seat is `.win`.
  - **Correct, before the reveal arrives:** `.hint` reads "<name> 정답!" (today's banner text).
  - **Error:** today's error text, as `p.warn` under the panel content.
  - **Game over:** a `.reveal` card with `h3` 최종 순위, `ol.rank` (`.p` place, name, `.s` points), today's final score line, and the 대기실로 `.btn.primary` when the lobby is open.
  - **Disconnected or replaced:** a `.reveal` card with today's text and the reset `.btn.primary`.
  - **Before the first round:** connecting, waiting for the next song as a late joiner, watching, and the sound-unlock button (a `.btn.primary`). Each is a `.hint`/`.sub` pair, using today's texts.
  - **A solo game** (`room === null`) uses the same layout, with no `.code` and no `.moods`.
- **`.seats`**: `h2` 자리 and `ol.seat-grid`, as in the mockup, keeping the accessible list name "자리".
  - Each of the 8 `li.seat` holds `.av` (an initial on the name colour) and `.sm` (`b` with the name, the 방장 and 나 tags as today; `small` with the points; `.ok` 정답! for the round winner).
  - The winner's seat is `.win`. Empty seats are `.seat.empty` 빈 자리.
  - Chat no longer shows in seat bubbles.
- **`aside.chat`**
  - `.chat-hd`: `b` 채팅 and `.live`.
  - `.msgs`: each line is a `div.msg`:
    - `i` (the name colour) and `span.t` with the single text `이름: 내용`;
    - `.mine` for the own lines;
    - `.msg.sys` for system lines, and `.msg.sys.correct` for the `✔ <name> 정답!` line.
  - `form.send` with the input (label "정답 입력", as today) and the button 보내기.

## Reveal frame (port of `showCover`): cycle 111, recorded here for that spec
- **On `reveal`:**
  - The `.cover` img `src` is the reveal's `coverUrl`.
  - `.noimg` shows the Korean title and is used when `coverUrl` is null or the image fails (`.shot.missing`).
  - On the image's `load`, `--ar` = clamp(naturalWidth/naturalHeight, 0.56, 0.8) is set on `.cover`, and `--peek` = round(18 + ar×30)% on `.deck`. Then `.revealed` is added by restarting it, as `play()` does.
  - With no image, `--ar` is .714 and `--peek` is 39%.
- **`.cap`** follows the mockup's rule:
  - sakura: the title (no rarity badge, per D-123);
  - pixel: `PLAYER 1 CLEAR!` when the winner is me, `STAGE CLEAR!` for another winner, `TIME OVER` with none;
  - neon: `SIGNAL FOUND · CH-0<n>`;
  - every other mood: the title.
- **`.stamp`** reads 정답 with a winner and 통과 without one. **`.sfx`** is the mockup's text.
- **pixel** runs the `pixelate` pass. **matsuri** fireworks come in 114.
- **Reduced motion** (`matchMedia('(prefers-reduced-motion: reduce)')`) skips `pixelate`, exactly as the mockup's `reduce` does.
- **Secrecy.** Nothing in `.cover` or `.cap` is filled before the `reveal` frame. Between songs `.cover` is emptied.

## i18n
- Every visible text above that is not already a key gets ko, en and ja: the tagline, the chat heading, 정답 공개, 정답자 없음, 최종 순위, the stamp words and the rest.
- The mockup's fixed English strings (`PLAYER 1 CLEAR!`, `STAGE CLEAR!`, `TIME OVER`, `SIGNAL FOUND`) stay in English in every language, as in the mockup.

## Acceptance
1. **`frontend/src/roomCss.test.js`** (node; new). It parses the reference HTML's second `<style>` block and `src/room.css` into rule lists. Whitespace is normalized, and `@keyframes`, `@container` and `@media` are compared as whole blocks.
   - Every rule of the reference, except the excluded selectors above, appears in `room.css` with an identical selector and identical declarations, in the same order.
   - `room.css` has no rule that the reference lacks.
   - `index.html` contains the reference's font `<link>` exactly.
2. **`frontend/src/indexCss.test.js`** (node; new): `index.css` has no `[data-room-theme` selector, and no rule for a class that `room.css` defines.
3. **jsdom tests** (`App.room.test.jsx`, new; plus the declared edits below):
   - `document.documentElement.dataset.mood` follows the `roomTheme` frame for each of the 8 ids, and is `sunset` by default.
   - **Game room markup:** the header has `.logo`, `.code` and `.settings`; the `.moods` buttons are enabled for the host and disabled for a guest; `.stage-top .round .num` reads `1 / 5`; `.clock` reads the seconds and gets `.low` at 10 or less; `.deck` gets `.player .disc canvas`.
   - The seats are a `.seat-grid` list named 자리 with 8 `li`, and `.win` goes to the winner.
   - A chat line renders as `.msg` > `.t` with the text `Mina: 이거 뭐였지`; the own line is `.mine`; the correct line is `.msg.sys.correct`.
   - **Reveal** (frames are in 111): the `.reveal` card shows the four titles in `dl.titles` and has no speed line. Before the `reveal` frame, no title text is in the DOM.
   - **Ranked reveal:** `.who` lists both solvers, `ol.rank` has the ranking rows, and both seats are `.win`.
   - **States:** the error text shows as `.warn`; disconnected shows the reset button; a late joiner sees the waiting text; the sound-unlock button calls unlock; a solo game has no `.code`.
   - **Other screens survive:** the start screen, the lobby, the admin page and the feedback dialog still render, and their main controls are found by role and name (the existing tests for them pass unchanged).
   - **Game over:** `ol.rank` in score order, and 대기실로.
4. **Declared test changes.** Existing tests that pin the old markup are rewritten to the new markup, keeping the behaviour they check (names, texts, roles, timing): `App.layout`, `App.loadingdisc`, `Seats.test`, `App.ranked` where it reads classes, and `App.revealcover` for the card text only. Any further file is listed in the test commit, and the lock covers exactly the files in that commit. The test for chat bubbles in seats is replaced by the log test above.
5. **Commands:** `npm test`, `npm run lint` and `npm run build` pass. The backend is not touched.
6. **Browser check (lead).** Edge with a local jar and vite, at 1280×800 and 390×844, for sunset, chalk, pixel and manga:
   - **Measured:** in playing, reveal and game over, `getComputedStyle` of the header, `.stage-top`, `.clock`, `.timebar`, `.seat`, `.chat-hd`, `.msg .t` and `.send` equals the reference's for font-family, font-size, border, border-radius, background-color and colour.
   - The reference is driven to the same scene by its own controls.
   - Any difference is a failure unless it comes from text content.
   - **Captured:** the same scenes side by side, for the owner.
   - Lobby, start, admin and feedback: one capture each, to confirm they are usable.

## File ownership
- **Test writer:**
  - the new tests above;
  - the edits to existing tests;
  - deleting `themeLayout.test.js` and `themeTokens.test.js`;
  - the `vite.config.js` `NODE_TESTS` list.
- **Implementer:**
  - `frontend/index.html`, `src/room.css` (new), `src/index.css`;
  - `src/App.jsx`, `src/components/GameScreen.jsx`, `Seats.jsx`, `ThemePicker.jsx` (now the header `.moods`), `VolumeControl.jsx`;
  - new components under `src/components/` (for example `Deck.jsx`, `drawPlayer.js`);
  - `src/game/themes.js`;
  - `src/i18n/{ko,en,ja}.json`.
  - Not `ChoiceGrid.jsx`; choice rooms are cycle 111. Until then a choice room keeps working with the old grid inside `.panel`, and its look may be plain.
- **Lead:** docs and the captures.

## Spec review (critic, round 1)
- **H1:** interim aliases, a check that the other screens survive, and no release until 114.
- **H2:** every existing state now has a place in the panel table, with tests.
- **H3:** the phone volume is hidden as the mockup's CSS does. Reported to the owner, not changed.
- **H4:** split into 110 (frame) and 111 (deck and reveal).
- **M5:** exclusion is per rule, and comments are stripped.
- **M6:** the browser check is now measured.
- **Nit:** `ol.seat-grid` and the heading 자리.

## Out of scope
- The deck drawing and reveal frames (111), choice-room markup (112), lobby chat (113), the lobby and start screen (114), and the canvases `#sky` and `#viz` (115).
- Any server change.
