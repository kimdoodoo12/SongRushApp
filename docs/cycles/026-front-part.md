# 026 front-part (C-013)

## Goal
Accept the part of a title before its first separator as an answer (D-049): "Demon Slayer", "Fate", "Gintama", "Re:Zero" and the like, for every slot and every series member, and rebuild the real catalog from the cache.

## Interface changes: `data/songrush_data/groups.py`
- **`front_part(s: str) -> str | None`** (pure, exported). It finds the earliest occurrence of any separator: `": "` (colon then a space), `"："` (full-width colon), `"/"` unless both its neighbours are digits (so `Ranma 1/2` and `22/7` are not cut; spec review H-2), or `" -"` (a space then a hyphen-minus; the next character may be anything). It returns `s[:i].rstrip()` when that occurrence starts at an index `i > 0` and the result is not empty; otherwise `None`. A colon not followed by a space (`Re:Zero`) is not a separator.
- **Accepted** (cycle 021 rule 4, extended): for every source string `s` of a group, the set gets `normalize(s)` and `normalize(strip_qualifier(s))` as before, and, when `f = front_part(s)` is not `None`, also `normalize(f)` and `normalize(strip_qualifier(f))`. A front-part result is added only when its normalized form has at least 4 characters, or at least 2 when the front part contains Hangul, kana or CJK ideographs (D-050; the same test as `titles.strip_qualifier`, which uses `titles._has_cjk`). Empty strings are dropped as before.
- Nothing else changes: slots, the join rule (which keeps using `titles.head`), group ids and reveal titles are as in cycle 021. The backend answer check (to be built from S-003) will compare a normalized chat message with this list, so it will need no front-part logic of its own.

## Acceptance criteria (offline)
1. **`front_part` examples:**
   - `"Demon Slayer: Kimetsu no Yaiba"` → `"Demon Slayer"`; `"Gintama: THE FINAL"` → `"Gintama"`; `"Fate/Zero"` → `"Fate"`;
   - `"Re:ZERO -Starting Life in Another World-"` → `"Re:ZERO"`; `"KIZUMONOGATARI -Koyomi Vamp-"` → `"KIZUMONOGATARI"`; `"86 - Eighty Six"` → `"86"`;
   - a full-width colon: `"<kanji>：<kanji>"` built in the test with `chr` → the part before it;
   - the earliest separator wins: `"A Title: Part / B"` → `"A Title"`;
   - a slash between digits is not a separator: `"Ranma 1/2"` → `None`, `"22/7"` → `None`, `"Nanaka 6/17"` → `None`;
   - no separator, a leading separator, or only an unspaced colon give `None`: `"Mob Psycho 100"`, `"/Title"`, `"Re:Zero kara Hajimeru Isekai Seikatsu"`.
2. **Fixture run** (cycle 021's 124-record relation fixture through `anilist._normalize`, all ids in the catalog):
   - 101922 (with its `anilist_id_in.json` media, as in cycle 021 criterion 4) accepts `normalize("Demon Slayer")`;
   - 21355 accepts `normalize("Re:Zero")` (from its English title);
   - 21400 accepts `normalize("Kizumonogatari")` (from Koyomi Vamp's English title in its group);
   - 356 accepts `normalize("Fate")`;
   - **lower bound:** every catalog anime's accepted list contains `normalize(s)` and `normalize(strip_qualifier(s))` (dropping empty strings) for every string `s` in the slots of every catalog anime that shares its group id. Relation-node titles are not used here, because the upper-bound set includes nodes that did not join (spec review B-1).
3. **Short front parts are not accepted (D-050):** inline anime with English titles `"K: Return of Kings"`, `"Re: Cutie Honey"`, `"IS: Infinite Stratos"` and `"GTO: The Animation"` do not accept `"k"`, `"re"`, `"is"` or `"gto"`, and each accepts its full normalized title; an inline anime with native title built from two kanji, a full-width colon and more kanji accepts the two-kanji front part; one with English `"Fate/Zero"` accepts `"fate"`.
4. **No separator, no change:** an inline anime whose only titles are `"Re:Zero kara Hajimeru Isekai Seikatsu"` (romaji) and `"Mob Psycho 100"` (English) has exactly the cycle 021 accepted list.
5. **Provenance (cycle 021 criterion 7, updated):** every accepted string equals `normalize(x)` or `normalize(strip_qualifier(x))` for some `x` that is a source string of the upper-bound set or the `front_part` of one.
6. **Cycle 021 tests updated to D-049:** in `test_kimetsu_accepts_titles_not_partial_or_synonyms` the assertion `normalize("Demon Slayer") not in accepted` becomes `in` (the `KnY` assertion stays: synonyms are still not accepted when an official title exists, D-035); in `test_joined_series_accept_series_title` the assertion that 21400 does not accept `normalize("Kizumonogatari")` becomes `in`; the provenance test follows criterion 5. No other existing test changes.
7. `.venv\Scripts\python.exe -m pytest` exits 0 from `data/`.

## Review changes (spec round 1)
The critic rebuilt the real catalog's accepted lists from the cache (all 5,015 matched the committed catalog) and applied the rule: 20,632 accepted strings became 21,905 (+1,273, in 730 of 3,321 groups); of the 38 strings newly shared by two groups, 37 are one franchise split into several groups, and the only unrelated one was `re`.
- **B-1.** Criterion 2's "still contains everything from cycle 021" used the upper-bound source set, which includes relation nodes that never joined, so even the current code failed it in all 31 fixture groups. It is now a lower bound over the group's catalog slots.
- **H-1, M-1 (user decision D-050).** Two-letter front parts (`is`, `in`, `re`, `id`) and three-letter ones (`gto`) would match ordinary chat; the user chose the qualifier rule's minimum (4 Latin characters, 2 with Hangul, kana or CJK).
- **H-2.** A slash between digits (`Ranma 1/2`, `22/7`, `Nanaka 6/17`) is not a separator.
- **N-1.** The backend sentence now says the answer check is still to be built.

## Test list
- `data/tests/test_groups.py`: criteria 1-5 added; the three tests in criterion 6 updated.

## File ownership
- Test writer: `data/tests/test_groups.py`.
- Implementer: `data/songrush_data/groups.py`.

## After the cycle (lead)
Rebuild the real catalog from the cache (`python -u -m songrush_data.build` from `data/`; only the AnimeThemes last-page freshness request goes out) and record the total number of accepted strings before and after, then commit `data/catalog.json`.

## Out of scope
Grouping (the join rule keeps `titles.head`), synonyms (D-035), and the backend answer check.
