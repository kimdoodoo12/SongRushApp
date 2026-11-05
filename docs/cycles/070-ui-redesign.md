# 070 ui-redesign (F-011)

## Goal
Restyle the web client with Tailwind CSS. Rooms use a seat-based layout taken from classic chat-guessing rooms (D-089):
- player seats down both sides, each showing that player's latest message as a speech bubble;
- the song stage in the centre;
- the chat log and the answer input below.

Game behaviour does not change.

## Behaviour

### 1. Tailwind
- Add Tailwind CSS v4 with its Vite plugin: `tailwindcss` and `@tailwindcss/vite`, exact versions pinned in `package.json` and the lockfile.
- `src/index.css` holds `@import "tailwindcss";` plus a small `@theme` palette:
  - a dark night-blue background, with surface, border and text colours;
  - one accent colour and one "correct" green.
- `App.css` is removed or reduced to what Tailwind cannot express.
- Styling is done with Tailwind classes in JSX. Existing `className` values that tests or code query (for example `chat-log`, `timebar`, `reveal`, `scoreboard`, `ranking`) stay on their elements, next to the Tailwind classes.
- `npm run build` and `npm run lint` pass.

### 2. App shell
- A top bar with the SongRush wordmark (plain text, our own) and, during a game, the round `n / N`, the own score and the settings line.
- The page background is the dark theme.
- The credit footer stays, in small muted text.
- The layout works from 360 px phone width up to desktop, with no horizontal scroll.

### 3. Start screen
- A centred card with two clear sections:
  - **혼자 하기**: nickname, 곡 수, filters, 시작.
  - **같이 하기**: 방 만들기, 방 코드, 참가.
- All existing labels, buttons, notices and behaviour stay.

### 4. Room seats: `components/Seats.jsx`, a new component
- **Count and placement.** Eight seats are shown in the lobby and during a room game.
  - On screens at least `md` wide: seats 1-4 in a column on the left of the centre, seats 5-8 in a column on the right.
  - Below `md`: one compact strip of seats (a wrapping or scrolling row) above the centre.
- **Order.** Seats follow the roster order. Seats beyond the roster render as empty slots with the text `빈 자리`.
- **Contents of a filled seat:**
  - an avatar circle with the name's first character;
  - the name, with the same text as today's roster row (`(방장)`, `(나)` marks) so existing lobby assertions hold;
  - the score during a game, from the scoreboard when present, else the roster;
  - a disconnected seat shown dimmed.
- **Speech bubble.**
  - The newest chat line from that player appears as a speech bubble by their seat. The text is capped at 40 characters plus `…`.
  - The bubble disappears when the next song is prepared, or 6 s after it appeared, whichever comes first.
  - The bubble shows only chat the client already received. Nothing new comes from the server, and correct answers stay hidden (the server never sends them as chat).
- **Correct mark.** When `correct` names a player, that player's seat gets a highlighted ring and the text `정답!` until the reveal ends.
- **Semantics.** The seats container is `role="list"` with `aria-label="자리"`. Each seat is a `role="listitem"`.

### 5. Lobby screen
- Room code shown large, with the copy-link row, inside a header card.
- The seats from item 4.
- Host controls (곡 수, filters, 게임 시작) or the waiting text, and 나가기.
- **The old roster list.** The `<ul className="roster">` is replaced by the seats. Any existing test that queries roster rows by text must still pass, because seat names carry the same text.

### 6. Game screen
- The centre stage holds, in order:
  - the round status;
  - a large time bar (same `progressbar` role and label);
  - a "재생 중" pulse while the song plays, the loading text and the sound-unlock button;
  - the skip button with `스킵 n/m`;
  - the `"<name> 정답!"` line;
  - the reveal card (title large, theme slug, song, artists, winner);
  - the error text.
- **Chat.** Below the stage, the chat log scrolls inside a fixed-height panel and auto-scrolls to the newest line. The answer input and 보내기 sit underneath and stay visible.
- **Scoreboard.** The scoreboard and final ranking keep their lists and labels. During a room game the seats show the scores; the scoreboard list may be visually compact.
- **Solo.** There are no seats in solo mode; the centre and chat are full width.

### 7. State
`game/state.js` adds `name` to each chat entry: `{ id, name, text }`. `text` stays exactly `"<name>: <text>"`. Nothing else changes.

## Acceptance criteria (Vitest, from `frontend/`)
1. **`components/Seats.test.jsx`** (new) renders `Seats` directly and checks:
   - **Seat count.** 3 roster players give 3 filled seats and 5 `빈 자리` slots, in roster order. The host and own marks are in the name text.
   - **Speech bubble.** With chat lines from 민수 and then 철수, each seat shows only its own player's newest line. A 45-character line is cut to 40 characters plus `…`.
   - **Bubble timeout.** With fake timers, a bubble is gone after 6 s. A newer line from the same player resets the timer.
   - **Bubble cleared on prepare.** A change of the round index clears every bubble.
   - **Correct mark.** `correct: "민수"` gives 민수's seat the text `정답!` and no other seat.
   - **Scores.** A score from the scoreboard is shown over the roster score.
   - **Disconnected.** A disconnected player's seat carries `data-connected="false"`.
2. **`App.layout.test.jsx`** (new) drives the app through the existing fakes, `testing/fakes.js`, unchanged:
   - **Lobby.** A room lobby shows `role="list"` with name `자리` and 8 list items.
   - **Room game.** A room game shows the seats, the time bar and the chat input. A chat frame from another player shows their bubble in their seat.
   - **Solo.** A solo game shows no `자리` list.
   - **Reveal.** After `reveal`, the reveal card title is visible.
