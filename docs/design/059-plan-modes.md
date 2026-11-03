# 059 plan: M-5 anime-specific modes

## Milestone (verbatim)
> **M-5** Anime-specific modes: OP/ED filter, year range, popularity difficulty. Widening the draw range beyond the cached set brings back D-026's lobby fetch of uncached songs (design 029).

## What exists (explorer survey on `dev` `64e0fc2`)
- **The catalog has what the filters need.**
  - Themes carry `type` (`OP`/`ED`) and `slug`; anime carry `year` and `season`.
  - `drawSet` is ordered by AniList popularity, descending, so a theme's index in it is its popularity rank. No score is stored.
  - The draw set has 586 OP and 914 ED. Every theme has a year, from 1986 to 2026.
  - The draw set by 5-year bucket:

    | Years | Themes |
    |---|---|
    | 1985-89 | 8 |
    | 1990-94 | 0 |
    | 1995-99 | 100 |
    | 2000-04 | 91 |
    | 2005-09 | 218 |
    | 2010-14 | 269 |
    | 2015-19 | 439 |
    | 2020-24 | 339 |
    | 2025+ | 36 |

  - 1,499 of the 1,500 are cached.
- **The backend ignores them today.**
  - `Candidate(themeId, audioBasename, durationMs)` is built only for cached draw-set themes (`CatalogLoader.java:83-108`).
  - `Anime` does not load `year`.
  - `Room.start(songCount)` draws uniformly from all candidates whose anime has a group (`Room.java:181-205`). There is no filter hook.
  - `create{songCount}` (solo) and `start{songCount}` (lobby) carry only the count. No settings are stored or broadcast.
- **Frontend.** Song-count selects sit in `StartScreen.jsx` (solo) and in `LobbyScreen.jsx`'s host controls. They send `create{nickname,songCount}` and `start{songCount}`.
- **Tests that pin message shapes.**
  - Frontend: `App.lobby.test.jsx` pins `start` as `{type,songCount}` and solo `create` as `{type,nickname,songCount}`, both with `toEqual`.
  - Backend: tests pin the key sets of `roster` and the player row.
  - No backend test rejects extra incoming keys.

## Items (each one cycle, test-checkable)
1. **B-011 backend: OP/ED and year filters on the cached draw set.**
   - `Candidate` gains `type` (`OP`/`ED`), `year` (the anime's year) and `rank` (the index in `drawSet`). The loader reads them.
   - `create` (solo) and `start` (lobby) accept these optional fields; absent fields mean no filter:
     - `types`: a non-empty subset of `["OP","ED"]`;
     - `yearFrom` and `yearTo`: integers from 1950 to 2100, with `yearFrom <= yearTo`.
   - Bad values give `error{code:"bad-filter"}`.
   - The draw uses only matching candidates. Too few gives `error{code:"not-enough-songs", available:n}`, sent to the requester only.
   - The `available` count is a number only. No catalog ids or records go to the client (Multiplayer rules).
   - Acceptance (JUnit):
     - every filter combination draws only matching themes;
     - absent fields behave as today, with the earlier tests unchanged;
     - each bad value gives `bad-filter`;
     - a too-narrow filter gives `not-enough-songs` with the exact `available`;
     - the loader test reads `type`, `year` and `rank` from the fixture catalog.
2. **F-008 frontend: filter controls for solo and host.**
   - Next to the song-count select:
     - a `곡 종류` select with `전체`, `오프닝만` and `엔딩만`;
     - a `연도` range with two selects. `처음부터` or a year from 1985 to the current year goes in the from-select, and `지금까지` or a year in the to-select.
   - The client sends only non-default fields, so the locked `toEqual` tests stay valid when the defaults are kept.
   - `not-enough-songs` shows `조건에 맞는 곡이 n곡뿐입니다. 곡 수를 줄이거나 조건을 넓혀 주세요.`
   - Acceptance (Vitest):
     - the defaults send exactly today's messages;
     - each control adds exactly its field;
     - `from > to` disables 게임 시작 and shows a hint;
     - the `not-enough-songs` message is shown with n.

## Needs decision (recorded, not asked: sleep mode)
- **D-069 popularity difficulty (product).** Tiers over the popularity rank of the 1,500 cached themes. Options:
  - **(a) Three tiers.** 쉬움 = rank 1-300, 보통 = 1-1,500 (today's behaviour), 어려움 = 501-1,500. **Recommended.**
  - **(b) Two tiers.** 쉬움 = top 500, 전체 = all.
  - **(c) A free "top N" slider.**
  - The dependent items (B-012 backend tier filter, F-009 frontend tier select) stay out of Ready until it is decided.
- **D-070 widening beyond the cached set (cost, product).** Options:
  - **(a) Not now, recommended.** Keep the cached 1,500 and, if wanted, grow the cache offline (for example to the top 3,000, about 11 GB of disk).
  - **(b) Build D-026's lobby fetch.** The backend fetches uncached themes from AnimeThemes while the lobby waits.
  - Option (b) adds external requests from the server, a disk cache policy and waiting-room UX. Its own design cycle would come first.

## Not planned (outside the milestone)
- Season or genre filters.
- Per-series caps.
- Showing the filter to non-hosts.
