# 040 rooms-lobby (B-005a)

## Goal
Friends can share a room: a host creates a room with a 5-character code, up to 8 players join by that code, the host picks the song count and starts, and after the game everyone is back in the same lobby. This covers D-046, D-047, D-048, D-058 and D-062 (design 034).

## Scope
- The cycle 036 `Room` (one game with a fixed player list) stays as it is.
- A new `Lobby` owns the room code, the player list, the host and the cleanup timers. It builds a fresh `Room` for each game from the players present at `start`.
- The cycle 037 solo path (`create` with `songCount`) becomes a lobby whose creator starts at once. After `gameOver` it returns to LOBBY like any lobby and is cleaned up by the TTLs (spec review H2: the cycle 039 screen opens a new socket per game, and no locked test creates again on the same socket after `gameOver`).
- Joining while a game runs is rejected with `in-game`. Late joiners (D-059) are B-006b.
- A player who leaves or disconnects during a game stays in that game's `Room` until `gameOver` and shows `connected:false`. They are removed when the lobby returns. Reconnect is B-005b. Accepted until B-006b: the `Room` still waits for that player's `ready` (up to the 25 s prepare timeout per song) and lists them in `scores` and `ranking`.
- `joined{code, name}` is the B-005a shape; B-005b adds the player token and snapshot (design 034).

## Interface (package `com.songrush.game`, test-facing)
- `final class RoomCodes`
  - `static final String ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"`, `static final int LENGTH = 5`.
  - `static String next(RandomGenerator random, Predicate<String> taken)` draws codes until one is not `taken`. It throws `IllegalStateException` after 100 tries.
- `final class Lobby`
  - Constructor: `Lobby(String code, Catalog catalog, AudioTokens tokens, GameScheduler scheduler, Clock clock, RandomGenerator random, Outbox outbox, Runnable onClosed)`.
  - Every method runs on the lobby's thread, which is the scheduler's thread, and each game's `Room` shares that scheduler.
  - `onClosed` runs once, when the lobby closes itself (see Cleanup).
  - Methods:
    - `String join(String playerId, String nickname)` returns the assigned name, or `null` when it sent an error.
    - `void leave(String playerId)`.
    - `void start(String playerId, int songCount)`.
    - `void ready(String playerId, int index)` and `void chat(String playerId, String text)` forward to the current `Room` for a connected player; in LOBBY they are ignored (lobby chat is not in B-005a).
    - `Room.Phase phase()` returns LOBBY when no game runs.
    - `String host()` returns the host's player id, or `null` when no connected player is present.
    - `List<String> names()` returns the names in join order.
    - `boolean closed()`.
  - Constants: `MAX_PLAYERS = 8`, `EMPTY_TTL = 2 min`, `IDLE_TTL = 10 min`.
- **Delivery contract (spec review H1).** The lobby never calls `outbox.toAll`. Every broadcast, including each `Room` message the `Room` sent with `toAll`, is expanded into one `outbox.toPlayer` per connected player in join order; a `Room`'s `toPlayer` goes through only if that player is still connected. Each `Room` gets its own wrapping `Outbox` that drops everything once the lobby is closed or that `Room` is no longer the current one (spec review M1: a dropped `Room`'s timers cannot be cancelled and may still run).

## Behaviour
1. **Join.** The nickname is trimmed and must be 1-16 characters. Otherwise the joiner gets `error{code:"bad-nickname"}`.
   - Other errors, checked in this order after the nickname: a closed lobby gives `error{code:"room-not-found"}`; a running game gives `error{code:"in-game"}`; a full lobby (8 players) gives `error{code:"room-full"}`. (A closed lobby is checked first of all.)
   - **Duplicate names** (D-048): if the name equals a present player's name, the first free of `name (2)`, `name (3)`, ... is used.
   - The joiner gets `joined{code, name}`, and then everyone gets `roster`.
   - The first player to join is the host; if a game ended with no connected player, the next player to join becomes host.
2. **roster** is `roster{phase, players:[{name, score, host, connected}, ...]}`.
   - `phase` is `"lobby"` or `"playing"`.
   - Players are listed in join order.
   - `score` is the player's score in the current or last game, and 0 before the first game.
   - These keys only, never player ids, so roster JSON is allow-listed.
   - It is sent to everyone after every join, leave, host change, start and return to the lobby.
3. **Leave.**
   - In LOBBY the player is removed.
   - During a game the player is marked `connected:false` and gets no further messages. They are removed when the lobby returns.
   - If the host leaves, the earliest-joined remaining connected player becomes host (design 034).
