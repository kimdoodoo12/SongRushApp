# 097: reveal cover and series wording (F-022)

From S-007, design `docs/design/088-songs-3000-covers.md`, decision D-103. The server sends `coverUrl` (`/cover/<id>` or null) in the reveal message since cycle 093, and easy's `not-enough-songs` carries `unit: "series"` since cycle 094.

## Goal

The reveal card shows the anime's cover when the reveal has one, the easy shortage message counts series instead of songs, and the dev server proxies `/cover`.

## Behaviour

- **Session** (`frontend/src/game/session.js`, the `reveal` mapping near `:438`): the reveal object gains `coverUrl`, set only when the message's `coverUrl` is a string that starts with `/cover/` and matches `^/cover/[A-Za-z0-9_-]{1,64}$`; otherwise `null`. Nothing else in the client stores or requests a cover.
- **Reveal card** (`frontend/src/components/GameScreen.jsx`, `RevealCard`): a fixed-size cover slot (the same box with or without a cover, so the card height does not change) at the top of the card. With a `coverUrl` it holds `<img src={coverUrl} alt="">` (decorative: the title is already on the card), `object-fit: contain`, `loading="eager"`, `draggable={false}`; if the image fails to load it is hidden and the slot stays empty. With `null` the slot is empty (no `img` element).
- **Shortage wording** (`frontend/src/game/state.js:34-37`): `not-enough-songs` with `unit === "series"` uses a new key `error.availableSeries`; otherwise the existing `error.available`.
  - ko: `출제할 수 있는 작품이 {{available}}개뿐입니다.`
  - en: `Series available: {{available}}`
  - ja: `出題できる作品は{{available}}作品だけです。`
- **Dev proxy** (`frontend/vite.config.js`): `'/cover': { target: 'http://localhost:8080' }` beside `/audio`.

## Acceptance criteria

1. A reveal with `coverUrl: "/cover/AbC_12-x"` renders one `img` with that `src` and `alt=""` inside the reveal card; a reveal with `coverUrl: null`, a missing field, or a value not matching the pattern (`"https://evil.example/x.png"`, `"/cover/../x"`, `"/audio/abc"`) renders no `img`.
2. The cover slot has the same size whether or not a cover is present (the slot element exists in both cases with the same size classes).
3. An `error` event on the image hides it (no broken-image icon) and leaves the slot.
4. `not-enough-songs` with `unit: "series"` shows the series message in ko, en and ja with the number; without `unit` the existing song message is unchanged.
5. `vite.config.js` proxies `/cover` to `http://localhost:8080`.
6. No message before the reveal makes the client request `/cover/` (no `img` or fetch for a cover outside the reveal card).
7. From `frontend/`: `npm test`, `npm run lint` and `npm run build` exit 0.

## Test list

- `frontend/src/App.revealcover.test.jsx` (new): criteria 1-4 and 6, driving the app with a fake socket as the existing `App.*.test.jsx` files do.
- `frontend/src/vite.config.test.js` or an assertion inside the new test file reading `vite.config.js` (criterion 5).

## File ownership

- Test writer: `frontend/src/App.revealcover.test.jsx` (and the config test if separate).
- Implementer: `frontend/src/game/session.js`, `frontend/src/game/state.js`, `frontend/src/components/GameScreen.jsx`, `frontend/src/i18n/ko.json`, `en.json`, `ja.json`, `frontend/vite.config.js`, and CSS in `frontend/src/` if the slot needs a class.

## Out of scope

A real-browser check across three languages and six moods needs the backend with real covers (I-012 output); it is done by the lead after I-012 and recorded in the I-012 cycle doc. Server changes.
