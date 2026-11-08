# 094: nested tiers by anime rank, no repeats in easy (B-021)

From S-007, design `docs/design/088-songs-3000-covers.md`, decisions D-105, D-106 and D-107. Replaces D-069's ranges (easy theme ranks 1-300, hard 501-1,500, normal all) in `DrawFilter` (`backend/src/main/java/com/songrush/game/DrawFilter.java:28-33,73-83`).

## Goal

Tiers cut by anime popularity rank and nest from the top: easy = anime ranks 1-100, normal (also when no tier is sent) = 1-300, hard = every draw-set anime; easy never repeats a series group within a game.

## Behaviour

- **Anime rank.** `Catalog.animeRank(int themeId)`: the 1-based rank of the theme's anime, counting distinct anime in `drawSet` order by first appearance (the draw set sorts by anime popularity, so each anime's themes are contiguous); 0 when the theme is not in the draw set. Computed once when the catalog is built, from `drawSet` alone, whether the theme is cached or not (like `rank`).
- **Tiers** (`DrawFilter`): constants `EASY_LAST_ANIME = 100`, `NORMAL_LAST_ANIME = 300`. The tier field still accepts `easy`, `normal` (mapped to no tier) and `hard`, and still rejects anything else. Matching uses the anime rank: easy `1..100`, no tier (normal) `1..300`, hard any rank `>= 1`. Types and year filters combine with the tier as today. The old theme-rank constants and `matchesRank(int)` are replaced (`matchesRank` may keep its name if it now takes the anime rank; the tests pin the behaviour).
- **Settings message.** Unchanged: `tier` appears only when `easy` or `hard` is set.
- **Easy draw.** The matching candidates are shuffled with the room's `RandomGenerator` (as today), then walked in order, keeping the first theme of each series group not yet kept, until N songs. If the pool has fewer distinct groups than N, everyone gets `not-enough-songs` with `available` = the number of distinct groups and `unit` = `"series"`.
- **Normal and hard draw.** As today: a uniform draw of N distinct themes; `not-enough-songs` carries `available` = matching songs and no `unit` field.

## Acceptance criteria

1. `Catalog.animeRank` returns 1-based anime ranks by first appearance in `drawSet`, the same value for every theme of an anime, and 0 for a theme outside the draw set.
2. Easy draws only themes whose anime rank is 1-100; no tier and `normal` draw only anime ranks 1-300; hard can draw any draw-set anime, including ranks above 300.
3. Over many seeded easy games, no game has two songs of the same series group.
4. Easy with fewer distinct groups than N answers `not-enough-songs` with `available` = distinct groups and `unit: "series"`; normal and hard answer with `available` = songs and no `unit`.
5. Type and year filters still combine with every tier; `settings` still carries `tier` only for easy and hard; an unknown tier is still `bad-filter`.
6. `.\gradlew.bat test` from `backend/` exits 0.

## Test list

- `backend/src/test/java/com/songrush/catalog/CatalogAnimeRankTest.java` (new): criterion 1.
- `backend/src/test/java/com/songrush/game/DrawFilterTierTest.java` and `RoomTierTest.java` (existing; rewrite their range assertions for the new tiers): criteria 2, 4 (normal/hard), 5.
- `backend/src/test/java/com/songrush/game/RoomEasyNoRepeatTest.java` (new): criteria 3 and 4 (easy).
- Any other existing test that pins the old ranges or the old default (all songs when no tier) is updated by the test writer; list them in the cycle doc.

## File ownership

- Test writer: the test files above and any other existing test that pins the old tier ranges.
- Implementer: `backend/src/main/java/com/songrush/catalog/Catalog.java` (and its builders if needed), `game/DrawFilter.java`, `game/Room.java`.

## Out of scope

Frontend wording for `unit: "series"` (F-022). Prefetching ranks 1,501-3,000 (I-012).
