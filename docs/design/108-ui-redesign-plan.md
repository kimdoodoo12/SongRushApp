# 108 ui-redesign-plan (D-121, D-122, D-123)

> **Superseded in part by D-124 (2026-11-11). Kept as history.**
> - The site now uses the mockup's stylesheet verbatim: `room.css`, plus the owner-approved `wide.css`, `start.css`, `choice.css` and `scroll.css`.
> - `themeLayout.test.js` and `ThemeDecor` are gone, and the mood is `html[data-mood]`, not `data-room-theme`.
> - Items 3-9 below were not done as written. They were done as cycles 110-116 (see BACKLOG F-032), followed by 117-119 (D-125 to D-127).
> - The request, the reference and the eight mood ids below are still correct.


## Request (owner, 2026-11-10)
- "이제 디자인 다른 세션으로부터 받은거 있지? 그거 적용시켜봐"
- Answers to the lead's questions:
  - Who picks the mood: "방장이 고르기 (지금처럼)".
  - Mood list: "시안 8종으로 교체".
  - Scope: "이거 참고하라고보냈어. 대기실도 참고해서 만들면 될듯".
  - New features (multi-select): 객관식 번호 배지, 대기실 채팅. Not picked: 맞힌 시간 표시, 속도 등급.

## Reference
- `docs/design/108-ui-design-reference.html` is the mockup the owner made in another session (artifact "SongRush Listening Room", version 1794321058-c443), saved verbatim.
  - Works, songs, players and covers in it are fictional. Its music is synthesized with Web Audio and its game is a local simulation with bots. Only the look and the layout are taken from it, never its game logic.
  - It is a reference, not a spec: where it disagrees with a game rule in CLAUDE.md or BACKLOG "Decided", the rule wins.

## What changes
1. **Moods.** The six room themes (`play, neon, sakura, chalk, star, arcade`) become eight moods: `sunset` (default), `rain`, `sakura`, `neon`, `chalk`, `pixel`, `matsuri`, `manga`. Each mood sets colour tokens, fonts, corner radius, the disc style, the chat style, the reveal frame and the lobby signage. Host-picked and broadcast as today.
2. **Game room layout.** Left: the stage (round and clock, time bar, deck with the disc, panel with hint/skip/reveal card, seats). Right: a 380 px chat column. Below 900 px the two stack.
3. **Reveal.** The disc slides aside and the cover appears in a frame at its own aspect ratio (clamped 0.56–0.8), in the mood's frame (polaroid, foggy glass, holo card, CRT, pinned print, pixel mosaic, festival poster with stamp, manga panel). With no cover, the same frame holds a title card.
4. **Choice rooms.** Top row: a small disc and the hint. Bottom row: only the tiles, centred; 4 → 4×1, 6 → 3×2, 8 → 4×2. Number badges 1..N on the tiles (D-123). At reveal the correct tile flies into the big frame.
5. **Lobby.** A room-code ticket (code, share link with copy, stub with n/8), a settings card with every existing control, the start and leave buttons, seats, per-mood signage text, and **lobby chat** (D-123).
6. **Start screen.** Uses the new mood tokens; no new layout.

## Not taken from the mockup
- The time-to-answer line and the speed grade (D-123: no).
- The mood bar in the game header for every player. The host picks in the lobby (D-122); the game header shows the current mood only.
- The mockup's cover upload control, its cheat list and its bots.

## Rules that hold
- No answer before the reveal: the reveal frame, the cover and the titles render only from the `reveal` frame, as today. Choice covers in `prepare` stay unmarked (D-111).
- Fairness: decoration never delays audio start; canvases and animations run independently of the playback clock.
- `prefers-reduced-motion`: every new animation (reveal frames, fly-in, background scenes) is off or instant.
- Fonts: the mockup's Google Fonts are loaded with `display=swap`; nothing waits on them.
- i18n: every new visible string has ko, en and ja entries (keys test).

