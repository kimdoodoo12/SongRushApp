# 004 Browser full-file prefetch and delayMs skew (S-004)
Stack updated for D-029 (2026-11-02); decisions unchanged.

> **Note 2026-11-02:** D-009 was decided by the user (server delivery; design 010).

Measured 2026-11-02 by `explorer` from the user's PC with installed Chrome (headless, `playwright-core@1.64.1`). Part 1 set no User-Agent override, so headless Chrome's default UA was sent (probably containing "HeadlessChrome"; the string was not recorded), with a fresh profile per run. Part 2 used only localhost. The scripts and raw data were session-scratchpad files: `s004/part1.js`, `part1-results.json`, `part2.js`, `part2-results.json` and `server.js`. The numbers below are quoted from them.

## Questions (BACKLOG S-004)
1. Option B: with `<audio preload="auto">` and no seek, how soon is `[30, 50]` buffered? The pass mark is within 52 s in at least 4 of 5 runs. Does Chrome stream the whole file?
2. How large is the start skew of design 002's `delayMs` path?

## Findings

### F1. Option B fails the pass mark (2 of 5)
| Run | File | `loadedmetadata` | `[30,50]` buffered | Final buffered |
|---|---|---|---|---|
| 1 | Bakemonogatari-OP1 | 35.7 s | 67.1 s | [0, 81.2] [88.2, 90.0] |
| 2 | Bakemonogatari-OP2 | 26.6 s | 54.9 s | [0, 84.1] [88.1, 90.5] |
| 3 | OP1 | 16.5 s | **42.8 s** | [0, 81.2] [88.2, 90.0] |
| 4 | OP2 | 18.3 s | **40.6 s** | [0, 84.7] [88.1, 90.5] |
| 5 | OP1 | 28.7 s | 59.0 s | [0, 80.9] [88.2, 90.0] |

- The median is 54.9 s and the worst run is 67.1 s.
- Every run showed the same three **serial** requests, each paying its own time to first byte (TTFB) of 6.5-19.5 s:
  1. `bytes=0-`, aborted after about 0.5 s of body
  2. the tail range, for the duration
  3. `bytes=65536-`, aborted after about 17.3-21.8 s of transfer
- **Chrome does not stream the whole file.** At the end, `networkState` was IDLE and `readyState` was 4. A 3.4-7.3 s hole before the fetched tail was never filled.
- **This hole breaks late offsets.** Design 002 D1 lets a clip run up to `est - 5`; for OP1 (est 90) the last clip is [65, 85]. All three OP1 runs stopped buffering at 80.9-81.2 s, so offsets 61-65 (5 of 56 integer offsets) would stall mid-clip for another TTFB, however long the lead. If B were used, the offset ceiling would have to follow the actual buffered end.
- curl TTFB just before each run was 6.6-25.1 s. The curl totals are invalid: on Windows, the design-cycle measurement script wrote to `/dev/null` and curl exited with code 23 and `size_download=0`. Design 003 F6 remains the only full-GET timing.

### F2. Browser-side skew of the delayMs path is about 1 ms
Two pages on one machine, 10 repetitions, `round:start {delayMs: 3000}` sent over a localhost realtime connection by a script, measured during the design cycle (the script used Socket.IO; design 011 uses raw WebSocket at `/ws`). Values are page B minus page A:

| Metric | min | median | max (absolute) |
|---|---|---|---|
| Receipt | −13 | −0.5 | 13 ms |
| `playing` event | −12 | −1 | 12 ms |
| First `currentTime` advance | −2 | −0.5 | 4 ms |

- The `delayMs` timer itself added 0-4 ms: `playing` came 3000-3004 ms after receipt.
- **Scope:** both pages share one Chrome process and one audio output, so this measures only timer and pipeline jitter. It does not cover one-way network latency differences between devices, or per-device output latency such as Bluetooth, which `currentTime` does not reveal. Within that scope, the jitter is small against the 300 ms assumption.

## Conclusion for D-009
Both options compared on the same evidence base. The TTFB observed today per request was 4.8-44 s (design 003 F1, F2 and F6; this doc's curl runs 6.6-25.1 s).

| | B. Browser full-file prefetch | C. Server cache |
|---|---|---|
| Requests to AnimeThemes | 3 serial, **per player** per clip | 1 per clip **per game** (server) |
| Clip ready, measured | `[30,50]` at 40.6-67.1 s (F1, 2 of 5 within 52 s) | upstream full GET 12.3-24.6 s in design 003 F6. With the worst observed TTFB, about 50 s |
| Round 1 | needs a lobby preload | needs a lobby wait of about 12-50 s (design 003 estimate) |
| 10 clips | needs a lead of 3 or more rounds. Each player's readiness is the **slowest** of their own serial TTFBs, so worst cases of 100 s or more are plausible (inferred from 41-44 s TTFBs × 3) | about 40-200 s warm-up with 3 in parallel (design 003 estimate); later clips arrive during play |
| Late offsets | the end of the file stays unbuffered; the offset range must shrink (F1) | the full file is cached, so any offset works |
| Several clips preloaded at once | not measured | not measured (server-side parallel GET measured only 2 at a time) |
| Client seek | local after buffering | **not measured** (against our server) |
| Answer leak (D-005) | stays | gone |
| D-002 | kept | **reversed** |
| Cost | none on our side | about 290-430 MB of server egress per game, plus temporary storage |

**Recommendation: C**, because it is the only option where AnimeThemes' slow, variable TTFB is paid once per clip by the server ahead of play, instead of by every player, serially, during play. It also removes the leak.

The `delayMs` sync of design 002 should then work, but only once client seek against our server is measured (inferred). Design 002 D1's ready timeout and prepare lead must be redesigned for a lobby warm-up in either case (design 003 decision 3).

The C implementation items, queued only after the user answers, must start with a measurement: server warm-up time from the deployed host, and client seek time against it.

## Resulting items
- **D-009** recommendation updated to C, citing this doc. Implementation items for C are queued only after the user answers.
- **S-005** design: hosting facts for both D-009 options.
  - It covers B (WebSocket only, small egress) and C (WebSocket plus cache, about 290-430 MB egress per game).
  - It must not pick a host; that is the user's M-4 decision.
  - Output: a cited table per host and a monthly cost comparison under stated assumptions (games per month).
- **S-006** design: AnimeThemes database dumps or bulk export.
  - **Low priority:** C-002 caches its pages, so the about 13 min crawl is a one-time cost.
  - It is appended after C-002 by the append rule. Moving it earlier is the user's call.

## Review changes (round 1)
| Finding | Change |
|---|---|
| H1: C figures cherry-picked | Conclusion table compares B and C on the same TTFB base; round 1 for C needs a lobby wait of 12-50 s |
| H2: B's late-offset failure missed | F1 notes the unbuffered end hole, which breaks offsets 61-65 |
| M1: 3-round-lead B dismissed in one line | Table rows for lead, slowest-player effect, per-player requests, and unmeasured parallel preload |
| M2: "usable as designed" overclaimed | Made conditional on the client-seek measurement; D1 still needs a redesign |
| M3: skew scope | Same output sink; Bluetooth and output latency out of scope |
| M4: BACKLOG contradicts the doc | S-004 closed; D-009 and D-005 texts updated; S-005 and S-006 queued; design 003 back-reference added |
| M5: S-005 and S-006 form and order | S-005 covers both options with an Output line; S-006 marked low priority |
| N1-N3 | Ranges corrected; UA wording made precise |
