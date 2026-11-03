# 040 rooms-lobby (B-005a)

## Goal
Friends can share a room: a host creates a room with a 5-character code, up to 8 players join by that code, the host picks the song count and starts, and after the game everyone is back in the same lobby. This covers D-046, D-047, D-048, D-058 and D-062 (design 034).

## Scope
- The cycle 036 `Room` (one game with a fixed player list) stays as it is.
- A new `Lobby` owns the room code, the player list, the host and the cleanup timers. It builds a fresh `Room` for each game from the players present at `start`.
- The cycle 037 solo path (`create` with `songCount`) keeps its behaviour. It becomes a lobby that starts at once and is discarded after `gameOver`, so the locked cycle 037 tests keep passing.
- Joining while a game runs is rejected with `in-game`. Late joiners (D-059) are B-006b.
- A player who leaves or disconnects during a game stays in that game's `Room` until `gameOver` and shows `connected:false`. They are removed when the lobby returns. Reconnect is B-005b.

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
    - `void ready(String playerId, int index)` and `void chat(String playerId, String text)` forward to the current `Room`. In LOBBY a chat is broadcast as `chat{name, text}` (`text` at most 200 characters, else `too-long`).
    - `Room.Phase phase()` returns LOBBY when no game runs.
    - `String host()` returns the host's player id.
    - `List<String> names()` returns the names in join order.
    - `boolean closed()`.
  - Constants: `MAX_PLAYERS = 8`, `EMPTY_TTL = 2 min`, `IDLE_TTL = 10 min`.

## Behaviour
1. **Join.** The nickname is trimmed and must be 1-16 characters. Otherwise the joiner gets `error{code:"bad-nickname"}`.
   - Other errors:
     - a full lobby (8 players) gives `error{code:"room-full"}`;
     - a running game gives `error{code:"in-game"}`;
     - a closed lobby gives `error{code:"room-not-found"}`.
   - **Duplicate names** (D-048): if the name equals a present player's name, the first free of `name (2)`, `name (3)`, ... is used.
   - The joiner gets `joined{code, name}`, and then everyone gets `roster`.
   - The first player to join is the host.
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
   - If the `Room` stays in LOBBY (`not-enough-songs`), the lobby stays in LOBBY too.
   - The `Room`'s messages go to the lobby's `Outbox`. Only connected players receive them.
5. **Back to the lobby** (D-062).
   - When the `Room` sends `gameOver`, the lobby records the final scores and removes the players who left. Then it returns to LOBBY and sends `roster`.
   - The host may start again, with scores starting from 0 in the new `Room`.
6. **Cleanup** (with the injected clock and scheduler).
   - When the last connected player leaves, the lobby closes after `EMPTY_TTL` unless someone joins first.
   - A lobby that stays in LOBBY with no join, leave or start for `IDLE_TTL` closes.
   - When the lobby closes:
     - its remaining players get `error{code:"room-closed"}`;
     - any running game stops (the current `Room` is dropped; nothing more is sent);
     - `onClosed` runs.
   - After closing, every method is a no-op, except that `join` answers `room-not-found`.
   - Every timer carries a generation, and a stale timer does nothing (as in cycle 036).
7. **Socket protocol** (`ws/GameSocketHandler`).
   - **`create{nickname}`, without `songCount`.** This makes a lobby:
     - Its code comes from `RoomCodes.next(new SecureRandom(), registry::containsKey)`.
     - The creator joins it as host.
     - The lobby runs on its own `ExecutorGameScheduler`.
     - The registry, a concurrent map from code to lobby, removes the code when `onClosed` runs.
   - **`create{nickname, songCount}`** keeps the cycle 037 solo path: a lobby whose creator starts at once with that count.
     - It still answers `bad-song-count` for counts outside 5-30 without making a room.
     - It is discarded after `gameOver`, so a new `create` works.
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
   - **Join and names.** Joining works up to 8 players, and the 9th gets `room-full`. Names follow D-048: `Hana`, `Hana (2)`, `Hana (3)`, and a fresh `Hana (2)` is used again after the old one leaves in LOBBY. Bad nicknames are rejected. `joined` and `roster` are sent, and roster JSON has exactly the keys `phase, players` and per player `name, score, host, connected`.
   - **Host.** The creator is host. When the host leaves, the next earliest-joined player becomes host and `roster` shows it.
   - **Start rights.** A non-host start gets `not-host`. Counts 4 and 31 get `bad-song-count`. A start during a game, and a join during a game, get `in-game`.
   - **Game and return.** A 3-player game runs to `gameOver` (driven with `ready` and scripted correct answers, as `RoomTest` does). After it the lobby is in LOBBY, the roster shows the final scores, and a second `start` runs a new game whose scores start at 0.
   - **Leave mid-game.** The player gets no further messages, shows `connected:false`, and is gone from the roster after `gameOver`.
   - **Cleanup.** The empty-lobby TTL closes the lobby and calls `onClosed` exactly once, but a join before the TTL cancels it. The idle TTL closes a lobby with players, who get `room-closed`. After closing, `join` gets `room-not-found`. A stale timer from before a join does nothing.
3. **Sockets** (`LobbySocketTest`, `RANDOM_PORT`, real data in a temp folder as in cycle 037, two or three `TestClient`s from the locked cycle 037 test package, which may be reused without modification):
   - A `create{nickname}` gets `joined` with a 5-character code.
   - A second client `join`s with the lower-case code and gets `joined` with its name, and both get a `roster` with 2 players.
   - A non-host `start` gets `not-host`.
   - The host `start{songCount:5}` makes both clients receive `prepare` for index 0.
   - A join with an unknown code gets `room-not-found`.
   - A duplicate nickname gets `Name (2)`.
   - After both `leave`, the code is gone, and a join with it gets `room-not-found` once the lobby has closed (use a test-only short `EMPTY_TTL`? No: assert only the immediate state, that the leavers can create new rooms. The 2-minute TTL is covered by `LobbyTest`).
4. **No regression.** The locked cycle 036 and 037 tests pass unchanged, and `.\gradlew.bat test` and `.\gradlew.bat build` exit 0.

## Test list
- `backend/src/test/java/com/songrush/game/RoomCodesTest.java` covers criterion 1.
- `backend/src/test/java/com/songrush/game/LobbyTest.java` covers criterion 2.
- `backend/src/test/java/com/songrush/ws/LobbySocketTest.java` covers criterion 3.
- New helpers are allowed in those test packages.

## File ownership
- **Test writer:** the three test files and new test helpers. Existing test files are not edited.
- **Implementer:** `game/RoomCodes.java`, `game/Lobby.java` and `ws/GameSocketHandler.java`. New package-private helpers are allowed in `game` and `ws`. `game/Room.java` is unchanged.

## Out of scope
Reconnect and player tokens (B-005b), late joiners, skip, the chat rate limit (B-006b), and the lobby screens (F-003).
