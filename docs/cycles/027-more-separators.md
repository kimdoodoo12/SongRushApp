# 027 more-separators (C-014)

## Goal
Let the front-part rule (D-049, D-050) see the subtitle separators that Japanese and Korean titles use (D-051): wave dashes, long dashes and full-width slash and hyphen.

## Interface change: `data/songrush_data/groups.py`
`front_part(s)` also treats these characters as separators, wherever they appear (no spacing requirement):
- `~` (U+007E), `～` (U+FF5E), `〜` (U+301C);
- `–` (U+2013), `—` (U+2014), `―` (U+2015);
- `－` (U+FF0D), `／` (U+FF0F).

**Every separator counts (D-052).** A new exported `front_parts(s) -> list[str]` returns the front part before each separator occurrence, in order of position, deduplicated (each computed like `front_part`: right-stripped, empty or index-0 results dropped). `front_part(s)` stays and returns the first of them, or `None`. The accepted list uses every entry of `front_parts`, each with `normalize` and `normalize(strip_qualifier(...))` and the D-050 minimum.

Rules otherwise unchanged from cycle 026: a separator at index 0, or one that leaves only whitespace before it, gives `None`; the result is right-stripped (including U+3000). The digit exception for `/` also applies to `／`, and to the wave dashes `~`, `～`, `〜` (D-052: `13話～16話` is a range): when both neighbours are digits (ASCII or full-width), the character is not a separator. The D-050 minimum applies when the front part is added to the accepted list. Everything else in `groups.py` is unchanged.

## Acceptance criteria (offline)
1. **New separators** (strings built with `chr` in the test):
   - `"Attack on Titan ~Chronicle~"` → `"Attack on Titan"`;
   - `"Chainsaw Man" + chr(0x2013) + " The Movie: Reze Arc"` (with a space before the en dash) → `"Chainsaw Man"`;
   - `"<kana>" + chr(0x301C) + "<kana>" + chr(0x301C)` → the first kana run;
   - `"<katakana>" + chr(0xFF0F) + "<katakana>"` → the part before the full-width slash;
   - `"86" + chr(0xFF0D) + "<katakana>" + chr(0xFF0D)` → `"86"`;
   - `"<hangul> ~<hangul>~"` → the Hangul before ` ~`, right-stripped;
   - em dash and horizontal bar cases: `"Title" + chr(0x2014) + "Sub"` → `"Title"`, `"Title" + chr(0x2015) + "Sub"` → `"Title"`.
2. **Every separator (D-052):** `front_parts("Chainsaw Man" + chr(0x2013) + " The Movie: Reze Arc")` (space before the en dash) is `["Chainsaw Man", "Chainsaw Man" + chr(0x2013) + " The Movie"]`, and `front_part` of it is `"Chainsaw Man"`; `front_parts("Name ~Sub~: More")` is `["Name", "Name ~Sub", "Name ~Sub~"]`; `front_parts("Mob Psycho 100")` is `[]`. An inline anime with that Chainsaw Man English title accepts `normalize("Chainsaw Man")`, `normalize("Chainsaw Man – The Movie")` and the full title.
3. **No cut:** a full-width slash between full-width digits (`"x" + chr(0xFF11) + chr(0xFF0F) + chr(0xFF12)`) and between ASCII digits (`"x1" + chr(0xFF0F) + "2"`) gives `None`; a wave dash between digits (`"Episode 13" + chr(0xFF5E) + "16"`) gives `None`; a string that starts with a wave dash gives `None`; `"Kaguya-sama wa Kokurasetai"` (ASCII hyphen inside a word) gives `None`.
4. **Accepted list:** an inline anime with English title `"Attack on Titan ~Chronicle~"` accepts `normalize("Attack on Titan")`; one with native title `"86" + chr(0xFF0D) + ...` does not accept `"86"` (D-050: 2 Latin characters).
5. Every cycle 021 and cycle 026 test passes unchanged, except one: the provenance test `test_accepted_strings_come_from_group_sources` computed allowed strings from `front_part(s)` only, which D-052 contradicts (the test writer showed it fails under a simulated D-052 implementation); its allowed set now loops over `front_parts(s)`; `.venv\Scripts\python.exe -m pytest` exits 0 from `data/`.
6. **No answer is lost** (lead, after the cycle): the catalog rebuilt from the cache accepts every string the cycle 026 catalog accepted, per group (a diff of the two catalogs shows no removed accepted string).

## Review changes (spec round 1)
The critic applied the first-separator rule to the real data offline: +195 accepted strings, no new string shared by unrelated groups, but 5 strings accepted in cycle 026 were lost (for example `chainsawmanthemovie`), and wave dashes inside words produced about 12 odd strings. The user decided both (D-052): accept the part before every separator, and accept the odd wave-dash strings except ranges between digits. Criterion 6 checks the no-loss property on the real catalog.

## Test list
- `data/tests/test_groups.py`: criteria 1-4 added; the provenance test's allowed set uses `front_parts` (criterion 5); no other existing test changes.

## File ownership
- Test writer: `data/tests/test_groups.py`.
- Implementer: `data/songrush_data/groups.py`.

## After the cycle (lead)
Rebuild the catalog from the cache and record the new accepted-string total and any new string shared by two unrelated groups.

## Out of scope
Grouping (`titles.head` is unchanged), D-050's minimum, synonyms.

## Result
- Tests `bfdb0c0` (locked); implementation `d1d1ef6` (attempt 1).
- Red phase (verifier): 15 failed (missing `front_parts`, old separator set), all in new tests and the provenance test of criterion 5.
- Verifier: full suite exit 0, `569 passed in 17.88s`; `git diff bfdb0c0 HEAD -- data/tests/` empty; criteria 1-5 confirmed.
- Criterion 6 (lead): the catalog rebuilt from the cache (exit 0, no clear) has 22,400 accepted strings (was 21,868): 0 removed, 532 added. `build catalog bytes=5730975`.
- The test writer caught that criterion 5 as first written ("every cycle 026 test unchanged") contradicted D-052, because the cycle 026 provenance test only allowed the first front part; the spec was amended before the tests were locked.

## Code review (critic)
No Blocker or High. Offline rebuild agrees with the lead's: 0 removed, 532 added; strings shared by two groups went from 52 to 58, all within one franchise.
- **Medium (follow-up).** `13話～16話`, the user's own example in D-052, is still cut because `話` precedes the wave dash; the spec's criterion used `Episode 13～16` instead. One extra string of the same anime results.
- **Nit.** BACKLOG's C-014 text still said "the earliest separator wins"; corrected.
- **Nit.** A separator at index 0 now drops only that cut, not the whole string (consistent with D-052; no real title starts with a separator).
