# 019 korean-titles (C-005)

## Goal
Collect per-AniList-id labels from Wikidata (`ko`, `en`, `ja`) and Korean strings from the pinned anime-offline-database (aod) release, politely, cached and offline-testable, so C-006 can fill the Korean slot (D-039) and the official English/Japanese sources (design 014 decision 3). A failing source is skipped with a warning and the build continues.

## Evidence gathered in the spec step (explorer, 2026-11-03)
- aod release `2026-27` (GitHub API 200, published 2026-07-04). Assets: `anime-offline-database-minified.json` (62,331,136 B) and `.jsonl`, plus `.zst` versions. No `.gz`; stdlib reads only the uncompressed JSON, so that asset is used. Downloaded once (200, 15.3 s) to `data/cache/aod/anime-offline-database-minified.json` (gitignored).
- aod schema: top-level `$schema, license, repository, scoreRange, lastUpdate ("2026-07-04"), data`. `data` has 41,537 records; each has `sources` (URL strings), `title`, `synonyms` and more. The AniList id is the `https://anilist.co/anime/<id>` source. 20,687 records have one; no AniList id appears in two records, but 4 records carry two AniList ids (for example Cosmic Break: 17295 and 206843). No `title` contains Hangul; Korean appears only in `synonyms`. 2,661 AniList-linked records have a Hangul string.
- Wikidata: one GET of the saved query for 6 ids returned 200 in 1.09 s with 5 bindings; 999999999 is absent. A missing label is an absent key, not null. In the older sample, `ko` is missing for 28/50 and 32/41 rows; labels like `나루토 (애니메이션)` occur (stripping is C-006's job). A form-encoded POST of 300 ids returned 200 in 2.74 s with 262 bindings; GET URLs over about 2,000 characters are risky, so requests use POST.
- Fixtures saved: `data/tests/fixtures/aod_sample.json` (15 raw records, 86,809 B), `wikidata_labels_batch.json` (raw SPARQL JSON, 2,690 B) and `wikidata_labels_batch.rq` (its query).

## Interface

### `data/songrush_data/wikidata.py`
- **`WDQS_URL`** is `https://query.wikidata.org/sparql`.
- **`QUERY_TEMPLATE`** is the exported constant below, with the literal placeholder `<values>` and no trailing newline.
- **`build_query(ids) -> str`** returns `QUERY_TEMPLATE` with `<values>` replaced by each id as a quoted decimal string, separated by one space, in the given order. No trailing newline:
  ```
  SELECT ?id ?item ?ko ?en ?ja WHERE {
    VALUES ?id { <values> }
    ?item wdt:P8729 ?id .
    OPTIONAL { ?item rdfs:label ?ko . FILTER(LANG(?ko) = "ko") }
    OPTIONAL { ?item rdfs:label ?en . FILTER(LANG(?en) = "en") }
    OPTIONAL { ?item rdfs:label ?ja . FILTER(LANG(?ja) = "ja") }
  }
  ```
  For `[5114, 21, 1535, 101922, 21366, 999999999]`, `build_query(...).rstrip()` equals the text of `wikidata_labels_batch.rq` with `.rstrip()`.
- **`fetch_labels(client, cache, ids, *, batch_size=200, log=print) -> dict[int, dict[str, list[str]]]`**
  - `batch_size` is between 1 and 200, otherwise `ValueError`. Every id must be an `int` and not a `bool`, otherwise `ValueError`. `None` is dropped and duplicates keep the first occurrence (as in cycle 018).
  - **Cache:** per id, key `wd-<first 16 hex of sha256(QUERY_TEMPLATE as UTF-8)>-<id>.json`, holding the **raw** list of bindings for that id: `{"bindings": [...]}` (an empty list when Wikidata returned none). Raw storage means a later change to the parsing applies to cached records too (the cycle 018 lesson). A cached record is a hit, so no refetch, even when its list is empty. A cached entry that is not valid JSON, not an object, or whose `bindings` is not a list of objects is deleted and treated as a miss (as cycle 018's `_read_cached` does).
  - **Request:** the uncached ids go in batches of at most `batch_size`, in input order, one request per batch: `POST WDQS_URL`, headers `Content-Type: application/x-www-form-urlencoded` and `Accept: application/sparql-results+json`, body `urllib.parse.urlencode({"query": build_query(batch), "format": "json"})` as UTF-8.
  - **Validation before caching anything from a batch:** the body is a JSON object with a `results.bindings` list; every binding has `id.value`, a decimal string of a requested id. Otherwise `ValueError`, and nothing from that batch is cached.
  - **Result:** every requested id is a key, mapped to `{"ko": [...], "en": [...], "ja": [...]}`. Each list holds the `value` of that language's binding over the id's bindings, in response order, duplicates removed, missing keys skipped. An id with no bindings maps to three empty lists. `ko` is not filtered for Hangul here; C-006 decides.
  - **Failure:** if a batch raises `HttpError`, `TransportError` or `ValueError`, it logs one line `warning: wikidata skipped: <exception class name>: <message>`, stops requesting, and returns only the ids resolved so far (cached hits and earlier batches). The caller continues. Bad arguments (`batch_size`, id types) still raise.
  - **Logging:** `wikidata batch i/n ids=<count>` per request, and at the end `wikidata ids=<count> with_ko=<ids with a ko label> cached=<hits>`.
- **Live use:** `PoliteClient(UrllibTransport(), SystemClock(), gap_s=3.0, timeout_s=60.0)`. No live request in tests.

### `data/songrush_data/aod.py`
- **`AOD_RELEASE`** is `"2026-27"`. **`AOD_URL`** is `https://github.com/manami-project/anime-offline-database/releases/download/2026-27/anime-offline-database-minified.json`.
- **`korean_by_anilist(document) -> dict[int, list[str]]`** (pure). `document` is the parsed aod JSON.
  - For every record in `document["data"]`, the AniList ids are taken from `sources` entries that fully match `^https://anilist\.co/anime/(\d+)$`.
  - The record's Korean strings are `title` then each `synonyms` entry, kept when `titles.has_hangul` is true, duplicates removed, order kept.
  - Each AniList id of the record maps to that list. Records with no AniList id, and ids whose list is empty, are left out.
  - If one AniList id appears in two records, the lists are concatenated in record order with duplicates removed.
  - A document that is not an object with a `data` list raises `ValueError`. Records that are not objects, or have non-list `sources`/`synonyms` or a non-string `title`, are skipped.
- **`AOD_SIZE`** is `62331136` and **`AOD_SHA256`** is `395be786fb2f98f1fca9e963911b9332110098a080e7e2569902bbc5641d1a41`. Both match the asset digest GitHub publishes for the release (`gh api repos/manami-project/anime-offline-database/releases/tags/2026-27`, checked 2026-11-03), and the cached file matches them.
- **`load_korean(client, path, *, expected_size=AOD_SIZE, expected_sha256=AOD_SHA256, log=print) -> dict[int, list[str]]`**
  - **Valid bytes** means: length equals `expected_size`, sha256 hex equals `expected_sha256`, and the bytes parse as a JSON object with a `data` list. Tests pass the size and hash of their own small bodies.
  - **Existing file:** if `path` exists and its bytes are valid, no request is made. Otherwise the file is deleted, one line `aod cached file invalid, refetching` is logged, and it downloads as below (once).
  - **Download:** one `client.request("GET", AOD_URL)`. Invalid bytes raise `ValueError` and nothing is written. Valid bytes are written with `FileCache(path.parent).put(path.name, body)` (atomic, Windows-retrying, temp cleanup).
  - It returns `korean_by_anilist` of the parsed document.
  - **Failure:** `HttpError`, `TransportError`, `ValueError` (including JSON errors) or `OSError` logs one line `warning: aod skipped: <exception class name>: <message>` and returns `{}`. A failed download leaves no file at `path`.
  - **Logging:** on success `aod release=2026-27 records=<n> anilist_ids_with_korean=<m>`, plus `aod downloaded <bytes> bytes` when it fetched.
- **Live use:** `PoliteClient(UrllibTransport(), SystemClock(), gap_s=3.0, timeout_s=60.0)` and `path = data/cache/aod/anime-offline-database-minified.json`. The release is pinned and hash-checked, so a valid file never expires and a damaged one is refetched once. Reading the 62 MB file whole is acceptable for a one-off build tool (the critic measured a 478 MB peak and a 1.5 s parse).

## Acceptance criteria (each is a test, offline, with a fake transport and a temp folder)
1. `build_query([5114, 21, 1535, 101922, 21366, 999999999]).rstrip()` equals the `.rq` file text `.rstrip()`, and `build_query` does not end with a newline.
2. **Wikidata request:** the fake records method, URL, headers and body. One POST to `WDQS_URL`; the body parses (`urllib.parse.parse_qs`) to `format=json` and a `query` equal to `build_query` of the batch; both headers are present.
3. **Wikidata shape on `wikidata_labels_batch.json`** (served for any request): 5114 gives `ko == ["강철의 연금술사 BROTHERHOOD"]` and non-empty `en` and `ja`; 101922 gives `ko == []` and non-empty `en`; 999999999 gives three empty lists.
   - **Several bindings per id** (inline body): id 7 with three bindings whose `ko` values are `A`, `B`, `A`, plus a fourth binding with no `ko`, gives `ko == ["A", "B"]`.
4. **Wikidata batching and cache:** 450 distinct ids with an empty-bindings fake make 3 POSTs (200, 200, 50), in input order; a rerun makes 0 POSTs; `batch_size=0` and `201` raise `ValueError`; a `str` or `bool` id raises `ValueError`; `None` and duplicates are ignored.
5. **Wikidata raw cache:** after a fetch, exactly one cache entry matches `wd-*-5114.json`, its name is `"wd-%s-5114.json" % sha256(QUERY_TEMPLATE)[:16]`, and it is `{"bindings": [...]}` holding the raw binding objects from the fixture (including the `item` key), and a second `fetch_labels` reads it back to the same result with 0 POSTs.
   - **Damaged entries:** a pre-seeded entry for 5114 holding `not json`, `[]`, or `{"bindings": "x"}` (each case separately) leads to 1 POST and a replaced valid entry.
6. **Wikidata validation:** a body that is not JSON, lacks `results.bindings`, or has a binding whose `id.value` was not requested makes `fetch_labels` log a line containing `wikidata skipped` and `ValueError`, return without that batch's ids, and cache nothing from that batch.
7. **Wikidata failure skip:** with 2 batches where the second answers HTTP 404 (an `HttpError` from `PoliteClient` with a fake transport, `max_attempts=1`), the first batch's ids are returned and cached, the second batch's ids are absent, and one log line contains `wikidata skipped`.
8. **aod parse on `aod_sample.json`:** `korean_by_anilist` gives 1535 `["데스 노트", "데스노트"]`, 98478 `["3월의 라이온", "3월의 라이온 2기"]`, 5114 `["강철의 연금술사 리메이크"]`; 298 is absent; 17295 and 206843 (one record) are both absent (no Hangul); every returned string has a Hangul syllable.
9. **aod edge cases (inline documents):** a record whose `sources` include `https://anilist.co/anime/7` and `https://anilist.co/anime/8` with Hangul synonym `가나` maps both 7 and 8 to `["가나"]`; a record whose only sources are `https://anilist.co/anime/7/extra` and `https://myanimelist.net/anime/7`, with Hangul synonym `다라`, gives nothing; two records sharing AniList id 9 merge in record order without duplicates; `{"data": 3}` and `[]` raise `ValueError`.
10. **aod download:** with no file, `load_korean` (given the fake body's size and sha256) makes 1 GET to `AOD_URL`, writes the file byte-identical to the body, leaves no other file in the folder, and returns the parsed mapping; a second call makes 0 requests. With an existing valid file, 0 requests.
11. **aod failure skip:** a download answering 404 (with `max_attempts=1`), a body that is not JSON, and a JSON body without `data` each log a line containing `aod skipped`, return `{}`, and leave no file at `path`. A body with the wrong size or sha256 is treated the same.
   - **Damaged existing file:** an existing file that is not valid (truncated, or wrong hash) is replaced after exactly 1 GET, a log line contains `refetching`, and the mapping is returned.
12. `.venv\Scripts\python.exe -m pytest` exits 0 from `data/`.

## Test list
- `data/tests/test_wikidata.py`: criteria 1-7.
- `data/tests/test_aod.py`: criteria 8-11.

## File ownership
- Test writer: `data/tests/test_wikidata.py`, `data/tests/test_aod.py`. The fixtures are already saved and are not edited.
- Implementer: `data/songrush_data/wikidata.py`, `data/songrush_data/aod.py`.

## Out of scope
Stripping `(애니메이션)`-style disambiguations, deciding which labels are accepted, and the reveal title (C-006). The real run and how the build combines sources (C-007). AniDB (C-008).

## Review changes (spec round 1)
The critic loaded the fixtures and the full cached release offline. Every expected value matched, the `.rq` comparison is exact (`.gitattributes` keeps fixtures byte-exact), and POST was confirmed as the right choice.
- **H1.** A damaged aod file would have made every later build skip aod for good, losing Korean titles for 2,661 ids. The release's size and sha256 are now pinned (they match GitHub's published digest), and an invalid file is deleted and fetched once more.
- **M1.** The download is written through `FileCache.put`, which already handles fsync, Windows retries and temp cleanup.
- **M2.** `QUERY_TEMPLATE` is exported so the locked test can compute the cache key.
- **M3.** An inline case pins response order and duplicate removal across several bindings.
- **M4.** Damaged Wikidata cache entries are deleted and refetched.
- **N1, N2.** The myanimelist record now carries a Hangul synonym so the source check means something; criterion 1 compares both sides with `rstrip`.
- **Scope.** The unrelated BACKLOG and portfolio edits went into their own commit (`7776e5e`) before the spec commit.
