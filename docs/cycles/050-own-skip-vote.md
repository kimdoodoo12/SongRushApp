# 050 own-skip-vote (B-008)

## Goal
The skip button never shows the wrong state. The server marks a rate-limited skip as belonging to the skip. The client then works out its own vote from its clicks minus the skips that were refused, and no longer guesses from the order in which `skipState` and errors arrive (cycle 048 review M1).

## Why not a per-player `skipState`
The locked cycle 045 tests pin `skipState` as one broadcast with exactly the keys `type, index, votes, needed`. Making it per-player would rewrite them. A tag on the error is enough: every `skipVote` the server accepts for judging produces either a count change or exactly one `rate-limited` error. Ignored skips (wrong phase or index, watcher, paused) produce no message.

## Behaviour
- **Server** (`game/Room.java`). A `skip` refused for lack of a token sends the sender `error{code:"rate-limited", for:"skipVote"}`. A chat refused for lack of a token keeps `error{code:"rate-limited"}` without `for`.
- **Client** (`frontend/src/game/session.js`, `state.js`).
  - The own vote for the current song is the parity of the clicks sent minus the `rate-limited` errors that carry `for:"skipVote"`. It resets on `prepare` and on a rejoin snapshot.
  - An error without `for` never changes the vote.
  - `skipState` updates only the count, and never touches the own vote.

## Changes to locked earlier tests (declared)
- `frontend/src/App.multi.test.jsx`, test "a rate-limited error after a click undoes the 스킵 취소 label": the delivered error gains `for: 'skipVote'`. Nothing else changes.
- The test "a rate-limited error after the next skipState does not undo the counted vote" stays as it is. Its error has no `for`, so it is a chat refusal and must not undo.
- `backend/src/test/java/com/songrush/game/RoomRoundsTest.java`, test `aSkipSpendsAChatTokenAndWithoutOneIsRateLimitedAndNotCounted` (spec review B1): its rate-limited assertion for the refused `skip` becomes an exact check of `{type:"error", code:"rate-limited", for:"skipVote"}` sent only to the sender; the shared `KEYS` entry for `error` and the chat-path `assertRateLimited` stay as they are (they now also guard that chat refusals carry no `for`).
- No other locked test may change.

## Acceptance criteria
1. **Backend** (`RoomSkipErrorTest`, cycle 036 fakes):
   - A rate-limited `skip` gives the sender exactly `{type:"error", code:"rate-limited", for:"skipVote"}`.
   - A rate-limited chat gives exactly `{type:"error", code:"rate-limited"}`.
   - Neither message goes to anyone else.
   - The earlier backend tests pass with only the declared change, and `.\gradlew.bat test` exits 0.
2. **Frontend** (`App.skipVote.test.jsx`, locked fakes):
   - Two quick clicks where only the second is refused: the client receives `skipState{votes:1}` and then an error with `for:"skipVote"`, and the label ends at `스킵 취소` (one vote counted).
   - Someone else's `skipState` arriving between my click and my refusal still undoes my toggle.
   - A chat `rate-limited` error (no `for`) after a click leaves `스킵 취소`.
   - Three clicks with the second refused: the label shows `스킵` (votes sent 3, refused 1, parity even).
   - `prepare` resets the label to `스킵`.
3. With the declared change, `npm test`, `npm run lint` and `npm run build` exit 0.

## Review changes (spec round 1)
- **Blocker.** A locked cycle 045 test pins the skip refusal's exact keys; it is declared and changed.
- **Medium.** B-008's backlog acceptance is updated to this design.

## Test list
- `backend/src/test/java/com/songrush/game/RoomSkipErrorTest.java`
- `frontend/src/App.skipVote.test.jsx`
- The declared one-field change in `frontend/src/App.multi.test.jsx` and the declared assertion in `backend/src/test/java/com/songrush/game/RoomRoundsTest.java`.

## File ownership
- **Test writer:** the files in the test list.
- **Implementer:** `backend/src/main/java/com/songrush/game/Room.java`, `frontend/src/game/session.js` and `frontend/src/game/state.js`.

## Out of scope
- Showing who voted.
- Restoring the own vote after a reconnect: the server clears it on reconnect (cycle 045).
