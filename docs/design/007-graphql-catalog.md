# 007 AnimeThemes GraphQL vs JSON:API (S-007)

Stack updated for D-029 (2026-11-02); decisions unchanged.

Researched 2026-11-02 by `explorer`. It sent 7 requests to animethemes hosts, at least 4 s apart, with the SongRushBot UA. It also read docs and GitHub. No audio was fetched. After critic review, 2 more requests re-fetched the fixtures with `id` fields. Critic and the lead re-parsed all saved responses.

## Questions (BACKLOG S-007)
1. What exactly does the JSON:API deprecation notice say, and is there a removal date?
2. Does one GraphQL query cover every catalog field?
3. How do pagination and limits work, and how large is a 100-anime page?

## Findings

### F1. JSON:API is deprecated; no removal date was found
- The raw HTML of https://api-docs.animethemes.moe/reference.html has a warning box that says: `The JSON:API is deprecated and it will be removed.`
- The docs have a "Migrating from JSON:API" page (https://api-docs.animethemes.moe/graphql/migrating/), which says: "While writing the GraphQL API, it was decided to make some changes to follow the best practices recommended by GraphQL."
- **No removal date or version** appears in the docs, the docs repo, the server releases (the latest are from 2022), or the issue search. Discord was not checked.
- GraphQL itself is labelled experimental. The rate-limit page says: "The current complexity value is experimental and debatable. It may change." The "getting started" page reportedly warns that the API is "experimental and subject to change"; that wording comes from a search summary and was not verified.

### F2. One GraphQL query covers every field
- Endpoint: `POST https://graphql.animethemes.moe/`.
- The pinned query is saved as `data/tests/fixtures/gql_page_request.json`. It is `animePagination(first:100, page:N)` selecting:
  - `id title{romaji} slug year season format synonyms{text}`
  - `animethemes{id type sequence slug song{title{romaji} performances{alias as artist{name{main} slug}}} animethemeentries{version episodes nsfw spoiler videos{nodes{basename resolution source link size audio{id basename filename link size}}}}}`
  - `resources{nodes{site externalId link}} series{nodes{id slug}}`

Differences from the JSON:API shapes that C-001 must handle:

| Field | JSON:API | GraphQL |
|---|---|---|
| Anime name | `name` | `title.romaji` |
| Season | "Summer" | enum `SUMMER` |
| Format | `media_format` | `format` |
| Artists | `song.artists[].name` | `song.performances[].artist.name.main` (one Performance per artist credit; the docs say to dedupe) |
| Videos, resources, series | flat lists | wrapped in `nodes` |
| Resource site | "AniList" | enum `ANILIST`; `externalId` is null for sites without an id |
| Song | `"song": null` on the same 2 themes (the earlier "always present" claim was wrong) | nullable: `aishen_qiaokeli_ing` OP1 (id 6013) and ED1 (id 6014) |
| Entry version | nullable; null counts as 1 | `Int!`, never null |
| Theme id, audio id | `id` | `id` (selected after review) |

Lead check of the saved responses:
- `gql_bakemonogatari.json` (6,611 B) has theme ids 3373-3378 and audio ids OP1 2563, OP2 2564, OP3 2565, OP4 2566, OP5 2567, ED1 2562, with the same 6 themes and audio sizes as the JSON:API fixture (3,610,594 / 3,500,980 / 3,518,682 / 3,659,136 / 3,581,945 / 3,566,146), AniList 5081, and series `monogatari` (id 116).
- `gql_page1.json` (376,520 B) applied with design 001's rules gives 358 themes, 0 without audio, and **349 kept** by the 1.5 MB floor. That is the same as the JSON:API page. `ajin` ED1 2,254,676 B is kept, and `akage_no_anne` ED1 1,415,172 B is excluded. `akuma_no_riddle` OP1 (theme 5705) has v1 audio 4743 and v2 audio 4744. Critic compared every theme's lowest-version audio between GraphQL and JSON:API page 1: 0 differences.

### F3. Pagination and limits
- Pagination is page-number based (https://api-docs.animethemes.moe/graphql/guide/pagination/):
  - `first` (max 100) and `page`;
  - `paginatorInfo {count currentPage hasMorePages lastPage perPage total}`.
- The real page 1 returned `total` 5021 and `lastPage` 51. The JSON:API had no total.
- Limits (https://api-docs.animethemes.moe/graphql/intro/ratelimiting/):
  - **90 requests/min**. Over the limit, the response is 429 with `Retry-After`.
  - Depth limit 13.
  - Complexity limit 10000. The full query at `first:100` stayed under it.
  - **Depth and complexity errors return HTTP 200 with an `errors` body.**
- Size: a 100-anime page is 368 KB with GraphQL vs 602 KB with the JSON:API.

### F4. GraphQL is not faster
| Request | TTFB |
|---|---|
| GraphQL single anime | 20.5 s |
| GraphQL 100-anime page | 26.0 s |
| GraphQL introspection | 32.3 s |
| JSON:API 100-anime page | 27.5 s |

That is one sample each. Both APIs are slow from this network, consistent with design 003's server-side delay. A full crawl is 51 pages, about 26 s each, so about 9-13 min serial.

## Options
- **A. Keep JSON:API.** It is officially deprecated, with no date. Building new code on it means planned rework.
- **B. Switch to GraphQL now (chosen).**
  - C-001 and C-002 are not implemented, so switching costs only fixture and criteria edits.
  - It is the documented successor, the payload is smaller, and it reports `total` and `lastPage`.
  - Risk: it is labelled experimental. A test against a saved fixture cannot detect live drift. The real mitigation is **run-time validation in C-002**: abort on missing or mistyped required fields, and warn when the collected count differs from `total`.

## Decision
1. **C-001 and C-002 use GraphQL.**
   - Fixtures: `data/tests/fixtures/gql_bakemonogatari.json` and `gql_page1.json` (pytest, design 011).
   - The pinned query is `data/songrush_data/animethemes.graphql`.
   - The JSON:API fixtures stay as design evidence and are not used by tests.
2. **Mapping rules for C-001** (design 001 decision 2, adapted):
   - name = `title.romaji`
   - season = lowercase enum
   - AniList id = the `resources.nodes` entry with `site == "ANILIST"`
   - `seriesIds` from `series.nodes[].id`
   - artists = unique `performances[].artist.name.main` in order
   - `song = null` gives an empty song title and no artists (the theme is still kept)
   - theme id = `animethemes[].id`; audio key = `audio.id`. Never use the basename as a key, because the filename contains the anime name (D-005/D-009).
   - entry version is non-null; the lowest version wins
3. **C-002** behaviour:
   - It posts the pinned query with `first:100` and stops when `hasMorePages` is false.
   - Retry applies only to 429 (wait `Retry-After`) and 5xx (backoff), up to 3 times.
   - **A 200 response with an `errors` array aborts immediately, with no retry.** Depth, complexity and validation errors are deterministic.
   - Run-time validation: a missing or mistyped required field aborts and names the page; a final count different from `total` logs a warning.
   - **The cache key includes a hash of the query text**, so a changed query never reads stale pages.
   - Request timeout at least 60 s; at least 3 s between requests.
4. C-003 (AniList) and C-004 are unaffected, apart from consuming C-001's output.

## Resulting items
- C-001 and C-002 criteria are rewritten in BACKLOG to match this doc. No new items.

## Review changes (round 1, combined with design 006)
| Finding | Change |
|---|---|
| H1: no theme or audio id in the query; basename as key would leak the answer | `id` added to `animethemes` and `audio`; fixtures re-fetched; numeric ids are the keys |
| H2: fixture-based drift test catches nothing | Replaced by run-time validation in C-002 |
| M1: `errors` retried like 429 | Abort immediately; retry only 429 and 5xx |
| M2: cache ignores query changes | Query hash in the cache key |
| M3: "song always present" wrong | Corrected; the null-song case added to C-001; version non-null noted |
