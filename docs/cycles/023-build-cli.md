# 023 build-cli (C-011, part 2 of C-007)

## Goal
Make the AnimeThemes crawl notice source changes (total check, last-page freshness, `refresh_all`), wire every source into one build command with a lock file and a logged report, and close the cycle 022 review items, so the lead can run the first real build.

## Interface changes

### `data/songrush_data/animethemes.py`
`crawl(client, cache, *, refresh_all=False, log=print) -> CrawlResult` (same result type).
- **refresh_all:** every page is fetched with `refresh=True` (no cache reads); the resume-shift check and the freshness step are skipped, since every page is fresh.
- **Total check:** the crawl remembers `paginatorInfo.total` of the first page it reads in a pass. If a later page in the same pass reports a different `total`, it logs `crawl: total changed on page <n> (<old> -> <new>); cleared <k> entries, restarting`, clears the page prefix and restarts.
- **Freshness:** when the pass ends and the last page was read from the cache (not fetched in this pass), it refetches that page once with `refresh=True`. If the fresh page's anime ids, `hasMorePages` or `total` differ from the cached one, it logs `crawl: last page <n> changed since it was cached; cleared <k> entries, restarting`, clears the prefix and restarts. If it is unchanged, the crawl result uses it as is.
- **Restart cap:** resume-shift, total-change and freshness restarts together happen at most twice in one `crawl` call; a third would raise `ValueError` naming the reason. (The resume-shift restart already existed and was unbounded.)
- Everything else (validation, duplicate check, order warning, summary line) is unchanged.

### `data/songrush_data/catalog.py` (cycle 022 review)
- `report["drawSetNoReveal"]`: the number of draw-set anime whose `reveal` is `None` (a separate key; `counts` keeps its six fields).
- `koreanGaps` lists each AniList id once: when several draw-set anime share an AniList id, only the first in the existing order (popularity descending, then anime id) is listed.

### `data/songrush_data/build.py` (new)
- `run(*, transport, clock, cache_root, out_dir, refresh_all=False, anilist_batch=50, aod_size=aod.AOD_SIZE, aod_sha256=aod.AOD_SHA256, log=print) -> dict`
  1. **Lock:** creates `out_dir/.build.lock` with `os.open(..., O_CREAT | O_EXCL | O_WRONLY)` and writes the process id. If it exists, raises `BuildLocked` (a `RuntimeError`) whose message names the lock path and says to delete it only if no build is running; nothing else is touched. The lock is removed in a `finally`.
  2. **Clients:** one `PoliteClient(transport, clock, ...)` per source with `timeout_s=60.0` and gaps AnimeThemes 3.0 s, AniList 2.0 s, Wikidata 3.0 s, aod 3.0 s (CLAUDE.md external source rules). One `FileCache(cache_root)`.
  3. **refresh_all:** `cache.clear("al-")` and `cache.clear("wd-")` before fetching, and the crawl runs with `refresh_all=True`. The pinned aod file is not touched (its hash check already covers damage).
  4. **Sources:** `animethemes.crawl` → `select_themes` per item → the distinct non-null `anilistId`s, sorted → `anilist.fetch_media(..., batch_size=anilist_batch)` → `wikidata.fetch_labels` → `aod.load_korean(client, cache_root / "aod" / "anime-offline-database-minified.json", expected_size=aod_size, expected_sha256=aod_sha256)`.
  5. **Assemble and write:** `catalog.build_catalog(...)`, then `write_catalog(out_dir / "catalog.json", ...)` and `write_gaps(out_dir / "korean_gaps.json", report)`.
  6. **Log** (one line each): `build counts <json of counts>`, `build catalog bytes=<n>`, `build englishFromSynonyms catalog=<n> drawSet=<m>`, `build drawSetNoReveal=<n>`, `build unplayable=<count>`, `build koreanGaps=<count>`, and one `build group <id> size=<n> titles=<json list>` line per entry of `largestGroups`.
  7. Returns the report dict with `catalogBytes` added.
