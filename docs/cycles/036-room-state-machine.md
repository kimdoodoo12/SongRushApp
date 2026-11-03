# 036 room-state-machine (B-006a)

## Goal
One room's game, from the start of a game to the final ranking, runs as a server-side state machine that judges chat with `Normalize` and never lets anything identifying the song reach a player before its reveal (design 034; D-011, D-015, D-016, D-018, D-021, D-047, D-058, D-060).

## Scope note
The ready step (start a song when every player has its file, or after a timeout) is moved here from B-006b, because even solo play needs the client to finish downloading before the song starts. B-006b keeps skip votes, the chat rate limit and late joiners. Players are fixed when the room is built; joining, leaving and reconnecting are B-005a/B-005b. Sockets are B-007.

## Interface (package `com.songrush.game`)
- `record PlayerRef(String id, String name)`.
- `interface Outbox { void toPlayer(String playerId, Map<String, Object> message); void toAll(Map<String, Object> message); }`. Every message is a map with a `"type"` key; values are `String`, `Integer`/`Long`, `Boolean`, `null`, `List` or `Map` of those, so Jackson can serialize it.
- `interface GameScheduler { Cancellable schedule(Duration delay, Runnable task); }` and `interface Cancellable { void cancel(); }`. The room calls everything on one thread: the scheduler runs tasks on the room's thread, and callers invoke room methods on it (the production single-thread scheduler is B-007).
- `final class Room`:
  - `Room(Catalog catalog, AudioTokens tokens, GameScheduler scheduler, Clock clock, java.util.random.RandomGenerator random, Outbox outbox, List<PlayerRef> players)`; at least one player.
  - `void start(int songCount)`, `void ready(String playerId, int index)`, `void chat(String playerId, String text)`.
  - `Phase phase()` with `enum Phase { LOBBY, PREPARE, PLAYING, REVEAL }`, `int songIndex()` (0-based; -1 in LOBBY), `Map<String, Integer> scores()` (player id to points).
- Constants (package-visible static fields so tests can use them): `PREPARE_TIMEOUT = 25 s`, `LEAD = 3 s`, `MAX_PLAY = 90 s`, `REVEAL_PAUSE = 5 s`, `TOKEN_SLACK = 30 s`, `MIN_SONGS = 5`, `MAX_SONGS = 30`, `MAX_CHAT = 200`.

## Behaviour
1. **start(n)** in LOBBY: `n` outside 5-30 sends `error{code:"bad-song-count"}` to all and stays in LOBBY. Drawable candidates are `catalog.candidates()` whose theme's anime has a non-null group; fewer than `n` sends `error{code:"not-enough-songs"}`. Otherwise all scores reset to 0, `n` distinct candidates are drawn with `random`, and song 0 is prepared. `start` outside LOBBY is ignored.
2. **prepare(k):** phase PREPARE; issue `tokens.issue(themeId, PREPARE_TIMEOUT + LEAD + MAX_PLAY + TOKEN_SLACK)`; send to all `prepare{index:k, total:n, audioUrl:"/audio/<token>"}`; schedule the prepare timeout.
3. **ready(player, k):** counts only in PREPARE, for the current index, from a known player. When every player is ready, or at the prepare timeout, the song starts.
4. **start of song k:** phase PLAYING; `startAt = now + LEAD`, `deadlineAt = startAt + min(durationMs, MAX_PLAY)` (epoch ms); send to all `start{index:k, startAt, deadlineAt, serverTime:now}`; schedule the deadline.
5. **chat(player, text):** from an unknown player: ignored. Text longer than 200 characters: `error{code:"too-long"}` to the sender. Text whose `Normalize.normalize` is empty: ignored. Then, with `match` = the normalized text is in `catalog.accepted(group)` of the current song:
   - PLAYING, `now >= startAt`, match: the song ends with this player as winner; they score 1 point; the message itself is never sent to anyone; all get `correct{index:k, name}`.
   - PREPARE or PLAYING before `startAt`, match: not sent to anyone; the sender gets `error{code:"too-early"}`; no score.
   - Otherwise (no match, or REVEAL or LOBBY): all get `chat{name, text}`.
