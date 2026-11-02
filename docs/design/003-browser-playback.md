# 003 Real-browser playback measurement (S-003)
Stack updated for D-029 (2026-11-02); decisions unchanged.

Measured 2026-11-02 by two `explorer` runs from the user's PC (Korea, home network):
- **Browser run:** installed Chrome, headless, driven by `playwright-core@1.64.1`. The page was served from `http://localhost:5173` with UA `SongRushBot/0.1`, and it made 8 audio loads.
- **curl run:** full-file GETs to `/dev/null`, 3 s apart, a few minutes after the browser run. No audio was saved.

The scripts and raw outputs were throwaway files in the session scratchpad: `s003/probe.js`, `probe2.js`, `results_run1.json`, `results2.json` and `run2.log`, and `s003b/run.sh` and `out*.txt`. Critic re-parsed them, and the numbers below match them.

**Sample sizes are tiny (n=1 to 4 per case), and AnimeThemes' time to first byte (TTFB) swung from 4.8 s to 44 s within minutes, so every comparison between the two runs is approximate.**

## Questions
These come from BACKLOG S-003:
1. How many requests does a seek take, and how long until `canplay` or `seeked` at a 30 s offset?
2. What is the `play()` start latency?
3. Are repeated loads faster?
4. How large is the start spread between two tabs?
5. Does the 40 KB/s duration estimate hold?

One question was added after the first run: how long does a plain full-file GET take?

## Findings

### F1. Seeking inside an AnimeThemes Ogg file is far too slow
| Run | File | `loadedmetadata` | `seeked` (30 s) | Requests |
|---|---|---|---|---|
| 1 | Bakemonogatari-OP1 | 72.9 s | not reached by 90 s | 4 |
| 1 | AikatsuOnParade-ED1 | 89.1 s | not reached by 90 s | 3 |
| 1 | Ajin-ED1 | 80.0 s | not reached by 90 s | 3 |
| 2 | Bakemonogatari-OP1 | 28.6 s | **87.0 s** | 8 logged |

- Chrome finds an offset in Ogg with **serial** open-ended Range requests.
- Run 2 had 6 requests that mattered:
  - `0-` and the tail took 14.6 s and 12.8 s of TTFB. That is the metadata phase, 28.6 s in total.
  - Then **4 seek requests** took 16.0 s, 11.7 s, 9.4 s and 18.5 s. That is the seek phase, about 58 s.
  - Two more logged requests did not count: one was aborted after 9 ms, and one started after `seeked`.
- In run 1, `fromDiskCache` was false for every request. Run 2 did not record cache fields.
- **One-round-ahead prefetch with seek (design 001 option A, about 26 s budget) is not viable.** No seek completed within 26 s, and all seeking loads spent at least 28.6 s on metadata alone.

### F2. Repeat loads are not faster
Bakemonogatari-OP1 was reloaded in a new page of the same browser context. It hit no cache: its two requests had TTFBs of 43.0 s and 41.0 s, and `loadedmetadata` was not reached within 90 s.

### F3. `play()` is instant once data is buffered
When the target position was already buffered, `play()` resolved in 0-2 ms, and `currentTime` started to advance within 10-22 ms (the poll interval was 10 ms). The bottleneck is the network, not decoding.

### F4. Two-tab start spread: not measured
The result for measurement 4 is `null` in `results_run1.json`, with no error recorded. Critic's diagnosis: run 1's loads never reached `seeked`, so `play()` was called on elements with no buffer. **The roughly 300 ms skew in design 002 remains an assumption.**

### F5. Size-based duration is unreliable
| File | Size (B) | Real duration | size/40000 | size/25000 |
|---|---|---|---|---|
| AfroSamurai-OP1 | 1,176,982 | 29.9 s | 29.4 | 47.1 |
| Ajin-ED1 | 2,254,676 | **89.6 s** | 56.4 | 90.2 |
| Bakemonogatari-OP1 | 3,610,594 | 90.0 s | 90.3 | 144 |

- Bitrates differ by file, from about 25 to 40 KB/s.
- `size/40000` behaved as a lower bound in this sample except for Bakemonogatari, which was over by 0.3 s. That error is absorbed by the `-5` margin in the offset rule.
- Of the three requested short-estimate themes, only Ajin was really measured. AfroSamurai is below C-001's 1.5 MB floor anyway. C-001 needs no change.
- AfroSamurai was loaded with `preload="metadata"` and no seek. It reached `loadedmetadata` in **18.7 s** after two serial requests (`0-` aborted, then the tail).

### F6. One full-file GET takes about 12-25 s
| Test | File | TTFB | Total | Speed |
|---|---|---|---|---|
| Sequential | Bakemonogatari-OP1 (3.6 MB) | 5.9 s | 12.3 s | 286 KiB/s |
| Sequential | Ajin-ED1 (2.3 MB) | 8.7 s | 14.2 s | 155 KiB/s |
| Sequential | AikatsuOnParade-ED1 (3.6 MB) | 4.9 s | 12.5 s | 278 KiB/s |
| 3 concurrent (one was a 404), run 1 | Bakemonogatari-OP2 / OP3 | 5.9 / 5.5 s | 12.6 / 12.7 s | about 270 KiB/s |
| 3 concurrent (one was a 404), run 2 | Bakemonogatari-OP2 / OP3 | 16.7 / 16.6 s | 24.6 / 24.1 s | about 140 KiB/s |
| Repeat | Bakemonogatari-OP1 | 5.0 s | 12.5 s | no edge-cache headers |
| API call, 35 B | `/anime/bakemonogatari?fields[anime]=name` | 8.1 s | 8.1 s | |

