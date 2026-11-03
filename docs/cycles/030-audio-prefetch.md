# 030 audio-prefetch (C-015)

## Goal
Download the draw set's audio from AnimeThemes into a local cache, politely and resumably, verify every file, and record each file's duration, so the backend can serve and time rounds (design 029, D-019, D-044, D-054, D-056).

## Interface: `data/songrush_data/audio.py`
- **`ogg_info(data: bytes) -> dict`** (pure): parses Ogg pages (capture pattern `OggS`, version 0, header fields, segment table) and returns `{"opus": bool, "preSkip": int, "lastGranule": int, "durationMs": int}`. `opus` is true when the first packet starts with `OpusHead`; `preSkip` is the little-endian 16-bit field at offset 10 of `OpusHead`; `lastGranule` is the granule position (read as an unsigned 64-bit integer) of the last page **of the `OpusHead` stream's serial number** whose granule is not `0xFFFFFFFFFFFFFFFF` (pages of other streams and pages with no completed packet are skipped); `durationMs = max(0, lastGranule - preSkip) * 1000 // 48000`. Raises `ValueError` for data that is not a sequence of Ogg pages. CRCs are not checked here (the size check covers corruption). A file with `durationMs <= 0` fails verification.
- **`prefetch(client, catalog, cache_dir, *, log=print) -> dict`**
  - `catalog` is the parsed `data/catalog.json`; the work list is the draw set (`drawSet` theme ids, in order) joined with `themes[].audio`.
  - **Lock:** `cache_dir/.prefetch.lock` created with `O_CREAT | O_EXCL`; if present, raises `PrefetchLocked` whose message names the path and says to delete it only if no prefetch is running. Removed in `finally`.
  - Creates `cache_dir` if needed before taking the lock.
  - **Index:** `cache_dir/index.json` maps theme id (string) to `{"basename", "size", "durationMs"}` and is written with `FileCache(cache_dir).put("index.json", ...)`. Audio files are **not** written through `FileCache` (its keys must be lowercase; basenames are CamelCase).
  - **Complete:** a theme is complete when `cache_dir/<basename>` exists, its size equals the catalog's `audio.size` (or the index's `paddedSize` once C-016 adds it), and `ogg_info` says Opus with a positive duration. A complete theme is skipped with no request; if it is missing from the index (an index lost or written before a kill), its entry is rebuilt from the local file, so losing `index.json` never refetches 5.4 GB (spec review M3, H2).
  - **Download:** for each incomplete theme, `client.request("GET", audio.url)` (never `HEAD`, which AnimeThemes answers with 403). The body must have exactly the catalog's `audio.size` bytes and `ogg_info(body)["opus"]` must be true; then it is written to `<basename>.part` (overwriting any leftover) through the module-level `audio._write_part(path, data)`, moved into place with `os.replace` (which overwrites on Windows, unlike `os.rename`; a `PermissionError` from antivirus scanning is retried as `FileCache` does), and the index entry is added. The index is rewritten atomically (`FileCache.put`) after every file, so an interrupted run loses at most the file in flight.
  - **Failures:** an `HttpError`, `TransportError`, size mismatch, non-Opus body or unparsable body is logged as `audio failed <themeId>: <reason>` and the run continues with the next theme; no final file or index entry is written for it. After **5 consecutive** failures the run stops with `audio stopped after 5 consecutive failures`. A local `OSError` from `_write_part` or `os.replace` stops the run at once, after removing the `.part`. Stopping early raises `PrefetchStopped` (an `Exception`) after the index has been written for the files already done.
  - **Basenames** must match `^[A-Za-z0-9._-]+\.ogg$`; others fail that theme without a request.
  - **Result and log:** returns `{"done": int, "skipped": int, "failed": int, "failedIds": list[int], "bytes": int}` and logs `audio done=<n> skipped=<n> failed=<n> bytes=<n>` plus `audio failed ids: <ids>` when any failed.
- **`main(argv=None) -> int`**: `python -m songrush_data.audio [--cache DIR]`, default cache `data/audio-cache` resolved from the module location; reads `data/catalog.json`; `PoliteClient(UrllibTransport(), SystemClock(), gap_s=3.0, timeout_s=120.0, log=print)` (so retries and back-off waits appear in the log; spec review M1); reconfigures stdout and stderr to UTF-8 with line buffering; exit 0 when the run finished (even with some failures, D-056), 3 for `PrefetchLocked`, 2 for usage errors, 1 for `PrefetchStopped` or any other exception.
- **`.gitignore`:** `data/audio-cache/` is ignored as a folder (so `.part` files are covered too).

