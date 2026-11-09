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
median is higher).

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
python -X utf8 perf/report.py --results perf/results/2026-11-10-baseline-1.json perf/results/2026-11-10-baseline-2.json --out docs/perf
```

- `timeline-<label>.png`: per song, chat and choice, prepare -> ready, start
  and audio done, with the server's 25 s prepare timeout as a dashed line.
- `waterfall-<label>-<format>-song<N>.png`: one bar per request of the
  format's typical song (audio done closest to the median), with sizes.
  Skipped, with a message, for results without `requests` (before cycle 103).
- `bytes-<label>.png`: audio and cover MB per song.
- `runs.png`: each run's p->audio done median, chat and choice, in the order
  given, with the spread across the runs.

`--out` defaults to `docs/perf`. Charts use Malgun Gothic for the Korean
text when it is installed. The chart data comes from `reportlib.py`.

The unit tests of the pure helpers (`perflib.py`, `reportlib.py`) run offline:

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
