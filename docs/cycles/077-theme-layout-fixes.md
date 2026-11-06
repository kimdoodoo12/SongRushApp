# 077 theme-layout-fixes (continues 076)

## Goal
Finish cycle 076. It reached zero box differences across the six themes but was blocked at the retry cap by a High review finding. This cycle starts from `cycle/076-stable-theme-layout` (commit `1daf393`) so that 076's work is kept. It fixes the review findings without breaking theme equality.

## Changes
1. **H1. Round counter clipping.** `.round-num` must show `30 / 30` in full in every theme, at 24 px and 30 px. Widen it to about 3.7em, or derive the width from the longest case. It stays a fixed box. The round row keeps its fixed size and the ` 번째 곡` label still fits.
2. **M1. Score lines that cut the important part.**
   - Scoreboard chips, ranking rows and the `"<name> 정답!"` banner become flex rows. The name is a `min-w-0 truncate` span. The score, `점`, `(나)` and ` 정답!` sit in `shrink-0` spans.
   - The DOM text of each `li` or element must stay exactly the same string as today. Locked tests read it, so no new text nodes and no missing spaces; use the existing text split across spans.
   - `card-winner` takes the card's full width.
3. **M2. Chalk frame.** The wood frame must not crowd the content. Either reduce the inset frame to about 6 px, or raise the shared `.room` padding for all themes equally.
4. **Nit N1.** The time bar fill must not cover the inset ring; inset the fill by the ring.
5. **M3. Stricter theme-layout test** (test writer). `themeLayout.test.js` additionally forbids these in theme rules:
   - the `border` shorthand and its side forms (`border-top`, `border-right`, `border-bottom`, `border-left`, `border-inline*`, `border-block*`), whatever the value;
   - `border-style: none|hidden`;
   - `content`, `text-transform`, `white-space`, `overflow*`, `box-sizing`, `float`, `zoom`, `writing-mode`, `font-variant*`, `font-feature-settings`.
   - It also collects every custom property used through `var()` inside a layout property anywhere in the stylesheet, and fails if a theme rule declares one of those names.
   - The self-tests flip accordingly.

## Acceptance
1. **Tests.** `npm test`, `npm run lint` and `npm run build` exit 0. The only test change is the declared rewrite of `themeLayout.test.js`, and that test fails on `1daf393` for the new reasons before the implementation.
2. **Box equality.** The lead's real-browser measurement shows 0 box differences across the six themes at 1280×820 and 390×844 (lobby, stage, reveal).
3. **Clipping.** The same run checks `scrollWidth <= clientWidth + 1` for `.round-num`, the scoreboard and ranking rows, `.correct` and `.card-winner` with real content, and finds no clipping of `n / N` in a 10-song game, at round 10.

## File ownership
- **Test writer:** `frontend/src/themeLayout.test.js`.
- **Implementer:** `frontend/src/index.css`, `frontend/src/components/*.jsx`, `frontend/src/App.jsx`.

## Note after the test writer
The stricter theme-layout test passes on `1daf393`, because no current theme rule uses the newly forbidden forms. So it guards against regressions and is not the red check of this cycle. The red evidence for H1 and M1 is the lead's browser clipping check (acceptance 3), run before and after the implementation.

## Red evidence (lead, before the implementation, build of `1732c02`)
The browser check on the real jar at 390×844 probed `.round-num` with the longest counters. All six themes clipped: `10 / 10` at 75–84 px against a 67 px box, and `30 / 30` at 81–84 px against 67 px, for 12 clippings in total. Box differences between themes were 0.

## Note: a mixed commit
Commit `0cee4f5` ("docs(077): red evidence ...") also holds the implementation (`GameScreen.jsx`, `index.css`). The lead used `git commit -a` while the implementer's edits were still uncommitted. The content is the implementation that was then verified. Only the message understates it. Pushed history is not rewritten.

## Result
- **Verifier at HEAD.**
  - `npm test`: 21 files, 295 tests passed.
  - `npm run lint` and `npm run build`: exit 0.
  - The locked-test diff from `1732c02` is empty.
  - The branch touches only `frontend/`, `docs/` and `PROGRESS.md`.
- **Lead's browser check, after the implementation.** Six themes, lobby, stage and reveal, at 1280×820 and 390×844: 0 box differences and 0 clippings. That includes `.round-num` probed with `10 / 10` and `30 / 30`; before, there were 12 clippings.
- **How it was built, where it differs from the spec.**
  - The banner keeps one text node. The locked tests use `getByText` on the whole `"<name> 정답!"` string, so it is right-aligned with `direction: rtl` around an `ltr` isolate. A long name loses its start and " 정답!" stays visible.
  - The time-bar ring is drawn by a `::after` overlay. Insetting the fill would need `var(--ring)` in a layout property, which the stricter test forbids.
  - The chalk frame is 4/6 px.
  - The round counter is 3.9em.
- **Code review.** No Blocker or High. M1–M3 and N1 from 076 are resolved. New Mediums go to Follow-ups:
  - the theme test does not see Tailwind's own tokens (`--spacing`, `--text-*`, …);
  - there is no in-repo regression guard for clipping, because the Playwright probe is not committed.
