# 060 draw-filters (B-011)

## Goal
The host (or solo player) can limit the draw to openings or endings and to a year range within the cached draw set. Every player is told which conditions apply (design 059, item 1).

## Behaviour
1. **Catalog.**
   - `Anime` gains `Integer year`, read from the catalog's `year` (null when absent).
   - `Theme.type` is already loaded.
   - No other record changes.
2. **`DrawFilter`** (new, `game/DrawFilter.java`): `record DrawFilter(Set<String> types, Integer yearFrom, Integer yearTo)`.
   - `DrawFilter.NONE` has all three fields null.
   - `matches(theme, anime)` is true when each set field passes. For year, `yearFrom <= anime.year() <= yearTo`, inclusive. When a year bound is set and `anime.year()` is null, it does not match.
   - `isEmpty()` is true when all three fields are null.
   - `toMessageFields()` returns only the fields that are set, in the order `types`, `yearFrom`, `yearTo`. `types` comes out as a list sorted `OP` before `ED`.
3. **Parsing** (`GameSocketHandler`, for `create` when it has `songCount` (solo) and for `start`). The optional fields are:
   - `types`: a JSON array. It must be non-empty, every element must be the string `OP` or `ED`, and no value may repeat.
   - `yearFrom` and `yearTo`: JSON integers from 1950 to 2100. When both are given, `yearFrom <= yearTo`.
   - Any violation, including a wrong JSON type, sends the sender `error{code:"bad-filter"}`, and nothing starts.
   - When none of the three fields is present, the filter is `DrawFilter.NONE`.
   - Unknown extra keys stay ignored, as today.
4. **Draw** (`Room.start(int songCount, DrawFilter filter)`). `Room.start(int)` stays and delegates with `NONE`.
   - The drawable list is built as today, with the group filter, and then narrowed by `filter.matches`.
   - **Too few songs.** If fewer than `songCount` remain, `toAll(error{code:"not-enough-songs", available:n})` is sent, where `n` is the narrowed count. The `available` key is always present, also with `NONE`.
   - **Success with a filter set.** When the filter is not empty, `toAll(settings{...toMessageFields()})` is sent first, then the first `prepare`.
   - **Success with no filter.** No `settings` message is sent, so the message sequence stays exactly as today.
   - **Kept for the game.** The filter is stored on the `Room` for this game: `settings()` returns it.
5. **Lobby.** `Lobby.start(playerId, songCount, filter)`. `Lobby.start(playerId, songCount)` stays and delegates with `NONE`.
   - A late joiner who becomes a watcher gets `settings` from the running room's non-empty filter, sent to that player only, right after the late-join `roster`.
   - A rejoin gets it after the snapshot.
   - With an empty filter, nothing extra is sent.
6. **Privacy.** `settings` and `available` carry only numbers and the strings `OP`/`ED`, never a catalog id or record (Multiplayer rules).

## Declared change to a locked earlier test
`backend/src/test/java/com/songrush/game/RoomTest.java`, test `fewerDrawableCandidatesThanTheCountIsRejected`. The `not-enough-songs` assertion now expects the keys `type, code, available` and `available` equal to the fixture's drawable count. To allow this, that one test checks the message with an exact `Map` comparison instead of the shared `KEYS` `error` entry. The `KEYS` map and every other test stay unchanged.

## Acceptance criteria (JUnit 5, from `backend/`)
1. **`DrawFilterTest`:**
   - `matches` for each field alone and combined, with inclusive bounds.
   - A null anime year does not match when a year bound is set.
   - `isEmpty`.
   - `toMessageFields` returns only the set fields, with `types` sorted `OP` before `ED`.
2. **`RoomFilterTest`** (cycle 036 fakes, a fixture catalog with known types and years):
   - For several filters, over 200 seeded draws, every drawn song's theme and anime match.
   - With a filter set, `settings` goes `toAll` exactly once, before the first `prepare`, with exactly the set fields.
   - With `NONE`, the first message after start is `prepare`, as today.
   - A too-narrow filter gives `toAll(error{code:"not-enough-songs", available:n})` with the exact `n`.
   - `settings()` returns the filter.
3. **`FilterSocketTest`** (ws fixtures, real handler):
   - **`bad-filter` cases** are sent to the sender only. Each of these gives it, for both solo `create` and lobby `start`:
     - `types:[]`
     - `types:["OP","OP"]`
     - `types:["IN"]`
     - `types:"OP"`
     - `yearFrom:1949`
     - `yearTo:2101`
     - `yearFrom:2010,yearTo:2000`
     - `yearFrom:2000.5`
     - `yearFrom:"2000"`
   - **A valid lobby filter.** Every member receives the same `settings` before `prepare`.
   - **A late joiner during that game.** It receives `settings` after its `roster`.
   - **Solo with `types:["OP"]`.** It receives `settings{types:["OP"]}` and then `prepare`.
4. **Loader.** A catalog loader test reads `year` from the fixture catalog's anime, and `type` as today.
5. **Earlier tests.** All earlier backend tests pass with only the declared change, and `.\gradlew.bat test` exits 0.

## Test list
- `backend/src/test/java/com/songrush/game/DrawFilterTest.java`
- `backend/src/test/java/com/songrush/game/RoomFilterTest.java`
- `backend/src/test/java/com/songrush/ws/FilterSocketTest.java`
- A new loader test in `backend/src/test/java/com/songrush/catalog/` (a new file, for example `CatalogYearTest.java`).
- New fixture resources only if needed, under `backend/src/test/resources/catalog-filter/`.
- The declared change in `RoomTest.java`.

## File ownership
- **Test writer:** the test list.
- **Implementer:**
  - `catalog/Anime.java`
  - `catalog/CatalogLoader.java`
  - new `game/DrawFilter.java`
  - `game/Room.java`
  - `game/Lobby.java`
  - `ws/GameSocketHandler.java`

## Out of scope
- The frontend (F-008).
- Popularity rank (B-012, D-069).
- Widening beyond the cache (D-070).