## Acceptance criteria (offline; fake transport; the test builds tiny valid Ogg Opus files in code: an `OpusHead` page, an `OpusTags` page and one or two audio pages with chosen granule positions)
1. **`ogg_info`:** on a built file with pre-skip 312 and last granule 4,320,312, `durationMs` is 90,000 and `opus` is true; a trailing page with granule `0xFFFFFFFFFFFFFFFF` is skipped; a page of a second serial number with a larger granule is ignored; a file whose first packet is `\x01vorbis` gives `opus` false; bytes that do not start with `OggS` raise `ValueError`; a file whose last granule equals the pre-skip has `durationMs` 0 and fails verification in `prefetch`.
2. **Download and index:** three draw-set themes served by the fake transport give three files with the exact bytes, an index with their basenames, sizes and durations, `done=3`, and requests in draw-set order, all `GET`, with the SongRush User-Agent and at least 3 s apart on the fake clock.
3. **Resume:** a second run makes no request and reports `skipped=3`; deleting one cached file makes the next run request only that theme; deleting `index.json` (files kept) makes the next run request nothing and rebuild the same index; a final file present with no index entry (killed between the move and the index write) is taken into the index without a request; a leftover `.part` is overwritten.
4. **Verification:** a body one byte short, a Vorbis body and a body that is not Ogg each give `failed` for that theme, leave no final file and no `.part`, and do not stop the run.
5. **Errors:** a 404 for one theme is reported, appears in `failedIds`, and the next theme is still fetched; five consecutive failures raise `PrefetchStopped` with the stop message, and the index holds the themes done before; `audio._write_part` monkeypatched to raise `OSError` stops at once with `PrefetchStopped` and leaves no `.part`; `main` with `prefetch` monkeypatched to raise `PrefetchStopped` returns 1.
6. **Lock:** with `.prefetch.lock` present, `prefetch` raises `PrefetchLocked` and makes no request; after a normal run no lock remains.
7. **Safety:** a basename with `/` or `..` fails without a request; nothing is written outside `cache_dir`; `git check-ignore data/audio-cache/x.ogg.part` matches.
8. **CLI:** `main(["stray"])` returns 2; `main` with `prefetch` monkeypatched to raise `PrefetchLocked` returns 3.
9. `.venv\Scripts\python.exe -m pytest` exits 0 from `data/`.

## Review changes (spec round 1)
- **B1.** Criterion 1's numbers were inconsistent (48,312 + 4,320,000 - 312 gives 91,000 ms, not 90,000); the granule is now 4,320,312.
- **H1.** The stop signal (`PrefetchStopped`), the result keys and the write hook (`audio._write_part`) are fixed in the interface; the impossible "fake client to `main`" path is gone.
- **H2, M3.** `os.replace` instead of a rename that fails on Windows when the target exists; completeness is decided from the local file, and a lost index is rebuilt without refetching.
- **M1.** The client logs retries and back-off.
- **M2.** Granules are read unsigned, only for the `OpusHead` stream, skipping `-1` pages; a zero duration fails verification.
- The critic checked the three real samples (88,841 / 90,081 / 91,981 ms with this rule) and the catalog's 1,500 draw-set entries (no missing audio, no bad basename, no duplicate).

## Test list
- `data/tests/test_audio.py`: criteria 1-8.

## File ownership
- Test writer: `data/tests/test_audio.py`.
- Implementer: `data/songrush_data/audio.py`, `.gitignore`.

## After the cycle (lead, D-054)
Start `python -u -m songrush_data.audio` from `data/` in the background with its log in `data/cache/audio-prefetch.log`; record the summary, the total size and the duration distribution in this file.

## Out of scope
Size padding (C-016), the backend (B-002, B-003), lobby fetch of uncached songs (M-5).

## Result
- Tests `d289496` (locked); implementation `34c4134` (attempt 1).
- Red phase (verifier): 38 errors (missing `songrush_data.audio`) and 2 failures (folder not gitignored).
- Verifier: full suite exit 0, `630 passed in 18.21s`; `git diff d289496 HEAD -- data/tests/` empty; `git check-ignore` matches `data/audio-cache/`; `ogg_info` on the three real samples gives 90,081 / 88,841 / 91,981 ms, all Opus. Criteria 1-9 confirmed.
- The test writer ran a throwaway reference implementation outside the repo to check its tests, which goes beyond its role; the implementer was told to write the module from the spec.

## Code review (critic)
No Blocker or High. Every request goes through one `PoliteClient` (3 s gap, 120 s timeout, User-Agent); repeated 403 or Cloudflare HTML stops after five 3 s-spaced attempts; 5xx costs at most 15 requests before the stop.
- **M1 (follow-up; operational rule meanwhile).** A long `Retry-After` is neither logged nor remembered across processes.
- **Nits (follow-up).** Timestamps in the log, the PID in the stale-lock message, a test pinning the client settings.
