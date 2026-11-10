# 112 choice-port (D-124, D-123; plan in docs/cycles/110-room-port.md)

## Goal
A choice room uses the mockup's choice markup and behaviour (`docs/design/108-ui-design-reference.html`, Version 21: `renderChoices`, `pickChoice`, `flyIn` and `setMode`), styled by the unchanged `room.css`.

## Behaviour
1. **Choice mode.** `body.choice-mode` is set while the current game is a choice game, and cleared otherwise and on leaving the game screen. With it, the mockup's `.choice-mode .now` grid places the small deck and the panel on top and the tiles below.
2. **Markup.** `ChoiceGrid` renders the mockup's `div.choices.k<k>` as the third child of `.now` (`grid-area: choices`).
   - Each choice is a `button.tile` (`aria-label` "보기 N", as today) holding:
     - `span.tframe > img` (alt "", the choice's cover URL). When the URL is null (the session drops a URL that fails its allow-list) or the image fails to load, `.tframe` holds no `img`, as with today's placeholder from cycle 098;
     - `span.tno` with the number N (D-123);
     - `span.tmark`.
   - The tiles stay from `prepare` to the next `prepare`, as today (cycle 100 A7).
3. **Marks**, as the mockup's `renderChoices`, mapped from today's state. **The first matching case applies:** reveal, then own pick (wrong or pending), then picking not allowed.
   - **At the reveal:** every tile is `disabled`. The correct tile is `.correct`; the own wrong pick is `.wrong`; every other tile is `.dim`.
   - **After the own wrong pick, before the reveal:** every tile is `disabled`. The picked tile is `.wrong`; the others are `.locked`.
   - **After the own pick before the `pick-result`:** the picked tile is `disabled` and the others are `.locked`.
   - **When picking is not allowed** (not audible, watching, a late joiner, someone already correct, or an `already-picked` refusal with no own pick): every tile is `disabled`, with no class.
   - The mark texts (✔ 정답, ✕ 오답) come from `room.css` (`::before`) as in the mockup, so they are Korean in every language. This is accepted under D-124.
4. **Panel texts** in a choice game, as in the mockup's `renderStage`, in i18n ko, en and ja:
   - **Waiting** (a choice game before the song is audible, where today's chat game shows its waiting hint): hint "곡이 나오면 표지 중에서 그 애니를 골라 보세요".
   - **Playing:** hint "들리는 곡의 애니를 아래 표지에서 골라 보세요" and the mockup's sub text.
   - **After a wrong pick:** `p.pickmsg` "<N>번은 오답이에요. 정답 공개를 기다려 주세요."
   - The skip row is unchanged.
5. **Fly-in.** At the reveal, when the correct tile exists and reduced motion is off, the deck's `.cover` animates from the tile's box to its own with the mockup's `flyIn`: Web Animations, 750 ms, the same keyframes and easing.
   - Without `Element.animate` (jsdom) it does nothing.
   - `room.css`'s `.choice-mode .revealed .cover{animation:none!important}` applies as in the mockup.
6. **Secrecy (D-111, A9).** Nothing marks the correct tile before the `reveal` frame: no class, attribute or order, and the number badges follow the server's shuffled order.

## Acceptance
1. **`App.choiceport.test.jsx`** (new, jsdom):
   - `body.choice-mode` is on in a choice game, off in a chat game, and off after leaving;
   - `.choices.k4`, `.k6` and `.k8` follow k;
   - the tiles are in server order with `.tno` 1..k and `aria-label` "보기 N", and each `img` `src` is that choice's URL;
   - before the reveal, no tile has `.correct`, `.dim` or `.wrong` unless it is the own wrong pick;
   - each mark case of item 3;
   - `.pickmsg` with N after a wrong pick;
   - the hint texts in ko and one in en;
   - **the fly-in:** the test stubs `requestAnimationFrame` (run at once), `getBoundingClientRect` on the tile and on `.cover` (non-zero boxes), `Element.prototype.animate`, and `window.matchMedia`. `animate` is called once on `.cover` at the reveal. It is not called under reduced motion, and not called when a box has zero width (the mockup's guard is kept);
   - a null choice URL renders a tile with no `img`.
2. **Declared changes:** `App.choice.test.jsx` and any other test that reads `.choice-grid`, `.choice-tile` or `data-choice` are rewritten to the new markup. The behaviour they check is kept (pick sent once, locked after a pick, disabled while not audible, correct shown at the reveal). They are listed in the test commit.
3. **Commands:** `npm test`, `npm run lint` and `npm run build` pass. `roomCss.test.js` and `indexCss.test.js` are unchanged.
4. **Browser check (lead):** the mockup and the site side by side, choice game with k = 4, 6 and 8:
   - playing and reveal at 1280×800 and 390×844, in sunset, chalk, pixel and manga;
   - computed styles of `.choices`, `.tile`, `.tframe` and `.tno` compared.

## File ownership
- **Test writer:** the new test and the declared changes.
- **Implementer:**
  - `src/components/ChoiceGrid.jsx`, `GameScreen.jsx`, `Deck.jsx` (only the fly-in hook), and `App.jsx` (only `choice-mode`);
  - `src/i18n/{ko,en,ja}.json`;
  - remove the interim `.choice-tile` rules from `src/index.css`.
  - Not `room.css`.

## Order
Cut from dev after cycle 111 is merged: both cycles touch `GameScreen.jsx` and `Deck.jsx`.

## Spec review (critic, round 1)
- **H1:** the fly-in test stubs are specified, and the mockup's zero-width guard is kept.
- **H2:** the mark cases have a fixed order.
- **M3:** a null or failed cover gives an empty `.tframe`.
- **M4:** `already-picked` with no own pick counts as not allowed.
- **M5:** the order after 111 is stated.
- **Nit:** the waiting hint is mapped.

## Out of scope
- The lobby's answer-mode control (cycle 114).
- Server changes.
