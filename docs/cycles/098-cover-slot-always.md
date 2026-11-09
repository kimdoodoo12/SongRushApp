# 098: cover slot always present (F-023)

From D-109 (owner, 2026-11-09) and the cycle 097 review (M1, N1, N2). Cycle 097 rendered the `.reveal-cover` slot only when the reveal had a cover, so the reveal card was about 140 px taller on rounds with a cover and the scoreboard and chat moved between rounds.

## Goal

Every reveal card has the same cover slot; it shows the cover when there is one and an empty placeholder otherwise.

## Behaviour

- `RevealCard` (`frontend/src/components/GameScreen.jsx`) always renders `div.reveal-cover` with `mx-auto mb-3 h-32 w-24` at the top of the card.
- With a valid `coverUrl` the slot holds `<img src={coverUrl} alt="" draggable={false} className="h-full w-full object-contain">`. With `coverUrl` null, or after the image fires `error`, the slot holds no `img` and gets the class `reveal-cover-empty` (a subtle placeholder styled in `frontend/src/index.css` with colours only from the existing theme variables, no layout properties in `[data-room-theme]` rules).
- The failure state is a boolean in a component keyed by `coverUrl` (`<RevealCover key={reveal.coverUrl ?? 'none'} ...>`), replacing `failedUrl`. The `undefined` check and `loading="eager"` are removed (session.js already normalizes `coverUrl` to a string or null).
- The game-over screen keeps the last reveal card, slot included.

## Acceptance criteria

1. `.reveal-cover` with `h-32` and `w-24` exists on the reveal card for a valid cover, for `coverUrl: null`, for a missing or invalid `coverUrl`, and after an image `error`.
2. An `img` exists only for a valid cover that has not failed; the empty cases carry `reveal-cover-empty` and no `img`.
3. A cover that fails in one round does not hide the next round's cover.
4. No `img` has a `loading` attribute; the existing criteria of cycle 097 (URL guard, nothing before the reveal, series wording, proxy) still hold.
5. From `frontend/`: `npm test`, `npm run lint`, `npm run build` exit 0.

## Test list

- `frontend/src/App.revealcover.test.jsx`: change the null/invalid cases from "no `.reveal-cover`" to "empty slot", the error case to "empty slot with `reveal-cover-empty`", rename the keyed test to what it pins, and add criterion 4's `loading` check.

## File ownership

- Test writer: `frontend/src/App.revealcover.test.jsx`.
- Implementer: `frontend/src/components/GameScreen.jsx`, `frontend/src/index.css`.

## Out of scope

The real-browser check across languages and moods (I-012).
