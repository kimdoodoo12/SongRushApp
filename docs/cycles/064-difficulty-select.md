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

## Review changes (spec round 1)
- **Blocker: `tier` was dropped before reaching the screen.** `session.js` forwards the `settings` message field by field, and so does the reducer in `state.js`. Both also pass `tier` through, so the line can show it. The implementer therefore also owns `src/game/session.js` and `src/game/state.js`.
- **Medium: solo selection is not covered.** A solo test was added. After `다시 하기`, 난이도 still shows 어려움, and the next `create` carries `tier:'hard'`.

## Result
- Tests `16ef9d4` (locked), implementation `fa2e7fc` (attempt 1). `App.jsx` needed no change.
- Red phase (verifier): 16 failures, all in the two new files, all for missing features. The 7 tests that already passed guard the default messages and unknown values.
- Verifier:
  - `npm test` twice (17 files, 245 tests), `npm run lint` and `npm run build` all exit 0. `git diff 16ef9d4 HEAD --stat` lists only the four production files.
  - Real-jar end-to-end: the client's exact solo message `{type:'create',nickname:'D',songCount:5,types:['OP'],tier:'easy'}` got `settings{"types":["OP"],"tier":"easy"}`, then `prepare`.
  - The served bundle contains `난이도`.

## Code review (critic)
No findings. Confirmed:
- `normal` is never sent.
- The settings line orders the parts the same way as the server's fields.
- Unknown tiers are ignored.
- The shared selection persists.
- Every clearing action drops the whole `settings` object, tier included.
