# 102 perf-baseline (D-113)

## Decision (owner, 2026-11-09)
"현재 시간이 얼마나 가는지 성능체크를 기록하고 저장했으면 좋겠는데. 그래야 비교할거 아니야"

- A measurement tool and its saved results live in a new top-level `perf/` folder (owner's choice).
- Each run plays 5 songs with chat answers and 5 songs with choice answers on the live site, and records medians. Network conditions vary, so the medians are what get compared.
- This cycle only records the baseline. Improvements (audio size, cover size, prefetch, gzip) come later and are compared against it.

## Tool: `perf/run.py`
- **Command:** `python -X utf8 perf/run.py --base https://animeopguessr.com/ --songs 5 --label <text>`. It uses Playwright with Edge, the same stack as the lead's checks. It needs only `playwright`, with no new dependency in `data/`.
- **For each answer format** (chat, then choice), the tool:
  1. opens a fresh browser context;
  2. creates a room as host, sets the answer format, and starts an N-song game;
  3. for each song, waits until it starts, skips it, and waits for the reveal.
- **Per song it records:**
  - `prepareToReady_ms`: the `prepare` frame received → `ready` sent;
  - `prepareToStart_ms`: `prepare` received → `start` received;
  - audio: the `/audio/` redirect time, then the final response's status, bytes, TTFB and total ms;
  - covers: count, total bytes, and the slowest cover's total ms.
- **Per run it records:**
  - page load ms (navigation to `load`);
  - the JS and CSS asset sizes and their `content-encoding`;
  - home TTFB (median of 3 plain requests);
  - the label, the ISO timestamp (UTC), the git commit of the checkout, and the site's JS asset name (which identifies the deployed build).
- **Output:**
  - The raw result goes to `perf/results/<UTC date>-<label>.json`, with per-song rows and per-run medians.
  - A summary line is printed.
  - A row is appended to the table in `perf/README.md`. The columns are date, label, build, median prepare→start (chat / choice), median audio MB and MB/s, median cover KB, JS KB and its encoding, and page load ms.
- **Etiquette:**
  - It names its players `perf-<n>` and leaves the room at the end.
  - It sends no answers.
  - It plays only one game per format.
  - There is no load testing: this measures one user's experience, not server capacity.
- **It never writes secrets,** and never writes presigned URLs or their query strings into the results: hosts and paths only, with the query stripped.

## `perf/README.md`
- What is measured and why: the song-preparation wait.
- How to run it, and how to read the table.
- The table, with the baseline row from this cycle.

## Acceptance
- **Baseline run.** The lead runs the tool against the live site. The run produces a JSON file in `perf/results/` and a README row, and both are committed.
- **No URL queries.** A grep of the JSON for `X-Amz-` finds nothing.
- **Pure helpers have unit tests.** The median computation and the URL query stripping live in a small module, tested in `perf/test_run.py` and run with the data venv's pytest: `data/.venv/Scripts/python.exe -m pytest perf`. The live run itself is not part of CI.
- **No other area changes.** Existing tests are unaffected.

## File ownership
- **Implementer:** `perf/**`.
- **Test writer:** `perf/test_run.py`.

## Out of scope
- Any performance improvement.
- Server-side metrics.
- Load testing.

## Review round 1 (critic, on the first baseline run). These override the text above.
The first baseline run (untracked, discarded) showed 7 of 10 songs not ready when the server started them at its 25 s prepare timeout. `prepareToStart_ms` is therefore capped at 25 s by `Room.PREPARE_TIMEOUT` and cannot show improvements.
- **B1. Honest metrics.**
  - **Per song:**
    - `readyBeforeStart` (bool);
    - `prepareToAudioDone_ms`, from `prepare` received to the end of the final audio response, uncapped (epoch-based: Playwright `timing.startTime` + `responseEnd`, against a wall-clock `prepare` time);
    - `inflight_at_prepare`, the audio and cover requests still pending when `prepare` arrived.
  - **Per format:** the `readyBeforeStart` count out of `songs`, the median of `prepareToAudioDone_ms`, and `audio_missing` (songs whose audio is None).
  - **`prepareToReady_ms`:** songs without a ready are filled with their start time as a lower bound, and `prepareToReady_censored: true` is set when a filled value sits at the median position.
  - **README row columns,** in order: date, label, build, then for chat and for choice `ready k/N` and `p→audio done` median ms, then audio MB (all), audio MB/s (all), choice cover KB (choice median), JS KB with its encoding, and page load min–max ms.
  - A cell gets a `*` when `audio_missing` is greater than 0.
  - "How to read" says that `p→s` is capped by the 25 s server timeout, and that the headline comparison is `p→audio done` and `ready k/N`.
- **H1. One song at a time.** Before clicking skip, the tool waits until this round's audio and cover requests have finished (up to `--song-timeout`), so downloads of different songs do not overlap. A song with `inflight_at_prepare > 0` is still recorded and marked.
- **H2. Attribution by round.** Each request is tagged with the round current at its creation, and fetches are keyed by (round, url). A cover shown in a round without a network request is counted as `cached`, not as bytes.
- **H3. Nothing missing silently.** Failed requests are recorded in `failedRequests` (round, path, error), and requests still pending at the end go to `uncountedRequests`. The settle wait is `--song-timeout`.
- **M2. Page load.** Page load is measured before the games, 3 fresh-context loads with their median and min–max. README notes that the WebSocket connection is not included, and that home TTFB is a cold request (DNS, TCP and TLS included).
- **Tests.** The pure aggregation is tested first in `perf/test_run.py`, with a declared rewrite of the `readme_row` tests.

## Result
- **Commits.**
  - Spec: `0f7eb50`; review round 1: `569046b`.
  - Tests: `343b514`; declared rewrite `3c98802`.
  - Tool: `553bd2c`; attempt 2: `2e5509a`.
  - Baseline result: the commit after `2e5509a`.
- **Verifier at `553bd2c`.**
  - `pytest perf`: 28 passed, with no `.pytest_cache`.
  - The locked test was unchanged.
  - No `X-Amz-` strings in the results.
  - Only `perf/`, BACKLOG and this doc changed.
  - After attempt 2, the lead re-ran pytest: 38 passed.
- **Review round 1 changed the method** (see above). The first live run was discarded: 7 of 10 songs were not ready at the 25 s server timeout, so its headline metric was the timeout constant.
- **Baseline** (`perf/results/2026-11-09-baseline.json`), live site, build `index-BL8wr47a.js`, owner's PC network in Korea:
  - **Ready before the start:** chat 4/5, choice 3/5. The others started before their audio arrived.
  - **Prepare to audio done,** median: chat 18.9 s, choice 21.2 s. Range 16.9 s to 40.8 s.
  - **Audio:** median 3.58 MB at 0.18 MB/s.
  - **Choice covers:** median 2,020 KB per song (6 covers). The reveal cover in chat is 97 to 529 KB.
  - **JS:** 367 KB, not compressed.
  - **Page load:** 2.1 to 3.9 s.
  - **Run health:** no song started while another download was still in flight, no requests failed, and nothing was left uncounted. No `X-Amz-` strings.
- **What it points at.** Audio size (316 kbps originals; only 90 s is played), cover size (up to 856 KB), no prefetch of the next song, and JS without gzip. The owner decides what to do next.
