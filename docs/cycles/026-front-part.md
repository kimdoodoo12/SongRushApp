# 026 front-part (C-013)

## Goal
Accept the part of a title before its first separator as an answer (D-049): "Demon Slayer", "Fate", "Gintama", "Re:Zero" and the like, for every slot and every series member, and rebuild the real catalog from the cache.

## Interface changes: `data/songrush_data/groups.py`
- **`front_part(s: str) -> str | None`** (pure, exported). It finds the earliest occurrence of any separator: `": "` (colon then a space), `"："` (full-width colon), `"/"`, or `" -"` (a space then a hyphen-minus; the next character may be anything). It returns `s[:i].rstrip()` when that occurrence starts at an index `i > 0` and the result is not empty; otherwise `None`. A colon not followed by a space (`Re:Zero`) is not a separator.
- **Accepted** (cycle 021 rule 4, extended): for every source string `s` of a group, the set gets `normalize(s)` and `normalize(strip_qualifier(s))` as before, and, when `f = front_part(s)` is not `None`, also `normalize(f)` and `normalize(strip_qualifier(f))`. A front-part result with fewer than 2 characters after `normalize` is not added (D-049: `K` from `K: Return of Kings`). Empty strings are dropped as before.
- Nothing else changes: slots, the join rule (which keeps using `titles.head`), group ids and reveal titles are as in cycle 021. The answer check in the backend compares a normalized chat message with this list, so it needs no change.

## Acceptance criteria (offline)
1. **`front_part` examples:**
   - `"Demon Slayer: Kimetsu no Yaiba"` → `"Demon Slayer"`; `"Gintama: THE FINAL"` → `"Gintama"`; `"Fate/Zero"` → `"Fate"`;
   - `"Re:ZERO -Starting Life in Another World-"` → `"Re:ZERO"`; `"KIZUMONOGATARI -Koyomi Vamp-"` → `"KIZUMONOGATARI"`; `"86 - Eighty Six"` → `"86"`;
   - a full-width colon: `"<kanji>：<kanji>"` built in the test with `chr` → the part before it;
   - the earliest separator wins: `"A Title: Part / B"` → `"A Title"`;
   - no separator, a leading separator, or only an unspaced colon give `None`: `"Mob Psycho 100"`, `"/Title"`, `"Re:Zero kara Hajimeru Isekai Seikatsu"`.
2. **Fixture run** (cycle 021's 124-record relation fixture through `anilist._normalize`, all ids in the catalog):
   - 101922 (with its `anilist_id_in.json` media, as in cycle 021 criterion 4) accepts `normalize("Demon Slayer")`;
   - 21355 accepts `normalize("Re:Zero")` (from its English title);
   - 21400 accepts `normalize("Kizumonogatari")` (from Koyomi Vamp's English title in its group);
   - 356 accepts `normalize("Fate")`;
   - every catalog anime's accepted list still contains every string it accepted under cycle 021 (computed in the test by applying the cycle 021 rule, `normalize(s)` and `normalize(strip_qualifier(s))` over the same upper-bound source set).
3. **One character is too short:** an inline anime with English title `"K: Return of Kings"` does not accept `"k"` but accepts `normalize("K: Return of Kings")`.
4. **No separator, no change:** an inline anime whose only titles are `"Re:Zero kara Hajimeru Isekai Seikatsu"` (romaji) and `"Mob Psycho 100"` (English) has exactly the cycle 021 accepted list.
5. **Provenance (cycle 021 criterion 7, updated):** every accepted string equals `normalize(x)` or `normalize(strip_qualifier(x))` for some `x` that is a source string of the upper-bound set or the `front_part` of one.
6. **Cycle 021 tests updated to D-049:** in `test_kimetsu_accepts_titles_not_partial_or_synonyms` the assertion `normalize("Demon Slayer") not in accepted` becomes `in` (the `KnY` assertion stays: synonyms are still not accepted when an official title exists, D-035); in `test_joined_series_accept_series_title` the assertion that 21400 does not accept `normalize("Kizumonogatari")` becomes `in`; the provenance test follows criterion 5. No other existing test changes.
7. `.venv\Scripts\python.exe -m pytest` exits 0 from `data/`.

## Test list
- `data/tests/test_groups.py`: criteria 1-5 added; the three tests in criterion 6 updated.

## File ownership
- Test writer: `data/tests/test_groups.py`.
- Implementer: `data/songrush_data/groups.py`.

## After the cycle (lead)
Rebuild the real catalog from the cache (`python -u -m songrush_data.build` from `data/`; only the AnimeThemes last-page freshness request goes out) and record the total number of accepted strings before and after, then commit `data/catalog.json`.

## Out of scope
Grouping (the join rule keeps `titles.head`), synonyms (D-035), and the backend answer check.
