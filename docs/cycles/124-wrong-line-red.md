# 124 wrong-line-red (F-034, from D-131, issue #44 request 2)

## Goal
The chat line that names a wrong pick is red, the way the correct line is green.

## Background
- Cycle 123 added the line `✘ <name> 오답` as a plain system line (`div.msg.sys`), so it has the dim colour of every notice. The correct line is `div.msg.sys.correct`, which `room.css` shows in the mood's `--ok` green with a tinted border and background.
- The owner saw it and asked for red (D-131). The mockup has no red token, and `room.css` is never edited (D-124), so the rule is an owner-approved addition in `choice.css`; the line only exists in choice rooms.

## Design
- `state.js`: the notice appended by `pickWrongNotice` carries `tone: 'wrong'`. Other notices have no `tone`.
- `GameScreen.jsx` (`ChatLog`): a notice with `tone === 'wrong'` renders with the classes `msg sys wrong`; every other notice stays `msg sys`.
- `choice.css` gains one rule at the end, shaped like `.msg.correct` in `room.css`:
  `.msg.wrong{color:#ff6b7a;font-weight:700;border-color:color-mix(in srgb,#ff6b7a 50%,transparent);background:color-mix(in srgb,#ff6b7a 8%,transparent)}`
- The red is one fixed colour for every mood. In the manga mood the existing `[data-mood="manga"] .msg.sys` rule is more specific, so the line keeps that mood's white on black there (the manga correct line is the mood's red box, so the two still differ); only the bold weight applies.

## Acceptance criteria
1. The line added by a `wrong` frame has exactly the classes `msg sys wrong`. A plain notice (for example the song-start line) is exactly `msg sys`, and the correct line is exactly `msg sys correct`.
2. `choice.css` holds exactly its earlier rules followed by the new rule (`choiceCss.test.js`, with its normalisation as today).
3. `room.css` is unchanged (`roomCss.test.js` passes; `git diff dev...HEAD -- frontend/src/room.css` is empty).
4. From `frontend/`: `npm test`, `npm run lint` and `npm run build` pass.
5. Seen in a browser by the lead against a local server: the wrong line is red and the correct line green in the default mood; in the manga mood the wrong line is readable and differs from the correct line.

## Test list
- `frontend/src/App.pickcount.test.jsx`: the class assertion of the wrong line (line 637) becomes `msg sys wrong`; add that a plain notice is `msg sys` and the correct line `msg sys correct` in the same log.
- `frontend/src/choiceCss.test.js`: `EXPECTED` gains the new rule at the end; the header comment says the file holds the choice layout rules and the wrong-line rule.

## File ownership
- Test writer: the two test files above.
- Implementer: `frontend/src/game/state.js`, `frontend/src/components/GameScreen.jsx`, `frontend/src/choice.css`.

## Out of scope
- Any other colour or layout change. Per-mood reds.
