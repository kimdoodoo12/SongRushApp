# 124 wrong-line-red (F-034, from D-131, issue #44 request 2)

## Goal
The chat line that names a wrong pick is red, the way the correct line is green.

## Background
- Cycle 123 added the line `✘ <name> 오답` as a plain system line (`div.msg.sys`), so it has the dim colour of every notice. The correct line is `div.msg.sys.correct`, which `room.css` shows in the mood's `--ok` green with a tinted border and background.
- The owner saw it and asked for red (D-131). The mockup has no red token, and `room.css` is never edited (D-124), so the rule is an owner-approved addition in `choice.css`; the line only exists in choice rooms.

## Design
- `GameScreen.jsx` (`ChatLog`): a notice whose `key` is `chat.wrongLine` renders with the classes `msg sys wrong`; every other notice stays `msg sys`. No state change.
- `choice.css` gains three rules at the end:
  - `.msg.wrong{color:#ff6b7a;font-weight:700;border-color:color-mix(in srgb,#ff6b7a 50%,transparent);background:color-mix(in srgb,#ff6b7a 8%,transparent)}`, shaped like `.msg.correct` in `room.css`.
  - `[data-mood="chalk"] .msg.wrong{color:#ffb3c1}`: on the chalk panel `#ff6b7a` is about 3.6:1; `#ffb3c1` is that mood's own `--a2` and gives about 5.5:1.
  - `[data-mood="manga"] .msg.wrong{background:#fff;color:#e0262f;border:2px solid #e0262f}`: the manga rule for every system line (`[data-mood="manga"] .msg.sys`, white on black, no border) is more specific than `.msg.wrong`, so without this the line would not be red there. The manga correct line is a red box with white text; the wrong line is its inverse, red text and a red border on white.

## Acceptance criteria
1. The line added by a `wrong` frame has exactly the classes `msg sys wrong`. A plain notice (for example the song-start line) is exactly `msg sys`, and the correct line is exactly `msg sys correct`.
2. `choice.css` holds exactly its earlier rules followed by the three new rules (`choiceCss.test.js`, with its normalisation as today).
3. `room.css` is unchanged (`roomCss.test.js` passes; `git diff dev...HEAD -- frontend/src/room.css` is empty).
4. From `frontend/`: `npm test`, `npm run lint` and `npm run build` pass.
5. Seen in a browser by the lead against a local server, in all eight moods: the wrong line is red and readable, and differs from the correct line and from a plain notice.

## Test list
- `frontend/src/App.pickcount.test.jsx`: the class assertion of the wrong line (line 637) becomes `msg sys wrong`; add that a plain notice is `msg sys` and the correct line `msg sys correct` in the same log.
- `frontend/src/choiceCss.test.js`: `EXPECTED` gains the three rules at the end; the header comment and the test name say the file holds the choice layout rules and the wrong-line rules.

## File ownership
- Test writer: the two test files above.
- Implementer: `frontend/src/components/GameScreen.jsx`, `frontend/src/choice.css`.

## Out of scope
- Any other colour or layout change.

## Spec review
One round. No Blocker. Two High, both taken: in the manga mood the red did not apply at all (the mood's own system-line rule is more specific), and in the chalk mood the red was about 3.6:1 on the panel. Each mood got its own rule. Also taken: the class comes from the notice's key in `ChatLog`, with no state change, and the browser check covers all eight moods because no automated test loads these stylesheets.

## Result
- Tests first: commit `381561d` (one assertion changed and one test added in `App.pickcount.test.jsx`; `EXPECTED` extended in `choiceCss.test.js`). Red, run by the verifier: `Tests 3 failed | 821 passed (824)`, the three being the class assertions and the stylesheet match.
- Implementation: commit `8d90dae`, first attempt. `GameScreen.jsx` (the class) and `choice.css` (three rules).
- Green, run by the verifier: `Test Files 53 passed (53)`, `Tests 824 passed (824)`; lint exit 0; build exit 0. `git diff 381561d HEAD -- '*.test.*'` is empty and `room.css` is not in the diff. Criteria 1 to 4 confirmed.
- Criterion 5, by the lead in Chromium against a local server, one ranked choice song with a wrong and a correct pick, the host switching through all eight moods. Computed styles of the wrong line:
  - sunset, rain, sakura, neon, matsuri: text `rgb(255, 107, 122)`, weight 700, a dashed border of the same red at 50%, an 8% red tint. The correct line beside it is each mood's green.
  - pixel: the same red with that mood's solid border.
  - chalk: text `rgb(255, 179, 193)`, border and tint as above.
  - manga: red text `rgb(224, 38, 47)` on white with a 2px red border; the correct line is white on that red.
- Review of the diff: no Blocker, no High. Medium: in the manga mood red is also the colour of the correct line (the mockup's `--ok` equals its red there), so the two lines are a red outline against a red fill. Shown to the owner in the eight-mood capture; the owner's call. Nit: the `ChatLog` header comment does not mention the new class (Follow-ups).
