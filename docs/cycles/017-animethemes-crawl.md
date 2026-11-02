# 017 animethemes-crawl (C-003)

## Goal
Fetch every anime and its OP/ED themes from AnimeThemes GraphQL with the polite client, and turn each page into plain records with one chosen audio file per theme (design 014 decisions 1-2).

## Interface: `data/songrush_data/animethemes.py`
- **`QUERY_PATH`** is `data/songrush_data/animethemes.graphql`. It holds the query text of the saved request fixture `data/tests/fixtures/gql_page_request.json`, unchanged except that `page:1` becomes `page:{page}`.
  - `fetch_page` substitutes the page number into that text.
  - No GraphQL variables are used, because only the literal form is proven by the saved request (critic M1).
  - The file is LF and ASCII.
- **`ANIMETHEMES_URL`** is `https://graphql.animethemes.moe/`.
- **`fetch_page(client, cache, page, *, refresh=False) -> dict`** POSTs `{"query": <text with the page number>}` as JSON (`Content-Type: application/json`). It returns `data.animePagination`.
  - **Cache key:** `at-<first 16 hex of sha256(template bytes)>-p<N>.json`, where the template is the file text with `{page}`, read as UTF-8 with LF line endings.
  - **Validation before caching** (`validate_page(body, N)`), applied to every response, refetches included. It raises `ValueError`, and caches nothing, when any of these holds:
    - the top-level `errors` is non-empty;
    - `data.animePagination` is missing;
    - `paginatorInfo.currentPage != N` (critic H1);
    - `paginatorInfo.perPage != 100`.
  - **Hit check:** a hit is judged with `cache.get`.
  - **`refresh=True`** bypasses the read, fetches, validates, then overwrites the cache entry with `cache.put` and returns the fresh result.
- **`crawl(client, cache, *, log=print) -> CrawlResult`** returns the raw anime items of all pages, in page order, as `CrawlResult.items`.
  - It fetches page 1, 2, ... while `paginatorInfo.hasMorePages` is true.
  - **Resume check:** when page k was a cache hit and page k+1 is not cached, it refetches page k with `refresh=True` (after the gap) and compares the lists of anime ids.
    - If they differ, the source changed: it clears the `at-<hash>-` prefix, logs one line containing `shifted`, and restarts from page 1.
    - If they are equal, it continues with the refreshed page k.
  - **Order check:** anime ids must ascend across pages, so the first id of page k+1 is greater than the last id of page k. A violation logs one warning naming both pages; it does not raise.
  - **Duplicate check:** a duplicate anime id across pages deletes the cache entry of the later page, then raises `ValueError` naming both pages. A rerun therefore refetches that page instead of repeating the error forever.
  - **Summary:** at the end it compares the item count with the last page's `paginatorInfo.total`, warns if they differ, and logs `crawl pages=P anime=A total=T`.
  - It returns `CrawlResult(items, pages, total)`.
- **`select_themes(item) -> dict`** converts one raw anime item into
  `{id, slug, name, year, season, anilistId, anidbIds, themes: [...]}`:
  - `name` is `title.romaji`.
  - `anilistId` is the `externalId` of the first `resources.nodes` entry with `site` `ANILIST`, as an int, or null; a null `externalId` gives null.
  - `anidbIds` lists every ANIDB `externalId` as ints, in response order, without duplicates or nulls (critic M2: one fixture anime has three).
  - **Themes:** only those with `type` `OP` or `ED` and at least one non-null audio. Each theme is
    `{id, type, slug, song, artists, audio: {id, basename, url, size}}`, where `url` comes from the fixture field `audio.link`:
    - `song` is `song.title.romaji`, or null.
    - `artists` lists each performance's `artist.name.main`, skipping nulls; it may be empty.
  - **Audio choice** (deterministic): entries are sorted by `version`, null first, then by their position in the response; within an entry, videos are sorted by `basename`. The first non-null `audio` is chosen.
    - **Deviation from design 014 decision 2** ("then id", "videos by id"): the saved query selects no entry or video id, so position and basename are used instead.
    - The critic measured `gql_page1.json`: videos within an entry always share one audio, and the two rules choose differently in 0 of 358 themes.
  - Themes keep their response order. Anime with zero kept themes are still returned, because grouping needs them (design 014).
