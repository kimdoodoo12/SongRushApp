# 035 java-normalize (B-004)

## Goal
Give the backend a `normalize` that gives exactly the same result as `data/songrush_data/titles.py` `normalize`, so a chat message is judged against the catalog's accepted strings (which the data tools normalized) the same way in both languages (D-015, design 034 F5).

## Interface: `com.songrush.game.Normalize`
- `static String normalize(String s)`; `null` throws `NullPointerException`. Steps, in order, mirroring `titles.py:37-46`:
  1. `java.text.Normalizer` NFKC.
  2. `toLowerCase(Locale.ROOT)`, then `ß` (U+00DF) -> `ss` and final sigma `ς` (U+03C2) -> `σ` (U+03C3).
  3. NFD; remove every code point U+0300-U+036F; NFC.
  4. `×` (U+00D7) -> `x`.
  5. Remove every code point whose general category is a punctuation (Pc, Pd, Ps, Pe, Pi, Pf, Po), symbol (Sm, Sc, Sk, So), separator (Zs, Zl, Zp), Cc or Cf, and the extra code points U+115F, U+1160, U+3164, U+FFA0, U+FE00-U+FE0F, U+180B-U+180F, U+E0100-U+E01EF. Iterate by code point (supplementary characters included).
  6. NFC.
- The source is ASCII-only (non-ASCII characters written as `\\uXXXX` escapes or code points).

## Acceptance criteria (JUnit 5, from `backend/`)
1. **Shared vectors:** every entry of `contracts/normalize-vectors.json` (read from the repo path `../contracts/normalize-vectors.json` with Jackson 3) gives `normalize(input) == expected`; the test reports each failing input by index. The file has 79 vectors; the test asserts it read at least 79.
2. **Catalog idempotence:** every string in `groups[].accepted` of the committed `data/catalog.json` is already normalized by the Python tool, so `normalize(s) == s` must hold for each (about 22,400 strings); the test reports the first mismatches with their code points.
3. **Null:** `normalize(null)` throws `NullPointerException`; `normalize("")` is `""`.
4. `.\gradlew.bat test` and `.\gradlew.bat build` exit 0; every earlier backend test still passes.

## Test list
- `backend/src/test/java/com/songrush/game/NormalizeTest.java`: criteria 1-3.

## File ownership
- Test writer: `NormalizeTest.java`.
- Implementer: `backend/src/main/java/com/songrush/game/Normalize.java`.

## Out of scope
`strip_qualifier`, `head` and front parts (only the data tools need them; accepted strings are precomputed). Matching rules (B-006a).
