# 032 catalog-loader (B-002)

## Goal
Load `data/catalog.json` and the audio-cache index into immutable in-memory lookups when the backend starts, and expose the draw candidates (draw-set themes whose audio is cached, with their durations), so later items can run rounds (design 029, D-056).

## Interface: package `com.songrush.catalog` (`backend/src/main/java/com/songrush/catalog/`)
- **Records** (immutable; lists and maps are unmodifiable copies):
  - `Anime(int id, Integer anilistId, Integer group, Map<String, List<String>> titles, String reveal)`; `titles` has the keys `ko`, `en`, `ja`, `romaji`.
  - `Theme(int id, int animeId, String type, String slug, String song, List<String> artists, String audioBasename)`.
  - `Candidate(int themeId, String audioBasename, long durationMs)`.
- **`Catalog`**: `Optional<Anime> anime(int id)`, `Optional<Theme> theme(int id)`, `List<String> accepted(int groupId)` (empty when unknown), `List<Integer> drawSet()` (the file's order), `List<Candidate> candidates()` (draw-set order), `int animeCount()`, `int themeCount()`.
- **`CatalogLoader.load(Path catalogJson, Path audioIndexJson) -> Catalog`** (static, no Spring):
  - Reads the catalog with Jackson 3 (`tools.jackson.*`, the version Spring Boot 4 ships; CLAUDE.md pitfall). Unknown fields are ignored.
  - A missing or unreadable catalog, invalid JSON, or a missing required field (`version`, `anime`, `themes`, `groups`, `drawSet`) throws `CatalogException` (a `RuntimeException`) whose message contains the catalog path and the reason.
  - **Candidates (D-056):** a draw-set theme is a candidate when the index has an entry for its id (keys are decimal strings), the entry's `basename` equals the theme's `audio.basename`, its `durationMs` is positive, and the file `audioIndexJson.getParent().resolve(basename)` exists. Others are left out.
  - A missing index gives zero candidates and logs one warning naming the path; an index that is not valid JSON throws `CatalogException` naming the index path.
- **Spring wiring:** a `@Configuration` class provides a `Catalog` bean from `songrush.data-dir`: `<dataDir>/catalog.json` and `<dataDir>/audio-cache/index.json`. A `CatalogException` therefore fails startup with the message. At startup it logs one line: `catalog loaded: <anime> anime, <themes> themes, <drawSet> draw set, <candidates> candidates from <path>`.

## Acceptance criteria (JUnit 5, from `backend/`)
1. **Lookups** on a small fixture catalog under `backend/src/test/resources/catalog/` (3 anime, 4 themes, 2 groups, a draw set of 3; Korean titles included): `anime`, `theme`, `accepted` return the fixture values; unknown ids give empty results; `drawSet()` keeps the file's order; the returned lists and maps cannot be modified (`UnsupportedOperationException`).
2. **Candidates** (fixture catalog plus an index and dummy `.ogg` files written to a `@TempDir`): a draw-set theme with an index entry and a file is a candidate with its `durationMs`; one with no index entry, one whose file is missing, one whose index basename differs, and one with `durationMs` 0 are not; the order follows the draw set.
3. **Errors:** a missing catalog, a catalog that is not JSON, and a catalog without `drawSet` each throw `CatalogException` whose message contains the path; a missing index gives zero candidates; an index that is not JSON throws `CatalogException` naming the index path.
4. **Spring context:** with the default `songrush.data-dir` (`../data`), the application context starts, the `Catalog` bean reports 5,021 anime, 14,591 themes and a 1,500-theme draw set (the committed catalog), and `candidates().size()` is between 0 and 1,500.
5. `.\gradlew.bat test` and `.\gradlew.bat build` exit 0 from `backend/`; the existing tests still pass.

## Test list
- `backend/src/test/java/com/songrush/catalog/CatalogLoaderTest.java`: criteria 1-3.
- `backend/src/test/java/com/songrush/catalog/CatalogBeanTest.java`: criterion 4.
- Fixture: `backend/src/test/resources/catalog/catalog.json`.

## File ownership
- Test writer: the two test classes and the fixture.
- Implementer: `backend/src/main/java/com/songrush/catalog/*`.

## Out of scope
Answer matching (the Java `normalize`, from S-003), rooms, rounds, the audio endpoint (B-003).
