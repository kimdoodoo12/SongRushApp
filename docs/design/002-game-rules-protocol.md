# 002 Game rules and room protocol (S-002)
Stack updated for D-029 (2026-11-02); decisions unchanged.

Researched 2026-11-02 by the `explorer` agent (public docs, `npm view`, and a local normalization experiment). Builds on design 001 (audio source, prefetch).

## Questions
1. Round defaults under "fixed clip + time limit": clip length, offset, time limit, rounds per game, gap between rounds.
2. Scoring by answer time; wrong guesses; ties.
3. Answer matching for anime titles: normalization, comparison, sequels/seasons.
4. Room lifecycle and realtime events, host transfer, reconnect.
5. Fair sync: preload and start at a server-announced time; acceptable skew.

## Findings

### F1. Clock sync is a small custom routine
- The common pattern is N round-trip probes, keeping the minimum-RTT sample (or the median), with `offset = serverTime - (clientSend + RTT/2)`. Sources: the PeerTime npm description, and gist https://gist.github.com/mfbx9da4/27613da25ac7ba5c4118 ("similar to NTP").
- Existing libraries are stale:

  | Package | Version | Date |
  |---|---|---|
  | `timesync` | 1.0.11 | 2022-09 |
  | `socketio-timesyncer` | 1.0.2 | 2022-05 |
  | `peertime` | 0.9.4 | 2022-05 |

- No measured browser accuracy was found. It is bounded by RTT asymmetry, which is inferred.

