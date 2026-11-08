# 097: reveal cover and series wording (F-022)

From S-007, design `docs/design/088-songs-3000-covers.md`, decision D-103. The server sends `coverUrl` (`/cover/<id>` or null) in the reveal message since cycle 093, and easy's `not-enough-songs` carries `unit: "series"` since cycle 094.

## Goal

The reveal card shows the anime's cover when the reveal has one, the easy shortage message counts series instead of songs, and the dev server proxies `/cover`.

## Behaviour

- **Session** (`frontend/src/game/session.js`, the `reveal` mapping near `:438`): the reveal object gains `coverUrl`, set only when the message's `coverUrl` is a string matching `^/cover/[A-Za-z0-9_-]{1,64}$` (the server's `CoverMap.ID` rule); otherwise `null`. Nothing else in the client stores or requests a cover.
- **Reveal card** (`frontend/src/components/GameScreen.jsx`, `RevealCard`): only when `coverUrl` is non-null, a `div` with class `reveal-cover` and the fixed size classes `mx-auto mb-3 h-32 w-24` at the top of the card (sizes come from these Tailwind classes in JSX, never from `[data-room-theme]` CSS, which `themeLayout.test.js` forbids for layout). It holds `<img src={coverUrl} alt="">` (decorative: the title is already on the card) with `h-full w-full object-contain`, `loading="eager"`, `draggable={false}`. When the image fires `error`, the `img` is removed from the DOM (component state keyed by `coverUrl`) and the `.reveal-cover` div stays. With `null` there is no `.reveal-cover` and no `img` (the card looks as today; design 088 and BACKLOG F-022: nothing when null). The game-over screen keeps the last reveal card, cover included.
- **Shortage wording** (`frontend/src/game/state.js:34-37`): `not-enough-songs` with `unit === "series"` uses a new key `error.availableSeries`; otherwise the existing `error.available`.
  - ko: `출제할 수 있는 작품이 {{available}}개뿐입니다.`
  - en: `Series available: {{available}}`
  - ja: `出題できる作品は{{available}}作品だけです。`
- **Dev proxy** (`frontend/vite.config.js`): `'/cover': { target: 'http://localhost:8080' }` beside `/audio`.

## Acceptance criteria

1. A reveal with `coverUrl: "/cover/AbC_12-x"` renders one `img` with that `src` and `alt=""` inside the reveal card; a reveal with `coverUrl: null`, a missing field, or a value not matching the pattern (`"https://evil.example/x.png"`, `"/cover/../x"`, `"/audio/abc"`) renders no `img`.
2. With a cover, `.reveal-cover` exists with the classes `h-32` and `w-24`; with `null` it does not exist.
3. After an `error` event on the image, the reveal card has no `img` and `.reveal-cover` remains.
4. `not-enough-songs` with `unit: "series"` shows the series message in ko, en and ja with the number; without `unit` the existing song message is unchanged.
5. `vite.config.js` proxies `/cover` to `http://localhost:8080` (checked by importing `../vite.config.js?raw` and matching the text, which works in the jsdom project).
6. Even when `prepare`, `start` and `snapshot` frames carry a `coverUrl: "/cover/AbC_12-x"` field, no `img` appears and no fetch to a `/cover/` URL happens before the reveal.
7. From `frontend/`: `npm test`, `npm run lint` and `npm run build` exit 0.

## Test list

- `frontend/src/App.revealcover.test.jsx` (new): criteria 1-4 and 6, driving the app with a fake socket as the existing `App.*.test.jsx` files do.
- Criterion 5 inside the same file via `import viteConfig from '../vite.config.js?raw'`.

## File ownership

- Test writer: `frontend/src/App.revealcover.test.jsx`.
- Implementer: `frontend/src/game/session.js`, `frontend/src/game/state.js`, `frontend/src/components/GameScreen.jsx`, `frontend/src/i18n/ko.json`, `en.json`, `ja.json`, `frontend/vite.config.js`, and CSS in `frontend/src/` if the slot needs a class.

## Out of scope

A real-browser check across three languages and six moods needs the backend with real covers (I-012 output); it is done by the lead after I-012 and recorded in the I-012 cycle doc. Server changes.
