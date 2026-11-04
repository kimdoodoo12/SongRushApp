# 064 difficulty-select (F-009)

## Goal
Solo players and hosts choose 난이도 (D-069). The choice reaches the server as the cycle 063 `tier` field, and the applied-conditions line shows it.

## Behaviour
1. **The select.** `FilterControls` gains a `난이도` select, placed after 곡 종류 and before the years.
   - `쉬움`: value `easy`
   - `보통`: value `normal`, the default
   - `어려움`: value `hard`
   - It is labelled `난이도` and has its own `<label>`, like the other selects.
2. **Selection.**
   - `DEFAULT_SELECTION` gains `tier: 'normal'`.
   - `toFields` adds `tier: 'easy'` or `tier: 'hard'`. It adds nothing for `normal`, or when the key is absent, so locked calls without `tier` keep returning what they did.
   - The selection is kept across games (shared App state, cycle 061).
3. **Settings line.**
   - `settingsLine` appends `쉬움` or `어려움` as the last part when `settings.tier` is `easy` or `hard`. Example: `오프닝만 · 2010~2019 · 쉬움`.
   - When only `tier` is set, the line is just `쉬움`.
4. Nothing else changes. Messages with the defaults stay exactly as they are today.

## Acceptance criteria (Vitest, from `frontend/`)
`src/game/filtersTier.test.js` and `src/App.difficulty.test.jsx`:
1. **`toFields` and `settingsLine`.**
   - `toFields` with `tier` set to `normal`, `easy` or `hard`.
   - `toFields` without a `tier` key.
   - `settingsLine` with tier alone, with tier plus types and years, and with an unknown tier value, which is ignored.
2. **Options.** The `난이도` select offers exactly 쉬움, 보통 and 어려움, with 보통 selected by default.
3. **Messages.**
   - **Solo.** With the defaults, the client sends exactly today's `create`. With 쉬움 it sends `{type:'create', nickname, songCount:10, tier:'easy'}`.
   - **Host.** With 어려움 it sends `{type:'start', songCount:10, tier:'hard'}`.
4. **Line.** `settings{types:['OP'], tier:'easy'}` shows `오프닝만 · 쉬움`.
5. **Kept.** The host picks 어려움, plays a game and returns to the lobby. 어려움 is still selected.
6. **Earlier tests and build.** All locked earlier tests pass unchanged, including `App.filters.test.jsx` and `filters.test.js`. `npm test`, `npm run lint` and `npm run build` exit 0.

## Test list
- `frontend/src/game/filtersTier.test.js`
- `frontend/src/App.difficulty.test.jsx`

## File ownership
- **Test writer:** the test list.
- **Implementer:** `src/game/filters.js`, `src/components/FilterControls.jsx`, and `src/App.jsx` if needed.

## Out of scope
- Server changes (done in cycle 063).
