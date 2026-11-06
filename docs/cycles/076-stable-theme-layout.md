# 076 stable-theme-layout

## Goal
Switching the room theme must not move or resize anything (owner, 2026-11-06). A theme may change only the look:
- colours, backgrounds and decorations;
- the font **family** (the owner keeps per-theme fonts for their mood);
- border colour, border style and corner radius;
- shadows, glows and animation styling.

Every box keeps the same position and size in all six themes.

## Why things move today
`src/index.css` gives each `[data-room-theme]` block, and the per-theme rules after them, values that occupy space:
- the border width token `--bw` (1, 2 or 3 px);
- the chalk room padding (26 px against 18 px);
- per-theme font sizes (chalk ×1.2, a 40 px card title, a 19 px seat name, the clock and round sizes);
- `font-weight` changes on display text;
- letter spacing.

Fonts with different metrics also change line heights and text widths.

## Rules
1. **Layout tokens live once, outside the theme blocks.** One border width (2 px) and one room padding apply to every theme. Themes keep their thicker look through shadows or outlines, which take no layout space:
   - `box-shadow: 0 0 0 1px <colour>` for the play and arcade 3 px frames;
   - an inset shadow for the chalk wood frame.
2. **Theme rules may not set layout properties.** Neither `[data-room-theme="…"]` blocks nor any rule whose selector starts with `[data-room-theme=` may set:
   - `padding*`, `margin*`, `gap*`;
   - `width`, `height`, `min-*`, `max-*`, `inset`, `top`, `right`, `bottom`, `left`;
   - `font-size`, `line-height`, `letter-spacing`, `font-weight`, `word-spacing`;
   - `border-width`, or any `border*` shorthand that includes a width;
   - `display`, `grid*`, `flex*`, `position`, `aspect-ratio`;
   - the layout custom properties `--bw` and `--pad` (any custom property whose name ends in `-size`, `-width`, `-height`, `-gap` or `-pad`).
   - `border-radius`, `transform`, `filter`, colour and background properties, `font-family`, shadows and animations stay allowed.
3. **Text boxes do not depend on font metrics.**
   - Display text that changes font per theme gets a fixed `line-height` (set once, outside themes) and a fixed-height box where it sits in a row: the round, the clock, seat names, chips, the code chip, picker buttons and the card label.
   - Single-line labels use `white-space: nowrap; overflow: hidden; text-overflow: ellipsis`.
   - The clock box has a fixed width that fits `90초`, with tabular figures where the font supports them.
   - The reveal card title may wrap, but the card has the same box in every theme for the same content. Its font size and line height are fixed, and only the family changes.
4. **Font sizes chosen once.** The sizes currently boosted for chalk (Nanum Pen Script renders small) are replaced by a per-family adjustment that does not change the box: `font-size-adjust` set inside the theme block. `font-size-adjust` changes glyph scale within the same line box, so it does not move layout.
5. Locked tests stay unchanged, and every DOM invariant from cycles 070 and 071 stays.

## Acceptance criteria
1. `frontend/src/themeLayout.test.js` (new, Vitest, node environment, reads `src/index.css` as text) checks:
   - every rule whose selector list contains `[data-room-theme=` declares none of the forbidden properties in rule 2;
   - `--bw` is declared exactly once in the stylesheet, outside any theme rule.

   The parser may be simple: split on braces and handle `@media` and `@layer` nesting.
2. `npm test`, `npm run lint` and `npm run build` exit 0. Every earlier frontend test passes unchanged.
3. **Layout equality (real browser, run by the lead and the verifier, recorded in this doc).**
   - On a local server built from this branch, a script switches the theme through all six in the lobby, and plays one room game per theme to the stage and to a reveal.
   - At 1280 × 820 and 390 × 844 it reads the bounding boxes of:
     - the room frame, every seat card, the stage, the round and the clock;
     - the record, the time bar, the hint and the skip button;
     - the chat log, the chat input, the send button;
     - the reveal card (same song forced per comparison where possible, otherwise its outer box only);
     - the lobby's code, share row and picker.
   - Every box is identical across the six themes within 0.5 px, except the reveal card's height when the title text differs.

## Test list
- `frontend/src/themeLayout.test.js`

## File ownership
- **Test writer:** the test list. Add it to the node project in `vite.config.js` only if it needs node (`fs`); if so, `vite.config.js` belongs to the test writer for that one line.
- **Implementer:** `frontend/src/index.css`, `frontend/src/components/*.jsx`, `frontend/src/App.jsx`.

## Out of scope
- New themes.
- Server changes.
- The mockup file.
