# perf: the song-preparation wait (D-113)

Every song starts with a wait: the server sends `prepare`, each browser
fetches the song's audio (and in a choice room the choice covers), decodes it,
and answers `ready`; when everyone is ready the server sends `start`. This
folder records how long that wait is on the live site, so later changes
(smaller audio, smaller covers, prefetch, compression) can be compared
against a saved baseline instead of a feeling.

It measures one user's experience, not server capacity. It is not a load test.

## What one run does

`perf/run.py` opens Edge through Playwright (autoplay allowed, locale ko-KR)
and, for each answer format, chat then choice:

1. opens a fresh browser context and loads the home page;
2. creates a room as host `perf-1`, sets 정답 형식 and 곡 수, and starts the game;
3. for each song, waits until it is audible, presses 스킵, and waits for the reveal;
4. after the last song goes back to the lobby and leaves the room.

It sends no answers and plays one game per format.

## What is recorded

Per song (`songs` in the JSON):

- `prepareToReady_ms`: the `prepare` frame received until this browser sent `ready`
  (download plus decode of the audio and the choice covers).
- `prepareToStart_ms`: `prepare` received until `start` received. This is the
  wait a player sees, and the main number to compare.
- `audio`: the `/audio/` redirect (`redirect_status`, `redirect_ms`), then the
  final storage response: `status`, `bytes` (as transferred), `ttfb_ms` and
  `total_ms`, with its `host` and `path`.
- `covers`: how many cover images were fetched for the song (choices and the
  reveal), their `total_bytes`, and `slowest_ms` from the `/cover/` request to
  the last byte of the final response.

Per run (`page`): page load ms (navigation start to `load`, the median of the
two fresh contexts), the JS and CSS asset sizes as sent with
`Accept-Encoding: gzip, br` and their `content-encoding`, and the home page
TTFB (median of 3 plain requests). Also the label, the UTC timestamp, the git
commit of this checkout, and `build`: the site's JS asset name, which
identifies the deployed build (the commit is only the checkout's).

`medians` holds per-format (`chat`, `choice`) and overall (`all`) medians.
Networks vary from run to run, so compare medians, not single songs.

URLs are saved as host and path only. Presigned query strings are never
written; the tool refuses to write a result that contains `X-Amz-`.

## How to run

From the repo root, with Playwright for Python and Edge installed:

```
python -X utf8 perf/run.py --base https://animeopguessr.com/ --songs 5 --label baseline
```

- `--songs N`: songs per format, 5-30 (default 5).
- `--song-timeout S`: seconds to wait for each song's start and reveal (default 120).
- `--headed`: show the browser window.
- `--no-readme`: write the JSON but do not add a row below.

The result goes to `perf/results/<UTC date>-<label>.json` (a `-2`, `-3`
suffix if that name exists), a summary line is printed, and a row is appended
to the table below. Commit both.

The unit tests of the pure helpers (`perflib.py`) run offline:

```
data/.venv/Scripts/python.exe -m pytest perf
```

## How to read the table

- **chat p->s / choice p->s**: median `prepareToStart_ms` per format, in ms. Lower is better.
- **audio MB**: median audio size per song; **MB/s**: median per-song download rate.
- **cover KB**: median cover bytes per song (choice rooms load several covers per song).
- **JS KB (enc)**: total JS as sent, and its content-encoding (`identity` = uncompressed).
- **page load ms**: median page load of the fresh contexts.
- `-` means no data for that cell.

A change is real when it moves the medians clearly beyond the run-to-run
spread; run twice when in doubt.

## Results

| date | label | build | chat p->s | choice p->s | audio MB | MB/s | cover KB | JS KB (enc) | page load ms |
|---|---|---|---|---|---|---|---|---|---|
