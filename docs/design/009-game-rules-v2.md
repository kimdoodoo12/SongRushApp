# 009 Game rules v2: chat answers, first-correct wins (S-011)

Stack updated for D-029 (2026-11-02); decisions unchanged.

Written 2026-11-02 by the lead from the user's answers. This revises **design 002 D1, D2, D3 and D5**. Design 002 D4 (rooms, identity, reconnect) still stands except where noted here.

Binding user decisions:
- **D-011, D-016.** Only the first correct answer scores. "First" means server arrival order, with no latency compensation.
- **D-012, D-017.** A skip vote passes with more than half of the connected players.
- **D-013.** No hints.
- **D-014.** The host sets the number of rounds.
- **D-015.** Answers come by chat. Every message is compared, attempts are unlimited, wrong answers are visible, and the correct message is hidden and announced.
- **D-018, D-021.** The whole song plays from the start, for at most 90 s. The round ends at the first correct answer, a skip, or the end of the song.
- **D-019.** The popular top ~1500 themes are pre-cached.

## Questions (BACKLOG S-011)
1. How "first" is decided, and what happens after it.
2. Guess attempts.
3. Skip vote.
4. Host-set rounds.
5. What changes in the protocol, scoring and ranking.

## Findings
- **F1. Reference behavior** (`docs/research/game-mechanics-2026-11-02.md`): the StarCraft use-map quiz.
  - The first chat answer takes the point.
  - Most maps ignore spaces.
  - Enough players pressing K skips the song.
  - The host sets options.
  - Ping makes near-simultaneous answers look different to each player.
- **F2. Arrival order within a room is total.** Spring delivers each WebSocket session's messages on its own thread, so the order is enforced by the room's single-thread executor (design 011 decision 2): every inbound message and every timer for that room runs on it, one at a time, in the order it was submitted. The first `chat:send` that matches and is handled wins; there is no tie to break.
  - This follows from the per-room executor (design 011). B-005 pins it with a test that submits two matching messages to the same room back to back.
- **F3. Latency bias (accepted by D-016).**
  - A player hears the song one downlink delay after `round:start + delayMs` (design 002 D5).
  - Their answer reaches the server one uplink delay later.
  - So a player with RTT 150 ms is effectively 150 ms behind a player on 0 ms. The user chose this over compensation.
- **F4. Audio timing inputs** (S-010 explorer, design 010):
  - cached files (whole songs, at most 90 s, design 010) remove the upstream wait;
  - a ~1.4 MB file reaches 8 players in a few seconds;
  - design 004 measured ≤13 ms skew with `delayMs = 3000`.

## Decisions

### D1. Round flow
```
lobby -> loading -> playing -> reveal -> (loading | finished)
finished -> lobby (host "play again")
loading --(0 connected)--> paused --(reconnect)--> loading
```
- **`playing` ends at the first of these:**
  1. **A correct answer.** Reason `correct`, with the winner's `playerId`.
  2. **A passed skip vote.** Reason `skipped`.
  3. **Time.** Reason `timeout`, when `playDurationMs = min(theme.durationMs, 90000)` has passed since the round started on the server clock (`round:start` send time + `delayMs`).
- **No settle window.** The round ends on the first correct answer. A later matching message reaches the server in `reveal` and is handled as reveal chat (D-016, server arrival order).
- **Every round timer is checked against the round's sequence number.**
  - Each round has a `roundSeq` that never repeats, including across swaps.
  - Timers check `roundSeq` when they fire: the timeout, the ready timeout and the reveal timer. A timer whose `roundSeq` is not current does nothing.
  - Without this, round n's 90 s timer could end round n+1 after an early win.
- **Lead-in.**
  - `playing` starts when `round:start` is sent.
  - Chat is compared only from the server's `roundStartTime = send time + delayMs` onward. During the 3 s lead-in a matching message neither wins nor is broadcast; a non-matching message is broadcast.
  - This restores design 002's `t < 0` rule.
- **`reveal` lasts 6 s** (unchanged). It shows the anime's reveal title and year, the song and artist, the reason, and the winner.
- **Loading.**
  - `round:prepare` for round n+1 is sent when round n starts, so the lead is 1 round. Round 1 is prepared when the host presses start.
  - The ready timeout is **15 s** (was 20 s; the files are served from our cache, design 010).
  - The quorum, the `prepareId` rules, the swap on timeout, the 3-strikes `audio_unavailable` end, and `missed` all stay as in design 002 D1.
