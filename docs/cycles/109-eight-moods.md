# 109 eight-moods (B-031 + F-025; D-122, design 108 items 1-2)

> **Superseded in part by D-124 (cycle 110).**
> - The frontend part (token blocks, `data-room-theme`, the theme-layout test, Do Hyeon for pixel) was replaced by the mockup's verbatim CSS and `html[data-mood]`.
> - The backend part (B-031: the eight ids and the old-id mapping) still holds.


## Decision (owner, 2026-11-10)
- "시안 8종으로 교체"
- "방장이 고르기 (지금처럼)"

The plan is in `docs/design/108-ui-redesign-plan.md`; this cycle implements its declared change 4. Items 1 and 2 are one cycle because they are one wire value.

## Goal
The room theme set becomes the eight moods `sunset rain sakura neon chalk pixel matsuri manga` (default `sunset`). Each mood has its token block and fonts from the mockup, and an old id or an old stored pick still works.

## Backend (B-031)
1. **Ids.** `Lobby.ROOM_THEMES` holds exactly the eight new ids, and `DEFAULT_THEME = "sunset"`.
2. **Old ids.** `setRoomTheme` accepts the old ids from tabs opened before the release, maps them, and stores and broadcasts only the mapped id:
   - `play` → `sunset`
   - `star` → `rain`
   - `arcade` → `pixel`
   - Any other id, including null, is `bad-room-theme` as today, still checked before `not-host`.
3. **The default stays omitted** (critic H1/H2). `sendRoomThemeTo` keeps omitting the default, as today. The new client's default is the server's default (`sunset`), so leaving it out shows the same thing. A tab opened before the release shows its own default look for a sunset room, which is acceptable.
   - Plan item 1's "always send" is dropped. It would have broken frame-order assertions in about ten lobby test files and gained nothing.
4. Nothing else in the lobby or room changes.

## Frontend (F-025)
1. **`game/themes.js`.**
   - `THEMES` lists the eight ids in picker order `sunset, rain, sakura, neon, chalk, pixel, matsuri, manga`. Swatches are taken from the mockup's `MOODS[*].sw`, as a CSS background value (gradients allowed).
   - `DEFAULT_THEME = 'sunset'`.
   - `themeId(v)`:
     - maps `play`/`star`/`arcade` as the server does;
     - returns a known id unchanged;
     - returns `sunset` for anything else.
   - The stored own pick goes through `themeId`.
2. **`index.css`.**
   - Replace the six `[data-room-theme]` token blocks with eight. Keep the existing token *names* that components already read. Every mood block defines exactly the same set of token names as the default block.
   - **Mapping** from the mockup's `:root[data-mood="<id>"]` tokens to the existing names (critic H3):

     | mockup | existing tokens |
     |---|---|
     | `--bg` | `--solid-bg`; `--bg` is that colour, optionally with one soft radial gradient of `--a1` or `--a3` |
     | `--panel` | `--panel`, `--solid`, `--card`, `--stage` |
     | `--panel-2` | `--panel-me`, `--track`, `--input` (`--input` is `--bg` in dark moods) |
     | `--line` | `--line`, `--frame`, `--card-line` |
     | `--ink` | `--ink`, `--clock`, `--btn-ink` |
     | `--dim` | `--soft` |
     | `--a1` | `--a1`, `--label`, `--go-line`, `--eq` |
     | `--a2` | `--a2`, `--me`, `--eq2` |
     | `--a3` | `--a3` |
     | `--ok` | `--good`, `--good-text` |
     | `--f-display` | `--font-d` |
     | `--f-body` | `--font-b` |
     | `--r` | `--r` |
     | `--glow` | `--glow1`, `--glow2` (`none` when the mockup has `none`) |

   - **Tokens the mockup does not have** follow the pattern of today's neon block (dark moods) or play block (`manga`, the one light mood), recoloured with that mood's own colours:
     - `--scheme`: dark, except `manga`, which is light.
     - Shadows: `0 0 #0000` in dark moods, a hard `0 4px 0 <line>` in manga.
     - `--fill`: `linear-gradient(90deg, a1, a2)`.
     - `--record`: a repeating radial gradient of two darks.
     - `--code-*`, `--bub*`, `--av-*`, `--btn`, `--sheen`, `--label-dot`, `--eq-*`: as in the pattern block, recoloured.
     - Each value must give readable contrast: `--ink` on `--panel`, and `--a1-ink` on `--a1`.
   - The default (`.app-shell` without a theme) equals `sunset`.
   - Remove the per-theme override rules for `star` and `arcade`, and the petal and star styles that only `ThemeDecor` used. Rewrite the `sakura` and `chalk` rules against the new tokens.
   - `themeLayout.test.js` must stay green **unchanged**: this cycle changes looks only, never layout.
3. **Fonts.**
   - The `index.html` Google Fonts link loads, with `display=swap`, the families that the eight moods' `--font-d` and `--font-b` use, plus any family that existing CSS still names. From the mockup: Jua, Gowun Dodum, Gowun Batang, Gaegu, Nanum Pen Script, Black Han Sans, IBM Plex Sans KR, Do Hyeon, Press Start 2P, Yeon Sung.
   - JetBrains Mono is not loaded yet: no token uses it until F-030 (critic M1).
   - A family that no CSS names any more (for example DotGothic16) is dropped. IBM Plex Sans KR keeps every weight that existing CSS uses.
