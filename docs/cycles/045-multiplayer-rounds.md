# 045 multiplayer-rounds (B-006b)

## Goal
With several players in a room:
- the song starts once every **connected** player is ready;
- a skip passes on a majority of connected players;
- chat is rate-limited;
- before `startAt` no chat reveals whether a guess matched.

Decisions covered: D-012, D-017, D-024, D-061 (design 034 M1 and M5; cycle 036 review M1).

## Scope
- **Split.** Late joiners (D-059) move to a new item **B-006c**: a player joining a running game spectates and joins the `Room` at the next `prepare`. It needs players added to a running `Room`, which is its own cycle.
- **Snapshot.** The skip vote is not added to the reconnect snapshot (the locked cycle 042 tests pin its exact keys). Instead every reconnect of a Room player, including a socket replacement, removes that player's skip vote, so a reconnected client always starts with no vote and a toggle never cancels a vote it cannot see (spec review H1).

## Interface changes (package `com.songrush.game`)
- **`Room`** (the locked cycle 036, 042 and 044 tests must still pass, except the two assertions listed under Test changes)
  - `void setConnected(String playerId, boolean connected)` marks a Room player's connection (an unknown id is ignored). All players start connected.
    - Disconnecting removes that player from the ready set and removes their skip vote.
    - It then re-evaluates the ready rule and the skip rule.
    - Reconnecting just marks them connected.
  - **Ready.** In PREPARE the song starts when every connected Room player is ready. With no connected Room player nothing starts; the lobby pauses the room in that case (cycle 044).
  - `void clearSkipVote(String playerId)` removes the player's vote if present and then re-sends `skipState` (no message when there was no vote).
  - `void skip(String playerId, int index)`
    - Ignored unless the phase is PLAYING (before or after `startAt`: a majority may skip a song before it is audible), the index is the current one, the room is not paused, and the player is a connected Room player.
    - It spends a token from the player's chat bucket (spec review M4); without a token it is dropped and only the sender gets `error{code:"rate-limited"}`.
    - It toggles the player's vote and sends everyone `skipState{index, votes, needed}`, where `needed = floor(connected / 2) + 1`.
    - When `votes >= needed`, the song ends at once with no winner, as at the deadline. The last `skipState` (with `votes >= needed`) sent just before the `reveal` tells clients the song was skipped; the `reveal` itself is unchanged (spec review B1: the locked cycle 036 test pins its exact keys).
    - Votes reset on every new song.
    - A connection change during PLAYING re-sends `skipState` if votes were present, and passes the skip if the threshold is now met.
  - **Chat rate limit (D-024).**
    - Each Room player has a token bucket with capacity 5 that refills at 2 tokens per second, measured with the injected clock in milliseconds; fractional refill carries over (tokens = min(5, tokens + elapsedMs * 2 / 1000) at each use, keeping the fraction). It starts full.
    - A chat that finds no token is dropped unjudged, and only the sender gets `error{code:"rate-limited"}`. This includes a correct answer: it does not score and is not shown.
    - The check runs before every other chat rule except unknown or `null` input.
  - **Before `startAt`** (in PREPARE, or in PLAYING with `now < startAt`), every judged chat goes only to its sender, as `chat{name, text}`, whether it matched or not. It never scores. From `startAt` on, misses go to everyone, as before.
- **`Lobby`**
  - Calls `room.setConnected(id, false)` on `disconnect` and on a connected Room player's `leave`.
  - On a grace `reconnect` of a Room player: after `joined` and `snapshot`, it calls `room.setConnected(id, true)` and `room.clearSkipVote(id)`, then any `resume` (cycle 044).
  - On a replacement `reconnect` (member still connected): after `joined` and `snapshot`, it calls `room.clearSkipVote(id)`.
  - Forwards `skip(playerId, index)` for connected members.
- **Socket** (`ws/GameSocketHandler`): `skipVote{index}` (the name in design 034) forwards to `lobby.skip`. A non-integer or missing `index` gives `bad-message`. With no room, the reply is `no-room`.

## Test changes to locked earlier tests
- `RoomTest.matchingChatBeforeStartAtReachesOnlyTheSenderAsPlainChat` (cycle 036): the two "miss before `startAt`" assertions now expect the miss to reach only its sender, not everyone. These are the `toAll` checks for "not the answer at all" in PREPARE and "still guessing" before `startAt`.
- The test writer may change only those assertions and the test name or description. The lead unlocks for this and records it.
- No other locked test may change. If another one breaks, the implementation is wrong.

