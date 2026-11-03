# 027 more-separators (C-014)

## Goal
Let the front-part rule (D-049, D-050) see the subtitle separators that Japanese and Korean titles use (D-051): wave dashes, long dashes and full-width slash and hyphen.

## Interface change: `data/songrush_data/groups.py`
`front_part(s)` also treats these characters as separators, wherever they appear (no spacing requirement):
- `~` (U+007E), `～` (U+FF5E), `〜` (U+301C);
- `–` (U+2013), `—` (U+2014), `―` (U+2015);
- `－` (U+FF0D), `／` (U+FF0F).

Rules unchanged from cycle 026: the earliest separator of any kind wins; a separator at index 0, or one that leaves only whitespace before it, gives `None`; the result is right-stripped (including U+3000). The digit exception for `/` also applies to `／`: when both neighbours are digits (ASCII or full-width), it is not a separator. The D-050 minimum applies when the front part is added to the accepted list. Everything else in `groups.py` is unchanged.

## Acceptance criteria (offline)
1. **New separators** (strings built with `chr` in the test):
   - `"Attack on Titan ~Chronicle~"` → `"Attack on Titan"`;
   - `"Chainsaw Man" + chr(0x2013) + " The Movie: Reze Arc"` (with a space before the en dash) → `"Chainsaw Man"`;
   - `"<kana>" + chr(0x301C) + "<kana>" + chr(0x301C)` → the first kana run;
   - `"<katakana>" + chr(0xFF0F) + "<katakana>"` → the part before the full-width slash;
   - `"86" + chr(0xFF0D) + "<katakana>" + chr(0xFF0D)` → `"86"`;
   - `"<hangul> ~<hangul>~"` → the Hangul before ` ~`, right-stripped;
   - em dash and horizontal bar cases: `"Title" + chr(0x2014) + "Sub"` → `"Title"`, `"Title" + chr(0x2015) + "Sub"` → `"Title"`.
2. **Earliest wins across kinds:** `"Name ~Sub~: More"` → `"Name"`; `"Name: Sub ~More~"` → `"Name"`.
3. **No cut:** a full-width slash between full-width digits (`"x" + chr(0xFF11) + chr(0xFF0F) + chr(0xFF12)`) gives `None`; a string that starts with a wave dash gives `None`; `"Kaguya-sama wa Kokurasetai"` (ASCII hyphen inside a word) gives `None`.
4. **Accepted list:** an inline anime with English title `"Attack on Titan ~Chronicle~"` accepts `normalize("Attack on Titan")`; one with native title `"86" + chr(0xFF0D) + ...` does not accept `"86"` (D-050: 2 Latin characters).
5. Every cycle 021 and cycle 026 test passes unchanged; `.venv\Scripts\python.exe -m pytest` exits 0 from `data/`.

## Test list
- `data/tests/test_groups.py`: criteria 1-4 added; no existing test changes.

## File ownership
- Test writer: `data/tests/test_groups.py`.
- Implementer: `data/songrush_data/groups.py`.

## After the cycle (lead)
Rebuild the catalog from the cache and record the new accepted-string total and any new string shared by two unrelated groups.

## Out of scope
Grouping (`titles.head` is unchanged), D-050's minimum, synonyms.
