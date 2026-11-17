# SongRush WebSocket protocol

The contract between the backend and every client (the React web client and the Flutter client).
The backend code is the source of truth: `backend/src/main/java/com/songrush/ws/GameSocketHandler.java`,
`game/Lobby.java` and `game/Room.java`. This file describes what that code does (cycle 144); it
changes nothing.

A change to any message changes this file and its example in `contracts/messages/` in the same cycle.
`ProtocolContractTest` (backend) fails when a message type the code sends or accepts is missing here,
when a type here has no example, or when an example has no heading here.

## Conventions

- **Socket.** One WebSocket at `/ws` (`ws://` or `wss://` on the page's host). Every frame is a JSON
  object in a text frame with a string `type`. A frame from the client over 4096 bytes is refused
  with `error{code: "too-large"}`; text that is not a JSON object with a string `type`, or an unknown
  `type`, gets `error{code: "bad-message"}`.
- **Times** are epoch milliseconds on the server clock (`serverTime`, `startAt`, `deadlineAt`,
  `playUntil`). Clients estimate their offset to the server clock with `ping`/`pong`.
- **Indexes.** `index` is the song's 0-based position in the game; `total` is the game's song count.
- **Optional fields.** "absent" means the key is left out; "null" means the key is present with
  JSON `null`. Both are stated per field. Clients must ignore unknown keys.
- **Recipients.** "sender" is the one session that sent the request; "everyone" is every connected
  member of the room (players and late joiners watching), each delivered separately in join order.
- **Names, never ids.** No frame carries a player id. Players are named by their room name (the
  nickname, with ` (2)`, ` (3)`... appended to a duplicate, D-048).

## Rules every client relies on

1. **The server is the authority.** It owns the answer, the round clock, the arrival order and the
   scores. The first correct answer by server arrival order wins (D-016).
2. **Nothing identifies the song before its reveal.** Before `reveal`, no frame carries a title, a
   song name, an artist, a catalog anime or theme id, or the song's cover. `audioUrl` is
   `/audio/<opaque token>`: a fresh random token per prepare that names nothing.
3. **Choice-room exception** (D-111, D-133). In a choice room (`answerMode: "choice"`) the cover URLs
   of a song's choices go out in its `prepare` (and in `snapshot`), shuffled, with nothing marking the
   correct one. The next song's choices also go out in the `reveal` of the song before it, as
   `nextChoices`, in the same shuffled order as its `prepare`, again unmarked.
4. **Early chat is private.** A chat line that matches the current song's accepted titles while the
   song cannot score yet (in PREPARE, or before `startAt`) goes to its sender only and never scores
   (D-132). With prefetch on reveal, during the reveal that already carries the next song's
   `prepare`, a line matching the **next** song's titles also goes to its sender only (D-120).
5. **Ranked (`gameMode: "ranked"`) never echoes a correct chat** to the others: everyone gets
   `correct{rank}`, and the correct text goes to its sender only. The song goes on until every
   connected player has answered (or picked), or the deadline, or a skip.
6. **Choice-room chat is plain chat.** In a choice room chat never scores and every line goes to
   everyone, at every moment, watchers included (D-135).
7. **First-come chat.** A winning chat line is broadcast to everyone right after `correct` (D-094).
   A line that does not match is broadcast as ordinary chat.
8. **A late joiner** (or a rejoin of a player who was only watching) watches the current song and
   plays from the next `prepare` (D-059).

## Close codes

| Code | Meaning | Client action |
|---|---|---|
| 4001 `replaced` | A `rejoin` with this session's token was accepted on another socket. | Do **not** reconnect. |
| 1008 | The server's outbound queue for this socket overflowed (256 frames) or a send failed. | Reconnect and `rejoin`. |
| other | Network loss, server restart. | Reconnect and `rejoin` within the 30 s grace (`Lobby.GRACE`); `error{rejoin-failed}` means the seat is gone. |

## HTTP endpoints

- `GET /audio/<token>`: the song's audio for the token in `prepare.audioUrl`. Disk mode streams the
  file; S3 mode answers `302` to a 60-second presigned URL (host possibly swapped to the CDN host).
  Always `Cache-Control: no-store`; no `Last-Modified`, `ETag`, `Content-Disposition` or `Range`
  support. Any failure (unknown or expired token) is `404` with `{"error":"not found"}`; an S3 signing
  failure is `503`. A token is valid for about 2.5 minutes (reveal pause + prepare timeout + lead +
  90 s + 30 s slack).
- `GET /cover/<id>`: a cover image (`image/jpeg` or `image/png`) from a `coverUrl`; S3 mode answers
  `302` to a 60-second presigned URL. Same headers and `404` as `/audio`. A cover id is stable per
  anime, which is why cover URLs are only sent under rule 3 or in `reveal`.

## Client to server

Every request below needs a seat (after `create`, `join` or `rejoin`) except `ping`, `create`,
`join` and `rejoin`; without one the sender gets `error{no-room}`. Examples are `client-<type>.json`;
all client examples are **hand-written** from `GameSocketHandler` (the tests build their frames in
code, so there is nothing to capture).

### client: ping

`{id?}`. `id` is any JSON value, echoed back. Answer: `pong` to the sender. Needs no seat.

### client: create

`{nickname, songCount?, types?, yearFrom?, yearTo?, tier?}`. `nickname`: string, trimmed, 1 to 16
characters (`bad-nickname`). Without `songCount` it makes a lobby with the sender as host. With
`songCount` (integer 5 to 30, `bad-song-count`) it is the solo path: the same, then the game starts at
once with the optional draw filter (as in `start`; `bad-filter`); a solo start that fails
(`not-enough-songs`) leaves no room. Errors: `bad-message`, `already-in-room`, `server-full` (200
lobbies). Answers, to the sender: `joined`, `roster`, then `token`.

### client: join

`{code, nickname}`. `code`: the 5-character room code (trimmed, upper-cased; alphabet
`ABCDEFGHJKMNPQRSTUVWXYZ23456789`). Errors: `bad-message`, `already-in-room`, `room-not-found`,
`bad-nickname`, `room-full` (8 seats). Answers: to the sender `joined`; during a game also `snapshot`
(and `settings` when the game has any); to everyone `roster`; to the sender `roomTheme` (off the
default only) and in the lobby `lobbySettings` (once the host has chosen); then `token`.

### client: rejoin

`{token}`: the `token` received earlier. Takes the seat back during the 30 s grace, or replaces a
socket that still holds it (that socket is closed with 4001). Errors: `bad-message`,
`already-in-room`, `rejoin-failed`. Answers to the sender: `joined`, `snapshot` (+ `settings` in a game
that has any), `roster` to everyone when the player was in grace, `roomTheme` (off the default),
`lobbySettings` (lobby phase, once chosen). No new `token`: the old one stays valid.

### client: start

Host only, lobby phase only. `{songCount, types?, yearFrom?, yearTo?, tier?, answerMode?, gameMode?}`.

| Field | Type | Rule |
|---|---|---|
| `songCount` | integer | 5 to 100 (`bad-song-count`); not an integer is `bad-message` |
| `types` | array of `"OP"`/`"ED"` | non-empty, distinct |
| `yearFrom`, `yearTo` | integer | 1950 to 2100, `yearFrom <= yearTo` |
| `tier` | `"easy"`, `"normal"`, `"hard"` | `easy` draws one song per series group; choices per song: easy 4, normal 6, hard 8 |
| `answerMode` | `"chat"` (default) or `"choice"` | else `bad-answer-mode` |
| `gameMode` | `"first"` (default, 선착순) or `"ranked"` (순위전) | else `bad-game-mode` |

A bad filter field (or a present JSON `null`) is `bad-filter`. Error order: `bad-message`,
`bad-filter`, `bad-answer-mode`, `bad-game-mode`, then `no-room`, `in-game`, `not-host`,
`bad-song-count`, then `not-enough-songs` (to everyone). On success everyone gets `settings` (when
the game has a filter, choice or ranked), `prepare` for song 0, and `roster` with phase `playing`.

### client: lobbySettings

Host only, lobby phase only. Same fields and checks as `start`. Stores the host's selection for
display; it does not start anything and `start` does not read it. Everyone gets the server
`lobbySettings` only when the selection changed.

### client: ready

`{index}` (integer). The sender has loaded `prepare.audioUrl` for song `index`. The song starts when
every connected player is ready, or after the 25 s prepare timeout. Ignored for another index or
outside PREPARE.

### client: skipVote

`{index}` (integer). Toggles the sender's skip vote for song `index` in PLAYING (also before
`startAt`) and, with play to limit, in the reveal window while the music plays on. Costs one chat
token (`error{code: "rate-limited", for: "skipVote"}`). Everyone gets `skipState`; a majority of
connected players ends the song with no winner (in the window: moves on at once).

