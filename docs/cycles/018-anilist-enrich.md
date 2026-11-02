# 018 anilist-enrich (C-004)

## Goal
Fetch AniList popularity, titles, synonyms and relations for exactly the AniList ids that AnimeThemes lists (D-036), politely and cached. This is the input for grouping (D-041, D-042) and for the draw set (D-034). Design 014 decisions 1, 3 and 6.

## Interface: `data/songrush_data/anilist.py`
- **`ANILIST_URL`** is `https://graphql.anilist.co`.
- **`MEDIA_FIELDS`** is the selection set:

  `id popularity title{romaji english native} synonyms relations{edges{relationType(version:2) node{id type format title{romaji english}}}}`

  `relationType(version:2)`: the saved relation fixture contains `SOURCE` (115 edges), which only version 2 returns, so the fixture was made with version 2. Design 014 F2's live check used the default (version 1); this cycle follows the fixture. Version 2 can also return `COMPILATION` and `CONTAINS`; C-006 must state how to treat them (the fixture has none).
- **`build_query(ids) -> str`** returns `query{Page(perPage:50){media(id_in:[<ids>],type:ANIME){<MEDIA_FIELDS>}}}`. The ids are literal, comma-joined and in the given order. No GraphQL variables are used, for the same reason as in cycle 017.
- **`fetch_media(client, cache, ids, *, batch_size=50, log=print) -> dict[int, dict | None]`**
  - `batch_size` is between 1 and 50, otherwise `ValueError`. If the first live batch of 50 with relations hits AniList's complexity limit, C-007's real build lowers it to 25.
  - **Input check:** every id must be an `int` and not a `bool`, otherwise `ValueError`, so no foreign text reaches the query.
  - **Order and duplicates:** it deduplicates `ids`, keeping the first occurrence, and drops `None`.
  - **Cache:** each id is cached separately under `al-<first 16 hex of sha256(MEDIA_FIELDS)>-<id>.json`.
    - A cached record is a JSON object `{"media": <media or null>}`.
    - A stored `null` means AniList returned no media for that id; it is a cache hit, so no refetch.
  - **Batches:** the uncached ids are requested in batches of at most 50, in input order, one POST per batch (`{"query": build_query(batch)}`).
  - **Batch validation:** every batch response is checked before anything is cached. It raises `ValueError` for:
    - a non-empty top-level `errors`;
    - a missing `data.Page.media` list;
    - a media `id` that was not requested.
    Any failure caches nothing for the whole batch.
  - **Batch results:** each requested id gets its media, or `null` if it is absent from the response, and its record is stored.
  - **Result:** a dict over all requested ids. A media dict holds:
    - `popularity`, an int or null;
    - `titles {romaji, english, native}`;
    - `synonyms`, a list;
    - `relations`, a list of `{type, node: {id, type, format, titles: {romaji, english}}}` in response order.
  - **Missing keys:** `popularity`, any title and node `type`/`format` become `None`; `synonyms` and `relations` become `[]`.
  - **Logging:** each fetched batch logs `anilist batch i/n ids=<count>`, and the end logs `anilist media=<found> missing=<null count> cached=<hits>`, plus one line `anilist missing ids: <ids>` when any id is null.
  - **No expiry:** cached records never expire. A rebuild that wants fresh popularity clears the prefix (`FileCache.clear("al-")`); C-007 exposes this as part of `refresh_all`. This is acceptable under D-036, which allows caching.
- **Live use:** `PoliteClient(UrllibTransport(), SystemClock(), gap_s=2.0, timeout_s=60.0)`. AniList is limited to 30 requests per minute (CLAUDE.md), so a 2 s gap keeps a single client at or below that. The client already honours `Retry-After` on a 429. **No live request in tests.**

## Acceptance criteria (each is a test, offline, with a fake transport)
1. `build_query([5081, 101922])` contains `id_in:[5081,101922]`, `perPage:50`, `type:ANIME` and `relationType(version:2)`.
2. **Batches:**
   - 120 distinct ids with no cache make 3 POSTs of sizes 50, 50 and 20, in input order.
   - A rerun makes 0 POSTs.
   - `batch_size=25` gives 5 POSTs.
   - `batch_size=0` or 51 raises `ValueError`.
   - A `str` or `bool` id raises `ValueError`.