4. **Start.**
   - Only the host may start. Anyone else gets `error{code:"not-host"}`.
   - A `songCount` outside 5-30 gives `error{code:"bad-song-count"}` (D-058).
   - `start` during a game gives `error{code:"in-game"}`.
   - Otherwise the lobby builds a `Room` from the connected players (as `PlayerRef(id, name)`) and calls `start(songCount)`.
   - If the `Room` stays in LOBBY (`not-enough-songs`), the lobby stays in LOBBY too and sends no roster.
   - Otherwise, after `room.start` returns, the lobby sends `roster{phase:"playing"}`; so the order is `prepare` (index 0), then `roster`.
5. **Back to the lobby** (D-062).
   - When the `Room` sends `gameOver`, the lobby first delivers it, then records the final scores, removes the players who left, returns to LOBBY and sends `roster{phase:"lobby"}`.
   - The host may start again, with scores starting from 0 in the new `Room`.
6. **Cleanup** (with the injected clock and scheduler).
   - When the last connected player leaves, the lobby closes after `EMPTY_TTL` unless someone joins first.
   - A lobby in LOBBY closes after `IDLE_TTL` without activity. The idle timer is (re)armed at creation, on each join and leave in LOBBY, on a failed start, and on the return to the lobby; it does nothing if it fires during a game.
   - When the lobby closes:
     - its remaining players get `error{code:"room-closed"}`;
     - any running game stops (the current `Room` is dropped; nothing more is sent);
     - `onClosed` runs.
   - After closing, every method is a no-op, except that `join` answers `room-not-found`.
   - Every timer carries a generation, and a stale timer does nothing (as in cycle 036).
7. **Socket protocol** (`ws/GameSocketHandler`).
   - **Handler bookkeeping (spec review H3, M6-M8).**
     - The nickname is validated (trim, 1-16) on the I/O thread before a lobby is made, so `bad-nickname` never leaves an empty lobby behind.
     - The session's room slot is reserved with `putIfAbsent` on the I/O thread before the lobby task runs, and released if `join` returns `null`.
     - `onClosed` removes the code from the registry (`remove(code, lobby)`), removes every session mapping that points to that lobby, and shuts the lobby's scheduler down (`shutdownNow`).
     - At most 200 lobbies exist at once; a `create` beyond that gets `error{code:"server-full"}`.
   - **`create{nickname}`, without `songCount`.** This makes a lobby:
     - Its code comes from `RoomCodes.next(new SecureRandom(), registry::containsKey)`.
     - The creator joins it as host.
     - The lobby runs on its own `ExecutorGameScheduler`.
     - The registry, a concurrent map from code to lobby, removes the code when `onClosed` runs.
   - **`create{nickname, songCount}`** keeps the cycle 037 solo path: a lobby whose creator starts at once with that count.
     - It still answers `bad-song-count` for counts outside 5-30 without making a room.
     - A `songCount` that is present but not an integer (`"5"`, `5.5`, `null`) is `bad-message`, as the locked cycle 037 test requires; only an absent `songCount` means a lobby.
   - **`join{code, nickname}`** looks the code up after `trim()` and upper-casing.
     - An unknown code gives `room-not-found`.
     - A session already in a room gets `already-in-room`.
     - A non-string `code` or `nickname` gives `bad-message`.
   - **`start{songCount}`** passes to the lobby. A missing or non-integer `songCount` gives `bad-message`; with no room, `no-room`.
   - **`leave` and a socket close** both call `lobby.leave`. The session is then free to create or join again.
   - **Unchanged from cycle 037:** `ready`, `chat` and `ping`, the queues, the 4096-byte limit, and the rule that the handler never throws.

## Acceptance criteria (JUnit 5, from `backend/`)
1. **RoomCodes** (`RoomCodesTest`):
   - 10,000 codes are 5 characters long and use only the alphabet.
   - With a `taken` predicate that rejects the first 3 draws, `next` returns the 4th draw. A seeded `Random` makes this deterministic: compare with a second identically seeded generator.
   - An always-taken predicate throws `IllegalStateException`.