### client: pick

Choice rooms only. `{index, choice}` (integers): the position in that song's `choices`. One pick per
song. Ignored when not a choice room, not PLAYING, another index or position out of range. Before
`startAt`: `error{code: "too-early", for: "pick"}`; a second pick: `error{code: "already-picked",
for: "pick"}`. An accepted pick sends everyone `pickState`; a correct one in 선착순 sends everyone
`correct` and ends the song; a wrong one sends the sender `pick-result` and everyone `wrong`. In
ranked, a correct pick sends everyone `correct{rank}` and the song goes on.

### client: chat

`{text}` (string). Up to 200 characters (`too-long`); burst 5, 2 per second (`rate-limited`);
a line that shows nothing is dropped silently. In the lobby it goes to everyone and is never
checked. In a game, see rules 4 to 7.

### client: roomTheme

**Legacy** (D-136: each player now picks their own mood on the client). `{theme}`: one of `sunset`,
`rain`, `sakura`, `neon`, `chalk`, `pixel`, `matsuri`, `manga` (old ids `play`, `star`, `arcade` map
to `sunset`, `rain`, `pixel`). Errors: `bad-room-theme` (checked first), `not-host`. Everyone gets the
server `roomTheme`. New clients should not send it.

### client: leave

`{}`. Leaves the room at once: no further frame reaches this socket for that room, and the token is
dropped. Nothing is sent back; everyone else gets `roster`.

