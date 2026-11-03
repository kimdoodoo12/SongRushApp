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
  - The file must be a UTF-8 JSON object (a UTF-8 BOM is accepted, since Windows editors add one). Otherwise it raises `ValueError` naming the file. For invalid JSON the message includes the line and column from the parser and a short Korean hint (a comma after the last item, keys in double quotes); for bytes that are not UTF-8 it says, in Korean, to save the file as UTF-8.
  - The same key written twice raises `ValueError` naming the key (spec review M1: `json` would silently keep only the last one, losing titles the owner added earlier).
  - For every key not starting with `_`: the key must be decimal digits, and the value must be a list of strings. Each string is stripped and NFC-normalized; empty strings are dropped; every remaining string must contain a Hangul syllable (`titles.has_hangul`). Any violation raises `ValueError` naming the file, the key and the problem. This file is hand-edited, so a typo stops the build loudly instead of being silently ignored.
  - Duplicates inside one list are removed, order kept. An id whose list ends up empty is left out.
  - Logs `manual korean: <n> ids, <m> titles`.
- **`merge_korean(*sources) -> dict[int, list[str]]`** (pure): for each id, concatenates the lists of the sources in the given order, removing duplicates and keeping order.

## Change: `data/songrush_data/build.py`
- `run(..., manual_path=None, ...)`: when `None`, it is `out_dir / "korean_titles_manual.json"`.
- Right after taking the lock and **before any request**, the build loads and validates the manual file (spec review H2: a typo then fails in under a second instead of after a several-minute crawl, and `--refresh-all` does not clear caches for nothing). After `aod.load_korean` it passes `merge_korean(manual, aod_korean)` to `build_catalog` (manual titles first, so they are preferred for the reveal title after Wikidata `ko`).
- It logs `manual korean: <k> ids not in the catalog` when manual ids are not among the crawled AniList ids (they are kept in the file but have no effect).
- A `ValueError` from `load_manual` ends the run with exit code 1 through `main`, as other errors do; the lock is removed. `main` takes no new flag: the file is `<--out>/korean_titles_manual.json`, which is `data/korean_titles_manual.json` by default.
- Failing the build on a typo, rather than skipping the file with a warning, is the lead's default: the check costs nothing now that it runs first, and a one-line warning would be lost in a long log. The owner can ask for the other behaviour.

## Acceptance criteria (offline)
1. **Missing file:** `load_manual` returns `{}` and logs `not found`.
2. **Empty and comment-only files:** `{}` and `{"_how_to": "x", "_example": {"1": ["가"]}}` both return `{}`.
3. **Parsing:** `{"20464": ["하이큐!!", " 하이큐!! ", "하이큐!!"], "97940": ["블랙 클로버"]}` returns `{20464: ["하이큐!!"], 97940: ["블랙 클로버"]}`; a file written with a UTF-8 BOM parses the same; an NFD Hangul string (built in the test with `unicodedata.normalize`) comes back in NFC.
4. **Errors name the problem:** a key `"abc"`, a value that is a string instead of a list, a list item that is not a string, a title with no Hangul (`"Haikyuu"`), a duplicated key (`'{"1": ["가"], "1": ["나"]}'`), invalid JSON with a trailing comma (`'{"1": ["가"],\r\n}'`), and bytes in cp949 each raise `ValueError` whose message contains the file name and, where there is one, the key; the trailing-comma message contains `line 2`.
5. **`merge_korean`:** `merge_korean({1: ["가", "나"]}, {1: ["나", "다"], 2: ["라"]})` is `{1: ["가", "나", "다"], 2: ["라"]}`.
6. **Build end to end** (cycle 023's fake transport): with a manual file that gives a Korean title to a draw-set anime that had none, the run's `korean_gaps.json` no longer lists that AniList id, the catalog anime's `ko` slot contains the title, and its reveal is that title; with a manual id that is not in the crawl, the run succeeds and logs `not in the catalog`.
7. **Build with a broken manual file fails before any request:** `run(..., manual_path=<file with {"x": ["가"]}>)` with the fake transport raises `ValueError` naming the file and `x`, the fake transport received no request, no catalog was written, and no lock file remains. (Spec review H1: a `main` test would pass without any manual handling, because `main` builds a real transport that the test network guard rejects.)
8. **Committed file:** `data/korean_titles_manual.json` exists, parses with `load_manual` to `{}`, and its `_how_to` value contains a Hangul syllable.
9. `.venv\Scripts\python.exe -m pytest` exits 0 from `data/`.

## Review changes (spec round 1)
- **H1.** Criterion 7 as written passed with no manual handling at all; it now drives `run` with the fake transport and asserts zero requests.
- **H2.** The manual file is validated right after the lock, before any request.
- **M1.** Duplicate keys are an error instead of a silent overwrite.
- **M2.** Error messages carry the parser's line and column, a Korean hint, and a UTF-8 hint for files saved in the ANSI code page.
- The critic confirmed the merge order (Wikidata `ko` first, then manual, then aod) and that the gap list drops an anime as soon as its own `ko` slot has a title.

## Test list
- `data/tests/test_manual.py`: criteria 1-5 and 8.
- `data/tests/test_build.py`: criteria 6 and 7 added; no existing test changes.

## File ownership
- Test writer: `data/tests/test_manual.py`, `data/tests/test_build.py`.
- Implementer: `data/songrush_data/manual.py`, `data/songrush_data/build.py`, `data/korean_titles_manual.json`.

## Out of scope
AniDB (C-008). Filling in the titles (the owner does that). Any change to the reveal or grouping rules.

## Result
- Tests `c3a8813` (locked); implementation `a7e858a` (attempt 1).
- Red phase (verifier): 17 errors (missing `songrush_data.manual`) and 4 failures (missing `manual_path`, missing merge), all in new tests.
- Verifier: full suite exit 0, `590 passed in 18.68s`; `git diff c3a8813 HEAD -- data/tests/` empty; both `.py` files pure ASCII; criteria 1-9 confirmed.

## Code review (critic)
No Blocker or High. Probes on hand-edited files: BOM plus CRLF parse; UTF-16 and cp949 saves give the Korean "save as UTF-8" hint; trailing commas, empty files and `//` comments give line/column plus the hint; leading-zero keys map to the same id and a repeat is an error; the Korean hint strings decode correctly (the escape-sequence pitfall did not bite).
- **M1, M2 (follow-up).** Mutation runs showed no test pins "validate before the `--refresh-all` cache clear" or "manual before aod in the merge"; both orders are correct in the code today.
