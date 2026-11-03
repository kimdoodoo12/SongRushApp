# 043 anidb-korean (C-008)

## Goal
Add AniDB's Korean titles to the Korean answer slot. They come from the official titles dump, fetched at most once per 24 h and never retried, and are mapped to our anime through the ANIDB resource that AnimeThemes lists for each anime (D-039, D-040, D-043; design 014).

## Source
- `https://anidb.net/api/anime-titles.xml.gz` is gzip-compressed XML. Its root is `<animetitles>`, holding `<anime aid="N">` elements whose `<title type="..." xml:lang="..">` children carry the titles.
- Only titles with `xml:lang="ko"` that contain a Hangul syllable (`titles.has_hangul`) are used, of any `type`: Korean has no single official title (D-039).
- The request goes through the existing `PoliteClient` with the SongRushBot User-Agent and a timeout of at least 60 s, created with **`max_attempts=1`** so its built-in retries for transport errors, 429 and 5xx never run (spec review B1; `http.py` defaults to 3 attempts).

## Interface: `songrush_data/anidb.py`
- `ANIDB_URL`, `MIN_INTERVAL = timedelta(hours=24)`, `MAX_XML_BYTES = 100 * 1024 * 1024`, and `NOT_BEFORE = 2026-11-03T18:34:00+09:00` (the previous request was 2026-11-02 18:34 KST, so no request may happen before this even when no marker exists; spec review H1).
- `parse_titles(gz_bytes) -> dict[int, list[str]]` decompresses the dump and returns aid to Korean titles in document order, without duplicates.
  - Decompression is streamed and bounded (for example `zlib.decompressobj(wbits=31)` with `max_length=MAX_XML_BYTES + 1`), so a gzip bomb never fully expands in memory.
  - Parsing uses an expat parser whose doctype and entity-declaration handlers raise, so any `<!DOCTYPE` or `<!ENTITY` is rejected in every encoding (spec review N1). The language is the `xml:lang` attribute, which ElementTree exposes as `{http://www.w3.org/XML/1998/namespace}lang`.
  - Within one aid, titles of type `main` or `official` come first, then the others (`syn`, `short`, ...), each group in document order, because the first Korean title can become the reveal title (`groups.py`; spec review M5).
  - It raises `ValueError` in these cases:
    - the data is not gzip;
    - the decompressed size exceeds `MAX_XML_BYTES`;
    - the XML has a doctype or an entity declaration;
    - the XML is malformed;
    - the root is not `animetitles`.
  - It skips an `<anime>` whose `aid` is not a positive integer.
- `korean_by_anilist(titles_by_aid, anime) -> dict[int, list[str]]`.
  - `anime` is the crawled AnimeThemes list. Each record has `anilistId`, which may be null, and `anidbIds`, a list.
  - For each record with an `anilistId`, the function concatenates the Korean titles of its `anidbIds`, without duplicates.
  - Records without an `anilistId`, and ids with no Korean title, are left out.
- `load_korean(client, cache_dir, anime, *, now, log=print) -> dict`. The cache folder is `cache/anidb/`, which is gitignored like the rest of `cache/`. `now` is an aware datetime; the build passes `datetime.fromtimestamp(clock.wall(), timezone.utc)`.
  1. If `anime-titles.xml.gz` exists and parses, the function uses it and makes no request. A cached file that fails to parse is logged as a warning, deleted, and treated as absent.
  2. Otherwise: if `now < NOT_BEFORE`, it logs `anidb skipped: not before <NOT_BEFORE>` and returns `{}`. It reads `attempt.json` (`{"at": "<ISO-8601 with offset>"}`); a marker that cannot be read or parsed, has no offset, or lies in the future counts as an attempt just now (fail-closed, with a warning). If the last attempt is less than `MIN_INTERVAL` before `now`, it logs `anidb skipped: last attempt <at>, next allowed <at+24h>` and returns `{}`.
  3. Otherwise it writes `attempt.json` with `now` **before** making the request, then makes exactly one GET.
     - On success it validates the body with `parse_titles` first, then stores it atomically (`FileCache.put`).
     - On any `HttpError`, `TransportError` or `ValueError` it logs `warning: anidb skipped: ...` and returns `{}`. It never retries and never deletes the attempt marker.
     - If storing the valid body fails with `OSError`, it logs a warning and still uses the parsed titles for this run.
  4. It logs `anidb aids_with_korean=<n> anilist_ids_with_korean=<m>`.
