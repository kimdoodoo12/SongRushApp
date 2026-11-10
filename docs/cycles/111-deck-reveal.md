# 111 deck-reveal (D-124; plan in docs/cycles/110-room-port.md)

## Goal
The `.deck` behaves as in the mockup (`docs/design/108-ui-design-reference.html`, Version 21):
- the disc is drawn per mood by a port of `drawPlayer()`;
- `.deck` gets `idle` and `revealed` as the mockup's `startRound`, `reveal` and `finish` set them;
- the reveal frame is a port of `showCover()` and `pixelate()`, exactly as specified in "Reveal frame" of `docs/cycles/110-room-port.md`.

## Behaviour
1. **Disc.** `drawPlayer()` is ported as a module, `src/components/drawPlayer.js`. It draws to the `.disc > canvas` for the current mood (`html[data-mood]`) at the mockup's canvas size and DPR handling. It redraws on a mood change.
   - The mockup's demo inputs are not taken. Where `drawPlayer` reads demo song data (hue, seed or similar), it uses a fixed value per mood.
2. **Deck classes.**
   - Before the first round and after the game: `.deck.idle`.
   - While a song is prepared or playing: no class.
   - From the `reveal` frame until the next `prepare`: `.revealed`. It is restarted on each reveal by removing it, forcing a reflow, and adding it again, as the mockup's `play()` does.
   - `body.playing` is as in cycle 110.
3. **Reveal frame.** Everything in 110's "Reveal frame" section: `--ar`, `--peek`, `.missing` and `.noimg`, the `.cap` rule per mood (no rarity badge), the `.stamp` text, `.sfx`, `pixelate` with the reduced-motion skip, and emptying `.cover` again on the next `prepare`.
   - The cover is never requested before the `reveal` frame.
4. **The old reveal-cover slot is removed.** The `.reveal-cover` box inside the `.reveal` card (kept in 110 for the old test) goes away; the deck frame is now the cover. `App.revealcover.test.jsx` is rewritten as a declared change to test the deck frame instead:
   - the cover appears only after the reveal;
   - with a null or failed cover, `.missing` is set and the Korean title shows;
   - the image `alt` is "<title> 표지", as in the mockup;
   - the deck box keeps its size (D-109 intent).
5. **Chat line time (`.tm`).** Each `.msg` gets the mockup's `span.tm` with the local `HH:MM` of arrival, between `i` and `.t`. The CSS shows it only in rain.
   - Tests that compare a chat line's whole text are changed, as declared changes, to read `.t`. The `.t` text stays exactly `이름: 내용`.
6. **System lines.** As in the mockup, the chat gets these system lines (`.msg.sys`), in the current language:
   - "<n>번째 곡 시작" when a song becomes audible;
   - "게임을 시작해요" at game start;
   - "게임 종료" at game over.
   - They are client-side only and carry no song information.

7. **Mood on the game screen (critic M1 of cycle 110).** On the game screen `html[data-mood]` is always the room theme, `sunset` until the theme frame, also while connecting and rejoining, so it never flips between the own pick and the room's. Only the start screen and the lobby use the own pick, outside a room. `.stage-top .tag` reads the same mood.

## Acceptance
1. **`App.deck.test.jsx`** (new, jsdom):
   - `.deck.idle` before the game, no class while playing, `.revealed` after `reveal`, and `.idle` after `gameOver`;
   - `.revealed` is re-added on the second reveal;
   - a mood change triggers a redraw (the `drawPlayer` module is mocked and its calls counted);
   - every item in 110's reveal-frame acceptance list: before and after reveal, `--ar` and `--peek` after a stubbed load, `.missing` with the title, the `.cap` cases, the `.stamp`, cleared on the next `prepare`;
   - under a mocked reduced-motion `matchMedia`, `pixelate` draws nothing (the `canvas.pix` stays hidden);
   - `.tm` reads the `HH:MM` from a mocked clock;
   - the three system lines appear in ko, and one of them in en;
   - during a rejoin, `html[data-mood]` stays the room theme and never takes the own pick.
2. **`drawPlayer.test.js`** (new, jsdom with a canvas stub): for each of the 8 moods it runs without throwing and draws at least one path.
3. **Declared test changes:**
   - `App.revealcover.test.jsx` (item 4);
   - every test that compares a whole chat line's text (item 5).
   - All of them are listed in the test commit.
4. **Commands:** `npm test`, `npm run lint` and `npm run build` pass. `roomCss.test.js` and `indexCss.test.js` are unchanged and pass.
5. **Browser check (lead):** the mockup and the site side by side, in reveal, for sunset, rain, sakura, neon, chalk, pixel, matsuri and manga at 1280×800, plus pixel and manga at 390×844. The frame, caption and stamp must match the mockup except where the cover image and title differ.

## File ownership
- **Test writer:** the new tests above and the declared changes.
- **Implementer:** `src/components/Deck.jsx`, `src/components/drawPlayer.js` (new), `src/components/GameScreen.jsx`, `src/App.jsx`, `src/game/state.js` (only if a field is needed for system lines or `.tm`), and `src/i18n/{ko,en,ja}.json`. It does not touch `room.css` or `index.css`.

## Out of scope
- `#viz` and `#sky` (cycle 115);
- choice tiles and the fly-in (cycle 112);
- server changes.
