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
