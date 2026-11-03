# 044 pause-empty (B-005c)

## Goal
When every player of a running game has disconnected, the game stops using up songs. The first player who reconnects within the grace period gets the current song prepared again (design 034 H3; split from B-005b in cycle 042).

## Interface (package `com.songrush.game`)
- `Room`. Its locked cycle 036 and 042 tests must still pass. It gains:
  - `void pause()`.
    - In PREPARE, PLAYING or REVEAL it cancels the pending timer and sets `paused()` to true. The phase and song index stay as they are, and nothing is sent.
    - In LOBBY, or when already paused, it does nothing.
  - `void resume()`. When paused, it clears the flag and continues:
    - from PREPARE or PLAYING, it prepares the **same** index again: a new audio token, a new `prepare`, and the ready set cleared;
    - from REVEAL, it continues as if the reveal pause had just ended. That means the next song's `prepare`, or `gameOver` after the last song.
    - When not paused, it does nothing.
  - `boolean paused()`.
  - While paused:
    - `ready` and `chat` are ignored;
    - `currentSong()` returns `null`;
    - any timer that was already queued does nothing, because the existing stale-task guard also checks the paused flag.
  - Scores are kept across a pause.
- `Lobby`. Its locked cycle 040 and 042 tests must still pass.
  - **Pause.** When a disconnect leaves no connected player and a game is running, the lobby calls `room.pause()`. Grace expiry and `leave` follow the same rule.
  - **Resume.** A `reconnect` of a member in grace that finds the room paused proceeds in this order:
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
   - **Pause and resume.** A 2-player game where both disconnect during PLAYING pauses the room: no further `prepare`/`start`/`reveal` is sent after the second disconnect, even 10 minutes of advanced time later (within `EMPTY_TTL`: use a time below 2 min). A `reconnect` within the grace period sends that player `joined`, `snapshot` (`song:null`) and `roster`, then a `prepare` for the same index.
   - **Disconnect then leave.** One player disconnects and the other sends `leave`. The room pauses as well.
   - **No resume.** If nobody reconnects, the lobby closes at `EMPTY_TTL` after the last disconnect and sends nothing else.
   - **One player left.** With one player still connected, a disconnect does not pause.
3. **No regression.** All earlier tests pass unchanged. `.\gradlew.bat test` and `.\gradlew.bat build` exit 0.

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