- The client starts playback `delayMs = 3000` after `round:start`, as in design 002 D5, and stops it on `round:reveal`. The two-element player is replaced by design 010's loader.
- **Theme pool exhaustion.** A swap takes the next spare from the room's drawn list (D-026). If none is left, the game ends with `audio_unavailable`, as for the 3rd timeout.

### D2. Chat and answers
- **Event: `chat:send {text}`** (c→s), acknowledged by an envelope reply with the request's `id`, carrying `{ok}` or `{error}` (design 011 decision 2).
  - `text` is trimmed. Empty text and text longer than **100 characters** are rejected.
  - A **flood limit** applies: a burst of 5 messages, refilling at 2 per second per player. A message over the limit gets `{error: "rate_limited"}` and is not compared.
  - These are technical limits against flooding, not attempt limits. D-015's "unlimited attempts" holds within normal typing speed.
- **Server handling, in arrival order:**
  - **If the room is `playing`, the sender is not `missed`, and `isCorrect(text, accepted(currentGroup))` (B-006):**
    - the sender wins the round, and their message is **not broadcast**;
    - the server sends `round:reveal {..., reason: "correct", winnerId}`.
  - **A `missed` player's chat during `playing` is rejected** with `{error: "missed"}` and is never compared. A missed player could otherwise type the answer inside a longer sentence, which would not match exactly and would be broadcast. Rejecting all of it leaves no comparison branch to leak through (CLAUDE.md: never send the answer before the round ends). Missed players chat normally in `reveal` and `lobby`.
  - **Otherwise** the server broadcasts `chat:message {playerId, text, at}` to the room. That covers wrong answers and lobby and reveal chat.
- **No per-player lock.** After a wrong answer the player can try again at once (D-015).
- **Flood limit token bucket.**
  - The bucket holds up to 5 tokens and refills continuously at 2 per second.
  - A rejected message uses no token.
  - Whether a sustained 2 messages/s ceiling fits D-015's "no cooldown" was asked as D-024. **Decided: keep it.** Without some ceiling, a bot pasting every title would win every round.
- **Threat model (prefetch).**
  - A modified client could play the prefetched next-round file early and answer right at `roundStartTime`.
  - For a friends-only game this is **accepted for now**. The fix, if wanted, is to encrypt each file with a per-round key sent at round start; that was asked as D-025. **Decided: accepted for now, to be revisited before M-4.**
  - The lead-in rule above stops answers before the start.

### D3. Scoring and ranking
- The winner of a round gets **1 point**. Nobody else gets points, and there are no negative points.
- **Ranking** orders players by points, highest first. Equal points share a rank, so the ranks run 1, 1, 3.
  - There is no time tie-break: design 002's mean-time rule needed per-guess times, and first-wins rounds do not produce them.
- The speed formula `score(t)`, `isWithinWindow` and the 500 ms grace are **removed**.

### D4. Skip vote
- **Events:** `round:skip {round, on: boolean}` (c→s) and `round:skipState {round, votes, needed}` (s→c, on every change).
- **Rules:**
  - Votes count only while `playing`, and for the current round.
  - Any connected player may vote, including `missed` players (they also want the round over).
  - `needed = floor(connected / 2) + 1`, which is more than half: 1 of 1, 2 of 2, 2 of 3, 3 of 4. When a player disconnects, their vote is removed and `needed` is recomputed.
  - **`votes >= needed` is checked whenever votes or the connected count change.** For example, with 4 connected, 2 votes and `needed` 3, a non-voter disconnects: `needed` becomes 2 and the skip passes.
  - A passed skip ends the round with reason `skipped`, and the reveal shows the answer with no winner.

### D5. Host-set rounds
- **`room:create {nickname, playerToken, settings: {rounds}}`.**
  - `rounds` is an integer from **5 to 30**, default **10**. Anything else is rejected with `bad_settings`.
  - The range is a technical default, not a user decision. It can change without redesign.
- **`room:settings {rounds}`** (c→s) lets the host change the setting in `lobby` only. It exists for "play again" after `finished → lobby`, where the previous settings are kept. A non-host gets `not_host`, and the call is rejected in any other state.
- The setting appears in the snapshot as `settings.rounds`.
- The game ends after `settings.rounds` reveals.