## What exists (survey, 2026-11-10)
- **Themes.**
  - `frontend/src/game/themes.js:4-13` has six ids, the default `play`, and the own pick in localStorage `songrush.roomTheme`.
  - Token blocks are in `index.css:33-139`, with overrides at `779-840`.
  - `ThemeDecor.jsx` draws petals and stars. `ThemePicker.jsx` is used in the lobby and on the start screen.
  - Backend: `Lobby.java:39` `ROOM_THEMES`, `:41` the default, `:618` the snapshot omits the default.
- **Tests with the old ids:** `App.theme.test.jsx`, `App.i18n.test.jsx:343,355`, `i18n/keys.test.js:51-55,113-115`, `ws/ThemeSocketTest.java`, `game/LobbyThemeTest.java:151`.
- **Layout rules.** `frontend/src/themeLayout.test.js` forbids theme-scoped CSS from changing layout (box properties, borders, text, overflow). Cycle 100 adds "theme box equality = 0 diff".
- **Game screen.**
  - `GameScreen.jsx` is a 3-column grid: seats | stage | chat (`:477`).
  - The reveal cover is a fixed `h-32 w-24` box (`:125-165`), pinned by `App.revealcover.test.jsx:148-156`.
- **Choice grid.** `ChoiceGrid.jsx` always uses 4 columns (cycle 100 A10). It has no number badges, and it has the `data-choice` marks.
- **Lobby.**
  - `LobbyScreen.jsx` has no chat. `ChatForm` renders only on the game screen (`GameScreen.jsx:454`).
  - The client reducer already appends `chat` frames on any screen (`state.js:280`).
  - The chat is not cleared on returning to the lobby.
- **Server.**
  - `Lobby.chat` forwards only while a room runs (`Lobby.java:395-416`). In the lobby phase `room == null`, so the line is dropped.
  - The rate bucket (burst 5, 2/s), `MAX_CHAT` 200 and `isInvisible` are private to `Room`.
- **Fonts.** A Google Fonts link sits in `frontend/index.html:7-12`. There is no Content-Security-Policy (Caddyfile, backend).
- **CSS** is Tailwind v4 plus hand-written token blocks.

## Declared rule changes (the owner's mockup supersedes them, D-121)
1. **`themeLayout.test.js`** stays in force for the structural layout: the stage, the chat column, the seats, the lobby controls and the choice grid box.
   - The mood frame elements are exempt, by an explicit allow-list of selectors:
     - the reveal frame (`.cover`, `.shot`, `.cap`, the stamp and sfx layers);
     - the tile skin (padding and rotation);
     - the lobby signage.
   - An exempt selector must not change the size of its parent box. Each cycle's browser check verifies this: the stage and grid boxes are equal across moods.
2. **Choice grid columns (cycle 100 A10):** 4 → 4×1, 6 → 3×2, 8 → 4×2 at every width, as the mockup draws them. D-121 makes the mockup the reference.
   - The other session reported that the owner chose this row layout there. That report is not evidence here, so the cycle doc shows the owner the capture.
   - The A10 browser check is kept: the whole grid is visible without vertical scroll at 390×844 while a song plays.
3. **Reveal cover size (D-109 kept in intent).** D-109 requires one cover slot size, so the card height never changes between rounds.
   - In the mockup the **deck** is a fixed square with the frame inside it. The deck box therefore keeps D-109: its size never depends on the image.
   - Only the frame inside the deck follows the image ratio:
     - it is clamped to 0.56–0.8, with `object-fit: cover` at 50% 30%;
     - it is 0.714 until the image loads;
     - it is absolutely positioned, so nothing else moves.
   - With no `coverUrl`, the frame holds a title card instead of D-109's empty placeholder, as in the mockup. The owner is told this in the cycle report.
   - The `h-32 w-24` cover box moves into the deck.
     - `App.revealcover.test.jsx` changes in that cycle, as a declared test change.
     - Its slot-always-present and no-height-change assertions are kept.
