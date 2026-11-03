# 038 frontend-bootstrap (F-001)

## Goal
`frontend/` is a React app in plain JavaScript (JSX) built with Vite, tested with Vitest, jsdom and Testing Library, and linted with ESLint's flat config, so the M-1 solo screen (F-002) has a place to live (D-029, D-030).

## Acceptance criteria (from `frontend/`)
1. **App:** `src/App.jsx` renders an `h1` with the text "SongRush". `src/main.jsx` mounts `App` into `#root` of `index.html`. The page language is Korean (`<html lang="ko">`).
2. **No TypeScript:** a test walks `frontend/`, skipping `node_modules` and `dist`, and fails if any `.ts`, `.tsx`, `.mts` or `.cts` file exists. Config files are JavaScript (`vite.config.js`, `eslint.config.js`). `package.json` has no `typescript` or `@types/*` dependency, and the test checks this as well.
3. **Commands:** `npm test` (Vitest run once, not watch mode), `npm run lint` (ESLint over `src` and the config files, with the React Hooks and React Refresh rules) and `npm run build` (Vite) each exit 0. `npm ci` works from the committed `package-lock.json`.
4. **Dev server:** `vite.config.js` proxies `/ws` (as a WebSocket) and `/audio` and `/health` to `http://localhost:8080` **without `changeOrigin`**, so Spring's same-origin WebSocket check passes (cycle 037 review). No test is required for this; the config is read in review.
5. **Git hygiene:** `frontend/node_modules/` and `frontend/dist/` are ignored by git, and no media files are committed.
6. **Versions:** current stable releases of React, Vite, Vitest, ESLint and Testing Library, pinned by the lock file. Node 24 / npm 11 are installed locally.

## Test list
- `frontend/src/App.test.jsx`: criterion 1 (Testing Library: `getByRole('heading', { level: 1, name: 'SongRush' })`).
- `frontend/src/noTypeScript.test.js`: criterion 2.
- This is a bootstrap item: the tests are written first and locked, but the "red" run is the absence of `package.json`. The verifier records `npm test` failing before the implementation (skill exception for bootstrap smoke tests).

## File ownership
- Test writer: the two test files above.
- Implementer: everything else under `frontend/` (`package.json`, `package-lock.json`, `index.html`, `vite.config.js`, `eslint.config.js`, `src/App.jsx`, `src/main.jsx`, the test setup file, and `.gitignore` entries) and the root `.gitignore` if needed. The implementer may run `npm install` (registry downloads of the dev tooling are expected).

## Out of scope
Screens, WebSocket client, styling system (F-002 onward).
