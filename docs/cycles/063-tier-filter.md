# 063 tier-filter (B-012)

## Goal
The host or solo player can choose a popularity difficulty (D-069). Easy draws from the 300 most popular cached themes, hard from ranks 501-1,500, and normal from all, as today.

## Behaviour
1. **Rank.**
   - A theme's rank is its 1-based position in the catalog's `drawSet`, which is ordered by popularity.
   - An uncached draw-set theme still takes its position, so ranks never shift when a download fails.
   - The rank comes from `catalog.drawSet()`. `Candidate` does not change, because locked tests construct it with three arguments.
2. **`DrawFilter`.**
   - It gains a fourth record component, `String tier`, which is `"easy"`, `"hard"` or null.
   - A public three-argument constructor `DrawFilter(Set<String>, Integer, Integer)` delegates with `tier = null`, so locked tests still compile and `NONE` equality holds.
   - `matchesRank(int rank)`:
     - `easy` is true for 1 <= rank <= 300;
     - `hard` is true for 501 <= rank <= 1500;
     - null is always true.
   - `isEmpty()` also requires `tier == null`.
   - `toMessageFields()` appends `tier` after `yearTo` when it is set. Earlier fields keep their order.
3. **Parsing** (`create` with `songCount`, and `start`). An optional `tier` field is accepted.
   - `"easy"` and `"hard"` are valid. `"normal"` is accepted and means null, because it equals today's draw.
   - Any other value, a non-string, or an explicit JSON `null` gives `bad-filter`.
   - The check order is the same as for the cycle 060 fields.
4. **Draw.**
   - `Room.start` narrows the list by `filter.matches(theme, anime) && filter.matchesRank(rank)`.
   - `available` counts the candidates left after both checks.
   - `settings` includes `tier` when it is set.

## Acceptance criteria (JUnit 5, from `backend/`)
1. **`DrawFilterTierTest`.**
   - `matchesRank` boundaries: 300 and 301 for easy; 500, 501 and 1500 for hard; null always true.
   - The three-argument constructor equals the four-argument one with a null tier.
   - `NONE` is the empty filter.
   - `isEmpty` is false when only the tier is set.
   - `toMessageFields` puts `tier` last.
2. **`RoomTierTest`.** Uses a generated `@TempDir` catalog with a draw set of 1,500 themes, all cached, and alternating OP/ED.
   - Over seeded draws, easy only ever draws ranks 1-300 and hard only 501-1,500.
   - Easy combined with `types:["ED"]` draws only ED themes ranked 1-300.
   - `available` is 150 for easy plus ED.
   - `settings` carries `tier`.
   - If the theme at rank 2 has no cached audio, the theme at rank 3 still counts as rank 3.
3. **`TierSocketTest`.**
   - `tier:"medium"`, `tier:1` and `tier:null` each give `bad-filter`.
   - `tier:"normal"` behaves exactly like an absent tier: no `settings` is sent, and the first message after start is `prepare`.
   - A lobby start with `tier:"hard"` sends `settings{tier:"hard"}` to all members.
4. All earlier backend tests pass unchanged, and `.\gradlew.bat test` exits 0.

## Test list
- `backend/src/test/java/com/songrush/game/DrawFilterTierTest.java`
- `backend/src/test/java/com/songrush/game/RoomTierTest.java`
- `backend/src/test/java/com/songrush/ws/TierSocketTest.java`
- Any helpers go inside those files. Catalogs are generated in `@TempDir`, never as static `.ogg` files.

## File ownership
- **Test writer:** the test list.
- **Implementer:** `game/DrawFilter.java`, `game/Room.java`, `ws/GameSocketHandler.java` and, if needed, `catalog/Catalog.java` (a rank lookup).

## Out of scope
- The frontend select (F-009).

## Review changes (spec round 1)
No Blocker or High. Added:
- **Socket test catalog size.** The `TierSocketTest` catalog has at least 510 themes, so a hard start (rank 501+, at least 5 songs) can succeed.
- **More rank boundaries.** `matchesRank(1501)` is false for hard, and rank 0 is false for every tier.
- **Catalog cost.**
  - Generated catalogs use 1-byte audio files and are built once per class, with a static `@TempDir` and `@BeforeAll`.
  - The rank-2-uncached case uses its own small catalog, so the "easy + ED = 150" count holds on the main catalog.
- **Constructor normalization.** The four-argument constructor maps `"normal"` to null and rejects any value other than `easy`, `hard` or null with `IllegalArgumentException`.