4. **Theme ids, additive (critic M1).**
   - **Server.**
     - It accepts the eight new ids **and** the old `play`, `star` and `arcade`, which it maps to `sunset`, `rain` and `pixel` before storing. The default is `sunset`.
     - The snapshot always carries `roomTheme`, so `Lobby.java:618` no longer omits the default and a client never has to guess it.
   - **Client.** It falls back to `sunset` for an unknown or old stored id. `neon`, `sakura` and `chalk` keep their ids, with the new look.
   - **Tabs opened before the release** keep working. They can still pick an old id, and for a new id they do not know they show their own fallback look.
   - The backend and frontend items can therefore ship in either order.

## Resulting items (one cycle each, in order)
1. **B-031 backend: eight mood ids.**
   - `ROOM_THEMES` holds the eight ids of D-122. `play`, `star` and `arcade` are mapped as in declared change 4. `DEFAULT_THEME` is `sunset`.
   - The snapshot always includes `roomTheme`.
   - **Tests:**
     - each new id round-trips;
     - each old id is stored and broadcast as its mapped id;
     - an unknown id is `bad-room-theme`;
     - a fresh room's snapshot carries `roomTheme: "sunset"`.
   - Existing tests that pin `play` as the default, or pin the omission, change as declared.
2. **F-025 frontend: mood tokens and picker.**
   - `themes.js` holds the eight ids and swatches, with the default `sunset`. The old stored ids fall back to it.
   - `index.css` has one token block per mood, taken from the mockup's `[data-mood]` blocks: colours, display, body and chat fonts, radius and glow.
   - The Google Fonts link adds Yeon Sung, Press Start 2P and JetBrains Mono, keeps `display=swap`, and drops unused families.
   - The picker shows eight buttons, each with a swatch and a name. `theme.<id>` exists in ko, en and ja.
   - `ThemeDecor` is removed; the scenes come in item 7.
   - Tests:
     - the theme, i18n and keys tests use the new ids;
     - jsdom checks `data-room-theme` for each id.
3. **F-026 frontend: game room structure and choice rows** (critic H2). This is the structure only, in a neutral look; the per-mood skins are F-030.
   - **Columns.** The stage is on the left and a 380 px chat column on the right. They stack below 900 px.
   - **Stage contents:**
     - the round and clock;
     - the time bar;
     - the deck with the disc, in one shared style;
     - the panel with the hint, skip and the reveal card;
     - the seats as a 4-column grid under it (2 columns under 560 px), keeping the role list "자리" with 8 items.
   - **Choice room.**
     - Top row: the small deck and the hint.
     - Bottom row: the tiles, with columns per declared change 2.
     - Number badges 1..N, `aria-hidden` (the tile keeps its `aria-label` "보기 N").
   - **Chat lines** keep the single text node `이름: 내용`.
   - **Tests.**
     - The existing role, label and text assertions stay.
     - New tests: the badges (1..k, in order) and the column class per k.
   - **Browser check** at 1280×800 and 390×844, in ko, en and ja:
     - the A10 no-scroll check;
     - the stage and grid boxes are equal in two moods.
4. **F-030 frontend: per-mood skins** (split from F-026).
   - The disc style per mood: LP and tonearm, CD, picture disc, wire ring, chalk record, pixel disc, drum, pen record.
   - The chat style per mood: bubble, diary line, pill, terminal, chalk, `▶` dialogue, lantern dot, manga bubble.
   - The tile skin per mood.
   - **Tests:**
     - `themeLayout.test.js` with the declared allow-list (declared change 1);
     - jsdom: the mood reaches the disc and the chat log for each id.
   - **Browser check:** the stage, chat column and grid boxes are equal across all eight moods at 1280×800 and 390×844, in ko.
5. **F-027 frontend: reveal frame and fly-in.**
   - **Reveal.** At `reveal` the disc slides aside (`--peek = 18 + ar×30 %`), and the cover appears in the mood frame at its own aspect ratio.
     - With no `coverUrl`, a title card appears in the same frame.
     - In a choice room the correct tile flies into the frame (FLIP, 750 ms).
   - One shared frame style; the per-mood frames are F-031.
   - **Reduced motion:** everything is instant under `prefers-reduced-motion`.
   - **Tests:**
     - the frame renders only after the `reveal` frame;
     - `--ar` follows the image's natural size, clamped. The test stubs `naturalWidth` and `naturalHeight` with `Object.defineProperty` and fires `load`. Before `load` the value is 0.714;
     - the deck box is the same with and without a cover;
     - the no-cover title card;
     - reduced motion adds no animation.
     - `App.revealcover.test.jsx` changes per declared change 3.
