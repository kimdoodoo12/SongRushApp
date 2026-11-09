# 103 perf-report (D-114)

## Decision (owner, 2026-11-10)
"성능 개선에 대한 순서 지키면서 캡쳐와 그에 필요한 표같은것들도 만들어서 완벽하게 만들어"

The performance work is documented the standard way: **problem → measurement → cause → improvement → re-measurement**. It comes with captures, charts and tables, and serves both as a record and as a portfolio piece. This cycle completes the first three steps with evidence. The improvement and re-measurement steps get their own cycles and add before/after material to the same report.

## Deliverables
1. **Captures from the tool** (`perf/run.py`), on by default, `--no-capture` to skip:
   - **HAR:** one file per answer format, recorded with Playwright `record_har_path` and `record_har_content="omit"` (no bodies). Before writing, every URL query is stripped and the result is checked for `X-Amz-`, `Signature=` and `Credential=`. Files: `perf/results/<date>-<label>-<format>.har`.
   - **Screenshots** (1280×800, ko UI, theme 놀이터): per format, one while `음원을 불러오는 중...` is shown, one while the song plays, and one of the reveal. Files: `perf/results/shots/<date>-<label>-<format>-{loading,playing,reveal}.png`.
   - **Per-request timeline:** per song, a `requests` list of `{kind: audio|cover|audio-redirect|cover-redirect, path (query stripped), start_ms, end_ms, bytes}`. Times are relative to the song's `prepare`.
   - **Environment block:**
     - `client`: OS, browser name and version, and the free-text `--env-note` (for example "Korea, home Wi-Fi");
     - `server`: "AWS EC2 ap-southeast-2 (Sydney)" and "S3 ap-southeast-2", constants in the tool;
     - the local time of day.
2. **Report generator** `perf/report.py`, using the system Python with matplotlib (charts use a Korean font, Malgun Gothic, falling back to the default). It reads one or more result JSONs and writes PNGs to `docs/perf/`:
   - `timeline-<label>.png`: per song (chat and choice), bars for prepare → ready, prepare → start and prepare → audio done, with a dashed line at the 25 s server prepare timeout;
   - `waterfall-<label>-<format>-song<N>.png`: a network waterfall for the most typical song (the song whose audio-done time is closest to the median), one bar per request, labelled audio or cover N, with its size;
   - `bytes-<label>.png`: stacked bytes per song, audio against covers, for chat and choice;
   - `runs.png`: `p→audio done` across all baseline runs, chat and choice, showing the spread between runs.
3. **Baseline, 3 runs:** labels `baseline-1`, `baseline-2` and `baseline-3`, each with captures. The three runs should be at least 30 min apart where possible, and the time of each is recorded. The first run of 2026-11-09 (no captures) stays as `baseline` and is referenced as run 0.
4. **The report** `docs/performance.md`, in Korean, for the owner and for readers:
   1. **문제** — what the player experiences, with the loading screenshot.
   2. **측정 방법** — the tool, the metrics and why: the metric capped at 25 s and how it was replaced (the measurement pitfall), the environment, and how to reproduce.
   3. **기준값** — a table across the 3 runs (median and range), the `runs.png` chart and `timeline-*.png`.
   4. **원인 분석** — each cause with evidence:
      - the audio bitrate and size against the 90 s that is played;
      - the throughput from Korea to Sydney;
      - the cover sizes and their count in choice mode, with the waterfall;
      - the 25 s timeout and late starts;
      - no prefetch, using the timeline from reveal to prepare;
      - JS not compressed.
   5. **개선 계획** — ordered by expected effect against effort, with the expected numbers (marked as estimates), and how each will be verified with the same tool and conditions.
   6. **개선 기록** — before/after tables and images, filled in later.

## Acceptance
- **Unit tests** in `perf/test_report.py` (data venv pytest) cover the pure parts of `report.py`:
  - choosing the typical song;
  - building the waterfall rows from `requests`;
  - building the bytes series;
  - summarising across runs (median and min–max per format).
- **Existing tests** in `pytest perf` keep passing.
- **The three baseline runs exist,** each with a JSON, 2 HARs and 6 screenshots.
- **Clean output:** a grep of all JSON and HAR files for `X-Amz-`, `Signature=` and `Credential=` finds nothing.
- **Charts** are generated, and the lead reviews them by eye.
- **The report** has the 6 sections, and every number in it is traceable to a results file or a command shown in the report.

## File ownership
- **Test writer:** `perf/test_report.py`, plus additions to `perf/test_run.py` if the request timeline helpers are pure.
- **Implementer:** `perf/run.py`, `perf/perflib.py`, `perf/report.py` and `perf/README.md`.
- **Lead:** `docs/performance.md`, `docs/perf/*.png` (generated) and `perf/results/*` (generated).

## Out of scope
- Any improvement to the site itself.

## Result
- **Commits.**
  - Spec: `b7ab1ed`.
  - Tests: `6435648`.
  - Tool: `85e1719`, plus the full-page screenshot fix and baseline-1 in `9ce8b5e`.
  - Report: `b31d4fe`, revised in the commit after it.
- **Verifier.**
  - `pytest perf`: 73 passed, with no `.pytest_cache`.
  - The locked tests were unchanged.
  - 4 JSON and 6 HAR files have 0 secret markers.
  - Each of baseline-1, -2 and -3 has 1 JSON, 2 HARs and 6 screenshots.
  - All 5 image links in the report resolve.
  - The `--help` of `run.py` and `report.py` works under the system Python. They need Playwright and matplotlib, which the data venv does not have; the README says so.
- **Baseline runs (KST).** baseline at 11-09 23:26 (from cycle 102), baseline-1 at 11-10 00:33, baseline-2 at 00:39 and baseline-3 at 01:07.
  - Runs 1 and 2 are 6 minutes apart, not the 30 the spec asked for. The report states this in 3.3.
- **Report review (critic).** No Blocker. All five Highs were fixed in the report:
  - **H1:** late players start mid-song and miss the opening (`playback.js`). They do not hear it late. In the evening run one song missed about 13 s.
  - **H2:** improvement 1 conflicts with D-053 (no transcoding). It is now marked as the owner's decision.
  - **H3:** every number now has a reproducible command and its output (appendix A). The live JS gzips to 115,065 B.
  - **H4:** the claim about time of day was softened to "varied between runs, cause unconfirmed". Two more 11 pm runs come first.
  - **H5:** covers do not measurably slow the audio (0.47 against 0.46 MB/s; pooled 7.0 s against 7.6 s). Cover shrinking is framed as a transfer-volume fix.
  - **Mediums**, all applied: a limits section, the meaning of the pooled and per-run medians, KST everywhere, the perceived wait between songs (13 to 30 s), the waterfall song's audio size, the fairness note on prefetch, and a glossary.
  - **Nits**, all applied: chart labels, the cover wording and the README example path.
- **Open, before improvements.**
  - Two more baseline runs around 11 pm.
  - Owner decisions: the target values, and D-053 for audio re-encoding.
