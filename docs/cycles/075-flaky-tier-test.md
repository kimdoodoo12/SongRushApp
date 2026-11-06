# 075 flaky-tier-test (follow-up from 072/074)

## Goal
Make `TierSocketTest.lobbyStartWithNormalSendsNoSettingsAndPrepareComesFirst` deterministic. It failed twice on GitHub Actions on unchanged code: runs 42042611967 and 42083199943, both passing on re-run.

## Cause (read from the code)
- `create` answers `joined`, then `roster` (both sent by `Lobby.join`), then `token`. The token is offered after the lobby call returns (`GameSocketHandler`).
- `ping` is answered at once on the socket thread.
- The test awaits `joined` and the roster, then pings, then takes `mark = received().size()`.
- So on a slow runner, `token` can arrive after the mark and be counted as "the first frame after start".

The game is correct. The test's mark is taken too early.

## Change
This is a test-only fix: the test waits for the host's `token` before it takes the mark. The fix goes into `createLobby` in `TierSocketTest`, so every caller is settled. The assertions stay the same. No production code changes.

Also check the other socket tests that take a `received().size()` mark right after a create or join, and make the same wait where the same race exists. Report each place.

## Acceptance
1. `.\gradlew.bat test` passes.
2. `TierSocketTest` passes 30 times in a row (`--tests` with a loop, or `@RepeatedTest` is not added).
3. The diff touches only test files under `backend/src/test/java/com/songrush/ws/`.
