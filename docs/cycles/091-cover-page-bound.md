# 091: bound the cover page loop (C-021)

From the cycle 090 review (M1): `covers._all_links` (`data/songrush_data/covers.py:142-152`) follows `hasMorePages` with no upper bound, so a misbehaving response would make an unattended run call AnimeThemes forever (every 3 s) and fill `data/cache` with `ci-` pages. This must land before I-012 runs the tool live.

## Goal

A cover run never requests more than page 1's `lastPage` + 5 pages, and a page that claims more pages but has no data stops the run.

## Acceptance criteria

1. `covers.MAX_PAGE_SLACK == 5`.
2. The run reads `paginatorInfo.lastPage` (a positive integer, not a bool; otherwise the page is invalid, raises `ValueError` and is not cached) from page 1; later pages' `lastPage` never changes the bound. If the loop would request page `lastPage(page 1) + MAX_PAGE_SLACK + 1`, it raises `ValueError` instead, without sending that request. With a fake transport whose every page has at least one anime, the right `currentPage` and `hasMorePages: true`, and whose page 1 says `lastPage: 2`, exactly 7 page requests are sent and the run raises `ValueError` (from `run`; `main` returns 1).
3. A page with `hasMorePages: true` and an empty `data` list raises `ValueError` (fetched pages are not cached, as for other bad pages).
4. `parse_page` keeps its 3-tuple return; existing tests in `data/tests/test_covers.py` keep passing unchanged.
5. `.venv\Scripts\python.exe -I -m pytest -q` from `data/` exits 0.

## Test list

- `data/tests/test_covers_bound.py` (new): criteria 1-3, reusing the fake transport style of `test_covers.py` (copy the small helpers it needs; do not import from another test module unless `test_covers.py` already exposes them in a way other test files use).

## File ownership

- Test writer: `data/tests/test_covers_bound.py`.
- Implementer: `data/songrush_data/covers.py`.

## Out of scope

Review nits N2 and N3 of cycle 090 (in Follow-ups).