### F2. Starting audio together
- Web Audio scheduling uses the hardware clock: "it is a bad idea to use JavaScript's setTimeout to directly start playing audio events" (https://web.dev/articles/audio-scheduling).
- Web Audio needs the full file fetched and decoded, plus CORS. Design 001 F3 shows the audio host sends **no CORS headers**, so Web Audio decoding of AnimeThemes files is not possible without a proxy.
- `<audio>` `currentTime` is "an approximation", and seeking "can still adjust the resulting playback position" (MDN HTMLMediaElement/currentTime).
- Autoplay: "playback of any media that includes audio is generally blocked if the playback is programmatically initiated in a tab which has not yet had any user interaction" (MDN Autoplay guide). A user gesture before the first round is mandatory.

### F3. Socket.IO 4.8.4 (npm, 2026-09-25)
Sources: socket.io docs, pages rooms, emitting-events, connection-state-recovery and client-options.
- Rooms are server-only. Sockets leave all rooms on disconnect.
- Acks support per-emit timeouts (v4.4+).
- Connection state recovery restores id, rooms and `data` "for a given delay", but "the recovery will not always be successful". Check `socket.recovered`.
- Client reconnection defaults: delay 1 s, max 5 s, randomization 0.5.

> **Stack note (D-029):** Socket.IO was the transport surveyed at the time. Design 011 decision 2 uses raw Spring WebSocket at `/ws` with a JSON envelope `{type, id?, payload}`; the event names below are kept, and a reply carrying the request's `id` is the ack.

### F4. Normalization experiment
The script was a throwaway in the session scratchpad and is not in the repo; its results are quoted below.
- NFKD, then strip `\p{M}`, then NFKC, lowercase, and strip punctuation and spaces:
  - "Ōkami-san" = "Okami-san". Full width "ＳＰＹ×ＦＡＭＩＬＹ" becomes `spyfamily`, but "SPY x FAMILY" becomes `spyxfamily`, so synonyms are needed.
  - K-On!! and K-ON! both become `kon`. Distinct titles can collide.
- **Pitfall**: stripping all `\p{M}` after NFKD destroys Japanese dakuten. ガールズ＆パンツァー becomes カールスハンツァー. Only Latin combining marks (U+0300-U+036F) may be stripped.
- Autocomplete libraries (npm):

  | Package | Version | Date | Unpacked size |
  |---|---|---|---|
  | fuse.js | 7.5.0 | 2026-08 | 417 KB |
  | match-sorter | 8.3.0 | 2026-04 | 183 KB |

  A hand-written prefix/substring scorer over about 5k names needs no dependency.

### F5. Mechanics of existing quiz games (rules only)
- AMQ asks for the anime, not the song, and accepts romaji or English names (https://scrapbox.io/slashnephy/AMQ).
- A Discord anime quiz bot notes that "season names are not the same", which suggests seasons are separate answers (gist ace3df/6551b664...).
- musicquizly FAQ:
  - Speed score decays linearly and never drops below 30%.
  - Wrong guesses are not penalized.
- Other observed defaults:

  | Setting | Values seen |
  |---|---|
  | Guess times | 10 s (anime_quiz), 30 s (Music Assistant) |
  | Rounds | 3 and 5 |

- AMQ's own default settings and formula were not found (the fandom wiki returned 402).

## Options and decisions

> **Superseded (2026-11-02):** D1, D2, D3, D5 and the event/snapshot tables are superseded by design 009 (rules, protocol) and design 010 decision 3 (client playback). D4 stands, except as noted in design 009 D7.

Revised after critic review round 1 (1 Blocker, 7 High, 5 Medium; all applied). Changes are listed in "Review changes" at the end.

### D1. Round timing
| Setting | Default |
|---|---|
| Clip = guess window `T` | **20 s** (the clip plays for the whole window) |
| Clip offset | Random integer second in `[10, estDurationSec − T/1000 − 5]`. Kept catalog themes have `estDurationSec >= 37` (design 001), so with a 20 s clip the range is at least [10, 12] |
| Rounds per game | **10** |
| Reveal screen | **6 s**. Shows the anime (with year), song, artist, and per-player results |
| Ready timeout | **20 s**. Round 1 is prepared right after the host presses start |
| Lead-in | `round:start` carries `delayMs = 3000` |

Room settings (ranges, OP/ED filters) are out of scope here; M-5 covers them.

**Ready quorum and audio failure.**
- The denominator is **connected** players.
- Each prepare has a `prepareId`, and only a `round:ready` with the matching `prepareId` counts.
- If fewer than half of the connected players are ready at the timeout, the server swaps in another theme, **clears the ready set**, and sends a new `round:prepare` with a new `prepareId` for the same round number.
- **3 consecutive ready timeouts for the same round** end the game with reason `audio_unavailable` ("audio source unavailable"): the 1st and 2nd timeouts swap the theme, and the 3rd ends the game (design 001 failure path).
- If **0** players are connected, the round loop pauses. Only the empty-room timer (60 s) runs. When someone reconnects, the loop resumes from `loading`.

**Players who are not ready sit out the round.**
- When the round starts, players without `ready` are marked `missed`: 0 points, excluded from the speed ranking for that round, and shown as "로딩 실패" in the reveal. Their guesses are rejected. A `round:ready` for the current round that arrives during `playing` is ignored, so it never clears `missed`.
- This avoids scoring someone who hears the clip seconds late against the server clock. That would be a fairness bug under the CLAUDE.md rules. The alternative, compensating per player, was rejected as more complex.

### D2. Scoring
- `t = serverReceiveTime − roundStartTime`, where `roundStartTime = round:start emit time + delayMs` on the server clock.
- A guess is **rejected** in each of these cases:
  - `t < 0`
  - `t > T + 500 ms` (the 500 ms grace covers uplink delay)
  - the round number is not the current round
  - the player is `missed` for the round
  - the player has already submitted in this round
- Accepted correct guess: `score = round(1000 × max(0.3, 1 − clamp(t, 0, T) / T))`.

  | t (T = 20000) | Score |
  |---|---|
  | 0 | 1000 |
  | 5000 | 750 |
  | 14000 | 300 (the floor starts at 0.7·T) |
  | 20000 | 300 |
  | 20400 | 300 (within grace) |

- Wrong or no answer scores 0. There are no negative points.
- **One submission per player per round.** The lock is per player, not per socket, so a second tab cannot submit again.
- Ranking order:
  1. Total score
  2. More correct answers
  3. Lower mean time of correct answers. Players with 0 correct answers skip this step, so there is no 0/0 comparison.
  4. Shared rank (1, 1, 3)

### D3. Answer matching

> **Superseded by designs 008 and 009 (2026-11-02):** answers are chat messages matched on the server; autocomplete, the client anime index and `round:guess` are dropped.

- The client picks an **anime id** from autocomplete. The server checks `answerId === round.animeId` and never trusts a client correctness flag. Correctness is not revealed until `round:reveal`: the guess ack is only `{accepted}`.
- The client gets an anime index (id, name, synonyms, year) from the server. The planning cycle defines the endpoint, for example `GET /api/anime-index` built from `catalog.json`. The index holds no audio URLs.
- Normalization, used only for autocomplete:
  1. NFKC
  2. lowercase
  3. NFD
  4. strip U+0300–U+036F only
  5. NFC
  6. strip `[\s\p{P}\p{S}]`

  Critic verified these outputs during the design cycle:

  | Input | Output |
  |---|---|
  | ガールズ＆パンツァー | `ガールズパンツァー` (dakuten kept) |
  | ｶﾞｰﾙｽﾞ | `ガールズ` |
  | Ōkami-san | `okamisan` |
  | Lucky☆Star | `luckystar` |
  | SPY×FAMILY | `spyfamily` |
  | 귀멸의 칼날 | `귀멸의칼날` |

  Collisions such as K-On!! and K-ON! are harmless, because both appear as candidates and the answer is an id. Candidates show the **year**, so remakes with the same name can be told apart.
- Ranking: prefix match first, then substring match, then shorter name. Each anime id appears once. No library.
- Sequels and seasons: AnimeThemes lists seasons as separate anime. The default accepts the **exact anime only**. Accepting any anime in the same series is **D-008** (needs user decision, non-blocking).
  - The deep listing fixture has no `series` key (critic checked: 0 of 100). C-002 must add `series` to the include, and C-001 must keep `seriesIds`, so D-008 stays possible.

### D4. Rooms and events
- Room code: 4 letters from an alphabet without ambiguous characters. The alphabet is `ABCDEFGHJKLMNPQRSTUVWXYZ` (no I, O). Shareable as a link `/r/ABCD`. A code collision is retried.
- An empty room (0 connected players) is deleted after 60 s. Max 12 players.
- Host: the creator. When the host disconnects or leaves, the role transfers immediately to the earliest-joined connected player. The host only starts games and "play again"; the server runs the game itself.
- Identity:
  - `playerToken`: random, stored in **sessionStorage**. That is per tab and survives refresh, so two tabs in one browser are two players, which also makes local multiplayer testing possible. Never sent to other clients.
  - `playerId`: a separate public id issued by the server.
  - A second socket with the same token wins as the newest: the old socket gets `room:replaced` and is disconnected.
- Joining a game in progress: an unknown token is rejected with `in_game`. A known token is a reconnect. There is no spectator mode.

State machine (server, per room):
```
lobby -> loading -> playing -> reveal -> (loading | finished)
finished -> lobby (host "play again")
loading --(0 connected)--> paused --(reconnect)--> loading
```

Events (every client->server event uses an ack with a timeout):

| Direction | Event | Payload | Notes |
|---|---|---|---|
| c->s | `room:create` | `{nickname, playerToken}` | ack `{code, playerId, snapshot}` |
| c->s | `room:join` | `{code, nickname, playerToken}` | ack `{playerId, snapshot}` or error `not_found`, `full`, `in_game`. The same token means reconnect |
| c->s | `room:leave` | `{}` | |
| s->c | `room:snapshot` | **public view** (below) | after join, reconnect or a membership change |
| s->c | `room:replaced` | `{}` | to the older socket of a reused token |
| c->s | `game:start` | `{}` | host only |
| s->c | `round:prepare` | `{round, prepareId, audioUrl, offsetSec}` | no anime info. Sent for round n+1 when round n starts, and again with a new `prepareId` after a swap |
| c->s | `round:ready` | `{round, prepareId}` | counted only if `prepareId` matches the latest prepare and the round has not started |
| s->c | `round:start` | `{round, delayMs, durationMs}` | |
| c->s | `round:guess` | `{round, animeId}` | ack `{accepted}` |
| s->c | `round:reveal` | `{round, anime, theme, results[{playerId, status: correct, wrong, none or missed, points, timeMs}], scores}` | |
| s->c | `game:end` | `{ranking}` | |

The snapshot is **built per socket**, because `myStatus` differs per player. Its shared part is public. It contains:
- the room code and state
- players `{playerId, nickname, connected, isHost, score}`
- `currentRound {round, audioUrl, offsetSec, elapsedMs, durationMs, myStatus: active | missed | answered}` while `playing`
- the pending `nextPrepare {round, prepareId, audioUrl, offsetSec}`

It **never** contains a `playerToken`, an anime id or name field for the current round, or anyone's guess content. The anime name inside `audioUrl` is the D-005 exception.

**Reconnect during `playing`.**
1. The snapshot gives `currentRound.elapsedMs` and `myStatus`.
   - If `myStatus` is `active` or `answered`, the client seeks to `offsetSec + (elapsedMs + localSinceSnapshot)/1000` and plays until `durationMs` is reached. `localSinceSnapshot` covers loading and the tap overlay.
   - If `myStatus` is `missed`, it shows "이번 라운드는 건너뜀" and does not offer guessing.
2. `nextPrepare` lets the client start prefetching the next round at once.
3. A page refresh loses user activation, so the client shows a "탭해서 계속" overlay before any audio.

### D5. Sync
- **Option chosen: relative delay.** `round:start` carries `delayMs`, and each client starts `play()` `delayMs` after it receives the event. The error is the difference in one-way latency between clients. This is inferred to be tens to about 150 ms.
- **Known scoring bias.** Scoring uses the server's `roundStartTime`, but a client hears the clip one downlink delay later, so that delay is added to its `t`. A 150 ms difference is about 7.5 points, and it also affects the mean-time tie-break. This is accepted for the MVP and is revisited with S-003's numbers. The 500 ms grace is also an assumption until S-003.
- **Option rejected: NTP-style clock sync** (5 `time:sync` probes plus an absolute `startAt`). It removes the one-way latency difference, but adds an event, a probe loop and re-sync logic. The `<audio>` seek approximation and the `play()` start latency (F2) are probably larger than the gain. This is inferred, and S-003 will measure it. If S-003 shows that clock error dominates, this choice gets revisited.
- **Playback.** The client keeps **two `<audio>` elements and alternates them**, so prefetching round n+1 never touches the element playing round n.
  1. On `round:prepare`, set `src` on the idle element with `preload="auto"` and seek to `offsetSec`. Send `round:ready` on `seeked` or `canplay`.
  2. On `round:start`, wait `delayMs`, then call `play()`.
  3. Stop after `durationMs`.
- **The skew tolerance of about 300 ms is an assumption.** S-003 adds "spread between `play()` and audible start" to its measurements.
- Known issue: `round:prepare` carries the audio URL, which contains the anime name. This is D-005 (design 001): an interim exception for M-1/M-2, and M-3 waits for the user.

## Resulting items
B-items depend on B-001, and F-items depend on F-001. Milestone planning cycles turn the event wiring and UI into items, citing this doc.
- **B-002** backend: scoring and ranking (pure).
- **B-003** backend: room registry (pure, with injected clock and random).
- **B-004** backend: round lifecycle (pure). Covers offset choice, prepare and `prepareId`, the ready quorum over connected players, swap with the timeout cap, pause at 0 connected, missed marking, reveal timing, and the game end.
- **B-005** backend: guesses, reveal and snapshot (pure). Covers the guess window and grace, rejections, the per-player lock, reveal results, and the per-socket snapshot redaction.
- **F-002** frontend: title normalization and autocomplete ranking (pure). Dropped with autocomplete (design 008); design 011 reuses the id F-002 for the WebSocket client.

Acceptance criteria are in BACKLOG.md.

## Review changes (round 1)
| Finding | Change |
|---|---|
| B1: score above 1000, t undefined | `t` defined from the server's round start, clamped, rejected outside `[0, T + 500]` |
| H1: snapshot leak | Public snapshot defined; separate `playerId` |
| H2: reconnect loses the clip | `currentRound` and `nextPrepare` added to the snapshot; resume by `elapsedMs`; gesture overlay |
| H3: quorum denominator | Connected players; pause at 0 |
| H4: late player scored unfairly | Not-ready players are `missed` |
| H5: S-003 did not measure skew | Skew added to S-003; 300 ms labelled an assumption |
| H6: two tabs, one token | sessionStorage; newest socket wins; per-player lock |
| H7: no `series` data | `series` added to the C-002 include and to C-001 `seriesIds`; D-008 registered |
| M1: unrequired scope | Spectator, `round:progress`, settings and the periodic re-sync removed |
| M2: simpler sync not compared | Adopted relative `delayMs`; clock sync recorded as the rejected option |
| M3: tie-break | Correct-answer count before time |
| M4: symbols kept | `\p{S}` stripped; year shown in candidates |
| M5: prefetch overwrites playing audio | Two alternating `<audio>` elements |

## Review changes (round 2)
| Finding | Change |
|---|---|
| H1: a stale ready after a swap counted for the new theme | `prepareId` on prepare and ready; the ready set is cleared on swap; a current-round ready during `playing` is ignored |
| H2: a reconnecting client could not tell it was `missed` | Per-socket snapshot with `myStatus` |
| H3: relative delay vs the CLAUDE.md wording; downlink delay in `t` | CLAUDE.md wording updated; bias documented; reconnect seek adds local elapsed time |
| H4: swap cap could be read two ways | Counted as 3 consecutive ready timeouts |
| H5: 0/0 mean time | Players with 0 correct answers skip the mean-time step |
| H6: flaky timing test, B-004 too large, snapshot criterion impossible | Structural assertion instead of timing; B-004 split into B-004 (lifecycle) and B-005 (guesses, reveal, snapshot); criterion names fields, with audioUrl as the D-005 exception |
