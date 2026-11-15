# 138 marks-in-language (from issue #66)

## Goal
On an English or Japanese screen, nothing in the reveal of a choice room shows Korean except the card's own KO line.

## Plan
- Issue #66 (bug, from the live site, en): "korean text appears when you get a choice correct in choice mode". Reproduced by the lead on the live site with four English players: the tiles show `✔ 정답` and `✕ 오답`, and the cover's caption and its no-image text show the Korean title (`오버로드 1기`), while the card shows KO, EN, JA and romaji lines as intended (D-095).
- Cause 1: the tile marks are CSS text in the mockup's stylesheet, `frontend/src/room.css:426` (`.tile.wrong .tmark::before{content:"✕ 오답"}`) and `:432` (`✔ 정답`). `room.css` is copied verbatim and never edited (D-124), so the fix overrides it in the approved `choice.css`: the `.tmark` element (ChoiceGrid.jsx:57) gets a `data-mark` attribute with the translated mark, and `choice.css` sets `.tile.wrong .tmark::before, .tile.correct .tmark::before {content: attr(data-mark)}` with enough specificity to win. New i18n keys for the two marks in ko (`✔ 정답`, `✕ 오답`, unchanged look), en (`✔ Correct`, `✕ Wrong`), ja (`✔ 正解`, `✕ 不正解`).
- Cause 2: the cover's caption and no-image text use the Korean title (Deck.jsx `title`, "its Korean title"; GameScreen passes it). They use the title in the screen's language instead: `titles[lang]` first, then Korean, then the reveal's title, the same order everywhere the Deck title is shown (alt text included).
- `choiceCss.test.js` (which pins `choice.css`'s exact rules) gets the new rule.

## Acceptance criteria (Vitest unless said otherwise)
1. In English, after a choice reveal, the correct tile's mark reads `✔ Correct` and a wrong pick's mark `✕ Wrong`; in Japanese `✔ 正解` / `✕ 不正解`; in Korean unchanged.
2. In English the cover caption and the no-image text show the English title; in Japanese the Japanese one; Korean as before; a title missing in the language falls back to Korean.
3. `room.css` is unchanged (`roomCss.test.js` passes); `choiceCss.test.js` passes with the new rule; `keys.test.js` passes (the new keys in all three languages).
4. `npm test`, `npm run lint`, `npm run build` pass.
5. Browser check (lead): an English choice room on a local server shows no Korean in the reveal outside the KO line and the language switch.

## Out of scope
- The card's KO/EN/JA/romaji lines (D-095); the pixel and neon captions that are fixed English text.

## Result
- Implementation and tests: commit `57fd150`, first attempt. `ChoiceGrid.jsx` puts the translated mark in `.tmark`'s `data-mark`; `choice.css` adds `html .tile.wrong .tmark::before, html .tile.correct .tmark::before {content: attr(data-mark)}` (specificity 0,3,2 over room.css's 0,3,1; no mood rule sets that content); new keys `answer.markCorrect` and `answer.markWrong` in ko, en, ja; `GameScreen.jsx` gives the deck the title in the screen's language, then Korean, then the reveal's title. New `App.markLanguage.test.jsx` (10 cases); `choiceCss.test.js` pins the new rule; `App.revealcover.test.jsx`'s English alt now expects the English title.
- Review, run by the verifier: `npm test` 59 files, 901 tests passed; lint exit 0; build exit 0 (the built CSS keeps the rule); `room.css` unchanged. Criteria 1 to 4 confirmed.
- Criterion 5, by the lead in Chromium against the local server, four English players in a choice room: after the reveal the tiles' computed marks read `✔ Correct` and `✕ Wrong`, the caption read the English title (`No Game, No Life`); the only Korean on the page was the card's KO line and the language switch's `한국어`.
- Merged straight to `dev` (D-134). Before capture for issue #66 in `docs/issues/066/`.