## Acceptance criteria (JUnit 5, from `backend/`)
1. **`RoomRoundsTest`**, using the cycle 036 fakes unchanged.
   - **Ready.**
     - With 3 players, after one disconnects in PREPARE, the other two `ready` and the song starts without waiting for the timeout.
     - A disconnect that leaves every remaining connected player ready starts the song immediately.
     - A player who reconnects in PREPARE is counted again.
   - **Skip.**
     - With 3 connected players: one vote gives `skipState{votes:1, needed:2}`, and a second vote sends `skipState{votes:2, needed:2}` and then ends the song with `reveal.winner:null`.
     - A skip in PLAYING before `startAt` counts.
     - Voting again toggles the vote off.
     - A skip in PREPARE or REVEAL, with the wrong index, from an unknown or disconnected player, or while paused is ignored.
     - `clearSkipVote` removes a vote and re-sends `skipState`; without a vote it sends nothing.
     - Votes reset on the next song.
     - With 4 players, 2 votes is not enough (`needed` is 3). After one non-voter disconnects, `needed` becomes 2 and the skip passes. After a voter disconnects, their vote is removed and the new `skipState` is sent.
   - **Rate limit.**
     - 5 chats in an instant all go through and the 6th gets `rate-limited` to the sender only.
     - 300 ms later a chat is still rejected; 600 ms after the burst one more goes through (the fraction carried over).
     - A `skip` also spends a token: after 5 chats, a `skip` gets `rate-limited` and is not counted.
     - A correct answer sent without a token does not score, and the song keeps playing.
     - Each player has their own bucket.
   - **Before `startAt`.** With 2 players, a match and a miss in PREPARE and in PLAYING before `startAt` each reach only the sender. From `startAt`, a miss reaches everyone.
   - **No leaks.** `skipState` has exactly `type, index, votes, needed`. The `reveal` key set is unchanged from cycle 036.
2. **`LobbyRoundsTest`**, using the cycle 036 fakes unchanged.
   - With 3 players in a game, one disconnects and the other two get the game started on their `ready`.
   - A `skip` through the lobby works.
   - A reconnect of a Room player restores them in the ready count for the next song.
   - A leaving player's skip vote is removed.
   - A replacement `reconnect` of a voter removes their vote (others get the new `skipState`); a replacement with no vote sends nothing besides `joined` and `snapshot`.
3. **Socket** (`RoundsSocketTest`, `RANDOM_PORT`, reusing the cycle 037 `TestClient` and `WsFixture` unchanged). With two clients in a lobby game, `skipVote{index}` from both ends the song: both get `skipState{votes:2, needed:2}` and then a `reveal` with `winner:null`. `skipVote{index:"x"}` gives `bad-message`.
4. **No regression.** All earlier tests pass, with only the listed assertion changes. `.\gradlew.bat test` and `.\gradlew.bat build` exit 0.

## Review changes (spec round 1)
- **Blocker.** `reveal` keeps its exact cycle 036 keys; a passed skip is signalled by the final `skipState`.
- **High.** Every reconnect of a Room player, including a replacement, clears their skip vote, so a client never toggles off a vote it cannot see.
- **Medium.** B-006c (late joiners) is added to the backlog; a skip before `startAt` counts; fractional refill carries over and is tested; `skipVote` spends a chat token.
- **Nits.** The message is `skipVote{index}` as in design 034; `setConnected` ignores unknown ids; reconnect order is `joined`, `snapshot`, then connection and vote changes.

## Test list
- `backend/src/test/java/com/songrush/game/RoomRoundsTest.java`: criterion 1.
- `backend/src/test/java/com/songrush/game/LobbyRoundsTest.java`: criterion 2.
- `backend/src/test/java/com/songrush/ws/RoundsSocketTest.java`: criterion 3.
- The listed assertion change in `RoomTest.java`.

## File ownership
- **Test writer:** the three new files and the listed `RoomTest` change.
- **Implementer:** `game/Room.java`, `game/Lobby.java`, `ws/GameSocketHandler.java`.

## Out of scope
- Late joiners (B-006c).
- Client UI (F-005).

## Locked-test correction (attempt 1)
- `RoomRoundsTest.twoOfThreeVotesSkipTheSongWithNoWinner` advanced to the skipped song's old deadline (60 s or more) and expected silence, then expected exactly one `prepare` after a further `REVEAL_PAUSE`; the 5 s reveal pause and the 25 s prepare timeout always fire first, so no implementation could pass it (implementer report, confirmed by the lead from the test source).
- The lead unlocked the tests; the test writer replaced only those two steps with: advance `REVEAL_PAUSE - 1 ms`, expect nothing; advance 1 ms, expect exactly `prepare` with index 1. The tests were locked again. No implementation change was made for it.

## Result
- Tests `67ad1b9` (locked; RoomTest's declared change included), one unpassable assertion corrected `ed1545b`, implementation `994c0d5` (attempt 1; the implementation needed no change after the correction).
- Red phase (verifier): 57 compile errors, all the missing `Room.skip`/`setConnected`/`clearSkipVote` and `Lobby.skip`; against no-op stubs 31 of 53 tests failed (4 new tests pass on stubs by design: they guard behaviour that is already true today).
- Verifier: `cleanTest test` and `build` exit 0; every suite 0 failures (RoomRoundsTest 26, LobbyRoundsTest 8, RoundsSocketTest 3, RoomTest 19); the feat commit changes no test; the RoomTest and RoomRoundsTest changes are exactly the declared ones; sources ASCII-only.

## Code review (critic)
No Blocker or High. Confirmed: connected-only ready never starts with nobody connected; a skip cannot pass with zero connected; votes are cleared on every connection change; the token bucket is exact (milli-tokens, capped, starts full, shared with skip); before `startAt` a match and a miss look identical to the sender, including the rate-limit error; `skipState` names no voter.
- **M1 (follow-up).** A replacing socket without a vote gets no `skipState`.
- **M2.** The backlog note about a snapshot skip vote was replaced in this cycle and is now marked as such.
