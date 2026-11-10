# perf: the song-preparation wait (D-113)

Every song starts with a wait: the server sends `prepare`, each browser
fetches the song's audio (and in a choice room the choice covers), decodes it,
and answers `ready`; when everyone is ready, or at the server's 25 s prepare
timeout, the server sends `start`. This folder records how long that wait is
on the live site, so later changes (smaller audio, smaller covers, prefetch,
compression) can be compared against a saved baseline instead of a feeling.

It measures one user's experience, not server capacity. It is not a load test.

## What one run does

`perf/run.py` opens Edge through Playwright (autoplay allowed, locale ko-KR):

1. loads the home page in 3 fresh browser contexts (page load);
2. then, for each answer format, chat then choice, in a fresh context:
   1. creates a room as host `perf-1`, sets 정답 형식 and 곡 수, and starts the game;
   2. for each song, waits until it is audible, then until this song's audio
      and cover downloads have finished (so two songs' downloads never
      overlap), presses 스킵, and waits for the reveal;
   3. after the last song goes back to the lobby and leaves the room.

It sends no answers and plays one game per format.

## What is recorded

Per song (`songs` in the JSON):

- `readyBeforeStart`: whether this browser sent `ready` before the server's
  `start`. False means the server started the song at its prepare timeout.
- `prepareToAudioDone_ms`: the `prepare` frame received until the last byte of
  the song's audio, uncapped (it can exceed 25 s). The main number to compare.
- `prepareToReady_ms`: `prepare` received until `ready` sent, or none when no
  ready came before the start.
- `prepareToStart_ms`: `prepare` received until `start` received.
- `inflight_at_prepare`: audio and cover requests still running when
  `prepare` arrived (from the previous song); such a song is still recorded.
- `settledBeforeSkip`: whether the song's downloads finished before the skip.
- `audio`: the `/audio/` redirect (`redirect_status`, `redirect_ms`), then the
  final storage response: `status`, `bytes` (as transferred), `ttfb_ms` and
  `total_ms`, with its `host`, `path` and the number of `attempts`.
- `covers`: the covers shown in that song (choices and the reveal): `count`
  fetched in that song with their `total_bytes` and `slowest_ms` (from the
  `/cover/` request to the last byte of the final response), and `cached`, the
  ones shown without a network request in that song (no bytes counted).

