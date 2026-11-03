# 043 anidb-korean (C-008)

## Goal
Add AniDB's Korean titles to the Korean answer slot. They come from the official titles dump, fetched at most once per 24 h and never retried, and are mapped to our anime through the ANIDB resource that AnimeThemes lists for each anime (D-039, D-040, D-043; design 014).

## Source
- `https://anidb.net/api/anime-titles.xml.gz` is gzip-compressed XML. Its root is `<animetitles>`, holding `<anime aid="N">` elements whose `<title type="..." xml:lang="..">` children carry the titles.
- Only titles with `xml:lang="ko"` that contain a Hangul syllable (`titles.has_hangul`) are used, of any `type`: Korean has no single official title (D-039).
- The request goes through the existing `PoliteClient` with the SongRushBot User-Agent, a timeout of at least 60 s, one attempt, and no retry.

## Interface: `songrush_data/anidb.py`
- `ANIDB_URL`, `MIN_INTERVAL = timedelta(hours=24)`, `MAX_XML_BYTES = 100 * 1024 * 1024`.
- `parse_titles(gz_bytes) -> dict[int, list[str]]` decompresses the dump and returns aid to Korean titles in document order, without duplicates.
  - It raises `ValueError` in these cases:
    - the data is not gzip;
    - the decompressed size exceeds `MAX_XML_BYTES`;
    - the XML contains `<!DOCTYPE` or `<!ENTITY`;
    - the XML is malformed;
    - the root is not `animetitles`.
  - It skips an `<anime>` whose `aid` is not a positive integer.
- `korean_by_anilist(titles_by_aid, anime) -> dict[int, list[str]]`.
  - `anime` is the crawled AnimeThemes list. Each record has `anilistId`, which may be null, and `anidbIds`, a list.
  - For each record with an `anilistId`, the function concatenates the Korean titles of its `anidbIds`, without duplicates.
  - Records without an `anilistId`, and ids with no Korean title, are left out.
- `load_korean(client, cache_dir, anime, *, now, refresh=False, log=print) -> dict`. The cache folder is `cache/anidb/`, which is gitignored like the rest of `cache/`.
  1. If `anime-titles.xml.gz` exists and `refresh` is false, the function parses the cached file and makes no request.
  2. Otherwise it reads `attempt.json` (`{"at": "<ISO-8601 with offset>"}`). If the last attempt is less than `MIN_INTERVAL` before `now`, it logs `anidb skipped: last attempt <at>, next allowed <at+24h>` and returns the cached result, or `{}` when nothing is cached.
  3. Otherwise it writes `attempt.json` with `now` **before** making the request, then makes exactly one GET.
     - On success it validates the body with `parse_titles` first, then stores it atomically (`FileCache.put`).
     - On any `HttpError`, `TransportError` or `ValueError` it logs `warning: anidb skipped: ...` and returns the cached result, or `{}`. It never retries and never deletes the attempt marker.
  4. It logs `anidb aids_with_korean=<n> anilist_ids_with_korean=<m>`.
- **Build integration** (`build.py`):
  - The AniDB result is merged as `merge_korean(manual, korean_aod, korean_anidb)`. The manual file comes first, then aod, then AniDB.
  - A new CLI flag, `--refresh-anidb`, passes `refresh=True`. Even with the flag, the 24 h marker still applies.
  - The AniDB client is added to the polite clients table with `(3.0, 60.0)`.

## Acceptance criteria (pytest, offline, from `data/`)
1. **Parsing** (`tests/test_anidb.py`, using a new fixture `tests/fixtures/anidb_titles_sample.xml`: a small hand-written sample of about 6 `<anime>` elements in the real format, with Korean, Japanese, English and `x-jat` titles; the test gzips it in memory):
   - Korean titles are returned per aid in document order. Duplicate Korean titles in one aid appear once.
   - A `ko` title without Hangul, such as Latin letters, is dropped.
   - An aid with no Korean title is absent.
   - The function raises `ValueError` for non-gzip bytes, for a document with `<!DOCTYPE`, for a document with `<!ENTITY`, for malformed XML, and for a wrong root.
   - A decompressed size over the cap raises `ValueError`. The test monkeypatches the cap down.
2. **Mapping.** An anime with two `anidbIds` gets both aids' titles, without duplicates. An anime without an `anilistId`, or whose `anidbIds` is `[]`, is absent.
3. **Throttle and cache** (fake client and an injected `now`):
   - A cached file makes no request.
   - With no file and no marker, exactly one request is made, and the marker is written before it. The test checks the marker inside the fake client's `request`.
   - With no file and a marker 23 h 59 min old, no request is made and the result is `{}`.
   - With a marker exactly 24 h old, one request is made.
   - A failing request (an `HttpError` 503, and a `TransportError`) makes one call only, keeps the marker and returns `{}`.
   - An invalid body (not gzip) is not cached and returns `{}`.
   - With `refresh=True` and a cached file, the 24 h marker still blocks a new request, and the cached result is returned.
4. **Build.** A build test, following the pattern of the existing build tests with fake clients, shows an AniDB Korean title appearing in the anime's Korean slot and in its group's accepted strings. An AniDB failure does not fail the build.
5. **Regression.** `.venv\Scripts\python.exe -m pytest` exits 0, and every earlier test still passes. The conftest network guard stays in force.

## Live run (lead, after the cycle is merged, not before 2026-11-03 18:34 KST)
- Run the build once with the cache. The only new live request is the AniDB dump.
- Record in the cycle doc:
  - the marker time;
  - the dump size;
  - the count of aids with Korean titles;
  - the new Korean strings in the catalog and the change in the accepted-string count;
  - the change in `korean_gaps.json`.
- Update the CLAUDE.md AniDB line with the next allowed time.

## Test list
- `data/tests/test_anidb.py` covers criteria 1-3.
- `data/tests/test_build.py` gets an added test, or a new `data/tests/test_build_anidb.py` when the existing file is easier to leave alone, for criterion 4.
- `data/tests/fixtures/anidb_titles_sample.xml`.

## File ownership
- **Test writer:** the test files above and the fixture.
- **Implementer:** `data/songrush_data/anidb.py` and `data/songrush_data/build.py`.

## Out of scope
- AniDB Japanese and English titles: AnimeThemes and AniList already cover them.
- Any AniDB HTTP API or page request (D-043).