4. **Picker.**
   - `ThemePicker` shows eight buttons in the order above, each with its swatch and its i18n name, `aria-pressed` as today.
   - i18n `theme.<id>` exists for the eight ids in ko, en and ja, and the six old name keys are removed:

     | id | ko | en | ja |
     |---|---|---|---|
     | sunset | 노을 옥상 | Sunset Rooftop | 夕焼けの屋上 |
     | rain | 비 오는 밤 | Rainy Night | 雨の夜 |
     | sakura | 벚꽃 밤 | Cherry Blossom Night | 夜桜 |
     | neon | 네온 시티 | Neon City | ネオンシティ |
     | chalk | 칠판 교실 | Chalkboard Classroom | 黒板の教室 |
     | pixel | 8비트 오락실 | 8-bit Arcade | 8ビットゲーセン |
     | matsuri | 여름 축제 | Summer Festival | 夏祭り |
     | manga | 만화 원고 | Manga Manuscript | 漫画原稿 |

5. **`ThemeDecor`** (petals and stars for the old themes) is removed, along with its render in `App.jsx`. The scenes return in F-029.

## Acceptance
1. **Backend tests** (`backend/src/test/java/.../game/LobbyThemeTest.java`, `.../ws/ThemeSocketTest.java`, updated as declared test changes):
   - each of the eight ids round-trips to every connected member;
   - `play`, `star` and `arcade` are stored and broadcast as `sunset`, `rain` and `pixel`;
   - `bogus` and null give `bad-room-theme`, and a guest gets `not-host` for a valid id;
   - a fresh lobby sends no `roomTheme` (the default `sunset`, omitted as today);
   - after a pick of `rain`, a rejoining member gets `roomTheme` `rain`.
2. **Frontend tests:**
   - `App.theme.test.jsx`, `App.i18n.test.jsx` and `i18n/keys.test.js` use the new ids and names;
   - `themeId` mapping (old → new, unknown → sunset, known unchanged);
   - the picker renders 8 buttons in order with the localized names in ko/en/ja;
   - a stored `arcade` loads as `pixel`;
   - a `roomTheme` frame sets `data-room-theme` on the shell for each of the 8 ids;
   - a node test (`src/themeTokens.test.js`, new): each of the eight `[data-room-theme]` blocks in `index.css` defines exactly the token names of the default block, and no block exists for `play`, `star` or `arcade`.
3. **Unchanged:** `themeLayout.test.js` is unchanged and green.
4. **Commands:** `.\gradlew.bat test` (backend), and `npm test`, `npm run lint`, `npm run build` (frontend), all pass.
5. **Browser check (lead):** captures of the lobby and of the game stage:
   - in sunset, rain, pixel and manga at 1280×800;
   - pixel, chalk and manga at 390×844, checking that Press Start 2P does not overflow the clock, code or score (critic M2).

## File ownership
- **Test writer:**
  - backend: `LobbyThemeTest.java`, `ThemeSocketTest.java`;
  - frontend: `App.theme.test.jsx`, `App.i18n.test.jsx`, `i18n/keys.test.js`, and the new `src/themeTokens.test.js`;
  - any other test that names an old id, listed in the test commit.
- **Implementer:**
  - backend: `game/Lobby.java`;
  - frontend: `src/game/themes.js`, `src/game/state.js` (the comment only), `src/index.css`, `index.html`, `src/components/ThemePicker.jsx`, `src/components/ThemeDecor.jsx` (delete), `src/App.jsx` (only the ThemeDecor removal), and the `src/i18n/{ko,en,ja}.json` theme keys.
- **Lead:** docs.

## Spec review (critic, round 1)
- **H1 and H2** (always sending the default theme; resending it on return to the lobby): dropped. The omission is kept.
- **H3** (token values cannot be checked): fixed with a mapping table, a derivation rule and a token-set test.
- **M1** (JetBrains Mono): deferred to F-030.
- **M2** (wide pixel font): 390 px captures for pixel and chalk added.
- **Nits** (unused petal CSS, IBM Plex weights): folded in.
- Also fixed: the stale `state.js:176` comment ("only when it is not play"). The implementer updates that comment; `src/game/state.js` is added to the implementer's files, for the comment only.

## Out of scope
- Layout changes, disc, chat and frame skins, the lobby redesign and scenes (later items).

## Result
- **Commits.**
  - Spec: `b3128aa`.
  - Tests: `baff944`, locked. It also registers `themeTokens.test.js` in `vite.config.js` `NODE_TESTS`, as noted in the test commit.
  - Implementation: `3b116c0`. Fix after the browser check: `ed713d6`.
- **Verifier**, on `3b116c0`:
  - `gradlew cleanTest test`: BUILD SUCCESSFUL, 881 tests, 0 failures, 0 errors, 1 skipped.
  - `npm test`: 546 of 546 passed.
  - lint clean, and the build passed.
  - The locked-test diff is empty, and `themeLayout.test.js` is unchanged.
- **Lead, after the fix** `ed713d6`: `npm test` 546 of 546, lint clean, and the locked-test diff is empty. The backend was not changed by the fix.
- **Browser check (lead),** local jar plus vite, in Edge:
  - Attempt 1 found two problems:
    - In pixel, the `.clock` "88초" overflowed its box at 1280 and at 390, and the "English" button was cut to "Engl…". Press Start 2P is too wide for the shared boxes.
    - The en/ja mood names were cut in the 8rem picker buttons (critic Medium).
  - Fix:
    - pixel's display font is Do Hyeon, and Press Start 2P is no longer loaded. It returns in F-030 with its own sizes, through the allow-list.
    - The picker buttons size to their names, with `min-width: 8rem`, and wrap.
  - Recheck:
    - pixel at 1280 and 390: no overflow on the clock, code, round or chips.
    - The picker in en and ja at 1280 and 390 has no cut name.
  - Captures: `docs/design/109/`.
- **Critic code review:** no Blocker or High.
  - Medium: the picker cut, fixed above.
  - Nits, both to Follow-ups:
    - manga's `--good` and `--bad` are nearly the same red;
    - chalk lost its wooden frame shadow.
