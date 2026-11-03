# 038 frontend-bootstrap (F-001)

## Goal
`frontend/` is a React app in plain JavaScript (JSX) built with Vite, tested with Vitest, jsdom and Testing Library, and linted with ESLint's flat config, so the M-1 solo screen (F-002) has a place to live (D-029, D-030).

## Acceptance criteria (from `frontend/`)
1. **App:** `src/App.jsx` renders an `h1` with the text "SongRush". `src/main.jsx` mounts `App` into `#root` of `index.html`. The page language is Korean (`<html lang="ko">`).
2. **No TypeScript:** a test walks `frontend/` (the root found from `import.meta.url`), skipping `node_modules` and `dist`, and fails if any `.ts`, `.tsx`, `.mts` or `.cts` file exists. Positive controls: the walk of `frontend/` must list `src/App.jsx` and `package.json`, and the same walk function run on a temp folder (`fs.mkdtempSync`) holding `a.ts` and `node_modules/x.d.ts` must report `a.ts` and not `x.d.ts`. Config files are JavaScript (`vite.config.js`, `eslint.config.js`). `package.json` has no `typescript` or `@types/*` dependency, and the test checks this as well.
3. **Commands:** `npm test` (the script is literally `"test": "vitest run"`), `npm run lint` (ESLint over `src` and the config files, with the React Hooks and React Refresh rules) and `npm run build` (Vite) each exit 0. `npm ci` works from the committed `package-lock.json`.
4. **Dev server** (scope added from the cycle 037 review): `vite.config.js` proxies `/ws` (as a WebSocket) and `/audio` to `http://localhost:8080` **without `changeOrigin`**, so Spring's same-origin WebSocket check passes (cycle 037 review). No test is required for this; the config is read in review.
5. **Git hygiene:** `frontend/node_modules/` and `frontend/dist/` are ignored by git, and no media files are committed.
6. **Versions:** current stable releases of React, Vite, Vitest, ESLint and Testing Library, pinned by the lock file. Node 24 / npm 11 are installed locally.

## Test list
- `frontend/src/App.test.jsx`: criterion 1 (Testing Library: `getByRole('heading', { level: 1, name: 'SongRush' })`).
- `frontend/src/noTypeScript.test.js`: criterion 2.
- **Vitest contract:** tests import `describe`, `it` and `expect` from `vitest` (no globals); `vite.config.js` sets `test.environment: 'jsdom'`; no jest-dom matchers (`getByRole` throws when nothing matches).
- This is a bootstrap item: the tests are written first and locked, and there is no meaningful red phase (no `package.json` exists yet); this is recorded as the skill's bootstrap exception.

## File ownership
- Test writer: the two test files above.
- Implementer: everything else under `frontend/` (`package.json`, `package-lock.json`, `index.html`, `vite.config.js`, `eslint.config.js`, `src/App.jsx`, `src/main.jsx`, the test setup file, ). The root `.gitignore` already ignores `node_modules/`, `dist/` and `coverage/`, so it is not changed. The implementer may run `npm install` (registry downloads of the dev tooling are expected); no `@types/*` packages (the create-vite template adds them), and `@testing-library/dom` is added explicitly.

## Review changes (spec round 1)
- **High.** The no-TypeScript test gets positive controls, so a wrong root or a skip-everything bug cannot pass.
- **Medium.** The Vitest contract (explicit imports, jsdom in config, no jest-dom) is fixed before the tests are locked.
- **Nits.** No pretend red phase; the test script is `vitest run`; no `@types/*`; the proxy is marked as added scope and `/health` dropped; the root `.gitignore` is left alone.

## Out of scope
Screens, WebSocket client, styling system (F-002 onward).