3. **`game/state` chat entries** carry `name` (a test in `App.layout.test.jsx` or `state.test.js`).
4. **Earlier tests.** Every existing frontend test passes **unchanged**, including all `App.*.test.jsx`, `clockSync`, `playback` and `noTypeScript`.
5. **Commands.** `npm test`, `npm run lint` and `npm run build` exit 0.

## Test list
- `frontend/src/components/Seats.test.jsx`
- `frontend/src/App.layout.test.jsx`

## File ownership
- **Test writer:** the test list.
- **Implementer:**
  - `frontend/package.json` and `package-lock.json` (Tailwind only), `vite.config.js`;
  - `src/index.css` (new), `src/App.css`, `src/main.jsx`, `src/App.jsx`;
  - `src/components/*.jsx`, including the new `Seats.jsx`;
  - `src/game/state.js` (only the chat `name`).

## Constraints
- No new runtime dependencies besides Tailwind.
- No images, fonts or icons from other games or sites. Avatars are letters and colours, and any icons are inline SVG or Unicode.
- All UI text stays Korean.
- Never put answer or catalog data in the DOM before the reveal. Seats show only roster, score and chat data the client already has.

## Out of scope
- STOMP (F-010) and new game features.
- Sound effects and animations beyond simple CSS transitions.

## Revisions after spec review round 1
These override the items above wherever they conflict.

- **B1. Seats and the roster list.**
  - Seats use no `<li>`. The seats are a `<div role="list" aria-label="자리">` holding `<div role="listitem">` children.
  - The lobby keeps `<ul className="roster">` with exactly today's `<li>` rows (name plus `(방장)` and `(나)`). It may be visually hidden with `sr-only` or shown small. Locked `listRows()` helpers read every `<li>` on the lobby screen.
- **H1. No game info in the top bar.**
  - The top bar holds only `<h1>SongRush</h1>`.
  - Round, score and the settings line stay where they are today, inside the game screen. They are styled, not duplicated.
  - A seat shows its score as `N점` (for example `3점`). The text `점수:` is never used on a seat.
- **H2. Seats props and the bubble text.**
  - Signature: `Seats({ players, me, chat, scoreboard, correct, roundIndex })`.
    - `players`: roster rows `{ name, score, host, connected }`.
    - `me`: the own name, or null.
    - `chat`: state chat entries `{ id, name, body, text }`.
    - `scoreboard`: `[{ name, score }]` or null.
    - `correct`: the winner's name, or null.
    - `roundIndex`: the current round index, or null.
  - State change (item 7): a chat entry is `{ id, name, body, text }`. `body` is the raw message; `text` stays exactly `"<name>: <body>"`.
  - The bubble shows `body` without the name prefix, cut to its first 40 UTF-16 code units plus `…` when longer.
- **H3. jsdom.** No `scrollIntoView` and no `matchMedia`. Auto-scroll sets `el.scrollTop = el.scrollHeight` in an effect. Responsive layout uses Tailwind `md:` classes only.
- **M1. Bubble state lives inside `Seats`.**
  - `Seats` keeps a chat-id watermark. When `roundIndex` changes, the watermark moves to the newest chat id, and only lines after it can show.
  - Each player's bubble is the newest line after the watermark with that `name`.
  - A `setTimeout` of 6000 ms hides it. A newer line from the same player restarts that player's timer.
  - The reducer gains only the `name` and `body` fields.
- **M2. Where the reducer test goes.** It lives in `App.layout.test.jsx`. It imports the reducer and the initial state from `game/state.js` and checks that a `chat` action gives an entry `{ id, name, body, text }`.
- **M3. Narrower scope.**
  - The start screen and the lobby keep their structure and only get Tailwind styling. Item 3's two-section card is a styling arrangement of the existing two blocks.
  - The "재생 중" pulse is dropped.
  - The seat `정답!` mark stays (D-089 room feel). It is a small badge.

## Result
- **Commits.** Tests `b7a3da6`, implementation `e42c96b`, then fixes `fa0e4d2`. The fixes are attempt 2, after code review: M1 hidden roster, M2 phone seat strip and shorter chat, N1 `enabled:hover`.
- **Verifier at `fa0e4d2`.**
  - `npm test`: 19 files, 266 tests passed.
  - `npm run lint`: exit 0.
  - `npm run build`: exit 0, CSS 38.29 kB, JS 256.12 kB.
  - The locked-test diff from `b7a3da6` is empty.
  - `npm ci` reproduced the lockfile at `e42c96b`.
- **Versions.** Tailwind `tailwindcss` 4.3.4 and `@tailwindcss/vite` 4.3.4, both pinned exactly, as devDependencies only.
- **Visual check.** Headless Edge screenshots of the start screen at 1280 px and inside a 360 px frame. Both render the dark theme with no horizontal overflow.
  - Edge enforces a minimum window width, so the 375 px window shot was not representative. The 360 px iframe shot was used instead.
  - The lobby and room-game layouts were not checked in a browser. They need a live backend.

## Code review
- No Blocker or High findings. M1, M2 and N1 were fixed in `fa0e4d2`.
- Recorded below the reporting bar:
  - there is no subgrid fallback for old browsers;
  - a disconnected seat's opacity creates a stacking context;
  - md seat cards stretch with a tall stage.