## Server to client

Examples `<type>.json` are frames captured from the backend's socket tests (see "Capturing the
examples"), so values such as titles, names and tokens are test fixtures.

### server: pong

To the sender of `ping`. `{id?, serverTime}`: `id` is the ping's `id`, absent when the ping had none.

### server: token

To the sender after a successful `create` or `join`, after `joined`. `{token}`: 22-character
base64url secret for `rejoin`. Keep it private (no logging).

### server: joined

To the sender on `create`, `join` and `rejoin`. `{code, name}`: the room code and the sender's
assigned name.

### server: roster

To everyone on every membership change, at start and at the return to the lobby.
`{phase, players}`: `phase` is `"lobby"` or `"playing"`; `players` is
`[{name, score, host, connected}]` in join order (`score` integer: the live score in a game, the last
game's score in the lobby; `host` boolean: the earliest-joined connected member; `connected`
boolean: false while in grace, or after leaving during a game).

### server: snapshot

To the sender after `joined` on a join during a game and on every `rejoin`.
`{phase, players, song, scores}`: `phase` and `players` as in `roster`; `scores` is
`[{name, score}]` (empty in the lobby); `song` is null in the lobby, while paused, and between songs
outside a reveal window, else an object with only values already sent:

| Key | When | Value |
|---|---|---|
| `index`, `total`, `audioUrl` | always | as in `prepare` |
| `startAt`, `deadlineAt` | PLAYING, or in a reveal window | as in `start` |
| `choices` | choice room | as in `prepare` |
| `picked` | choice room | the receiver's own picked position, or null |
| `pickState` | choice room | `{picked, total}` counts as in the `pickState` frame |
| `answered` | ranked | boolean: the receiver already answered this song correctly |
| `correct` | ranked | `[{name, rank}]` already sent in `correct` frames |
| `reveal` | in a reveal window (play to limit, music still playing) | the `reveal` frame without `type` |
| `skipState` | in a reveal window | `{votes, needed}` |

### server: settings

To everyone at game start (before the first `prepare`), and to a joiner or rejoiner after
`snapshot`, only when the game has a filter, choice or ranked. `{types?, yearFrom?, yearTo?, tier?,
answerMode?, gameMode?}`: only the set filter fields; `answerMode: "choice"` only for choice;
`gameMode: "ranked"` only for ranked (`chat` and `first` are left out).

### server: lobbySettings

The host's latest lobby selection, display-only for guests. To everyone when the host changes it, to
a joiner or rejoiner in the lobby phase, and to everyone at each return to the lobby. Fields as
`settings`, plus `songCount` (integer).

### server: roomTheme

**Legacy** (D-136). `{theme}`. To everyone when the host sends `roomTheme`; to a joiner or rejoiner
only when the room's theme is not the default `sunset`. New clients may ignore it.

### server: prepare

To everyone when a song is prepared; to a joiner during an early-prepared reveal, alone. Load the
audio, then send `ready`.