6. **F-031 frontend: per-mood reveal frames** (split from F-027).
   - The frames: polaroid, foggy glass wipe, holo card, CRT glitch, magnet-pinned print, pixel mosaic-to-sharp, festival poster with the 정답 stamp, and manga panel with speed lines and "두둥!".
   - All of them are instant under reduced motion.
   - **Tests:**
     - jsdom: the frame's mood for each id;
     - reduced motion adds no animation and creates no pixel canvas;
     - the stamp text is 정답 with a winner and 통과 without one (i18n).
7. **B-032 backend: lobby chat.**
   - **Behaviour.** While a lobby has no running room, a `chat` from a connected member is broadcast as `{type:"chat", name, text}` to the lobby's connected members.
   - **Rules,** the same as in-game chat and shared, not copied:
     - burst 5 and 2/s per player (`rate-limited`);
     - `MAX_CHAT` 200 (`too-long`);
     - invisible-only lines are dropped.
   - It is never answer-checked. A member who is not connected, or a closed lobby, sends nothing.
   - **Tests:**
     - broadcast, rate limit, too-long, invisible, no answer check;
     - the in-game chat tests pass unmodified.
8. **F-028 frontend: lobby redesign and lobby chat.**
   - **Layout:**
     - a room-code ticket: code, share link and copy, and a stub with n/8;
     - a settings card with every existing control and the same labels;
     - the start and leave buttons, and the seats.
   - **Per-mood signage** from the mockup's `SKIN` table: title, code label, admit word, start label, seat heading and empty-seat word. All of these are i18n keys in ko, en and ja.
   - **Chat.** The lobby shows a chat log and input.
     - The chat is cleared when the player enters a lobby from the start screen, and when they press 대기실로 after a game.
     - The last song's reveal chat stays on the final ranking screen until then.
   - **Guests** see the settings locked, "분위기 (방장이 고름)", and the waiting text.
   - **Tests.**
     - The lobby labels and buttons stay.
     - New: lobby chat send and render; the chat is cleared on entry; signage for two moods × three languages.
     - The `querySelectorAll('li')` helper in `App.lobby.test.jsx` is scoped to the roster, as a declared test change.
9. **F-029 frontend: mood scenes and visualizer.**
   - **Scenes:** a background canvas per mood: embers, rain, petals, chalk dust, pixel stars, fireworks on reveal for matsuri, none for manga.
   - **Visualizer:** a deck visualizer around the disc while playing.
   - **Limits.**
     - They pause when the tab is hidden, and they are off under reduced motion.
     - The analyser is a side tap. The source → gain → destination path is unchanged; the analyser hangs off the gain and has no output.
   - **Tests:**
     - the playback start time for a given `startAt` is identical with and without the visualizer;
     - the analyser is never connected to the destination;
     - no canvas under reduced motion;
     - one canvas;
     - it is removed on leave.

## Order and release
- Every item can be released alone (declared change 4).
- The owner reviews the look in the browser after F-030, F-031 and F-028, using the captures in each cycle doc.

## Spec review (critic, round 1)
- **H1, the D-109 cover slot.** Declared change 3: the deck stays a fixed box, and the frame floats inside it.
- **H2, cycle size.** F-026 and F-027 are split, which adds F-030 and F-031.
- **M1, id compatibility.** Declared change 4: additive, with the old ids mapped and the theme always in the snapshot.
- **M2, untestable criteria.** Added the analyser side-tap test and the `naturalWidth` stub. The box checks are fixed browser checks.
- **M3, unsupported owner confirmation.** Reworded.
- **Nit, lobby chat clearing.** The clearing moments are stated.
- **Secrecy and fairness.** No problem found. The fly-in uses only `correctChoice` from `reveal`, and lobby chat runs only when no room exists.
