# 139 song-count (B-040/F-040, from D-137)

## Goal
The host can choose 5 to 100 songs and type the number directly.

## Plan
- Server: `Room.MAX_SONGS` 30 → 100 (backend/src/main/java/com/songrush/game/Room.java:54-56; checked in `Room.start` :371 and `Lobby` :424). Too few drawable songs still gives `not-enough-songs`.
- Client: `frontend/src/components/LobbyScreen.jsx:9-10, 77-88`: `MAX_SONGS` 100; the stepper's middle value becomes an editable number for the host (an `<input type="number" inputmode="numeric">` with the same label `곡 수`, min 5, max 100); typing commits on blur or Enter, clamps to 5..100 and ignores non-numbers (reverts to the current value); −/+ stay. Guests see the value read-only as today. The look stays the mockup's: any new rule goes in an approved stylesheet (`start.css` or `wide.css`; `room.css` is never edited, D-124), so the input looks like the current number.
- CLAUDE.md "Product decisions": "the host picks N (5-30, default 10)" → 5-100 (D-137).

## Acceptance criteria
1. JUnit: a start with 100 songs (enough drawable) is accepted; 101 and 4 are refused with `bad-song-count`; existing tests that pinned 30 as the maximum are updated (listed in the Result).
2. Vitest: the host types 50 and presses Enter: the lobby setting shows 50 and the start sends `songCount: 50`; typing 200 gives 100, 2 gives 5, letters revert; −/+ still step by one and stop at 5 and 100; a guest cannot edit.
3. `room.css` unchanged; `./gradlew test` and `npm test`, lint, build pass.

## Out of scope
- Presets; the draw's own limits (not-enough-songs stays).

## Result
- Implementation and tests: commit `26abf91`, first attempt. `Room.MAX_SONGS` 100 (`Lobby` uses it). The host's stepper middle is an `<input type="number">` (commit on Enter or blur, rounded, clamped to 5..100, non-numbers revert); guests keep the read-only output; the input's look is in `start.css` (pinned by `startCss.test.js`); `room.css` unchanged. New `RoomSongCountTest` (100 accepted, 101 and 4 refused, 99 drawable gives not-enough-songs) and `App.songCount.test.jsx` (7 cases). Existing tests moved from 30/31 to 100/101: `RoomTest`, `LobbyTest`, `LobbySettingsTest`, `LobbySettingsSocketTest` (its "bad count" 99 is now valid, so 101), `App.flow`, `App.lobby`, `App.lobbyport` (the host's control is now an INPUT); `testing/lobby.js` reads the input. CLAUDE.md says 5-100 (D-137).
- Review, run by the verifier with `--rerun-tasks` (one `EOFException` rerun): backend 1301 tests, 0 failures, 0 errors, 1 skipped; frontend 60 files, 909 tests passed; lint and build exit 0. Criteria 1 to 3 met; no assertion weakened beyond the bound.
- Left: the solo path's own cap (`GameSocketHandler.java:59-61`, 30) is unused by the client; Follow-ups.
- Merged straight to `dev` (D-134).
