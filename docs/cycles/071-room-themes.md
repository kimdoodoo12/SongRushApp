# 071 room-themes (F-012)

## Goal
Move the approved mockup (https://claude.ai/artifact/F5VLEQudmM2Zv2SvZfZ3ZF, version 2) into the real client. There are six room looks; the host picks one, and every member of the room sees it (D-090). The game screen gets the mockup's room layout:
- a title row;
- seats on both sides;
- a centre stage with a spinning record, an equalizer and a big clock while a song plays, and a flip-in reveal card;
- chat under the stage.

Game rules do not change.

## Themes
Ids and Korean names (the order is the picker order):

| id | name | look |
|---|---|---|
| `play` | 놀이터 | default; bright cartoon |
| `neon` | 무대 | dark neon |
| `sakura` | 벚꽃 | pastel, falling petals |
| `chalk` | 칠판 | chalkboard with a wood frame |
| `star` | 별밤 | starry night |
| `arcade` | 오락실 | retro pixel |

## Backend (`game/Lobby.java`, `ws/GameSocketHandler.java`)
1. **State.** A lobby has a theme, `play` at creation. It is kept across games and returns to the lobby, and it is dropped with the lobby.
2. **Client message.** The client sends `{type:"theme", theme}`. The handler parses it and calls `Lobby.setTheme(playerId, theme)` on the lobby thread, like `start`.
   - A `theme` that is missing, not a string, or not one of the six ids gives error `bad-theme` to the sender, and nothing changes.
   - A sender who is not the host gets error `not-host`.
   - The theme may change in any phase, including during a game.
   - Setting the same theme again is accepted and broadcast again.
3. **Broadcast.** On a change, every connected member gets `{type:"theme", theme}`.
4. **Joiners.** A player who joins, rejoins or replaces a socket gets `{type:"theme", theme}` to that player only, after their join sequence (after the `roster` that follows it), **only when the theme is not `play`**.
   - Existing locked tests pin exact message sequences and the roster's keys, so `roster`, `snapshot` and `joined` keep their exact shape.
   - Lobbies with the default theme send exactly what they send today.
5. **Solo.** There is no theme message in solo games. The theme is chosen locally.

## Frontend
6. **State.**
   - `game/state.js` gains `theme` (initially `null`, meaning "no room theme"). It is set by a `theme` action carrying the theme id, and reset to `null` by `reset`.
   - `session.js` dispatches `{type:'theme', theme}` on a `theme` frame for a room. It handles the frame like `roster`: ignored before the snapshot during a rejoin.
   - `session.js` exposes `setTheme(theme)`, which sends `{type:"theme", theme}` and is used only by the host UI.
7. **Applied look.**
   - The app root element carries `data-room-theme="<id>"`.
   - Inside a room (lobby or game), the id is the room theme, or `play` when none has been received.
   - Outside a room (start screen, solo game), the id is the player's own pick.
     - The pick is made on the start screen with the same picker.
     - It is stored in `localStorage` under `songrush.theme`, with every access wrapped in try/catch.
     - Default `play`.
   - Unknown ids fall back to `play`.
8. **Picker.**
   - It is a `role="group"` with `aria-label="분위기"`, holding one `<button type="button" aria-pressed>` per theme. Each button's accessible name is the Korean theme name, and each shows a small colour swatch.
   - On the start screen it changes the own pick.
   - In the lobby:
     - The host's picker calls `setTheme` and shows the server's current theme as pressed.
     - Non-hosts see the picker disabled (`disabled` on every button), with the current theme pressed.
9. **Styling.**
   - Each theme is a set of CSS custom properties under `[data-room-theme="<id>"]` in `src/index.css` (colours, fonts, border width, radius, shadows, record, equalizer and bar fills), ported from the mockup.
   - Components use the tokens through Tailwind arbitrary values or small component classes.
   - Fonts load from Google Fonts with `display=swap` and real fallbacks: Jua, Gowun Dodum, Black Han Sans, IBM Plex Sans KR, Nanum Pen Script, Gaegu, Do Hyeon, DotGothic16, Gowun Batang.
   - Decorations (sakura petals, stars, arcade scanlines) are CSS-only or a small `aria-hidden` element, with no images. They respect `prefers-reduced-motion`.
10. **Room game layout (mockup).**
    - **Title row:** the room code (no "방 코드:" text during a game, because a locked test checks it is absent) and the settings line. Keep today's single `.settings` `<p>` with the same text.
    - **Seats** stay the `Seats` component: same props, roles and texts. Only the styling is new.
    - **Stage while a song plays:**
      - the round, keeping an element whose exact text is today's `n / N`;
      - a big clock showing the remaining whole seconds followed by `초`, from the existing timer state;
      - a spinning record and an equalizer, `aria-hidden`;
      - the time bar, keeping the `progressbar` role, its label and the `.timebar` / `.timebar-fill` classes;
      - the hint `들리는 곡의 애니 제목을 채팅으로 맞혀 보세요`;
      - the skip control.
      - The record and equalizer show only while the round has started and no reveal is shown. Loading, watching and sound-unlock texts stay as today.
    - **Reveal:** the existing reveal card (same texts and `.reveal` class) is styled as the mockup's flip card, with a CSS flip-in animation.
    - **Chat:**
      - Each log line stays a single text node `"<name>: <body>"`, because locked tests match the whole text.
      - Each line gets a small coloured marker before the text, using a per-name colour.
      - The input and 보내기 keep their labels.
    - **Solo:** the same stage and chat, without seats.
11. **Lobby.**
    - A header with the room code and share row.
    - The picker (item 8).
    - The seats.
    - Host controls or the waiting text, and 나가기.
    - The hidden roster `<ul className="roster" hidden>` stays exactly as is.
12. **Start screen.** Same structure and behaviour, styled with the theme tokens, plus the picker.
13. **Unchanged.** `<h1>SongRush</h1>` and the credit footer stay.

## Acceptance criteria
**Backend** (JUnit 5, `.\gradlew.bat test` from `backend/`):
1. `game/LobbyThemeTest.java` (new) uses `RecordingOutbox`, `ManualScheduler` and `GameFixture` as the other Lobby tests do. It checks:
   - **Host sets a theme.** The host's `setTheme(host, "neon")` sends `{type:"theme", theme:"neon"}` to every connected member, and to no disconnected one.
   - **Not the host.** A non-host gets `error not-host`, and nothing is broadcast.
   - **Bad theme.** `"lava"` and `null` give `error bad-theme` to the sender only.
   - **Joiner after a change.** After a change to `star`, a new joiner's messages end with `theme star`, sent after its `roster`.
   - **Joiner on the default.** With the default theme, a joiner gets no theme message.
   - **Rejoin.** A rejoining player gets `theme` when the theme is not `play`.
   - **Kept across games.** The theme survives a game and the return to the lobby.
   - **During a game.** Setting the theme during a game is accepted.
2. `ws/ThemeSocketTest.java` (new) uses a real socket through the existing `WsFixture` and `TestClient`. It checks:
   - **Host change.** `{type:"theme", theme:"sakura"}` from the host reaches both clients.
   - **Malformed.** `{type:"theme", theme:5}` gives `error bad-theme`.
3. All earlier backend tests pass unchanged.

**Frontend** (Vitest, from `frontend/`):

4. `src/App.theme.test.jsx` (new) uses the existing fakes. It checks:
   - **Start screen.** The start screen shows the 분위기 group with 6 buttons in the order 놀이터, 무대, 벚꽃, 칠판, 별밤, 오락실.
     - Clicking 칠판 sets `data-room-theme="chalk"` on the app root and stores `chalk` in `localStorage['songrush.theme']`.
     - A stored `star` is applied on the next mount.
     - A stored unknown id gives `play`.
   - **Lobby as host.** Clicking 무대 sends exactly `{type:"theme", theme:"neon"}`. The pressed button changes only after a server `theme` frame.
   - **Lobby as non-host.** The picker buttons are disabled. A server `{type:"theme", theme:"arcade"}` frame sets `data-room-theme="arcade"` and presses 오락실.
   - **Room theme over own pick.** A room uses the room theme even when the own pick differs. Before any theme frame, the room shows `play`.
   - **Game stage.** After `start`, the stage shows the hint text and a clock text ending in `초`.
   - **Reset.** After leaving the room (`reset`), the root shows the own pick again.
   - **Reducer.** `reducer(initialState, {type:'theme', theme:'star'}).theme === 'star'`, and `reset` gives `null`.
5. All earlier frontend tests pass unchanged, including `App.layout.test.jsx` and `components/Seats.test.jsx`.
6. `npm test`, `npm run lint` and `npm run build` exit 0.

## Test list
- `backend/src/test/java/com/songrush/game/LobbyThemeTest.java`
- `backend/src/test/java/com/songrush/ws/ThemeSocketTest.java`
- `frontend/src/App.theme.test.jsx`

## File ownership
- **Backend implementer:** `backend/src/main/java/com/songrush/game/Lobby.java`, `backend/src/main/java/com/songrush/ws/GameSocketHandler.java`.
- **Frontend implementer:**
  - `frontend/index.html` (the font link);
  - `frontend/src/index.css`;
  - `frontend/src/App.jsx`;
  - `frontend/src/components/*.jsx`, with a new `ThemePicker.jsx` and a new `game/themes.js` for the id/name list;
  - `frontend/src/game/state.js`;
  - `frontend/src/game/session.js` (only the `theme` frame and `setTheme`).
- **Never touch:** the untracked `Dockerfile`, `compose.yml` and `.dockerignore` at the repo root. They are the owner's own Docker work.

## Out of scope
- STOMP.
- Sounds.
- New game rules.
- Custom uploaded backgrounds.

## Revisions after spec review round 1
These override the items above wherever they conflict.

- **H1. Six themes.** D-091 records the six themes.
- **H2. The visual source of truth is `docs/design/071-room-mockup.html`** (a copy of mockup version 2).
  - Port its per-theme tokens (`.room.t-*` blocks), its layout rules and its decorations.
  - Before merging, the lead takes real-browser screenshots of the lobby, a room game while playing, and the reveal, for at least `play`, `neon` and `arcade` at 1280 px and 390 px. The owner confirms them.
- **H3. Where the theme lives.**
  - `App` renders a full-width outer `<div className="app-shell min-h-screen" data-room-theme=...>` that paints the theme background (`--bg`) over the whole page. Inside it is today's `.app` max-width wrapper.
  - The body background becomes a neutral fallback.
  - Tests query `[data-room-theme]` with `document.querySelector`.
- **M1. When joiners get the theme.**
  - On paths that send `roster` after `sendJoinedAndSnapshot` (lobby join, in-game join, grace rejoin), the room-theme message is sent to that player right after that `roster`. It comes before anything `room.resume()` broadcasts.
  - On the replace path (connected-member reconnect, no roster), it is sent right after `snapshot`, or after `settings` when there is one.
  - It is sent only when the theme is not `play`.
  - LobbyThemeTest adds the replace case: joined, snapshot, roomTheme.
- **M2. Wire names.**
  - Message `{type:"roomTheme", theme}`, error `bad-room-theme`.
  - Java method `setRoomTheme`. State field `roomTheme`. Action `{type:'roomTheme', theme}`. Session API `setRoomTheme`.
  - localStorage key `songrush.roomTheme`.
  - The test names in the criteria change accordingly.
- **M3. DOM invariants the locked tests rely on.**
  - No new `<li>` anywhere on the start or lobby screens. The picker and decorations use `div`, `span` and `button`.
  - The `.settings` `<p>` renders in both solo and room games under the same condition as today, exactly once, above the stage.
  - Reveal texts render once. The flip is a CSS animation of the single card, never a duplicated face.
  - The chat marker is an empty `aria-hidden` span before the text node. The line text stays exactly `"<name>: <body>"`.
- **M4. Cycle size.** One cycle with two implementers working in parallel on disjoint files. Disagreement recorded: the critic preferred a split. The lead keeps one cycle because the backend part is small and independent.
- **Nits.**
  - Validation runs before the host check: a bad theme from a non-host gives `bad-room-theme`.
  - A `setRoomTheme` from a disconnected member or a closed lobby is ignored silently.
  - The client adds `ERROR_TEXT['bad-room-theme'] = '알 수 없는 분위기입니다.'`.
  - The clock shows `Math.ceil(remainingMs / 1000)`.
  - "In a room" means `state.room !== null`, including the over and disconnected screens.
  - The client never sends `roomTheme` in solo games.
