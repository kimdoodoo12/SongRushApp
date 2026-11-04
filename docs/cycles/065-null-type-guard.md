# 065 null-type-guard (B-013)

## Goal
A catalog theme with no `type` can never break a draw. Today `DrawFilter.matches` calls `contains(null)` on an immutable `Set.copyOf` set, which throws `NullPointerException`. The exception would leave the lobby stuck as in-game (cycle 060 review, Medium).

## Behaviour
- `DrawFilter.matches(theme, anime)`: when `types` is set and `theme.type()` is null, the result is false. When `types` is null, a null-type theme still matches, as today.
- Nothing else changes.

## Acceptance criteria (JUnit 5, from `backend/`)
1. **`DrawFilterNullTypeTest`.**
   - `new DrawFilter(Set.of("OP"), null, null).matches(nullTypeTheme, anime)` is false and does not throw.
   - `DrawFilter.NONE.matches(nullTypeTheme, anime)` is true.
   - The test obtains `Theme` and `Anime` through a `@TempDir` catalog loaded by `CatalogLoader`, where one theme has no `type` key.
2. **`Room.start` with that catalog.**
   - With `types:["OP"]`, `start` does not throw.
   - It sends `not-enough-songs` with an `available` that excludes the null-type theme, or it draws only OP themes, depending on the count requested.
3. All earlier backend tests pass unchanged. `.\gradlew.bat test` exits 0.

## Constraint for this cycle (an upload to the live server is running)
- **Commands.** Run only `.\gradlew.bat test`. Do not run `build`, `bootJar` or `assemble`: they rewrite `backend/build/libs/*.jar`.
- **Files.** Do not touch `deploy/`, `frontend/` or `data/`.

## Test list
- `backend/src/test/java/com/songrush/game/DrawFilterNullTypeTest.java`

## File ownership
- **Test writer:** the test file.
- **Implementer:** `backend/src/main/java/com/songrush/game/DrawFilter.java`.

## Out of scope
- The other follow-ups.

## Result
- Tests `808fbec` (locked), fix `844d309` (attempt 1). No build ran during the live upload; only `gradlew test` was used.
- Red phase (verifier): 3 of the 6 new tests failed. The failures came from the `NullPointerException` at `ImmutableCollections$Set12.contains`, through `DrawFilter.matches` and `Room.start`. The other 45 suites passed.
- Verifier: `cleanTest test` exits 0 with 46 suites and 366 tests, 0 failures. The diff touches only `DrawFilter.java` (+3/-2).

## Code review (critic)
No findings. Confirmed:
- the short-circuit `theme.type() == null ||` avoids `contains(null)`;
- `NONE` still matches a theme with a null type;
- the reveal message for such a theme is built with `LinkedHashMap` (null-safe), and the frontend shows a null `themeSlug` as an empty line.

A nit for later: no test draws a null-type theme under `NONE` through to the reveal.
