# 034 Game protocol and rounds (S-003)

## Questions
1. Which realtime transport, and how are messages routed per room?
2. How do clients start each song at the same moment (design 029)?
3. How is "first correct answer by server arrival order" made well-defined (D-011, D-016)?
4. How does the Java backend judge answers exactly like the data tools (D-015)?
5. What is the room and game flow, including skip, reconnect, late joiners and cleanup?
6. What must never reach a client before the reveal?

## Findings (explorer, 2026-11-03; repo and Spring documentation, no external data requests)
- **F1. Transport.** `spring-boot-starter-websocket` and its test starter are already dependencies (`backend/build.gradle:22,24`). With the raw `WebSocketHandler` API the application must serialize sends per session; Spring documents `ConcurrentWebSocketSessionDecorator` for that (Spring Framework 7 reference, WebSocket server). STOMP would add a broker and destinations a server-authoritative room game does not need; SockJS is unnecessary for the browsers of D-053.
- **F2. Clock sync.** NTP-style: the client sends `ping{id, t0}` with `performance.now()`; the server answers `pong{id, ts}` with its epoch milliseconds; offset = `ts - (t0 + rtt/2)`. Eight pings on join, keep the median offset of the three lowest-RTT samples, re-sync every 30 s and before each song. `startAt` (server epoch ms) maps to `AudioContext` time through one `(ctx.currentTime, performance.now())` pair, minus `outputLatency`.
- **F3. Lead time.** Median audio is 3.58 MB (max 16.7 MB, design 029 F3); decoding a 90 s Opus file is expected to take well under a second on desktop and up to 1-2 s on phones (unmeasured; the owner's iPhone check is I-003). A prefetch-then-ready phase makes the start independent of download speed: clients fetch and decode, report `ready`, and the server then announces `startAt = now + 3 s`.
- **F4. Ordering.** WebSocket messages arrive on container threads. A per-room single-threaded executor that runs every inbound message and every timer gives one total order, so "first correct" is the first one processed (D-016).
- **F5. normalize.** `data/songrush_data/titles.py:37-46`: NFKC; `lower()` plus `ß`→`ss` and final `ς`→`σ`; NFD, drop U+0300-U+036F, NFC; `×`→`x`; remove categories P*, S*, Z*, Cc, Cf and a small extra set (Hangul fillers, variation selectors, Mongolian free variation selectors); final NFC. `contracts/normalize-vectors.json` holds 79 shared vectors with no Java consumer yet. The catalog's `accepted` strings are already normalized (cycle 021), so matching is `accepted.contains(normalize(chat))`.
- **F6. Leaks.** Catalog theme and anime ids are public AnimeThemes ids (CLAUDE.md multiplayer rules), as are basenames, titles, song and artists. Safe before the reveal: the song index and total, an opaque audio token, `startAt`, `deadlineAt`, the phase and the roster.

## Options
1. **Transport.** (A) Raw WebSocket with JSON messages `{"type": ...}`. (B) STOMP over WebSocket. **Choose (A).**
2. **Ordering.** (A) Per-room single-threaded executor (actor). (B) `synchronized` room methods with timers on a shared scheduler. **Choose (A)**: one order for messages and timers.
3. **Start.** (A) Prefetch-then-ready, with a ready timeout. (B) A fixed lead time for everyone. **Choose (A).**
4. **Reconnect.** (A) A `playerToken` kept in `sessionStorage`; the seat and score are held for a 30 s grace period. (B) No reconnect. **Choose (A).**

## Decision
- **Rooms.** A room has a 5-character code from `ABCDEFGHJKMNPQRSTUVWXYZ23456789` (no look-alikes), generated with `SecureRandom` and retried on collision. Share link: `/r/<code>`. At most 8 players (D-046); a duplicate nickname gets ` (2)`, ` (3)`... (D-048). The host sets the song count, 5-30, default 10 (D-047, D-058), and starts the game. If the host leaves, the longest-connected player becomes host; an empty room is deleted after 2 minutes. After `gameOver` the room returns to the lobby (D-062); a room idle in the lobby for 10 minutes is deleted.
- **Players and reconnect.** On join the server issues a random `playerToken`; reconnecting with it within 30 s reclaims the seat and score, and the server sends a full snapshot. A second socket with the same token replaces the first. A player who joins while a game runs is added with 0 points from the next song (D-059).
- **Game.** At start the server draws N distinct candidates at random. Per song:
  1. **prepare:** the server issues a fresh audio token per song (TTL: prepare timeout + 90 s + 30 s) and sends `prepare{index, total, audioUrl}`.
  2. **ready:** clients fetch, decode and send `ready`; when every connected player is ready, or after 25 s, the server sends `start{startAt, deadlineAt}` with `startAt = now + 3 s` and `deadlineAt = startAt + min(durationMs, 90 s)` (D-018, D-021, D-060).
  3. **guessing:** `chat{text}` is rate-limited per player by a token bucket (burst 5, 2 per second, D-024); messages over the limit are dropped and not judged, and the sender gets a notice. A message whose normalized form is accepted ends the song: it is not broadcast; everyone gets `correct{name}` ("<name> 정답!") and the player scores 1 point (D-011, D-015, D-016). Other messages are broadcast as chat.
  4. **skip:** `skipVote` toggles the player's vote; it passes when votes are more than half of the connected players; players in their reconnect grace period are not counted (D-012, D-017, D-061).
  5. **end:** at the first correct answer, a passed skip, or `deadlineAt`, the server sends `reveal{reveal title, anime titles by slot, song, artists, winner or none}` and the scores, then waits 5 s before the next `prepare`.
  - After the last song: `gameOver{ranking}`; players with equal points share a rank.
- **Messages.**
  - Client to server: `create`, `join`, `start`, `ready`, `chat`, `skipVote`, `ping`, `leave`.
  - Server to client: `joined{playerToken, room snapshot}`, `roster`, `prepare`, `start`, `chat`, `correct`, `skipState`, `reveal`, `scores`, `gameOver`, `pong`, `error{code}`.
- **Never before the reveal:** catalog theme or anime ids, basenames, audio URLs other than `/audio/<token>`, titles, accepted strings, song, artists, durations other than `deadlineAt`, and the draw order. Record `toString` output goes to server logs only.
- **Client.** The browser uses native `WebSocket` with exponential reconnect (0.5-10 s), the clock sync of F2, an audio-unlock click (`AudioContext.resume()`) on join, full decode before `ready`, scheduling at `startAt`, and a countdown bar to `deadlineAt`.

## Design review (critic) and changes
- **H1 (stale timers and messages).** Every timer and every per-song client message carries the song index (`ready{index}`, `skipVote{index}`); room handlers ignore anything whose phase or index does not match. Tested: a correct answer's leftover deadline timer must not end the next song; a late `ready` for song k must not count for song k+1.
- **H2 (late or failed clients).** A client that becomes ready after `startAt` plays from offset `now - startAt` and stops at `deadlineAt`; a failed fetch is retried once, then the song shows "재생 실패". Reconnecting and late-joining clients follow the same rule.
- **H3 (everyone gone).** With zero connected players the game pauses: timers stop and the current song is prepared again on the first reconnect; the 2-minute deletion still applies.
- **H4, H5 (item order and size).** Items are split and ordered so M-1 (solo) is playable first; see the table below.
- **M1 (user decision D-061).** "Connected" means an open socket, for ready and for skip alike; a player in the reconnect grace period is not counted, and a leaving player's vote is removed with the result re-evaluated.
- **M2.** A late joiner (D-059) spectates until the next `prepare`: not in the ready set or the skip count; their chat is broadcast but not judged.
- **M3.** Snapshot and roster are allow-listed: phase, song index and total, the current `audioUrl`/`startAt`/`deadlineAt` when a song is running, players (name, score, host, connected), and the receiver's own skip vote. No player tokens other than the receiver's own, no catalog fields. A test checks the JSON.
- **M4.** A replaced socket gets the close code 4001 (`replaced`), and the client does not reconnect after it.
- **M5.** A chat message that matches the current song's accepted set is never broadcast in any phase before the reveal; it scores only in the guessing phase (from `startAt` to the end).
- **M6.** The client re-takes the `AudioContext` clock pair right before scheduling each song, after `resume()` if the context is not running; `start` carries the server time so a clock offset that drifted by more than the RTT triggers a re-sync.
- **M7.** The room executor never blocks on a socket: each session has an outbound queue drained by its own sender; a session whose queue overflows is closed.
- **M8.** Limits: nickname 1-16 characters, chat 200 characters, WebSocket message 4 KiB; larger input gets `error{code}`.
- **M9.** A game cannot start with fewer candidates than the song count (`error{code:"not-enough-songs"}`).
- **M10.** Rooms take an injected `Clock` and a scheduler that tests advance by hand.
- **Nits.** Audio bytes and the length derived from `deadlineAt - startAt` are accepted under D-057; drawing at game start rather than room creation is consistent with D-026 being deferred to M-5 (design 029); ties share a rank in standard competition order (1, 1, 3); after `gameOver` the room returns to the lobby and the host can start again with scores reset (user decision D-062).

## Resulting items and milestones
| Item | Milestone | Content |
|---|---|---|
| B-004 | M-1 | Java `normalize` and the shared vector test |
| B-006a | M-1 | One room's song state machine: draw, prepare/start/deadline, judging, reveal, game over, index guards, injected clock and scheduler |
| B-007 | M-1 | Minimal WebSocket endpoint: a one-player room that starts at once; bounded outbound queues |
| F-001 | M-1 | React bootstrap (already in Ready) |
| F-002 | M-1 | Solo play screen: audio unlock, fetch and decode, play on `start`, countdown, chat, reveal, final score |
| B-005a | M-2 | Rooms and lobby: codes, join, 8 players, nickname dedupe, host and host transfer, song count 5-30, start, restart after game over, cleanup, input limits |
| F-003 | M-2 | Lobby screens: create, join by code or `/r/<code>`, roster, song count, start |
| B-005b | M-3 | Reconnect: player token, 30 s grace, socket replacement (code 4001), allow-listed snapshot, pause with no one connected |
| B-006b | M-3 | Multiplayer rounds: ready with a 25 s timeout and `startAt`, skip votes (D-061), the chat token bucket (D-024), late joiners spectating until the next song |
| F-004 | M-3 | Clock sync and synchronized playback, late-start offset, and a test page for the owner's iPhone check (I-003) |
| F-005 | M-3 | Multiplayer game screen: skip, live scores, final ranking, restart |

## Requests made
None to external data sources; Spring documentation pages were read.
