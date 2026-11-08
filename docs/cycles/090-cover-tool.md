# 090: cover tool (C-019)

From S-007, design `docs/design/088-songs-3000-covers.md` (options E2 and F3), decisions D-103 and D-104.

## Goal

A data tool `python -m songrush_data.covers` downloads the AnimeThemes LARGE_COVER image of every draw-set anime into a gitignored `data/cover-cache/`, politely and resumably, and records each one under a random id that the server will later use as the cover's public name.

## Behaviour

- **Image links.** A lean paged GraphQL query to `https://graphql.animethemes.moe/`:
  `query{ animePagination(first:100,page:{page}){ paginatorInfo{ currentPage hasMorePages lastPage total } data{ id images{ nodes{ facet link } } } } }`
  Each page body is cached in the existing `FileCache` (`data/cache`) under the key `ci-<first 16 hex of sha256(query template)>-p<N>.json` (not `at-`), so the main crawl cache is untouched, a changed query never reuses old pages, and a rerun makes no request for cached pages. A cached or fetched page whose `paginatorInfo.currentPage` is not N is an error. `--refresh-pages` deletes the `ci-` pages first and fetches them again (for new draw-set anime or replaced cover links). Pages are fetched from 1 until `hasMorePages` is false. Real response shape (live probe, 2026-11-08, `first:1`):
  `{"data":{"animePagination":{"data":[{"slug":"hackliminality","images":{"nodes":[{"facet":"LARGE_COVER","link":"https:\/\/pub-92474f7785774e91a790e086dfa6b2ef.r2.dev\/anime\/large-cover\/bVkooFKrLcBFIosFL9u1adHcVgPuWNTfWvnhdADn.jpg"},{"facet":"SMALL_COVER","link":"https:\/\/pub-92474f7785774e91a790e086dfa6b2ef.r2.dev\/anime\/small-cover\/4uUHaLb3kacptEzXbzAOiIFcZMEGWMnmOzCPoREk.avif"}]}}]}}}`
  A GraphQL `errors` body or a page without `data.animePagination` is an error, not a cache entry.
- **Which anime.** The anime of the draw set in `data/catalog.json` (each `drawSet` theme's `animeId`). Only those are downloaded. An anime listed on the pages but with no `LARGE_COVER` link (or a link that is not `https`) is counted as `noCover`; a draw-set anime missing from every page is counted as `notListed`. Both are skipped. When `notListed > 0` the run logs a hint to rerun with `--refresh-pages`.
- **Download.** One GET per cover through `PoliteClient` (the shared `SongRushBot` User-Agent, gap 3 s, timeout 60 s, its 429 `Retry-After` handling). A body is accepted only when it is non-empty, at most 2,000,000 bytes, and starts with the JPEG signature `FF D8 FF`. It is written to `data/cover-cache/<id>.jpg` through a temp file and a replace.
- **Ids.** `index.json` in the cover cache: `{"version": 1, "covers": {"<animeId>": {"id": "<token_urlsafe(16)>", "file": "<id>.jpg", "size": <bytes>}}}`. An anime keeps its id across runs; a new anime gets a fresh `secrets.token_urlsafe(16)` that is unique within the index. The index is written atomically after every successful download, so a killed run resumes.
- **Lock.** The run holds `<covers>/.covers.lock` (created with `O_CREAT|O_EXCL`, as audio's `.prefetch.lock`, and removed at the end); if it already exists the run sends no request and exits 3.
- **Broken index.** An `index.json` that exists but is not a JSON object with `version: 1` and a `covers` object stops the run before any request with a non-zero exit; it is never rebuilt, because the random ids cannot be recovered from file names and the server's map depends on them.
- **Resume.** An anime whose index entry exists and whose file exists with the recorded size is skipped with no request. A file with the wrong size is downloaded again under the same id.
- **Failures.** A failed download (HTTP error, transport error, bad body) is counted and the run moves on; 5 consecutive failures stop the run with a non-zero exit. The final log line reports `covers drawSetAnime=<n> cached=<n> downloaded=<n> noCover=<n> notListed=<n> failed=<n>`.
- **CLI.** `python -m songrush_data.covers [--cache DIR] [--covers DIR] [--catalog PATH] [--refresh-pages]` with defaults `data/cache`, `data/cover-cache`, `data/catalog.json`; a bare positional argument is rejected.
- **Git.** `.gitignore` gains `data/cover-cache/`.

## Acceptance criteria

1. On a saved page fixture, the page parser returns `{animeId: largeCoverLink}` and ignores `SMALL_COVER`; an `errors` body raises.
2. Pages are fetched until `hasMorePages` is false and cached under `ci-<digest>-p<N>.json`; a second run with the same cache sends no page request; a page whose `currentPage` is wrong raises; `--refresh-pages` refetches every page.
3. Only draw-set anime are downloaded; a listed anime without a LARGE_COVER (or with a non-https link) counts as `noCover`, an unlisted draw-set anime counts as `notListed`, and neither sends a request.
4. A downloaded cover lands at `<covers>/<id>.jpg` with `index.json` holding `id`, `file`, `size`; ids are 22-character url-safe strings and unique.
5. A rerun skips covers whose file has the recorded size (no request) and keeps every id; a file with the wrong size is fetched again under the same id.
6. A body that is empty, larger than 2,000,000 bytes or not starting with `FF D8 FF` is a failure and leaves no file; 5 consecutive failures stop the run with a non-zero exit; a success resets the count.
7. A 429 with `Retry-After` is waited out through `PoliteClient` (fake clock).
8. The final log line has the counts above; the CLI rejects a bare positional argument.
8a. With `.covers.lock` present the run sends no request and exits 3, and the lock is removed after a normal run.
8b. A malformed `index.json` (not an object, wrong version, `covers` not an object) stops the run with a non-zero exit before any request and leaves the file untouched.
9. `git check-ignore data/cover-cache/x.jpg` succeeds.
10. `.venv\Scripts\python.exe -I -m pytest -q` from `data/` exits 0. Tests make no network request (fake transport, saved fixtures).

## Test list

- `data/tests/test_covers.py`: criteria 1-8 with a fake transport and fake clock in the style of `test_audio.py` and `test_s3upload.py`.
- `data/tests/fixtures/covers/page-1.json` (and `page-2.json` if needed): small hand-made pages in the real shape above, with ids, facets and links chosen by the test.
- `data/tests/test_covers_gitignore.py`: criterion 9 as `test_audio.py:579-589` does (`git check-ignore -q --no-index`, skipped when git is absent), for `data/cover-cache/x.jpg` and `data/cover-cache/index.json`.

## File ownership

- Test writer: `data/tests/test_covers.py`, `data/tests/test_covers_gitignore.py`, `data/tests/fixtures/covers/`.
- Implementer: `data/songrush_data/covers.py`, `.gitignore`.

## Out of scope

Running the tool for real (I-012), S3 upload (C-020), serving covers (B-020), showing them (F-022).
