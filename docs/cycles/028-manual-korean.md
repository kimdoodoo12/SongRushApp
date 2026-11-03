# 028 manual-korean (C-009)

## Goal
Let the owner add Korean titles by hand for popular anime that still have none (D-040), in a small committed file that the build merges as Korean titles, so the Korean-gap list shrinks as they fill it in.

## The file: `data/korean_titles_manual.json` (committed, edited by the owner)
- A JSON object. Each key is an AniList id written as decimal digits (the `anilistId` shown in `data/korean_gaps.json`); each value is a list of Korean titles for that anime.
- Keys starting with `_` are comments and are ignored, so the file can carry a short how-to.
- The committed starting file is `{"_how_to": "<one Korean sentence: copy anilistId from korean_gaps.json and list Korean titles>"}` plus one worked example under a `_example` key, which is also ignored.

## Interface: `data/songrush_data/manual.py`
- **`load_manual(path, *, log=print) -> dict[int, list[str]]`**
  - A missing file returns `{}` and logs `manual korean: <path> not found, skipped`.
  - The file must be a UTF-8 JSON object (a UTF-8 BOM is accepted, since Windows editors add one). Otherwise it raises `ValueError` naming the file.
  - For every key not starting with `_`: the key must be decimal digits, and the value must be a list of strings. Each string is stripped and NFC-normalized; empty strings are dropped; every remaining string must contain a Hangul syllable (`titles.has_hangul`). Any violation raises `ValueError` naming the file, the key and the problem. This file is hand-edited, so a typo stops the build loudly instead of being silently ignored.
  - Duplicates inside one list are removed, order kept. An id whose list ends up empty is left out.
  - Logs `manual korean: <n> ids, <m> titles`.
- **`merge_korean(*sources) -> dict[int, list[str]]`** (pure): for each id, concatenates the lists of the sources in the given order, removing duplicates and keeping order.

## Change: `data/songrush_data/build.py`
- `run(..., manual_path=None, ...)`: when `None`, it is `out_dir / "korean_titles_manual.json"`.
- After `aod.load_korean`, the build loads the manual file and passes `merge_korean(manual, aod_korean)` to `build_catalog` (manual titles first, so they are preferred for the reveal title after Wikidata `ko`).
- It logs `manual korean: <k> ids not in the catalog` when manual ids are not among the crawled AniList ids (they are kept in the file but have no effect).
- A `ValueError` from `load_manual` ends the run with exit code 1 through `main`, as other errors do; the lock is removed.

## Acceptance criteria (offline)
1. **Missing file:** `load_manual` returns `{}` and logs `not found`.
2. **Empty and comment-only files:** `{}` and `{"_how_to": "x", "_example": {"1": ["가"]}}` both return `{}`.
3. **Parsing:** `{"20464": ["하이큐!!", " 하이큐!! ", "하이큐!!"], "97940": ["블랙 클로버"]}` returns `{20464: ["하이큐!!"], 97940: ["블랙 클로버"]}`; a file written with a UTF-8 BOM parses the same; an NFD Hangul string (built in the test with `unicodedata.normalize`) comes back in NFC.
4. **Errors name the problem:** a key `"abc"`, a value that is a string instead of a list, a list item that is not a string, a title with no Hangul (`"Haikyuu"`), and invalid JSON each raise `ValueError` whose message contains the file name and, where there is one, the key.
5. **`merge_korean`:** `merge_korean({1: ["가", "나"]}, {1: ["나", "다"], 2: ["라"]})` is `{1: ["가", "나", "다"], 2: ["라"]}`.
6. **Build end to end** (cycle 023's fake transport): with a manual file that gives a Korean title to a draw-set anime that had none, the run's `korean_gaps.json` no longer lists that AniList id, the catalog anime's `ko` slot contains the title, and its reveal is that title; with a manual id that is not in the crawl, the run succeeds and logs `not in the catalog`.
7. **Build with a broken manual file:** `main([...])` with a manual file containing `{"x": ["가"]}` returns 1, writes no catalog, and leaves no lock file.
8. **Committed file:** `data/korean_titles_manual.json` exists, parses with `load_manual` to `{}`, and its `_how_to` value contains a Hangul syllable.
9. `.venv\Scripts\python.exe -m pytest` exits 0 from `data/`.

## Test list
- `data/tests/test_manual.py`: criteria 1-5 and 8.
- `data/tests/test_build.py`: criteria 6 and 7 added; no existing test changes.

## File ownership
- Test writer: `data/tests/test_manual.py`, `data/tests/test_build.py`.
- Implementer: `data/songrush_data/manual.py`, `data/songrush_data/build.py`, `data/korean_titles_manual.json`.

## Out of scope
AniDB (C-008). Filling in the titles (the owner does that). Any change to the reveal or grouping rules.
