# 060 draw-filters (B-011)

## Goal
The host (or solo player) can limit the draw to openings or endings and to a year range within the cached draw set. Every player is told which conditions apply (design 059, item 1).

## Behaviour
1. **Catalog.**
   - `Anime` gains `Integer year`, read from the catalog's `year` (null when absent).
   - `Theme.type` is already loaded.
   - No other record changes.
2. **`DrawFilter`** (new, `game/DrawFilter.java`): `record DrawFilter(Set<String> types, Integer yearFrom, Integer yearTo)`. The constructor copies `types` with `Set.copyOf` when it is non-null.
   - `DrawFilter.NONE` has all three fields null.
   - `matches(theme, anime)` is true when each set field passes. For year, `yearFrom <= anime.year() <= yearTo`, inclusive. When a year bound is set and `anime.year()` is null, it does not match.
   - `isEmpty()` is true when all three fields are null.
   - `toMessageFields()` returns only the fields that are set, in the order `types`, `yearFrom`, `yearTo`. `types` comes out as a list sorted `OP` before `ED`.
3. **Parsing** (`GameSocketHandler`, for `create` when it has `songCount` (solo) and for `start`). The optional fields are:
   - `types`: a JSON array. It must be non-empty, every element must be the string `OP` or `ED`, and no value may repeat.
   - `yearFrom` and `yearTo`: JSON integers from 1950 to 2100. When both are given, `yearFrom <= yearTo`.
   - Any violation, including a wrong JSON type and an explicit JSON `null`, sends the sender `error{code:"bad-filter"}`, and nothing starts.
   - **Check order (review M1).** For solo `create`, the filter is checked after `bad-song-count` and before the lobby is created, so a bad filter never leaves an empty lobby. For `start`, it is checked in the handler together with the other shape checks, so `bad-filter` comes before `not-host`, `in-game` and `no-room`.
   - When none of the three fields is present, the filter is `DrawFilter.NONE`.
   - Unknown extra keys stay ignored, as today.
4. **Draw** (`Room.start(int songCount, DrawFilter filter)`). `Room.start(int)` stays and delegates with `NONE`.
   - The drawable list is built as today, with the group filter, and then narrowed by `filter.matches`.
   - **Too few songs.** If fewer than `songCount` remain, `toAll(error{code:"not-enough-songs", available:n})` is sent, where `n` is the narrowed count. The `available` key is always present, also with `NONE`.
   - **Success with a filter set.** When the filter is not empty, `toAll(settings{...toMessageFields()})` is sent first, then the first `prepare`.
   - **Success with no filter.** No `settings` message is sent, so the message sequence stays exactly as today.
   - **Kept for the game.** The filter is stored on the `Room` for this game: `settings()` returns it.
5. **Lobby.** `Lobby.start(playerId, songCount, filter)`. `Lobby.start(playerId, songCount)` stays and delegates with `NONE`.
   - **One place for joiners (review H2).** Whenever a player gets `joined` + `snapshot` while a room with a non-empty filter exists (late join as a watcher, a grace rejoin, a socket replace), that player gets `settings` right after the `snapshot`, from `sendJoinedAndSnapshot`, once.
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
   - **A valid lobby filter.** The socket test's own `@TempDir` catalog mixes OP and ED and spans several years. Every member receives the same `settings` before `prepare`, and every `reveal` matches the filter.
   - **A late joiner during that game.** It receives `settings` right after its `snapshot`.
   - **Solo with `types:["OP"]`.** It receives `settings{types:["OP"]}` and then `prepare`.
3b. **`LobbyFilterTest`** (game-level fakes).
   - In a game started with a filter, these each receive `settings` exactly once, immediately after their `snapshot`:
     - a late joiner;
     - a member who disconnects and rejoins within grace;
     - a member whose socket is replaced.
   - With no filter, none of them receives `settings`.
   - A solo `create` whose filter is bad leaves no lobby behind, because the lobby count is unchanged.
4. **Loader.** A catalog loader test reads `year` from the fixture catalog's anime, and `type` as today.
5. **Earlier tests.** All earlier backend tests pass with only the declared change, and `.\gradlew.bat test` exits 0.

## Review changes (spec round 1)
- **High.**
  - Fixtures are generated in `@TempDir`, not as static resources: the gitignored `.ogg` files would break a fresh clone.
  - `settings` goes out after the snapshot on every join path, and is tested for late join, rejoin and replace.
- **Medium.**
  - The order of the `bad-filter` check is stated, so solo never creates an empty lobby.
  - The socket test builds an ED-containing catalog, so the filter really narrows the draw.
- **Nit.**
  - `types` is copied.
  - An explicit `null` gives `bad-filter`.

## Test list
- `backend/src/test/java/com/songrush/game/DrawFilterTest.java`
- `backend/src/test/java/com/songrush/game/RoomFilterTest.java`
- `backend/src/test/java/com/songrush/ws/FilterSocketTest.java`
- `backend/src/test/java/com/songrush/game/LobbyFilterTest.java`
- A new loader test in `backend/src/test/java/com/songrush/catalog/` (a new file, for example `CatalogYearTest.java`).
- **No static fixture resources (review H1).** `*.ogg` is gitignored, so static audio would vanish in a fresh clone. Each test writes its own catalog (OP and ED themes, several years), `index.json` and audio bytes into a `@TempDir`, the way `GameFixture` does. The writer helpers live inside the new test files.
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
