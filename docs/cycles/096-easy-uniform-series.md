# 096: easy draws series uniformly (B-022)

From D-108 (owner, 2026-11-08). Cycle 094 drew easy games by shuffling themes and keeping the first theme of each unseen series group (design 088 option D2). The cycle 094 review simulated 20,000 ten-song easy games on the real catalog: the series with 85 themes appeared in 83% of games and the one with 73 themes in 79%, against about 13% each if series were equally likely. The owner chose uniform series.

## Goal

In easy, every series group of the matching pool is equally likely to be in a game; within a chosen group, every matching theme is equally likely.

## Behaviour

- `Room.start` with tier easy: group the matching candidates by series group (insertion order of first appearance in `catalog.candidates()`, so the draw is deterministic for a seeded `RandomGenerator`), shuffle the list of groups with the room's random (Fisher-Yates with `random.nextInt(i, size)` as today; Java 17), take the first N groups, and for each pick one of its themes with `random.nextInt(size)`. The song order is the order of the chosen groups.
- Too few groups: unchanged (`not-enough-songs`, `available` = distinct groups, `unit: "series"`).
- Normal and hard: unchanged.

## Acceptance criteria

1. On a fixture where one series group has many more matching themes than the others (for example one group of 40 themes and 19 groups of 1 theme each, N = 5), over 4,000 seeded easy games every group appears in between 18% and 32% of games (expected 25%), and the big group's share is within the same band.
2. Within a group, over many games every theme of the group is chosen at least once.
3. No easy game repeats a group; the existing easy tests (`RoomEasyNoRepeatTest`, `RoomTierTest`, `TierSocketTest`) still pass unchanged.
4. Normal and hard draws are unchanged (existing tests pass).
5. `.\gradlew.bat test` from `backend/` exits 0.

## Test list

- `backend/src/test/java/com/songrush/game/RoomEasyUniformTest.java` (new): criteria 1-2.

## File ownership

- Test writer: `RoomEasyUniformTest.java`.
- Implementer: `backend/src/main/java/com/songrush/game/Room.java`.

## Out of scope

Weighting by anything other than series; normal and hard draws.