- Live use: `PoliteClient(UrllibTransport(), SystemClock(), gap_s=3.0, timeout_s=120.0)`. **No live request in tests.** The first live run (C-007's real build) checks the logged pages 1 and 2 (`currentPage` and the id ranges) before the full crawl continues.

## Acceptance criteria (each is a test, offline)
1. **Query file:** `animethemes.graphql` with `{page}` replaced by `1` equals the fixture request's `query` exactly, apart from line endings. It is ASCII and LF.
2. **fetch_page** with a fake transport returning `gql_page1.json`:
   - It sends a POST whose JSON `query` contains `page:1`, and returns `animePagination`.
   - A second call makes 0 transport calls (cached).
   - These raise and leave no cache entry:
     - a response with `errors`;
     - a response whose `currentPage` is 1 for a page-2 request;
     - a response missing `animePagination`.
   - `refresh=True` makes a call and overwrites the entry.
3. **crawl** with a fake that serves 3 synthetic pages (ids 1-3, 4-6, 7-9; `hasMorePages` true, true, false):
   - It makes 3 calls and returns 9 items.
   - A rerun with every page cached makes 0 calls.
   - **Resume:** pages 1-2 cached, page 3 not cached. The refetch of page 2 returns the same ids, so 2 calls in total (page 2 again, then page 3).
   - **Shift:** the refetched page 2 returns ids 5-7. The crawl clears, logs `shifted` once, restarts, and returns the fresh pages.
   - **Order:** pages `[1,3]` then `[2,4]` (no duplicates) warn once, naming page 1 and page 2.
   - A duplicate id across pages raises, and the later page's cache entry is gone.
   - **Summary:** `total` 9 versus 9 items gives no warning; `total` 10 versus 9 items warns once. The summary line is logged, and `CrawlResult.pages` is 3.
4. **select_themes** on `gql_page1.json`:
   - For every item, the expected kept themes and chosen audio are computed **in the test** from the fixture by applying the stated rules independently; no hard-coded counts.
   - Every kept theme has `type` in {OP, ED} and a non-null audio.
   - Synthetic cases:
     - a theme whose only audio is in a `version: 2` entry, while the `version: null` entry has no audio, takes the version-2 audio;
     - two videos in one entry with different audios take the one whose video basename sorts first;
     - an `IN` theme is dropped;
     - a theme with no audio is dropped;
     - an anime with no themes returns `themes: []`;
     - a missing ANILIST resource gives `anilistId` null;
     - anime 15 in the fixture has three `anidbIds` in order;
     - an ANIDB resource with a null `externalId` is skipped.
5. `.venv\Scripts\python.exe -m pytest` exits 0 from `data/`.

## Test list
- `data/tests/test_animethemes.py`: criteria 1-4, using fakes for the transport and clock from the C-002 interfaces.

## File ownership
- Test writer: `data/tests/test_animethemes.py`.
- Implementer: `data/songrush_data/animethemes.py` and `data/songrush_data/animethemes.graphql`.

## Out of scope
AniList (C-004), Korean titles (C-005), grouping (C-006), catalog output (C-007), and the live crawl, which C-007's real build runs.

## Review changes (spec round 1)
The critic measured `gql_page1.json`:
- 100 anime and 358 themes, every video with audio;
- entry versions are always integers;
- videos within an entry share one audio;
- `externalId` is an int;
- the cache key passes the key pattern.

Changes:
- **H1.** A page that ignored the requested number would have been cached for good, causing endless duplicate errors. Responses are now validated (`currentPage`, `perPage`, `errors`) before caching, and a duplicate deletes the later page's entry.
- **H2.** The audio rule's deviation from design 014 is recorded, with the measurement.
- **M1.** GraphQL variables are unproven offline, so the literal `page:N` form of the saved request is used.
- **M2.** `anidbIds` keeps every AniDB id.
- **M3.** The crawl ends with a count check against `total` and a summary.
- **M4.** The hit check and the refresh path are explicit.
- **Nits:** `audio.link` maps to `url`; a null `externalId` gives null.

## Test commit and result
- Test commit: `113a8c9` test(017), 32 tests. Red phase: an ImportError only; the 267 existing tests pass.
- Implementation: `28cef70`.
- The verifier ran 299 data tests, all passing. The locked tests are unchanged. The query file is ASCII and LF, with no trailing newline and one `{page}`.

## Code review (critic)
No Blocker or High findings. Mutation run: 13 of 16 caught.

The critic simulated failure modes:
- the shift restart terminates;
- re-validating cached bytes cannot loop;
- a 500 on page 2 leaves only page 1 cached, and a rerun resumes cleanly;
- Ctrl+C leaves the same state.

Findings:
- **M1:** an anime deleted upstream mid-crawl drops one item silently, and the count check against `total` still passes. Fix: record `total` per page and restart on a change. Moved into C-007's acceptance.
- **M2:** a fully cached crawl never sees new anime. Fix: refetch the last page at the end, plus a `refresh_all` option exposed by the C-007 CLI. Moved into C-007's acceptance.
- **M3:** three surviving mutations need tests. Recorded as Follow-ups:
  - read-time validation;
  - deletion of a corrupt entry;
  - the "first ANILIST" rule.
- **Nits:**
  - the reference audio helper mirrors the implementation's sort;
  - a refreshed page is logged as `(cached)`;
  - `HttpError` messages lack the page number.