| Key | Type | Notes |
|---|---|---|
| `index`, `total` | integer | |
| `audioUrl` | string | `/audio/<token>`, new on every prepare (also on a resume's re-prepare of the same song) |
| `choices` | `[{coverUrl}]` | choice room only; `coverUrl` is `/cover/<id>`; shuffled, nothing marks the correct one |
| `correct` | `[{name, rank}]` | ranked only, and only on a resume's re-prepare of a song that already has a ranking |
| `holdMs` | integer | prefetch on reveal only: play nothing for this long (the rest of the reveal pause); absent otherwise |

### server: start

To everyone when the song starts (all ready, or the 25 s prepare timeout).
`{index, startAt, deadlineAt, serverTime}`: play from the start of the audio at `startAt` (3 s after
`serverTime`); the song ends at `deadlineAt` (at most 90 s after `startAt`, or the audio's end).

### server: chat

`{name, text}`. Recipients follow rules 4 to 7: in the lobby, to everyone; in a game, to everyone,
or to the sender only (an early matching line, a watcher's line before the reveal, a ranked correct
line).

### server: correct

To everyone. `{index, name, rank?}`: in 선착순 the winner of song `index` (the reveal follows); in
ranked `rank` (1, 2, 3...) is present and the song goes on. Names nothing of the song.

### server: wrong

Choice rooms only, to everyone, after a wrong pick. `{index, name}`: who picked wrong, never the
position.

### server: pick-result

Choice rooms only, to the sender of a wrong pick, before `wrong`. `{index, choice}`: the sender's
own wrong position (a correct pick is told by `correct` / `reveal.correctChoice`).

### server: pickState

Choice rooms only, to everyone: at every `start`, after every accepted pick, and when a player's
connection changes during PLAYING. `{index, picked, total}`: connected players who picked, and
connected players.

### server: skipState

To everyone after every vote change, and at the start of a skippable reveal window (with `votes`
0). `{index, votes, needed}`: `needed` is a majority of connected players (`connected / 2 + 1`).

### server: reveal

To everyone when the song ends (a first-come correct answer, ranked all answered, a passed skip, or
the deadline).

| Key | Type | Notes |
|---|---|---|
| `index` | integer | |
| `reveal` | string or null | the entry's own display title |
| `titles` | `{ko, en, ja, romaji}` | each an array of strings (may be empty) |
| `song` | string or null | the song name |
| `artists` | array of strings | |
| `themeType` | string | `"OP"` or `"ED"` |
| `themeSlug` | string | e.g. `"OP1"` |
| `winner` | string or null | the winner's name; in ranked the rank-1 name |
| `coverUrl` | string or null | `/cover/<id>`, null when the anime has no cover |
| `correctChoice` | integer | choice room only: the correct position in this song's `choices` |
| `nextChoices` | `[{coverUrl}]` | choice room, not the last song: the next song's choices as its `prepare` will carry them |
| `ranking` | `[{name, rank, points}]` | ranked only (3, 2, then 1 point) |
| `playUntil` | integer | play to limit on (the default): the music plays on until then; the deadline when the song was decided by answers, else now. Absent when the server runs with play to limit off |

### server: scores

To everyone right after every `reveal`. `{scores}`: `[{name, score}]` in player order.

### server: gameOver

To everyone after the last song's reveal; the room then returns to the lobby (`roster` with phase
`lobby`, and `lobbySettings` when the host has one). `{ranking}`: `[{rank, name, score}]`, score
descending, ties share a rank (3, 3, 1 gives ranks 1, 1, 3).

### server: error

`{code, ...}` to the sender, except `not-enough-songs`, a `bad-song-count` raised by the room, and
`room-closed`, which go to everyone. Extra fields: `for` (`"skipVote"` or `"pick"`) on a refused vote
or pick, so the client can undo it; `available` (integer) and `unit` (`"series"`, absent when
counting songs) on `not-enough-songs`.

| Code | Cause |
|---|---|
| `bad-message` | not a JSON object with a string `type`, unknown `type`, or a field of the wrong JSON type |
| `too-large` | frame over 4096 bytes |
| `bad-nickname` | empty or over 16 characters after trimming |
| `bad-song-count` | outside 5..100 (lobby) or 5..30 (solo `create`) |
| `bad-filter`, `bad-answer-mode`, `bad-game-mode` | see `start` |
| `already-in-room` | this socket already holds a seat |
| `server-full` | 200 lobbies exist |
| `room-not-found` | no lobby with that code (or it closed) |
| `room-full` | 8 seats taken |
| `no-room` | the request needs a seat this socket does not hold |
| `rejoin-failed` | unknown token, or the grace ran out |
| `in-game`, `not-host` | `start`, `lobbySettings` or `roomTheme` refused |
| `not-enough-songs` | the draw has fewer songs (or series groups) than asked |
| `rate-limited` | over the chat flood limit (also skip votes, with `for: "skipVote"`) |
| `too-long` | chat over 200 characters |
| `too-early` | pick before `startAt` (`for: "pick"`) |
| `already-picked` | second pick of a song (`for: "pick"`) |
| `bad-room-theme` | legacy `roomTheme` with an unknown id |
| `room-closed` | the lobby closed (2 min with nobody connected, or 10 min idle in the lobby) |

## Capturing the examples

The server examples are written by the backend socket test clients (`TestClient`, `StatusClient`)
only when the system property `songrush.captureFrames` names a folder; each type keeps the largest
frame of the run. From `backend/`:

```
.\gradlew.bat cleanTest test --console=plain "-Dsongrush.captureFrames=<absolute path to contracts/messages>"
```

`cleanTest` makes the tests run even when they are up to date. A normal `.\gradlew.bat test` writes
nothing. Hand-written examples: every `client-*.json`; no server example is hand-written (all 20
server types are produced by the existing socket tests). The largest frame is not always the
fullest shape: `snapshot.json` is a chat room in PREPARE (no socket test rejoins a choice room or a
reveal window), so its optional `song` keys are documented only in the table above.
