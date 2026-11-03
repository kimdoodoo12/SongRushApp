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
- The source is ASCII-only: non-ASCII characters are built from code points (`(char) 0xDF`), never pasted or written as backslash-u escapes through a file-writing tool (CLAUDE.md pitfall; the JVM's default file encoding here is MS949).
- Known and accepted drift: Java 17 has Unicode 13.0 and Python 3.12 Unicode 15.0, so 420 code points assigned in 14.0/15.0 (newer emoji and a few letters and punctuation) are left as they are in Java; none occurs in the catalog (spec review sweep). Cycle 015 accepted this.

## Acceptance criteria (JUnit 5, from `backend/`)
1. **Shared vectors:** every entry of `contracts/normalize-vectors.json` (read from the repo path `../contracts/normalize-vectors.json` with Jackson 3) gives `normalize(input) == expected`; the test reports each failing input by index. The file has 79 vectors; the test asserts it read at least 79.
2. **Catalog idempotence:** every string in `groups[].accepted` of the committed catalog (`../data/catalog.json` from `backend/`, as `CatalogBeanTest` reads it) is already normalized by the Python tool, so `normalize(s) == s` holds for each; the test asserts it read at least 22,000 strings and reports the first mismatches with their code points.
2b. **Raw titles reach their group** (spec review: criterion 2 alone exercises only the pass-through path): for every `anime[]` entry with a non-null `group` and every string `t` in its `titles` slots, `normalize(t)` is in that group's `accepted` list; the test asserts it checked at least 21,000 pairs and reports failures with code points.
3. **Null:** `normalize(null)` throws `NullPointerException`; `normalize("")` is `""`.
4. `.\gradlew.bat test` and `.\gradlew.bat build` exit 0; every earlier backend test still passes.

## Test list
- `backend/src/test/java/com/songrush/game/NormalizeTest.java`: criteria 1, 2, 2b, 3.

## File ownership
- Test writer: `NormalizeTest.java`.
- Implementer: `backend/src/main/java/com/songrush/game/Normalize.java`.

## Review changes (spec round 1)
The critic ran a Java 17 implementation of these steps against Python: 0 mismatches over 39,388 real catalog and vector strings and 300,000 random strings of risky characters; 420 mismatches over all single code points, all Unicode 14/15 additions unassigned in Java 17.
- **High.** Criterion 2 only exercises the pass-through path; criterion 2b checks the whole pipeline on 21,163 real (anime, title) pairs (all hold in Python).
- **Medium.** Minimum counts and the catalog path are stated.
- **Nit.** The verifier checks that `Normalize.java` is ASCII-only.

## Out of scope
`strip_qualifier`, `head` and front parts (only the data tools need them; accepted strings are precomputed). Matching rules (B-006a).

## Result
- Tests `f523b9e` (locked); implementation `0139679` (attempt 1).
- Red phase (verifier): `:compileTestJava FAILED`, 5 errors, all "cannot find symbol" for `Normalize`.
- Verifier: `cleanTest test` and `build` exit 0; 13 suites, 0 failures; NormalizeTest 4 tests; `git diff f523b9e HEAD -- backend/src/test` empty; `Normalize.java` is ASCII-only.

## Code review (critic)
No findings at Blocker, High, Medium or Nit. The critic compiled `Normalize.java` into a harness and compared it with Python over all 1,114,112 single code points, 200,000 random risky strings and 28 named edge cases (surrogate pairs, unpaired surrogates, ZWJ sequences, variation selectors, tags): the only differences were the 420 Unicode 14/15 code points the spec accepts.