### D6. Theme pool

> **Updated by D-026 (design 010 "Decision update"):** themes are drawn at room creation from all catalog themes, and uncached ones are prefetched in the lobby; the rest of this section is superseded.

- Rounds draw without repetition from the **pre-cached** themes (D-019, design 010). The pool must have at least `rounds` themes.
- Repeated anime are allowed only when the pool has no other choice.
- Mode filters (OP/ED, year, difficulty) are M-5.

### D7. Protocol changes (relative to design 002 D4)
Events travel over the raw WebSocket at `/ws` as a JSON envelope `{type, id?, payload}`, where `type` is the event name below. A request that carries `id` gets a reply with the same `id`; that reply is the ack (design 011 decision 2).

| Direction | Event | Change |
|---|---|---|
| c->s | `room:create` | adds `settings {rounds}` |
| c->s | `room:settings` | new |
| c->s | `round:guess` | **removed** (replaced by `chat:send`) |
| c->s | `chat:send` | new |
| s->c | `chat:message` | new |
| c->s | `round:skip` | new |
| s->c | `round:skipState` | new |
| s->c | `round:prepare` | `{round, prepareId, audioUrl}`; no `offsetSec` (the song plays from 0); `audioUrl` is opaque (design 010) |
| s->c | `round:start` | `{round, delayMs, playDurationMs}` |
| s->c | `round:reveal` | `{round, anime: {revealTitle, year}, theme: {slug, song, artists}, reason: correct, skipped or timeout, winnerId?, scores}`; it also ends playback |

- **Snapshot during `playing`:** `currentRound {round, audioUrl, elapsedMs, playDurationMs, myStatus: active or missed, skip: {votes, needed, mine}}`, plus `nextPrepare`, `settings`, players and scores.
  - It never contains the anime, the group, the accepted strings, or a hidden correct message.
- **Reconnect** during `playing` resumes from `elapsedMs`, as in design 002 D4.
  - After a page refresh the Blob is gone, so the client downloads the file again from the round's `audioUrl` and then seeks.
  - A `missed` player may also load and play from `elapsedMs` to listen along, but cannot chat until the reveal.

## Options considered
- **"First" judgment.** The user chose (a) server arrival order over (b) RTT compensation and (c) client-reported time. (c) is spoofable; (b) adds RTT probes.
- **After a correct answer.** The user chose an immediate end over playing on to the time limit.
- **Settle window after a win.** Rejected. Once arrival order defines "first", a window would only add latency.
- **Tie-break on equal points.** Rejected for now, because there is no per-guess timing to use. Shared ranks are simplest.

## Review changes (round 1)
Critic: 0 Blocker, 2 High, 5 Medium, 5 Nit.

| Finding | Change |
|---|---|
| H1: a stale timer can end the next round | `roundSeq` check on every timer; B-004 criterion added |
| H2: a missed player's sentence containing the answer would be broadcast; hidden messages act as an oracle | All missed-player chat is rejected during `playing`, with no comparison |
| M1: backlog said a second matching message is not broadcast | Fixed: it is reveal chat |
| M2: token-bucket wording | Criterion restated with same-instant sends and rejected messages using no token |
| M3: flood limit vs "no cooldown" | Recorded as D-024 for the user |
| M4: skip passes on disconnect untested | Check on every vote and connection change; criteria for 4→3 and 2 players |
| M5: prefetch lets a modified client hear early; lead-in answers were accepted | Lead-in answers ignored; prefetch threat accepted for now, encryption recorded as D-025 |
| N1 | `round:end` merged into `round:reveal` |
| N2 | Pool exhaustion on swap ends the game with `audio_unavailable` |
| N3 | `room:settings` rationale (play again) and settings persistence stated |
| N4 | Wording fixed ("cached files"); reconnect after refresh re-downloads; missed players may listen |
| N5 | M-1 wording is fixed on the design 008 branch |

## Resulting items
- **B-002 rewritten:** ranking only.
- **B-003 extended:** room settings.
- **B-004 rewritten:** round lifecycle v2.
- **B-005 rewritten:** chat, skip, reveal and snapshot.
- All four leave `[?]`. B-004 and B-005 depend on B-007 (manifest, `durationMs`), B-008 (drawn list) and B-006 (design 008).
