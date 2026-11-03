# 032 catalog-loader (B-002)

## Goal
Load `data/catalog.json` and the audio-cache index into immutable in-memory lookups when the backend starts, and expose the draw candidates (draw-set themes whose audio is cached, with their durations), so later items can run rounds (design 029, D-056).

## Interface: package `com.songrush.catalog` (`backend/src/main/java/com/songrush/catalog/`)
- **Records** (immutable; lists and maps are unmodifiable copies):
  - `Anime(int id, Integer anilistId, Integer group, Map<String, List<String>> titles, String reveal)`; `titles` has the keys `ko`, `en`, `ja`, `romaji`; `anilistId`, `group` and `reveal` may be null (6 catalog anime, none in the draw set).
  - `Theme(int id, int animeId, String type, String slug, String song, List<String> artists, String audioBasename)`; `song` may be null (17 catalog themes, none in the draw set).
  - `Candidate(int themeId, String audioBasename, long durationMs)`.
- **`Catalog`**: `Optional<Anime> anime(int id)`, `Optional<Theme> theme(int id)`, `List<String> accepted(int groupId)` (empty when unknown), `List<Integer> drawSet()` (the file's order), `List<Candidate> candidates()` (draw-set order), `int animeCount()`, `int themeCount()`.
- **`CatalogLoader.load(Path catalogJson, Path audioIndexJson) -> Catalog`** (static, no Spring):
  - Reads the catalog with Jackson 3 (`tools.jackson.databind`, the version Spring Boot 4 ships; CLAUDE.md pitfall). Jackson annotations, if any, stay in `com.fasterxml.jackson.annotation`. Unknown fields are ignored.
  - A missing or unreadable catalog, invalid JSON, or a missing required field (`version`, `anime`, `themes`, `groups`, `drawSet`, or a record's `id`) throws `CatalogException` (a `RuntimeException`) whose message contains the catalog path and the reason.
  - **Integrity (spec review M1):** a draw-set id that is not a theme, a theme whose `animeId` is not an anime, or an anime whose non-null `group` is not a group throws `CatalogException` naming the offending id.
  - **Candidates (D-056):** a draw-set theme is a candidate when the index has an entry for its id (keys are decimal strings), the entry's `basename` equals the theme's `audio.basename`, its `durationMs` is positive, and the file `audioIndexJson.getParent().resolve(basename)` exists with exactly the entry's `size` (or `paddedSize` if present). Others are left out.
  - A missing index gives zero candidates and logs one warning naming the path; an index that is not valid JSON throws `CatalogException` naming the index path.
- **Spring wiring:** a `@Configuration` class provides a `Catalog` bean from `songrush.data-dir`: `<dataDir>/catalog.json` and `<dataDir>/audio-cache/index.json`. A `CatalogException` therefore fails startup with the message. At startup it logs one line: `catalog loaded: <anime> anime, <themes> themes, <drawSet> draw set, <candidates> candidates from <path>`.

## Acceptance criteria (JUnit 5, from `backend/`)
1. **Lookups** on a small fixture catalog under `backend/src/test/resources/catalog/` (3 anime, 4 themes, 2 groups, a draw set of 3; Korean titles included): `anime`, `theme`, `accepted` return the fixture values; unknown ids give empty results; `drawSet()` keeps the file's order; the returned lists and maps cannot be modified (`UnsupportedOperationException`).
2. **Candidates** (fixture catalog plus an index and dummy `.ogg` files written to a `@TempDir`): a draw-set theme with an index entry and a file is a candidate with its `durationMs`; one with no index entry, one whose file is missing, one whose index basename differs, and one with `durationMs` 0 are not; the order follows the draw set.
3. **Errors:** a missing catalog, a catalog that is not JSON, and a catalog without `drawSet` each throw `CatalogException` whose message contains the path; a missing index gives zero candidates; an index that is not JSON throws `CatalogException` naming the index path.
4. **Spring context:** with the default `songrush.data-dir` (`../data`), the application context starts and the `Catalog` bean's counts equal those of the committed `data/catalog.json` read independently in the test (anime, themes, draw-set length); every candidate's theme id is in the draw set and candidates keep the draw-set order. (Spec review M2: no hard-coded counts, since the catalog is rebuilt from time to time.)
5. `.\gradlew.bat test` and `.\gradlew.bat build` exit 0 from `backend/`; the existing tests still pass. `SongrushPropertiesTest.Override` set `songrush.data-dir=/tmp/x`, a folder with no catalog, which now fails startup (spec review B1): it now points at the fixture folder `src/test/resources/catalog` and asserts that path; nothing else in it changes.
6. **Integrity:** a fixture variant whose draw set names a missing theme, and one whose theme names a missing anime, each throw `CatalogException` naming the id.

## Test list
- `backend/src/test/java/com/songrush/catalog/CatalogLoaderTest.java`: criteria 1-3.
- `backend/src/test/java/com/songrush/catalog/CatalogBeanTest.java`: criterion 4.
- Fixture: `backend/src/test/resources/catalog/catalog.json`.
- `backend/src/test/java/com/songrush/SongrushPropertiesTest.java`: the `Override` property value only (criterion 5).

## File ownership
- Test writer: the two test classes, the fixture, and `SongrushPropertiesTest.java` (the override value only).
- Implementer: `backend/src/main/java/com/songrush/catalog/*`.

## Answer safety (spec review M3)
`Catalog` and its records hold the answers (titles, reveal, accepted strings, song, artists, audio basenames). They are never serialized into an HTTP or WebSocket response; the startup log carries counts only.

## Review changes (spec round 1)
- **B1.** The existing `SongrushPropertiesTest.Override` pointed at `/tmp/x`, which has no catalog and would fail startup; it is now owned by the test writer and points at the fixture.
- **M1.** Referential integrity is defined. **M2.** Criterion 4 compares with the committed catalog instead of hard-coded counts. **M3.** The answer-safety rule is stated.
- **N1-N3.** Jackson annotation package, nullability from the real data, and a size check for candidates (as the Python side does).
- The critic confirmed Jackson 3.1.7 is on the classpath, the real index is written atomically, and the catalog's draw set has no missing audio or unsafe basename.

## Out of scope
Answer matching (the Java `normalize`, from S-003), rooms, rounds, the audio endpoint (B-003).

## Result
- Tests `7660de0` (locked); implementation `c3aec42` (attempt 1; the implementer's first run failed one locked test, `accepted(1).add`, because `Map.copyOf` kept the inner lists mutable, and fixed it).
- Red phase (verifier): `:compileTestJava FAILED`, 43 errors, all "cannot find symbol" for the missing `com.songrush.catalog` types.
- Verifier: `.\gradlew.bat cleanTest test`, `build` and `cleanTest test --offline` exit 0; 36 tests, 0 failures; `git diff 7660de0 HEAD -- backend/src/test` empty; no controller or serializer touches the catalog. Startup log: `catalog loaded: 5021 anime, 14591 themes, 1500 draw set, 117 candidates` (the prefetch was still running).

## Code review (critic)
No Blocker or High. JSON robustness (int range, nulls, wrong types, duplicate ids), the three integrity checks, deep immutability and safe publication all hold; the Windows index rewrite race is covered by `os.replace` and the size check.
- **M2 (queued in B-003).** The records' default `toString` prints titles, reveal, song, artists and the basename (itself the answer); B-003 overrides them to ids and pins it with a test.
- **M1 and Nits (follow-up).** The bean test passes with zero candidates; a warning logs a basename; duplicate draw-set ids; title key order; untested `paddedSize` and basename branches.
