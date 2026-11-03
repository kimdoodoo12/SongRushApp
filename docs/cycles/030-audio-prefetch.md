# 030 audio-prefetch (C-015)

## Goal
Download the draw set's audio from AnimeThemes into a local cache, politely and resumably, verify every file, and record each file's duration, so the backend can serve and time rounds (design 029, D-019, D-044, D-054, D-056).

## Interface: `data/songrush_data/audio.py`
- **`ogg_info(data: bytes) -> dict`** (pure): parses Ogg pages (capture pattern `OggS`, version 0, header fields, segment table) and returns `{"opus": bool, "preSkip": int, "lastGranule": int, "durationMs": int}`. `opus` is true when the first packet starts with `OpusHead`; `preSkip` is the little-endian 16-bit field at offset 10 of `OpusHead`; `lastGranule` is the granule position of the last page; `durationMs = max(0, lastGranule - preSkip) * 1000 // 48000`. Raises `ValueError` for data that is not a sequence of Ogg pages. CRCs are not checked here (the size check covers corruption).
- **`prefetch(client, catalog, cache_dir, *, log=print) -> dict`**
  - `catalog` is the parsed `data/catalog.json`; the work list is the draw set (`drawSet` theme ids, in order) joined with `themes[].audio`.
  - **Lock:** `cache_dir/.prefetch.lock` created with `O_CREAT | O_EXCL`; if present, raises `PrefetchLocked` whose message names the path and says to delete it only if no prefetch is running. Removed in `finally`.
  - **Index:** `cache_dir/index.json` maps theme id (string) to `{"basename", "size", "durationMs"}`. A theme is complete when it is in the index and `cache_dir/<basename>` exists with that size; complete themes are skipped with no request, so a rerun resumes.
  - **Download:** for each incomplete theme, `client.request("GET", audio.url)` (never `HEAD`, which AnimeThemes answers with 403). The body must have exactly the catalog's `audio.size` bytes and `ogg_info(body)["opus"]` must be true; then it is written to `<basename>.part` (overwriting any leftover), renamed to `<basename>`, and the index entry is added. The index is rewritten atomically (`FileCache.put`) after every file, so an interrupted run loses at most the file in flight.
  - **Failures:** an `HttpError`, `TransportError`, size mismatch, non-Opus body or unparsable body is logged as `audio failed <themeId>: <reason>` and the run continues with the next theme; no final file or index entry is written for it. After **5 consecutive** failures the run stops with `audio stopped after 5 consecutive failures`. A local `OSError` while writing stops the run at once, after removing the `.part`.
  - **Basenames** must match `^[A-Za-z0-9._-]+\.ogg$`; others fail that theme without a request.
  - **Result and log:** returns and logs `audio done=<n> skipped=<n> failed=<n> bytes=<n>` plus the list of failed theme ids.
- **`main(argv=None) -> int`**: `python -m songrush_data.audio [--cache DIR]`, default cache `data/audio-cache` resolved from the module location; reads `data/catalog.json`; `PoliteClient(UrllibTransport(), SystemClock(), gap_s=3.0, timeout_s=120.0)`; reconfigures stdout and stderr to UTF-8 with line buffering; exit 0 when the run finished (even with some failures), 3 when locked, 2 for usage errors, 1 when it stopped early or raised.
- **`.gitignore`:** `data/audio-cache/` is ignored as a folder (so `.part` files are covered too).

## Acceptance criteria (offline; fake transport; the test builds tiny valid Ogg Opus files in code: an `OpusHead` page, an `OpusTags` page and one or two audio pages with chosen granule positions)
1. **`ogg_info`:** on a built file with pre-skip 312 and last granule 48,312 + 4,320,000, `durationMs` is 90,000 and `opus` is true; a file whose first packet is `\x01vorbis` gives `opus` false; bytes that do not start with `OggS` raise `ValueError`.
2. **Download and index:** three draw-set themes served by the fake transport give three files with the exact bytes, an index with their basenames, sizes and durations, `done=3`, and requests in draw-set order, all `GET`, with the SongRush User-Agent and at least 3 s apart on the fake clock.
3. **Resume:** a second run makes no request and reports `skipped=3`; deleting one cached file makes the next run request only that theme.
4. **Verification:** a body one byte short, a Vorbis body and a body that is not Ogg each give `failed` for that theme, leave no final file and no `.part`, and do not stop the run.
5. **Errors:** a 404 for one theme is reported and the next theme is still fetched; five consecutive failures stop the run with the stop message and exit code 1 from `main` (with `prefetch` monkeypatched or a fake client); a write error (a `cache_dir` file write that raises `OSError`, injected by monkeypatching) stops at once and leaves no `.part`.
6. **Lock:** with `.prefetch.lock` present, `prefetch` raises `PrefetchLocked` and makes no request; after a normal run no lock remains.
7. **Safety:** a basename with `/` or `..` fails without a request; nothing is written outside `cache_dir`; `git check-ignore data/audio-cache/x.ogg.part` matches.
8. **CLI:** `main(["stray"])` returns 2; `main` with `prefetch` monkeypatched to raise `PrefetchLocked` returns 3.
9. `.venv\Scripts\python.exe -m pytest` exits 0 from `data/`.

## Test list
- `data/tests/test_audio.py`: criteria 1-8.

## File ownership
- Test writer: `data/tests/test_audio.py`.
- Implementer: `data/songrush_data/audio.py`, `.gitignore`.

## After the cycle (lead, D-054)
Start `python -u -m songrush_data.audio` from `data/` in the background with its log in `data/cache/audio-prefetch.log`; record the summary, the total size and the duration distribution in this file.

## Out of scope
Size padding (C-016), the backend (B-002, B-003), lobby fetch of uncached songs (M-5).
