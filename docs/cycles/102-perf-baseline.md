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