- `main(argv=None) -> int`: `python -m songrush_data.build [--refresh-all] [--cache DIR] [--out DIR] [--anilist-batch N]`. Defaults: cache `data/cache`, out `data` (both relative to the `data/` folder, resolved from the module location, so the command works from any working directory). It uses `UrllibTransport()` and `SystemClock()`. A positional argument is an error (CLAUDE.md pitfall about wrappers swallowing flags). Exit code 0 on success, 2 when `BuildLocked`, 1 on any other exception (after logging it).
- `if __name__ == "__main__": sys.exit(main())`.

## Acceptance criteria (offline; fake transport and clock; temp folders)
1. **Rerun freshness:** after a full crawl of three synthetic pages, a second crawl makes exactly one request, for the last page, and returns the same items. (This replaces cycle 017's `test_crawl_rerun_fully_cached_makes_no_calls`, which pinned zero requests; the backlog item changes that behaviour.)
2. **Last page changed:** if the refetched last page has an extra anime id, the crawl logs `changed since it was cached`, restarts once, and returns the fresh items.
3. **Total changed mid-pass:** pages whose `total` differs between page 1 and page 2 make the crawl log `total changed`, clear the prefix, and restart; a source that keeps changing raises `ValueError` after two restarts, with no more than 3 passes of requests.
4. **refresh_all:** with every page cached, `crawl(..., refresh_all=True)` requests every page once, in order, and makes no extra freshness request.
5. Every other existing crawl test still passes (resume refetch, resume shift, order warning, duplicate, summary).
6. **Catalog review items:** `report["drawSetNoReveal"]` counts a playable draw-set anime with `reveal` `None` (an anime with no media joined by a `COMPILATION` edge); two AnimeThemes anime sharing AniList id 10, both in the draw set without Korean, give one `koreanGaps` entry with the smaller anime id; an existing `catalog.json` is left byte-identical when `write_catalog` raises for the size cap.
7. **End-to-end:** a fake transport routes by URL: AnimeThemes (two synthetic pages built from `gql_page1.json` items), AniList (normalized-shape answers for the requested ids, built in the test), Wikidata (one `ko` label for one id, empty bindings otherwise) and aod (a small document whose size and sha256 are passed to `run`). `build.run` writes `catalog.json` and `korean_gaps.json`; a second `run` with the same caches produces byte-identical files; the second run's only request is the AnimeThemes last-page freshness check.
8. **End-to-end draw set:** in that run, `drawSet` is ordered by the test's popularities and contains no two themes with the same `audio.basename`, and the Korean-gap file lists the draw-set AniList ids that have no Korean string (computed in the test from its inputs).
9. **Lock:** with `out_dir/.build.lock` present, `run` raises `BuildLocked`, makes no request, and leaves existing output files and the lock unchanged; after a successful run no lock file remains; after a run that raises inside a source, the lock is removed.
10. **refresh_all end-to-end:** `run(refresh_all=True)` on warm caches clears `al-` and `wd-` entries (the fake sees the AniList and Wikidata requests again) and requests every AnimeThemes page.
11. **CLI:** `main(["unexpected"])` returns a non-zero code without running; `main(["--refresh-all", "--cache", c, "--out", o])` with `build.run` monkeypatched records the parsed arguments (refresh_all true, the given paths); `BuildLocked` from the patched `run` gives exit code 2.
12. **Logging:** the run logs one `build counts` line, one `build catalog bytes=` line, and one `build group` line per largest group.
13. `.venv\Scripts\python.exe -m pytest` exits 0 from `data/`.

## Test list
- `data/tests/test_animethemes.py`: criterion 1 replaces `test_crawl_rerun_fully_cached_makes_no_calls`; criteria 2-4 added. No other existing test changes.
- `data/tests/test_catalog.py`: criterion 6 added. No existing test changes.
- `data/tests/test_build.py` (new): criteria 7-12.

## File ownership
- Test writer: the three test files above.
- Implementer: `data/songrush_data/animethemes.py`, `data/songrush_data/catalog.py`, `data/songrush_data/build.py`.

## After the cycle: first real build (lead)
`.venv\Scripts\python.exe -m songrush_data.build` from `data/`, with live sources under the CLAUDE.md rules. If AniList rejects a 50-id batch for query complexity, rerun with `--anilist-batch 25` (cycle 018). Record the counts, the catalog size, the largest groups and the run time in this file, and commit `data/catalog.json` and `data/korean_gaps.json`.

## Out of scope
AniDB (C-008), the manual supplement (C-009), the Java loader (S-003).