2. **Lobby** (`LobbyTest`). These tests reuse the cycle 036 fakes in the `com.songrush.game` test package (`ManualScheduler`, `MutableClock`, `RecordingOutbox`, `GameFixture`, which are locked and not modified):
   - **Join and names.** Joining works up to 8 players, and the 9th gets `room-full`. Names follow D-048: `Hana`, `Hana (2)`, `Hana (3)`, and a fresh `Hana (2)` is used again after the old one leaves in LOBBY. Bad nicknames are rejected. `joined` and `roster` are sent; roster JSON has exactly the keys `type, phase, players` and per player exactly `name, score, host, connected`.
   - **Delivery.** The `RecordingOutbox` records no `toAll` from the lobby at all; every message is a `toPlayer`.
   - **Host.** The creator is host. When the host leaves, the next earliest-joined player becomes host and `roster` shows it.
   - **Start rights.** A non-host start gets `not-host`. Counts 4 and 31 get `bad-song-count`. A start during a game, and a join during a game, get `in-game`.
   - **Game and return.** A 3-player game runs to `gameOver` (driven with `ready` and scripted correct answers, as `RoomTest` does). After it the lobby is in LOBBY, the roster shows the final scores, and a second `start` runs a new game whose scores start at 0.
   - **Leave mid-game.** The player gets no further messages (no `toPlayer` to their id), shows `connected:false`, and is gone from the roster after `gameOver`. Order checks: `prepare` then `roster` at start; `gameOver` then `roster{phase:"lobby"}` at the end.
   - **Cleanup.** The empty-lobby TTL closes the lobby and calls `onClosed` exactly once, but a join before the TTL cancels it. The idle TTL closes a lobby with players, who get `room-closed`; the idle timer does not close a lobby during a game, and is rearmed on the return. After closing, `join` gets `room-not-found`, and messages from a `Room` dropped by the close are not delivered (do not assert that the scheduler has no pending tasks: `Room` timers cannot be cancelled). A stale timer from before a join does nothing.
3. **Sockets** (`LobbySocketTest`, `RANDOM_PORT`, real data in a temp folder as in cycle 037, two or three `TestClient`s from the locked cycle 037 test package, which may be reused without modification):
   - A `create{nickname}` gets `joined` with a 5-character code.
   - A second client `join`s with the lower-case code and gets `joined` with its name, and both get a `roster` with 2 players.
   - A non-host `start` gets `not-host`.
   - The host `start{songCount:5}` makes both clients receive `prepare` for index 0.
   - A join with an unknown code gets `room-not-found`.
   - A duplicate nickname gets `Name (2)`.
   - After both clients send `leave`, each can at once send `create{nickname}` again and gets `joined` with a 5-character code (the sessions are free). Whether the old code still accepts joins during `EMPTY_TTL` is not asserted here; closing after `EMPTY_TTL` and the following `room-not-found` are covered by `LobbyTest`.
   - `create{nickname:""}` gets `bad-nickname`, and a following valid `create` works (no orphan slot).
   - Note for the test writer: the host first receives a 1-player `roster`; wait until a roster with the expected player count arrives.
4. **No regression.** The locked cycle 036 and 037 tests pass unchanged, and `.\gradlew.bat test` and `.\gradlew.bat build` exit 0.

## Test list
- `backend/src/test/java/com/songrush/game/RoomCodesTest.java` covers criterion 1.
- `backend/src/test/java/com/songrush/game/LobbyTest.java` covers criterion 2.
- `backend/src/test/java/com/songrush/ws/LobbySocketTest.java` covers criterion 3.
- New helpers are allowed in those test packages.

## File ownership
- **Test writer:** the three test files and new test helpers. Existing test files are not edited.
- **Implementer:** `game/RoomCodes.java`, `game/Lobby.java` and `ws/GameSocketHandler.java`. New package-private helpers are allowed in `game` and `ws`. `game/Room.java` is unchanged.

## Review changes (spec round 1)
- **High.** The lobby expands every broadcast into per-player sends to connected players only; the solo path returns to the lobby instead of a discard that would have shown `room-closed` on the cycle 039 screen; closing a lobby frees its sessions and its thread.
- **Medium.** Dropped `Room`s are muted through a per-`Room` outbox; criterion 3's last bullet is rewritten; roster keys include `type`; message order at start and at `gameOver` is fixed; the idle timer's rearm points are listed; the handler validates nicknames before making a lobby and reserves the session slot first; at most 200 lobbies.
- **Nits.** Absent vs non-integer `songCount`; error priority; `host()` may be `null`; the roster-wait note; lobby chat dropped from scope; the `joined` shape grows in B-005b.

## Out of scope
Reconnect and player tokens (B-005b), late joiners, skip, the chat rate limit (B-006b), and the lobby screens (F-003).
