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

## Items (revised after the plan review; each one cycle, test-checkable)
1. **B-011 backend: OP/ED and year filters on the cached draw set.**
   - `Candidate` gains `type` (`OP`/`ED`) and `year`, the anime's year as read by the loader. `rank` moves to B-012 (review M4).
   - `create` (solo) and `start` (lobby) accept optional fields. A field that is absent means no filter:
     - `types`: a non-empty list of distinct values from `OP` and `ED`;
     - `yearFrom` and `yearTo`: integers from 1950 to 2100, inclusive, with `yearFrom <= yearTo`.
   - Any other value gives `error{code:"bad-filter"}`, including a duplicate in `types`.
   - The draw uses only matching candidates, counted after the existing group filter.
   - If too few match, the server sends `error{code:"not-enough-songs", available:n}` to all, as today, where `available` is the matching count (review H1).
     - Declared change: the locked `RoomTest.fewerDrawableCandidatesThanTheCountIsRejected` key-set assertion changes to allow `available`.
   - At a successful start, a new message `settings{types?, yearFrom?, yearTo?}` goes to every member before the first `prepare`, with only the fields that were set (review M1).
     - A late joiner gets it after their `roster`.
     - It carries only numbers and the strings `OP` and `ED`, never a catalog id or record.
   - Acceptance (JUnit):
     - each filter combination draws only matching themes;
     - absent fields behave as today;
     - every bad value gives `bad-filter`: empty, duplicate or unknown types; out-of-range or reversed years; a non-integer;
     - a too-narrow filter gives `not-enough-songs` with the exact `available`;
     - `settings` reaches all members once per game, before `prepare`, and reaches a late joiner;
     - the loader reads `type` and `year` from the fixture catalog;
     - earlier tests pass, with only the one declared assertion changed.
2. **F-008 frontend: filter controls for solo and host.**
   - Next to the song-count select:
     - a `곡 종류` select: `전체`, `오프닝만`, `엔딩만`;
     - a `연도` from-select: `처음부터`, 1990, 2000, 2005, 2010, 2015, 2020, 2025;
     - a `연도` to-select: `지금까지`, 1999, 2004, 2009, 2014, 2019, 2024.
   - The years are fixed 5-year steps, because single years are mostly too thin (1987-88 and 1990-94 have no cached songs; review M3). So no clock is involved.
   - Only non-default fields are sent, so the locked `toEqual` tests stay valid with the defaults.
   - `from > to` disables both 시작 (solo) and 게임 시작 (host), with a hint.
   - Error text:
     - `not-enough-songs` with `available` shows `조건에 맞는 곡이 n곡뿐입니다. 곡 수를 줄이거나 조건을 넓혀 주세요.`;
     - without `available` it keeps today's `출제할 곡이 부족합니다.` (review H2);
     - `errorText` and the session's error branch pass `available` through.
   - Every player sees the received `settings` on the game screen as one line, such as `오프닝만 · 2010~2019`.
   - The last settings sent are kept when the room returns to the lobby (D-062). They live in app or session state, not in the host-controls component (review M2).
   - Acceptance (Vitest):
     - the defaults send exactly today's messages;
     - each control adds exactly its field;
     - the from/to guard works on both buttons;
     - both error texts appear in the right case;
     - the settings line is shown;
     - the selections survive a game and the return to the lobby.

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

## Review changes (plan round 1)
- **High.**
  - `not-enough-songs` still goes to all and gains `available`, through one declared locked-test change.
  - The frontend falls back to today's text when `available` is absent.
- **Medium.**
  - The applied conditions are sent to everyone, since a filter only the host knows is a hint.
  - Selections are kept across games.
  - Year choices are 5-year steps.
  - `rank` moves to B-012.
- **Nits.**
  - Both start buttons are named.
  - No clock is used for the year options.
  - Boundary rules are stated: duplicates are rejected and bounds are inclusive.
