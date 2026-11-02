# 023 build-cli (C-011, part 2 of C-007)

## Goal
Make the AnimeThemes crawl notice source changes (total check, last-page freshness, `refresh_all`), wire every source into one build command with a lock file and a logged report, and close the cycle 022 review items, so the lead can run the first real build.

## Interface changes

### `data/songrush_data/animethemes.py`
`crawl(client, cache, *, refresh_all=False, log=print) -> CrawlResult` (same result type).
- **refresh_all:** every page is fetched with `refresh=True` (no cache reads); the resume-shift check and the freshness step are skipped, since every page is fresh.
- **Total check:** the crawl remembers `paginatorInfo.total` of the first page it reads in a pass. If a later page in the same pass reports a different `total`, it logs `crawl: total changed on page <n> (<old> -> <new>); cleared <k> entries, restarting`, clears the page prefix and restarts.
- **Freshness:** when the pass ends and the last page was read from the cache (not fetched in this pass), it refetches that page once with `refresh=True`.
  - **Unchanged** (same ids, `hasMorePages` and `total`): the crawl result uses it as is.
  - **Appended only** (the fresh ids start with the cached ids, only new ids follow, and `total` grew by exactly the number of new ids): the fresh page replaces the cached entry, its new items are added, and if `hasMorePages` is now true the crawl continues with the next pages as usual. It logs `crawl: last page <n> gained <k> anime`. No cache is cleared, so a rebuild after AnimeThemes adds an anime does not refetch all pages (spec review M4; CLAUDE.md: cache so a rebuild does not refetch).
  - **Any other change:** it logs `crawl: last page <n> changed since it was cached; cleared <k> entries, restarting`, clears the prefix and restarts.
- **Restart cap:** resume-shift, total-change and freshness restarts together happen at most twice in one `crawl` call; a third would raise `ValueError` naming the reason. (The resume-shift restart already existed and was unbounded.)
- Everything else (validation, duplicate check, order warning, summary line) is unchanged.

### `data/songrush_data/catalog.py` (cycle 022 review)
- `report["drawSetNoReveal"]`: the number of draw-set anime whose `reveal` is `None` (a separate key; `counts` keeps its six fields).
- `koreanGaps` lists each AniList id once: when several draw-set anime share an AniList id, only the first in the existing order (popularity descending, then anime id) is listed.

