# 044 pause-empty (B-005c)

## Goal
When every player of a running game has disconnected, the game stops using up songs. The first player who reconnects within the grace period gets the current song prepared again (design 034 H3; split from B-005b in cycle 042).

## Interface (package `com.songrush.game`)
- `Room`. Its locked cycle 036 and 042 tests must still pass. It gains:
  - `void pause()`.
    - In PREPARE, PLAYING or REVEAL it cancels the pending timer, increments the generation (so every task scheduled before the pause stays stale forever, spec review N1) and sets `paused()` to true. The phase and song index stay as they are, and nothing is sent.
    - In LOBBY, or when already paused, it does nothing.
  - `void resume()`. When paused, it clears the flag and continues:
    - from PREPARE or PLAYING, it prepares the **same** index again: a new audio token, a new `prepare`, and the ready set cleared;
    - from REVEAL, it continues as if the reveal pause had just ended. That means the next song's `prepare`, or `gameOver` after the last song.
    - When not paused, it does nothing.
  - `boolean paused()`.
  - While paused:
    - `ready` and `chat` are ignored;
    - `currentSong()` returns `null`;
    - any timer that was already queued does nothing (the generation changed).
  - Scores are kept across a pause.
- `Lobby`. Its locked cycle 040 and 042 tests must still pass.
  - **Pause.** A `disconnect`, or a `leave` by a connected member, that leaves no connected **Room player** (a member who is in the current game's `Room`) during a game calls `room.pause()`. Connected spectators (members who were in grace when the game started, cycle 042) do not keep a game running (spec review M1). The empty-lobby timer keeps counting all connected members, as before.
  - **Resume.** A `reconnect` of a **Room player** in grace that finds the room paused proceeds in this order (a spectator's reconnect sends only `joined`, `snapshot` and `roster` and never resumes):
    1. it sends that player `joined`, then the `snapshot`, which has `song:null` because the room is paused;
    2. it sends everyone the `roster`;
    3. it calls `room.resume()`, which sends the `prepare`.
  - A replacement reconnect (cycle 042) never finds the room paused, because a connected member exists.
  - **Empty TTL.** `EMPTY_TTL` still runs from the last disconnect. A paused game whose players never return closes with the lobby.

## Acceptance criteria (JUnit 5, from `backend/`)
1. **`RoomPauseTest`** (cycle 036 fakes, unchanged).
   - **Pause in PLAYING.** Advancing the clock past the old deadline, and well beyond it, sends nothing, and `songIndex()` is unchanged. `resume()` then sends a `prepare` for the same index with a different `audioUrl`. After `ready`, the song starts with a fresh `startAt`.
   - **Pause in PREPARE.** The prepare timeout does not fire while paused. `resume()` re-prepares the same index.
   - **Pause in REVEAL.** The reveal pause does not fire. `resume()` prepares the next index at once. On the last song, `resume()` sends `gameOver`.
   - **While paused.**
     - `ready` and a correct-answer `chat` are ignored: no score and no message.
     - `currentSong()` is `null`.
     - `pause()` twice and `resume()` twice are harmless.
     - `pause()` in LOBBY does nothing.
     - Scores from before the pause are kept.
2. **`LobbyPauseTest`** (cycle 036 fakes, unchanged).
   - **Pause and resume.** A 2-player game in PLAYING of song 0: advance until `deadlineAt - 10 s`, then both players disconnect. Advance `GRACE - 1 ms` (past the old deadline and a following reveal pause): no `start`/`reveal`/`scores`/`prepare`/`gameOver` is sent and the phase and song index are unchanged. Then `reconnect(P1)` sends P1 `joined`, `snapshot` (`song:null`) and the `roster`, then everyone connected gets a `prepare` with the same `index` and a different `audioUrl`, in that order.
   - **Spectators.** A member in grace when the game started reconnects (a spectator); then both Room players disconnect: the room pauses although the spectator is connected. While paused, a spectator's reconnect (after a second disconnect) does not resume; a Room player's reconnect does.
   - **Last song.** Pausing during the last song's REVEAL and reconnecting a Room player sends `joined`, `snapshot`, `roster`, then `gameOver` and `roster{phase:"lobby"}`; members still in grace stay, members whose grace expired are removed.
   - **Disconnect then leave.** One player disconnects and the other sends `leave`. The room pauses as well.
   - **No resume.** If nobody reconnects, nothing is sent up to `EMPTY_TTL - 1 ms` after the last disconnect and the lobby is still open; 1 ms later it closes.
   - **One player left.** With one player still connected, a disconnect does not pause.
3. **No regression.** All earlier tests pass unchanged. `.\gradlew.bat test` and `.\gradlew.bat build` exit 0.

## Review changes (spec round 1)
- **High.** The pause-and-resume criterion could not be met (the fixture songs are longer than the 30 s grace); it now disconnects 10 s before the deadline.
- **Medium.** Pausing and resuming depend on Room players, not spectators; the last-song resume path through `gameOver` is tested at the lobby level.
- **Nits.** `pause()` bumps the generation instead of relying on a flag check; only `disconnect` and a connected member's `leave` can trigger the pause.

## Test list
- `backend/src/test/java/com/songrush/game/RoomPauseTest.java`: criterion 1.
- `backend/src/test/java/com/songrush/game/LobbyPauseTest.java`: criterion 2.

## File ownership
- Test writer: the two new test files.
- Implementer: `game/Room.java` and `game/Lobby.java`.

## Out of scope
- Socket changes. The handler already maps a close to `disconnect` and a `rejoin` to `reconnect`.
- B-006b rules.
- Client behaviour (F-005).

## Code review round 1 and attempt 2
- **High (critic).** With a connected spectator, a paused game whose Room players all lose their grace (or `leave`) can never resume, never reaches `gameOver`, and the lobby never closes (the empty timer counts the spectator; the idle timer ignores running games).
- **Added rule.** While paused, when no Room player is connected or in grace any more (the last one's grace expires, or the last one in grace sends `leave`), the lobby abandons the game: it drops the `Room` (nothing more from it is delivered), clears the Room players, removes members whose grace expired, returns to LOBBY, sends `roster{phase:"lobby"}` to the connected members, arms the idle timer, and leaves an already running empty timer untouched (it is not re-armed, so the close time does not move). No `gameOver` is sent.
- **Added criterion 2b (`LobbyAbandonTest`).** A spectator is connected; both Room players disconnect (pause); after `GRACE` the spectator receives `roster{phase:"lobby"}` with only themselves, `lobby.phase()` is LOBBY, the host can start again, and with nobody joining the lobby closes at `IDLE_TTL`. Variant: one Room player leaves while the other is in grace, then that grace expires — same result. Without a spectator, the existing empty-timer close still applies (no change).
- **Test conflict (attempt 2).** The attempt-1 test `LobbyPauseTest.ifNobodyReconnectsNothingIsSentAndTheLobbyClosesAtTheEmptyTtl` asserted the game still paused (`PLAYING`) at `EMPTY_TTL - 1 ms`; under the added rule the game is abandoned once the last grace expires, so that one assertion now expects `LOBBY`. The lead unlocked the tests for this single-line change, made by the test writer, and locked them again; every other assertion in that test is unchanged.

## Result
- Attempt 1: tests `77f8308` (locked), implementation `478c4db`; verifier green (184 tests), but the critic found High H1 (a connected spectator could keep a paused game alive forever).
- Attempt 2: abandon rule added to the spec (`5247893`), new test `LobbyAbandonTest` `42210d5` (red on attempt 1 for the right reason: no lobby roster, phase still PLAYING), one conflicting attempt-1 assertion corrected `b744b9a`, implementation `0764843`.
- Verifier (attempt 2): `cleanTest test` and `build` exit 0; 190 tests, 0 failures (LobbyAbandonTest 6, LobbyPauseTest 6, RoomPauseTest 16); no test change in the feat commit; exactly one changed test line between `42210d5` and `b744b9a`.

## Code review (critic, attempt 2)
H1 fixed on every path (grace expiry, leave of the last player in grace, leave of a connected Room player, a spectator's reconnect cancelling the empty timer); the abandon cannot fire while a Room player is connected or in grace; the dropped Room sends nothing more.
- **M1 (follow-up).** The connected-leave abandon path has no pinning test.
- **M2 (to F-005).** The current client ignores a lobby roster that arrives without `gameOver`.
- **Nit (follow-up).** That leave path sends a `playing` roster just before the `lobby` roster.