- `requests` (cycle 103): every finished `/audio/` and `/cover/` request of
  the song as `{kind, path, start_ms, end_ms, bytes}`. `kind` is `audio`,
  `cover`, or `audio-redirect` / `cover-redirect` for a hop that was
  redirected further (the site's 302 before the storage request); `path` has
  no query; times are ms since the song's `prepare` on the same clock as
  `prepareToAudioDone_ms`; `bytes` is the response body as transferred.

- Between songs (cycle 107, D-119), for song 2 onward (none for song 1, or
  when a needed frame is missing). All are measured from the previous song's
  `reveal` receipt; the reveal card lasts 5 s:
  - `betweenSongs_ms`: until this song is audible, the wait a player sees
    between two songs. The audible moment is the `start` receipt plus
    (`startAt` - `serverTime`) of that `start` frame (the lead the client
    waits before playing). The main number for step 2 (곡 사이 대기).
  - `prepareAfterReveal_ms`: until this song's `prepare`. About 0 when the
    server prepares the next song as the reveal starts
    (`SONGRUSH_GAME_PREFETCHONREVEAL=true`), about 5000 when it waits for
    the reveal to end. It shows which mode a run measured.
  - `prevRevealToReady_ms`: until this browser sent `ready`.

  The round record keeps the first `start` frame's `startAt` and `serverTime`
  for this.

Requests are attributed to the song that was current when the page made them.
Failed requests go to `failedRequests` (format, round, host, path, error), and
requests still running at the end of a game to `uncountedRequests`.

Per run (`page`): page load (navigation start to `load`) of the 3 fresh
loads, as `load_ms` (median), `load_ms_min` and `load_ms_max`; the JS and CSS
asset sizes as sent with `Accept-Encoding: gzip, br` and their
`content-encoding`; and the home page TTFB (median of 3 plain requests). Also
the label, the UTC timestamp, the git commit of this checkout, and `build`:
the site's JS asset name, which identifies the deployed build (the commit is
only the checkout's).

`medians` holds per-format (`chat`, `choice`) and overall (`all`) medians and
counts: `songs`, `readyBeforeStart`, `audio_missing` (songs whose audio
download was not seen), and `prepareToReady_censored` (true when a song
without a ready, counted at its start time, sits at the median, so the real
median is higher). Since cycle 107 also `betweenSongs_ms` (median), and
`readyBeforeRevealEnd` of `readyBeforeRevealEnd_total`: of the songs from
song 2 on, how many were ready within 5 s of the previous reveal, that is
before the reveal card ended. Result files from before cycle 107 have none of
the between values.

Per run (`environment`): `client` (`os`, `browser`, `version` and `note`, the
free text of `--env-note`), `server` (where the site runs, constants in the
tool: EC2 and S3 in ap-southeast-2, Sydney) and `local_time`, the local start
time with its UTC offset, since the time of day can change the network.

URLs are saved as host and path only. Presigned query strings are never
written; the tool refuses to write a result or HAR that contains `X-Amz-`,
`Signature=` or `Credential=`.

## Captures

On by default; `--no-capture` turns them off (`captures` is then null).

- **HAR**, one per format: `perf/results/<date>-<label>-<format>.har`.
  Playwright records it without bodies (`record_har_content="omit"`) into a
  temp folder. After the game every URL, query string, `redirectURL` and
  `Location`/`Referer` header is stripped of its query, and the text is
  checked for the three markers above. If one remains, nothing is written
  (no JSON, no HAR) and the tool exits 1. The raw HAR is deleted with the
  temp folder. Open it in the browser's DevTools (Network, import HAR).
- **Screenshots**, full page in a 1280x800 window (scrolled to the top first,
  so they can be taller than 800 px), Korean UI, room look 놀이터 (the default), of
  each format's first song: `perf/results/shots/<date>-<label>-<format>-loading.png`
  while `음원을 불러오는 중...` shows, `-playing.png` once the song is
  audible, `-reveal.png` at the reveal. If the loading text does not appear
  within 10 s of the first `prepare`, the loading shot is taken anyway.
  `captures.<format>.loadingTextVisible` says whether the text was seen, and
  `loadingShotAfterPrepare_ms` when the shot was taken.

The format games always use a 1280x800 window, with or without captures, so
runs stay comparable.

## How to run

From the repo root, with Playwright for Python and Edge installed:

```
python -X utf8 perf/run.py --base https://animeopguessr.com/ --songs 5 --label baseline
```

- `--songs N`: songs per format, 5-30 (default 5).
- `--song-timeout S`: seconds to wait for each song's start, its downloads and
  its reveal, and for leftover downloads at the end of a game (default 120).
- `--headed`: show the browser window.
- `--no-readme`: write the JSON but do not add a row below.
- `--no-capture`: no HARs and no screenshots.
- `--env-note TEXT`: where the client was, e.g. `--env-note "Korea, home Wi-Fi"`
  (saved as `environment.client.note`).

The result goes to `perf/results/<UTC date>-<label>.json` (a `-2`, `-3`
suffix if that name exists), a summary line is printed, and a row is appended
to the table below. Commit both.

## Charts

`perf/report.py` draws the report charts from one or more result JSONs. It
needs matplotlib, so run it with the system Python (the data venv does not
have it):

```
python -X utf8 perf/report.py --results perf/results/2026-11-09-baseline.json perf/results/2026-11-09-baseline-1.json perf/results/2026-11-09-baseline-2.json perf/results/2026-11-09-baseline-3.json --out docs/perf
```

- `timeline-<label>.png`: per song, chat and choice, prepare -> ready, start
  and audio done, with the server's 25 s prepare timeout as a dashed line.
- `waterfall-<label>-<format>-song<N>.png`: one bar per request of the
  format's typical song (audio done closest to the median), with sizes.
  Skipped, with a message, for results without `requests` (before cycle 103).
- `bytes-<label>.png`: audio and cover MB per song.

Times in the charts are Korean time (KST, UTC+9), converted from the UTC
`timestamp`; the chart text says 표지 for covers.
- `runs.png`: each run's p->audio done median, chat and choice, in the order
  given, with the spread across the runs.

`--out` defaults to `docs/perf`. Charts use Malgun Gothic for the Korean
text when it is installed. The chart data comes from `reportlib.py`.

## DevTools captures

`perf/devtools_shot.py` (cycle 104, D-115) captures the browser's own
DevTools Network panel on the live site. It needs Playwright, so run it with
the system Python (the data venv does not have it):

```
python -X utf8 perf/devtools_shot.py --label baseline
```

- `--base URL`: site root (default `https://animeopguessr.com/`).
- `--views pageload,choice,chat,between`: which views to capture (default all four).
- `--edge PATH`: `msedge.exe` (default: under Program Files (x86), then Program Files).

It writes to `perf/results/shots/` and prints each saved path (with its
Korean time, HH:MM KST, and the Network summary line on stderr):

- `<label>-devtools-pageload.png`: the home page loaded with Disable cache,
  with a Content-Encoding column and big rows (transferred and resource size).
  Font files (fonts.gstatic.com) are filtered out; the Google Fonts css2
  stylesheet stays, as a compressed (gzip/br) row next to the site's own JS
  and CSS.
- `<label>-devtools-{choice,chat}-{loading,done}.png`: one song's requests,
  1 s after 음원을 불러오는 중 appears, and when the song's audio is done.
- `<label>-game-{choice,chat}-{loading,done}.png`: the game page at the same moments.
- The `between` view (cycle 107) plays a chat game. Once song 1 is audible it
  clears the Network log and presses 스킵, then:
  - `<label>-devtools-between-reveal.png`: about 2 s into the reveal, still
    recording. With the prefetch-on-reveal mode on, song 2's audio download
    shows here, during the reveal.
  - `<label>-game-between-reveal.png`: the game page, showing the reveal card.
    If the card is already gone (the mode off: it lasts 5 s), the log says so.
  - `<label>-devtools-between-done.png`: once song 2 is audible, with
    recording stopped.

It opens a visible Edge window, with DevTools undocked, for about 3 minutes;
leave it alone while it runs. The profile is a fresh temp folder, deleted at
the end. Disable cache, checked for the page load, stays checked for the song
views of the same run.

Presigned-URL signatures are hidden before every DevTools screenshot, and the
panel's text is checked again after it, before the image is written: an image
taken while the panel still holds `X-Amz-`, `Signature=` or `Credential=` is
never written, and is retaken, up to 30 times. If no clean image results,
nothing is written and the tool exits 1. The S3 bucket host (shown under each
name with big rows; it holds the AWS account id) is replaced by
`S3 (ap-southeast-2)` and checked the same way, and neither it nor a signature
is ever printed in the log.

## Comparison chart

`perf/compare.py` draws the before/after chart of one improvement step from
result JSONs (system Python, it needs matplotlib):

```
python -X utf8 perf/compare.py --before <json ...> --after <json ...> --before-label "..." --after-label "..." --title "..." --out docs/perf/stepN/compare-stepN.png
```

Two panels by default: audio download done per format, and the download
speed. `--between` (cycle 107) adds a third panel, 곡 사이 대기 (초): the
median of `betweenSongs_ms` over every song of every run, per format, before
and after, and prints `before between_s: ...` / `after between_s: ...`. Older
result files have no such values and show 없음. Without `--between` the chart
and the printed lines are as before.

## Tests

The unit tests of the pure helpers (`perflib.py`, `reportlib.py`,
`devtools_shot.py`) run offline:

```
data/.venv/Scripts/python.exe -m pytest perf
```

## How to read the table

- **The headline comparison is `p->audio done` and `ready k/N`.**
  - `ready k/N`: of N songs, k were ready before the server started them.
  - `p->audio done`: median ms from `prepare` to the audio's last byte, per format.
  - A `*` after it means some songs had no audio download recorded
    (`audio_missing` > 0), so the median covers fewer songs.
- `prepareToStart_ms` (p->s, in the JSON only) is capped by the server's 25 s
  prepare timeout: once downloads take longer, it stays near 25 s and cannot
  show an improvement.
- **audio MB / MB/s**: median audio size per song and median per-song download
  rate, over both formats.
- **choice cover KB**: median cover bytes fetched per song in the choice game
  (several covers per song).
- **JS KB (enc)**: total JS as sent, and its content-encoding (`identity` = uncompressed).
- **page load min-max ms**: the fastest and slowest of the 3 fresh loads. It
  does not include the WebSocket connection.
- Home TTFB (JSON only) is a cold request each time: DNS, TCP and TLS are included.
- `-` means no data for that cell.

A change is real when it moves the medians clearly beyond the run-to-run
spread; run twice when in doubt.

## Results

| date | label | build | chat ready | chat p->audio done ms | choice ready | choice p->audio done ms | audio MB | MB/s | choice cover KB | JS KB (enc) | page load min-max ms |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 2026-11-09 | baseline | index-BL8wr47a.js | 4/5 | 18909 | 3/5 | 21188 | 3.58 | 0.18 | 2020 | 367 (identity) | 2118–3904 |
| 2026-11-09 | baseline-1 | index-BL8wr47a.js | 5/5 | 6759 | 5/5 | 6304 | 3.59 | 0.56 | 1469 | 367 (identity) | 2189–2888 |
| 2026-11-09 | baseline-2 | index-BL8wr47a.js | 5/5 | 9162 | 5/5 | 7012 | 3.60 | 0.40 | 1760 | 367 (identity) | 1861–2066 |
| 2026-11-09 | baseline-3 | index-BL8wr47a.js | 5/5 | 7426 | 5/5 | 4640 | 3.48 | 0.53 | 1835 | 367 (identity) | 1839–2050 |
| 2026-11-10 | step1-off-1 | index-BL8wr47a.js | 5/5 | 7051 | 5/5 | 8732 | 3.58 | 0.43 | 1680 | 367 (identity) | 4956–5747 |
| 2026-11-10 | step1-off-2 | index-BL8wr47a.js | 5/5 | 9946 | 5/5 | 7884 | 3.58 | 0.36 | 1393 | 367 (identity) | 5069–6253 |
| 2026-11-10 | step1-off-3 | index-BL8wr47a.js | 4/5 | 12507 | 4/5 | 9396 | 3.66 | 0.39 | 1919 | 367 (identity) | 5212–5344 |
| 2026-11-10 | step1-on-1 | index-BL8wr47a.js | 5/5 | 2483 | 5/5 | 2632 | 3.56 | 1.53 | 1764 | 367 (identity) | 4248–8158 |
| 2026-11-10 | step1-on-2 | index-BL8wr47a.js | 5/5 | 2380 | 5/5 | 2775 | 3.62 | 1.66 | 1995 | 367 (identity) | 2235–5228 |
| 2026-11-10 | step1-on-3 | index-BL8wr47a.js | 5/5 | 2309 | 5/5 | 2401 | 3.52 | 1.33 | 1751 | 367 (identity) | 5090–21109 |
| 2026-11-10 | step2-off-1 | index-BL8wr47a.js | 5/5 | 2318 | 5/5 | 2637 | 3.60 | 1.57 | 1117 | 367 (identity) | 4920–5318 |
| 2026-11-10 | step2-off-2 | index-BL8wr47a.js | 5/5 | 2292 | 5/5 | 2500 | 3.21 | 1.52 | 2090 | 367 (identity) | 4781–5511 |
| 2026-11-10 | step2-off-3 | index-BL8wr47a.js | 5/5 | 2283 | 5/5 | 2574 | 3.61 | 1.66 | 1617 | 367 (identity) | 5194–5515 |
| 2026-11-10 | step2-on-1 | index-D06XTYIU.js | 5/5 | 1922 | 5/5 | 2042 | 3.62 | 2.09 | 1223 | 367 (identity) | 2109–4143 |
| 2026-11-10 | step2-on-2 | index-D06XTYIU.js | 5/5 | 1777 | 5/5 | 1980 | 3.54 | 2.14 | 2044 | 367 (identity) | 2173–3476 |
| 2026-11-10 | step2-on-3 | index-D06XTYIU.js | 5/5 | 1753 | 5/5 | 1984 | 3.47 | 2.00 | 1282 | 367 (identity) | 2996–3700 |

## Annotated captures (for the report)
`python -I perf/annotate.py --spec perf/annotations.json --out docs/perf/annotated` draws red boxes and labels on copies of the captures (coordinates in `perf/annotations.json`). The originals in `perf/results/shots/` are never changed. Needs Pillow (system Python; not in `data/.venv`). New captures need new coordinates.

### Choosing chart panels
`perf/compare.py --panels between` draws only the "곡 사이 대기 (초)" panel. It shows the saving in seconds, the 8 s rule minimum, and "정답 공개 안에 준비 N/M". `--panels download,speed` is the default, step 1's chart. `--between` is kept as an alias that adds the between-songs panel.