- **Where the wait comes from.** Design 001 F3 measured DNS, connect and TLS together at under 1 s, so the wait is server-side. In this run, a 404 response (6,586 B) had the same TTFB as 200 responses sent at the same moment: 4.8 s vs 5.9 s, and 16.2 s vs 16.6 s. **Inferred:** the delay is queueing or processing at AnimeThemes, so a server of ours placed elsewhere would probably see the same TTFB. This is not measured from another location.
- **Direction, inferred:** one GET pays one TTFB, while a seek pays several serial TTFBs. That fits F1, but the two runs used different clients at different times.

## Options for delivering audio
**A. Browser prefetch with seek** (design 001 option A). Rejected by F1.

**B. Browser full-file prefetch, no seek until buffered.**
- Load the whole file into `<audio preload="auto">`, wait until `buffered` covers `[offset, offset+20]`, and only then seek, locally.
- It keeps D-002.
- **Not measured.** F1 and F5 show that Chrome still fetches the tail first (2 serial TTFBs), and it is unknown whether Chrome then streams the full body without a seek. S-004 measures this.
- Given F6's per-request TTFB (5-44 s), it likely needs a lead of 2 or more rounds, and round 1 has to be loaded during a lobby wait.
- Every player downloads from AnimeThemes, so the D-005 answer leak stays.

**C. Server cache.**
- The server downloads each game's files with one GET each, up to 3 at a time, starting when the host presses start. Clients play from our server through opaque `/audio/<clipId>` URLs, which our server serves with fast Range requests.
- Warm-up, using the observed TTFB range of 5-44 s plus about 7 s of transfer per file:
  - Round 1 is ready in **12-50 s**.
  - 10 files take **about 40-200 s** with 3 in parallel, and later files arrive during play.
- The client side of this path (seeking against our own server) is not measured.
- It removes the D-005 leak and enables CORS and Web Audio.
- Costs:
  - **It reverses D-002.**
  - Server bandwidth is about 3.6 MB × players × rounds: about 290 MB for an 8-player, 10-round game, and about 430 MB with 12 players.
  - Temporary disk or memory is needed per game.
- Legal or terms review is not listed as a cost here. The user decided on 2026-11-02 that excerpt playback is not a copyright concern for this project, and the design follows that. Round-1 critic suggested adding it; that suggestion was declined for this reason.

**D. Always start at offset 0.** No seek is needed, but metadata alone took 18.7-89 s in F1 and F5, and every clip would be the intro. Rejected.

> **Resolved 2026-11-02:** D-009 was decided (option C, then the whole-song cache in design 010). D-005 is resolved. F-002 and autocomplete were dropped (design 008).

## Decision
1. **S-004 runs before D-009 is put to the user.** (Done. See design 004: B failed 2 of 5, and the recommendation is C.) It is the next design cycle, so the user can choose between B and C with both measured.
2. **D-009 (needs the user's decision): how audio reaches players.**
   - Options: (C) server cache, which also removes the leak, or (B) browser full-file prefetch together with a leak scope from D-005.
   - The recommendation will be written after S-004. With current data, C is the only option with a measured path, at the upstream fetch only.
   - D-005 stays open. It only matters if B is chosen.
3. **Premises invalidated now.** These cannot proceed until D-009 is answered:
   - Design 002 D5 step 1, "seek on prepare, ready on `seeked`", is option A and is invalid.
   - Design 002 D1's 20 s ready timeout and "prepare n+1 when n starts" are invalid. Under B or C, round 1 alone needs 12-50 s.
   - **B-004** (round lifecycle) and **B-005**, which builds on it, are marked `[?]`, blocked by D-009, in BACKLOG.
   - **M-1** planning must not create playback, prepare or ready items before D-009 is answered. Non-audio M-1 items (UI shell, autocomplete, reveal screen) may be planned.
4. **Unaffected:** B-001, F-001, C-001..C-004, B-002, B-003 and F-002. C-001's duration rule stays as it is (F5).

## Resulting items
- **S-004** design: option B measurement and a delayMs skew check (blocking for D-009). Questions and acceptance are in BACKLOG.

## Review changes (round 1)
| Finding | Change |
|---|---|
| H1: B-004 and design 002 D1/D5 rest on a dead premise | Decision 3 lists the invalid premises. B-004 and B-005 are marked `[?]`. The M-1 limit is stated precisely |
| H2: B dismissed without measurement | S-004 is blocking and runs before D-009; "only measured option" is softened |
| H3: "network-wide" contradicts design 001 F3 | Rewritten as server-side delay (inferred); C re-estimated with the observed maximum TTFB |
| M1: seek request count and cross-run comparison | 4 seek requests; marked approximate and inferred |
| M2: D-009 replacing D-005 drops the leak question | D-005 kept; D-009 options include a leak scope under B |
| M3: two-tab test measures the wrong thing | S-004 redesigned: a localhost realtime `delayMs` path, repeated B runs with a curl baseline, Chrome UA |
| M4: lower-bound claim overstated | F5 reworded |
| M5: legal and terms cost missing | **Declined.** The user's 2026-11-02 decision is that excerpt playback is not a copyright concern |
| N1-N3 | 18.7 s noted; cache claim limited to run 1; "3 concurrent"; the 12-player bandwidth figure added |