6. **end of song k** (first correct answer, or the deadline): phase REVEAL; cancel the pending deadline; send to all `reveal{index:k, reveal, titles, song, artists, themeType, themeSlug, winner}` (`reveal` is the anime's reveal title, `titles` its `ko/en/ja/romaji` lists, `song` and `artists` from the theme, `winner` the winner's name or `null`), then `scores{scores:[{name, score}, ...]}` in player order; after `REVEAL_PAUSE` prepare song k+1, or after the last song send `gameOver{ranking:[{rank, name, score}, ...]}` and return to LOBBY.
7. **Ranking:** points descending, ties in player order, standard competition ranks (scores 3, 3, 1 give ranks 1, 1, 3).
8. **Stale work is ignored.** Every scheduled task records the song index and phase it was scheduled for, and does nothing if either has changed; `ready` for another index is ignored.
9. **Never before the reveal:** no message other than `reveal` contains a theme or anime id, an audio basename, a title, the song, the artists or the duration. The keys of each message are exactly those listed above.

## Acceptance criteria (JUnit 5, from `backend/`)
Tests build a catalog with `CatalogLoader.load` from a temp folder (a small `catalog.json` with at least 6 groups and a `audio-cache/index.json` whose files exist with the listed sizes), use a fake clock, a manual scheduler that tests advance by hand (running due tasks in time order, including tasks scheduled while advancing), a seeded `Random`, a recording `Outbox`, and the real `AudioTokens` on the fake clock.
1. **Full game:** with 1 player and `start(5)`, each of 5 songs goes prepare, ready, start, deadline, reveal, scores, pause; then `gameOver` and LOBBY. The 5 drawn themes are distinct candidates; each `audioUrl` resolves through `tokens.resolve` to that song's theme id; `deadlineAt - startAt` is `min(durationMs, 90000)` (the fixture has one song shorter and one longer than 90 s).
2. **Ready:** with 2 players the song starts only after both send `ready{index}`; with one ready it starts at `PREPARE_TIMEOUT`; `ready` with another index or from an unknown player does not count.
3. **Correct answer:** after `startAt`, a chat whose text normalizes to an accepted string (for example the title with different case, spaces and punctuation) ends the song at once: all get `correct`, the text appears in no message, the winner has 1 point, and `reveal.winner` is their name.
4. **Stale timer:** a song ended by a correct answer is followed by the next song, and advancing the clock past the old song's deadline does not end the new song (it ends at its own deadline).
5. **Too early:** a matching chat in PREPARE and in PLAYING before `startAt` is sent to no one, the sender gets `too-early`, and no one scores; a non-matching chat is broadcast as `chat{name, text}`.
6. **Chat limits:** 201 characters gives `too-long` to the sender only; a text that normalizes to empty is ignored; after the reveal, the answer text is broadcast as normal chat.
7. **Start errors:** `start(4)` and `start(31)` give `bad-song-count`; a catalog with fewer drawable candidates than `n` gives `not-enough-songs`; both stay in LOBBY.
8. **Ranking:** a 3-player game with scores 3, 3, 1 (by scripted correct answers) gives `gameOver` ranks 1, 1, 3 in player order for the tie; `start` again resets scores to 0.
9. **No leaks:** every message sent before a song's `reveal` has exactly the specified keys; serialized to JSON with Jackson it contains none of the song's theme id, anime id, basename, any title, song or artist strings as a JSON value (compare values, not substrings of numbers).
10. `.\gradlew.bat test` and `.\gradlew.bat build` exit 0; earlier tests still pass.

## Test list
- `backend/src/test/java/com/songrush/game/RoomTest.java`: criteria 1-9.
- Test helpers owned by the test writer: `backend/src/test/java/com/songrush/game/{ManualScheduler,MutableClock,RecordingOutbox,GameFixture}.java` (any subset).

## File ownership
- Test writer: the test files above.
- Implementer: `backend/src/main/java/com/songrush/game/{PlayerRef,Outbox,GameScheduler,Cancellable,Room}.java` and new package-private helpers in `com.songrush.game`. No changes to `catalog` or `audio`.

## Out of scope
Sockets and the production scheduler (B-007), joining/leaving/host (B-005a), reconnect (B-005b), skip, rate limit, late joiners (B-006b).
