# 046 late-joiners (B-006c)

## Goal
Someone can join while a game is running. They watch the current song and join the game at the next `prepare` with 0 points (D-059). This covers both a new player and a member who was in grace when the game started (the cycle 042 spectator).

## Interface (package `com.songrush.game`)
- **`Room`**
  - `void addPlayer(PlayerRef player)`.
    - Adds a **pending** player. A known id, or an id that is already pending, is ignored.
    - A pending player is not in the ready set or the skip count, and gets no row in `scores`, `scores()` or the ranking.
    - At the next `prepare(index)` every pending player becomes a full player: score 0, connected (or not, as last set), keeping their chat bucket (spec review N1), and placed after the existing players in player order.
    - `prepare` runs for the next song, for a `resume`, and for a new game's first song.
    - In LOBBY, `addPlayer` is ignored, because the lobby builds a new `Room` per game.
  - `setConnected` also accepts pending ids. It records their state, which takes effect only when they are promoted: a pending player's connection never changes the ready rule, `connectedCount` or `skipState.needed` (spec review M4).
  - `boolean isPlayer(String id)` is true for promoted players only (spec review H2).
  - **Chat from a pending player (watcher)** spends a token from its own bucket (starting full) and never scores. In PREPARE and PLAYING **every** watcher chat reaches only its sender, as `chat{name, text}`, match or miss; in REVEAL everyone gets it (spec review M1: judging a watcher's chat would let a near-miss spelling through and give a second tab a match/miss oracle; a watcher cannot score anyway).
    - A watcher's `ready` and `skip` are ignored.
- **`Lobby`**
  - **`join` during a game** is now allowed. The checks run in this order: closed, then nickname, then room full. During a game the cap counts members who are connected or in grace (members who left or whose grace expired wait only for removal at the return, spec review M3); in LOBBY every member counts, as before (D-046).
    - The joiner becomes a member, connected and not in grace.
    - The joiner gets `joined{code, name}` and then the `snapshot` (cycle 042 shape, `phase:"playing"`). Then everyone gets `roster`.
    - Then `room.addPlayer(PlayerRef(id, name))` is called.
    - The `in-game` error remains only for `start` during a game.
  - **A grace `reconnect` of a member who is not a Room player** (the cycle 042 spectator) while a game runs calls `room.addPlayer` the same way, after `joined`, `snapshot` and `roster`.
  - **Room players.** The lobby drops its own `roomPlayers` mirror and asks the room instead: a Room player is `room != null && room.isPlayer(id)` (spec review H2).
  - **Connections.** During a game every member's `disconnect`, `leave` and grace `reconnect` is passed to `room.setConnected` (the room ignores ids it does not know), so a watcher who leaves before promotion is promoted as disconnected and not waited for (spec review H1).
  - **Until promotion.** A pending player does **not** count for the cycle 044 pause, resume and abandon rules. Those rules stay as locked: a connected watcher neither keeps a game running nor resumes it.
- **Snapshot and roster.** These are unchanged in shape. A pending player appears in `roster` like any member, but not in `scores` until promoted.

## Changes to locked earlier tests (declared; the test writer changes only these)
- **`LobbyTest.startAndJoinDuringAGameGetInGame`.**
  - Before: a `join` during a game gets `in-game` and `names()` stays two.
  - After: that join succeeds. P3 gets `joined` and `snapshot`, and `names()` lists the three players.
  - The `start`-during-game `in-game` part stays.
  - The nickname-before-anything check stays.
  - The test may be renamed.
- **`LobbyReconnectTest.memberInGraceAtStartSpectatesAndCannotScore`.**
  - Before: the spectator's correct-title chat after `startAt` produces no message.
  - After: it reaches only the spectator, as `chat{name, text}`. Nobody else gets anything, and nobody scores.
  - The rest stays.
- No other locked test may change.

## Deviation from B-006c and design 034 M2
Both say a watcher's chat is "broadcast but not judged". Broadcasting it would let a watcher who knows the answer announce it to the players. This cycle shows a watcher's chat only to the watcher until the reveal; B-006c's text is updated accordingly.

## Acceptance criteria (JUnit 5, from `backend/`)
1. **`RoomLateJoinTest`** (cycle 036 fakes, unchanged):
   - **Joining mid-song.** A player added in PLAYING of song 0 is not counted in `skipState.needed`. Their `ready` and `skip` are ignored. A correct chat from them reaches only them, both before and after `startAt`, and does not score. Their miss reaches only them as well (watcher chat is sender-only until the reveal). They have their own rate limit.
   - **Promotion.** At the next `prepare` they are in the ready set (the song waits for their `ready`, if connected), in `skipState.needed`, and in `scores` with 0 points, after the existing players. They score from then on, and the ranking includes them.
   - **Disconnected while pending.** A pending player marked disconnected before promotion is promoted as disconnected and not waited for; before promotion that `setConnected(false)` changes neither `skipState.needed` nor the ready wait.
   - **Watcher chat.** In PREPARE and PLAYING both a match and a miss from a watcher reach only the watcher; in REVEAL a watcher's chat reaches everyone.
   - **Bucket kept.** A watcher who used 5 tokens and is promoted at once still has no token left.
   - **Ignored adds.** `addPlayer` of a known or already pending id, and `addPlayer` in LOBBY, do nothing.
   - **Last song.** A player added during the last song's PLAYING is never promoted. `gameOver` ranks only the players.
   - **Pause.** A player added while paused is promoted by `resume`'s `prepare`.
2. **`LobbyLateJoinTest`** (cycle 036 fakes, unchanged):
   - **Joining mid-game.** A `join` during a game sends that player `joined`, then `snapshot` with `phase:"playing"` and the current `song`, then a roster to everyone with the new name. At the next song's `prepare` they become a Room player: their `ready` is waited for, and their correct answer scores.
   - **Room full.** The 9th member gets `room-full` even during a game; when one of 8 leaves during the game, a new `join` succeeds.
   - **Leaves before promotion.** A watcher who `leave`s before the next song is promoted as disconnected: the next song starts on the Room players' `ready` without waiting for the timeout.
   - **Disconnect and reconnect before promotion.** A watcher who disconnects and reconnects within the grace is promoted connected and is waited for.
   - **Next game.** A player who joins during the last song is not in that game's ranking; after the return to the lobby they are a normal member and are a Room player of the next game.
   - **Spectator reconnect.** A member in grace at `start` who reconnects during song 0 becomes a Room player at song 1's `prepare`.
   - **Watcher and pause.** A connected late joiner does not prevent the pause when every Room player has disconnected. While paused, that joiner's `reconnect` cannot resume.
   - **No `toAll`.** The no-`toAll` `@AfterEach` check is included.
3. **Socket** (`LateJoinSocketTest`, reusing the cycle 037 `TestClient` and `WsFixture` unchanged):
   - A third client joins with the code after the host started a game. It gets `joined` and a `snapshot` with `phase:"playing"`, then the next `prepare`.
4. **No regression.** All earlier tests pass with only the declared changes. `.\gradlew.bat test` and `.\gradlew.bat build` exit 0.

## Review changes (spec round 1)
- **High.** Every connection change of every member reaches the room during a game; the lobby asks `room.isPlayer` instead of keeping a mirror updated from intercepted `prepare` messages.
- **Medium.** Watcher chat is sender-only until the reveal (no near-miss leak, no oracle); the deviation from B-006c and design 034 is recorded; during a game the cap counts connected or in-grace members; pending disconnects do not change counts.
- **Nit.** Promotion keeps the chat bucket.

## Test list
- `backend/src/test/java/com/songrush/game/RoomLateJoinTest.java`: criterion 1.
- `backend/src/test/java/com/songrush/game/LobbyLateJoinTest.java`: criterion 2.
- `backend/src/test/java/com/songrush/ws/LateJoinSocketTest.java`: criterion 3.
- The two declared assertion changes in `LobbyTest` and `LobbyReconnectTest`.

## File ownership
- **Test writer:** the three new files and the two declared changes.
- **Implementer:** `game/Room.java` and `game/Lobby.java`. The socket handler needs no change, because `join` already forwards.

## Out of scope
The client UI for watching (F-005).

## Code review round 1 and attempt 2
- **High (critic).** During a game the cap counts only connected or in-grace members, and members who `leave` stay until the return; one socket repeating `join`/`leave` grows the member list (and, across a song boundary, the Room players, `scores` and ranking) without bound, enlarging every `roster` until other players' outbound queues overflow (before this cycle, joins during a game were rejected).
- **Added rule.** During a game the lobby also refuses a `join` with `room-full` when it already holds `2 * MAX_PLAYERS` (16) members of any state (connected, in grace, left or expired). In LOBBY the existing 8-member cap is unchanged.
- **Added criterion 2b (`LobbyJoinChurnTest`).** In a running game with 3 connected players, 100 rounds of `join` + `leave` by new ids keep `names().size()` at most 16, the joins beyond that get `room-full`, and after `gameOver` the returned lobby holds only the still-connected members.

## Result
- Attempt 1: tests `04264c0` (locked; the two declared locked-test changes included), implementation `db4b974`; verifier green, critic High H1 (unbounded members through join/leave churn during a game).
- Attempt 2: rule added to the spec (`f0e10ba`), new test `LobbyJoinChurnTest` `c24aa0a` (4 of 5 red on attempt 1 for the right reason: no `room-full` at 16 members), implementation `4f93f0a`.
- Verifier (attempt 2): `cleanTest test` and `build` exit 0; 262 tests, 0 failures (RoomLateJoinTest 20, LobbyLateJoinTest 9, LateJoinSocketTest 1, LobbyJoinChurnTest 5); the feat commit changes no test.

## Code review (critic, attempt 2)
H1 fixed: members, Room players and watchers, roster, snapshot, scores and ranking rows are all bounded by 16 during a game; the lobby returns to at most 8 members; grace rejoins are not affected by the cap.
- **M1 (follow-up, existing since cycle 042).** A `join` followed at once by `leave` can leave a stale rejoin token and send one token frame after the leave.