3. **Missing ids:**
   - The fake answers with media only for some ids. The others map to `None`, and a rerun makes 0 POSTs.
   - `None` and duplicate input ids are ignored.
4. **Shapes, on the fixture `anilist_id_in.json`** (served as the response for its ids):
   - 101922 has `titles.english` `"Demon Slayer: Kimetsu no Yaiba"`, `titles.romaji` `"Kimetsu no Yaiba"` and an int `popularity`.
   - The synonyms keep their order.
5. **Relations, using `anilist_relations_franchises.json`** (a list of media records).
   - The fake transport reads `id_in` from the request query and answers `{data:{Page:{media:[...]}}}` with only the fixture records whose id was requested.
   - Requesting `[356]`: its relations include a `PREQUEL` to node 11741 with `titles.romaji` `"Fate/Zero 2nd Season"` and `titles.english` `None`, since the fixture node has no `english`.
   - Node `type` and `format` are kept, and `synonyms` is `[]` and `popularity` is `None`, since the fixture lacks them.
6. **Validation:**
   - A response with `errors`, without `data.Page.media`, or with an unrequested id raises `ValueError`.
   - No cache entry is written for any id of that batch.
7. **Partial reuse:** with 30 of 60 ids already cached, exactly 1 POST for the other 30.
7b. **Missing log:** when ids 7 and 9 come back absent, one log line contains `missing ids` together with both 7 and 9.
8. `.venv\Scripts\python.exe -m pytest` exits 0 from `data/`.

## Test list
- `data/tests/test_anilist.py`: criteria 1-7.

## File ownership
- Test writer: `data/tests/test_anilist.py`.
- Implementer: `data/songrush_data/anilist.py`.

## Out of scope
Grouping and the title rules (C-006), the real run (C-007), and Korean titles (C-005).

## Review changes (spec round 1)
The critic counted the fixtures offline.
- **H1.** 50 ids together with relations were never sent in one query, so AniList's complexity limit is a risk. `batch_size` is now a parameter, and C-007 drops it to 25 if the first live batch fails.
- **H2.** Criterion 5 could not run as written: the 124-record fixture would trip the unrequested-id check, and the fixture lacks `popularity`, `synonyms`, `native` and node `english`. The fake now answers only requested ids, and defaults for missing keys are defined.
- **M1.** Missing ids are logged by id. The cache never expires, and refresh happens by clearing the prefix through C-007's `refresh_all`.
- **M2.** The reason for version 2 is now the fixture evidence (`SOURCE`). `COMPILATION` and `CONTAINS` are flagged for C-006.
- **Nit.** Ids are type-checked before they enter the query.

## Test commit and result
- Tests: `0b2815a` (locked). Implementation: `2711fad` (attempt 1).
- Verifier: `.venv\Scripts\python.exe -m pytest` from `data/` gave `336 passed in 6.31s`; `tests/test_anilist.py -v` gave `37 passed`. `git diff 0b2815a HEAD -- data/tests/test_anilist.py data/tests/conftest.py` was empty. `git diff --stat 0b2815a HEAD`: only `data/songrush_data/anilist.py` (177 insertions). Criteria 1-8 and 7b confirmed.

## Code review (critic)
No Blocker or High. The critic ran a probe against a fake transport (dedupe, null cache hit, log lines, failed batch caches nothing, User-Agent `SongRushBot/0.1.0 (+https://github.com/kimdoodoo12/SongRushApp)`, timeout 60.0).
- **M1 (Follow-up).** The cache stores the normalized record, but the key hashes only `MEDIA_FIELDS`. A later fix to `_normalize` would leave old-shape records in the cache unnoticed. Store the raw media and normalize on read (the cycle 017 convention), or add a record version to the key.
- **N1 (Follow-up).** Commit `c322bcd` also recorded the unrelated D-043 question for C-008. Record questions for other items in a separate commit.