- **Build integration** (`build.py`):
  - The AniDB result is merged as `merge_korean(manual, korean_aod, korean_anidb)`. The manual file comes first, then aod, then AniDB.
  - AniDB is loaded **after** every required source has succeeded, right after aod and just before `build_catalog`, so a failing required source never spends the 24 h marker (spec review H2).
  - The AniDB client is a `PoliteClient` with gap 3.0 s, timeout 60 s and `max_attempts=1`. `--refresh-all` does not affect AniDB, and there is no AniDB refresh flag (to refresh, the cached dump is deleted by hand).

## Acceptance criteria (pytest, offline, from `data/`)
1. **Parsing** (`tests/test_anidb.py`, using a new fixture `tests/fixtures/anidb_titles_sample.xml`: a small hand-written sample of about 6 `<anime>` elements in the real format, with Korean, Japanese, English and `x-jat` titles; the test gzips it in memory):
   - Korean titles are returned per aid in document order. Duplicate Korean titles in one aid appear once.
   - A `ko` title without Hangul, such as Latin letters, is dropped.
   - An aid with no Korean title is absent.
   - The function raises `ValueError` for non-gzip bytes, for a document with `<!DOCTYPE`, for a document with `<!ENTITY`, for malformed XML, and for a wrong root.
   - A decompressed size over the cap raises `ValueError`. The test monkeypatches the cap down.
2. **Mapping.** An anime with two `anidbIds` gets both aids' titles, without duplicates. An anime without an `anilistId`, or whose `anidbIds` is `[]`, is absent.
3. **Throttle and cache** (fake client and an injected `now` after `NOT_BEFORE` unless stated):
   - A cached file makes no request.
   - With no file and no marker, exactly one request is made, and the marker is written before it. The test checks the marker inside the fake client's `request`.
   - With no file and a marker 23 h 59 min old, no request is made and the result is `{}`.
   - With a marker exactly 24 h old, one request is made.
   - A failing request (an `HttpError` 503, and a `TransportError`) makes one call only, keeps the marker and returns `{}`.
   - An invalid body (not gzip) is not cached and returns `{}`.
   - With no file and no marker but `now` before `NOT_BEFORE`, no request is made.
   - A corrupt marker (not JSON, no offset, or in the future) blocks the request.
   - A corrupt cached file is deleted and, with no recent marker, one request is made.
   - A store failure (`OSError` from the cache) still returns the parsed titles.
   - Streaming cap: with the cap monkeypatched to a small value, an over-cap dump raises without being fully decompressed (the test can only check the `ValueError`).
   - Ordering: within an aid, `official`/`main` titles come before `syn`/`short`.
4. **Build** (`tests/test_build.py`, whose routing transport and fixtures the test writer extends so the existing tests keep passing): an AniDB Korean title appears in the anime's Korean slot and in its group's accepted strings; a second run makes no AniDB request; with the **real `PoliteClient`** and the routing transport, a 503, a `TransportError` and a 429 with `Retry-After: 1` each lead to exactly one AniDB request and a successful build; a failing required source (as in the existing lock test) makes no AniDB request.
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

## Review changes (spec round 1)
- **Blockers.** The AniDB client uses `max_attempts=1` (the polite client retries 3 times by default), with a real-client build test; `test_build.py` is extended, since its routing transport rejects unknown URLs and would fail every existing build test.
- **High.** A hard-coded `NOT_BEFORE` covers the previous request when no marker exists; AniDB runs after the required sources, so their failure never spends the marker.
- **Medium.** `OSError` on store is handled; a corrupt marker fails closed and a corrupt cache is refetched; decompression is streamed and bounded; `xml:lang` is read with its namespace; official titles come first within an aid.
- **Nits.** Doctype and entity declarations are rejected by the parser, not by a byte search; the `--refresh-anidb` flag is dropped; `now` comes from the build clock.

## Test list
- `data/tests/test_anidb.py` covers criteria 1-3.
- `data/tests/test_build.py` is extended (the `urls` fixture, the routing transport, the sources) for criterion 4; its existing tests must keep passing.
- `data/tests/fixtures/anidb_titles_sample.xml`.

## File ownership
- **Test writer:** the test files above and the fixture.
- **Implementer:** `data/songrush_data/anidb.py` and `data/songrush_data/build.py`.
- Fixture note: `anidb_titles_sample.xml` uses the real form (`<?xml version="1.0" encoding="UTF-8"?>`, `<anime aid="..">`, `<title type="main|official|syn|short" xml:lang="..">`).

## Out of scope
- AniDB Japanese and English titles: AnimeThemes and AniList already cover them.
- Any AniDB HTTP API or page request (D-043).