### `data/songrush_data/build.py` (new)
- `run(*, transport, clock, cache_root, out_dir, refresh_all=False, anilist_batch=50, aod_size=aod.AOD_SIZE, aod_sha256=aod.AOD_SHA256, log=print) -> dict`
  0. Creates `out_dir` if it does not exist.
  1. **Lock:** creates `out_dir/.build.lock` with `os.open(..., O_CREAT | O_EXCL | O_WRONLY)` and writes the process id. If it exists, raises `BuildLocked` (a `RuntimeError`) whose message names the lock path and says to delete it only if no build is running; nothing else is touched. The lock is removed in a `finally`.
  2. **Clients:** one `PoliteClient(transport, clock, ...)` per source with gaps AnimeThemes 3.0 s, AniList 2.0 s, Wikidata 3.0 s, aod 3.0 s, and timeouts AnimeThemes 120 s (a page takes 8-30 s; cycle 017's live-use line) and 60 s for the others (CLAUDE.md external source rules). One `FileCache(cache_root)`.
  3. **refresh_all:** `cache.clear("al-")` and `cache.clear("wd-")` before fetching, and the crawl runs with `refresh_all=True`. The pinned aod file is not touched (its hash check already covers damage).
  4. **Sources:** `animethemes.crawl` → `select_themes` per item → the distinct non-null `anilistId`s, sorted → `anilist.fetch_media(..., batch_size=anilist_batch)` → `wikidata.fetch_labels` → `aod.load_korean(client, cache_root / "aod" / "anime-offline-database-minified.json", expected_size=aod_size, expected_sha256=aod_sha256)`.
  5. **Assemble and write:** `catalog.build_catalog(...)`, then `write_catalog(out_dir / "catalog.json", ...)` and `write_gaps(out_dir / "korean_gaps.json", report)`.
  6. **Log** (one line each): `build counts <json of counts>`, `build catalog bytes=<n>`, `build englishFromSynonyms catalog=<n> drawSet=<m>`, `build drawSetNoReveal=<n>`, `build unplayable=<count>`, `build koreanGaps=<count>`, and one `build group <id> size=<n> titles=<json list>` line per entry of `largestGroups`, with the titles list dumped with `ensure_ascii=False` so Korean and Japanese are readable.
  7. Returns the report dict with `catalogBytes` added.
- `main(argv=None) -> int`: `python -m songrush_data.build [--refresh-all] [--cache DIR] [--out DIR] [--anilist-batch N]`. Defaults: cache `data/cache`, out `data` (both relative to the `data/` folder, resolved from the module location, so the command works from any working directory). It uses `UrllibTransport()` and `SystemClock()`.
  - **Console encoding:** before anything else it calls `sys.stdout.reconfigure(encoding="utf-8", errors="backslashreplace")` and the same for `sys.stderr` (CLAUDE.md environment pitfall: the Windows console and pipes are cp949, which cannot encode accented romaji or Korean). Without this, a long real build would crash at its first non-ASCII log line (spec review H1).
  - **Exit codes:** 0 on success; 2 for a usage error (a positional argument or an unknown flag; `main` catches argparse's `SystemExit` and returns 2 rather than raising; CLAUDE.md pitfall about wrappers swallowing flags); 3 when `BuildLocked`; 1 on any other exception, after logging it (the log line itself must survive non-ASCII text).
- `if __name__ == "__main__": sys.exit(main())`.

## Acceptance criteria (offline; fake transport and clock; temp folders)
1. **Rerun freshness:** after a full crawl of three synthetic pages, a second crawl makes exactly one request, for the last page, and returns the same items. (This replaces cycle 017's `test_crawl_rerun_fully_cached_makes_no_calls`, which pinned zero requests; the backlog item changes that behaviour.)
2. **Last page grew or changed:**
   - If the refetched last page has one new anime id appended and `total` grew by one, the crawl logs `gained 1 anime`, clears nothing, makes no other request, and returns the old items plus the new one.
   - If it gained ids and now has `hasMorePages` true, the crawl also fetches the next page.
   - If the refetched last page lost or reordered ids, the crawl logs `changed since it was cached`, restarts once, and returns the fresh items. Every fresh page carries the same new `total`, so the restarted pass is consistent (spec review N5).
3. **Total changed mid-pass:** pages whose `total` differs between page 1 and page 2 make the crawl log `total changed`, clear the prefix, and restart; a source that keeps changing raises `ValueError` after two restarts, with no more than 3 passes of requests.
4. **refresh_all:** with every page cached, `crawl(..., refresh_all=True)` requests every page once, in order, and makes no extra freshness request.
5. Every other existing crawl test still passes (resume refetch, resume shift, order warning, duplicate, summary).
6. **Catalog review items:** `report["drawSetNoReveal"]` counts a playable draw-set anime with `reveal` `None` (an anime with no media joined by a `COMPILATION` edge); two AnimeThemes anime sharing AniList id 10, both in the draw set without Korean, give one `koreanGaps` entry with the smaller anime id; an existing `catalog.json` is left byte-identical when `write_catalog` raises for the size cap.
7. **End-to-end:** a fake transport routes by URL: AnimeThemes (two synthetic pages built from `gql_page1.json` items), AniList (raw GraphQL answers `{"data":{"Page":{"media":[...]}}}` whose entries carry `id`, `popularity`, `title`, `synonyms` and `relations.edges`, for the requested ids, built in the test), Wikidata (one `ko` label for one id, empty bindings otherwise) and aod (a small document whose size and sha256 are passed to `run`). `build.run` writes `catalog.json` and `korean_gaps.json`; a second `run` with the same caches produces byte-identical files; the second run's only request is the AnimeThemes last-page freshness check.
8. **End-to-end draw set:** in that run, `drawSet` is ordered by the test's popularities and contains no two themes with the same `audio.basename`, and the Korean-gap file lists the draw-set AniList ids that have no Korean string (computed in the test from its inputs).
9. **Lock:** with `out_dir/.build.lock` present, `run` raises `BuildLocked`, makes no request, and leaves existing output files and the lock unchanged; after a successful run no lock file remains; after a run that raises inside a source, the lock is removed.
10. **refresh_all end-to-end:** `run(refresh_all=True)` on warm caches clears `al-` and `wd-` entries (the fake sees the AniList and Wikidata requests again) and requests every AnimeThemes page.
11. **CLI:** `main(["unexpected"])` and `main(["--nope"])` return 2 (no `SystemExit` escapes) without calling `run`; `main(["--refresh-all", "--cache", c, "--out", o, "--anilist-batch", "25"])` with `build.run` monkeypatched records the parsed arguments; `BuildLocked` from the patched `run` gives 3; any other exception gives 1.
11b. **Console encoding:** with `sys.stdout` and `sys.stderr` replaced by `io.TextIOWrapper(io.BytesIO(), encoding="cp949")` and a patched `run` that logs an accented Latin string and a Korean string (both built with `chr` in the test), `main([...])` returns 0 and the captured bytes decode as UTF-8 containing both strings; a patched `run` that raises `ValueError` with a non-ASCII message returns 1 without a `UnicodeEncodeError`.
11c. **Output folder:** `run` with an `out_dir` that does not exist creates it and succeeds.
12. **Logging:** the run logs one `build counts` line, one `build catalog bytes=` line, and one `build group` line per largest group.
13. `.venv\Scripts\python.exe -m pytest` exits 0 from `data/`.

## Test list
- `data/tests/test_animethemes.py`: criterion 1 replaces `test_crawl_rerun_fully_cached_makes_no_calls`; criteria 2-4 added. No other existing test changes.
- `data/tests/test_catalog.py`: criterion 6 added. No existing test changes.
- `data/tests/test_build.py` (new): criteria 7-12, 11b, 11c.

## File ownership
- Test writer: the three test files above.
- Implementer: `data/songrush_data/animethemes.py`, `data/songrush_data/catalog.py`, `data/songrush_data/build.py`, and `.gitignore` (add `data/.build.lock` and `data/~tmp-*`, which a killed build can leave next to the committed catalog; spec review N1).

## After the cycle: first real build (lead)
`.venv\Scripts\python.exe -m songrush_data.build` from `data/`, with live sources under the CLAUDE.md rules. It takes about 15-40 minutes (51 AnimeThemes pages at 3 s plus 8-30 s each, about 100 AniList batches, about 25 Wikidata POSTs; aod is already cached), longer than a foreground tool call, so the lead runs it in the background with its output in a log file and waits in-turn. If the process is killed, `finally` does not run: the crawl resumes from cached pages, and the lead deletes a stale `data/.build.lock` by hand after checking that no build process is running. The manual check of pages 1 and 2 that cycle 017 planned is covered by `validate_page` (currentPage, perPage) and the duplicate check, which stop the crawl on the first bad page. If AniList rejects a 50-id batch for query complexity, rerun with `--anilist-batch 25` (cycle 018). Record the counts, the catalog size, the largest groups and the run time in this file, and commit `data/catalog.json` and `data/korean_gaps.json`.

## Review changes (spec round 1)
- **H1.** A real build would crash at its first non-ASCII log line, because the Windows console and pipes use cp949 (the critic's probe: printing an accented title to a file raised `UnicodeEncodeError`). `main` now reconfigures stdout and stderr to UTF-8, titles are logged with `ensure_ascii=False`, and criterion 11b pins it with a cp949 stream.
- **M1.** argparse raises `SystemExit(2)` on a bad argument, which clashed with the planned exit code 2 for a held lock. `main` returns 2 for usage errors and 3 for `BuildLocked`.
- **M2.** Criterion 7 asked for normalized AniList answers; the transport must return the raw `data.Page.media` shape with `id`, or `_validate_batch` rejects it.
- **M3.** The real build outlasts a foreground tool call; the post-cycle step runs it in the background and says how to recover from a kill.
- **M4.** Clearing the whole AnimeThemes cache whenever one anime is added would refetch every page on most rebuilds. An append-only change to the last page now keeps the cache.
- **Nits.** `.gitignore` covers the lock and temp files; `out_dir` is created; AnimeThemes keeps cycle 017's 120 s timeout; the cycle 017 manual page check is superseded by validation; criterion 2 states that every fresh page carries the new total.
- The critic confirmed that only `test_crawl_rerun_fully_cached_makes_no_calls` changes among the existing crawl tests, that `O_EXCL` works on Windows, and that request volume stays within the CLAUDE.md limits.

## Out of scope
AniDB (C-008), the manual supplement (C-009), the Java loader (S-003).

## Result
- Tests `a4940ba`; fixture correction `20c0ee7` (the `streams` fixture swapped stdout in setup, and pytest's capture undid it before the test body, so no implementation could pass three CLI tests; the implementer reported it with a `-s` run as evidence, the lead unlocked only `test_build.py`, and the test writer moved the swap into the test body without changing any assertion). Implementation `521f82a` (attempt 1).
- Red phase (verifier): 11 failed and 17 errors, all in new or replaced tests (old crawl behaviour, missing `refresh_all`, missing `drawSetNoReveal`, missing `build` module).
- Verifier: full suite exit 0, `514 passed in 14.87s`; `git diff 20c0ee7 HEAD -- data/tests/` empty; `python -m songrush_data.build stray` and `--nope` exit 2, `--help` exits 0; `git check-ignore` matches `data/.build.lock` and `data/~tmp-*`. Criteria 1-13, 11b, 11c confirmed.

## Code review (critic)
No Blocker or High. External source rules hold in `build.run` (gaps 3/2/3/3 s, AnimeThemes timeout 120 s, others 60 s, AniList only receives crawl ids).
- **F1, F2 (Medium, queued as C-012).** With real 100-item pages, a new anime that spills onto a new page, or a total change during a resumed or first crawl, still clears the whole AnimeThemes cache and restarts; the has-more continuation branch cannot be reached with consistent pages, and its test uses a page shape the source never sends.
- **F3 (Medium, follow-up).** `main` does not enable line buffering, so a background build's log file stays empty until the buffer fills; the first real build is run with `python -u`.
- **F4 (Medium, follow-up).** No test pins the per-source gaps and timeouts.
