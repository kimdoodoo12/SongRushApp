"""Unit tests for the pure helpers of the perf tool (cycle 102, D-113).

Review round 1: declared rewrite of the summarize and readme_row tests
(honest, uncapped metrics; see "Review round 1" in
docs/cycles/102-perf-baseline.md). The median and strip_query tests are kept.

Run from the repo root: data/.venv/Scripts/python.exe -m pytest perf
pytest's default "prepend" import mode puts perf/ on sys.path, so the module
is imported as plain `perflib` (no package, no conftest needed).
No test here touches the network.

Contract for perf/perflib.py (the implementer builds to this)
-------------------------------------------------------------

median(values) -> float | int | None
    values: any iterable of numbers or None. None entries are ignored.
    No numbers left -> None. Odd count -> the middle value. Even count ->
    the mean of the two middle values. The input is not mutated.

strip_query(url: str) -> str
    Returns the URL without its ?query and #fragment; scheme, host (with
    port) and path are kept unchanged. A URL without either is returned as is.

summarize(songs: list[dict]) -> dict
    One song row (what run.py records per song; extra keys are allowed,
    e.g. "inflight_at_prepare"):
        {
          "format": "chat" | "choice",
          "index": int,                       # 1-based song number
          "readyBeforeStart": bool,           # missing key counts as False
          "prepareToReady_ms": float | None,  # None: no ready before start
          "prepareToStart_ms": float | None,
          "prepareToAudioDone_ms": float | None,
          "audio": None | {
              "redirect_ms": float | None,
              "status": int | None,
              "bytes": int | None,
              "ttfb_ms": float | None,
              "total_ms": float | None,
              "host": str, "path": str,       # query stripped
          },
          "covers": None | {
              "count": int,
              "total_bytes": int | None,
              "slowest_ms": float | None,
          },
        }
    Returns one entry per format present in songs, plus "all" over every
    song (when songs is empty: only "all"):
        {"chat": {...}, "choice": {...}, "all": {...}}
    each entry having exactly these keys:
        songs               number of song rows
        readyBeforeStart    number of rows whose readyBeforeStart is True
        audio_missing       number of rows whose "audio" is None
        prepareToStart_ms   median of song["prepareToStart_ms"]
        prepareToAudioDone_ms  median of song["prepareToAudioDone_ms"]
        prepareToReady_ms   median where a row with prepareToReady_ms None
                            is filled with its prepareToStart_ms (a lower
                            bound); a row with both None contributes nothing
        prepareToReady_censored  True when a filled value sits at the median
                            position: for an odd count the middle element, for
                            an even count either of the two middle elements
                            (a filled value equal to a median-position value
                            counts). False when there are no values.
        audio_bytes         median of song["audio"]["bytes"]
        audio_total_ms      median of song["audio"]["total_ms"]
        audio_mb_per_s      per song (bytes / 1e6) / (total_ms / 1000), then
                            the median; a song whose bytes is None or whose
                            total_ms is None or <= 0 contributes no value
        cover_bytes_median_kb  median of song["covers"]["total_bytes"] / 1000
    Medians are None when there is no data; counts are 0 for no rows.
    A song whose "audio" or "covers" is None contributes nothing to those
    medians.

readme_row(result: dict) -> str
    result (the run's JSON, extra keys allowed):
        {
          "label": str,
          "timestamp": "2026-11-09T12:34:56Z",   # ISO, UTC
          "build": str,                           # site JS asset name
          "page": {"load_ms_min": float | None,
                   "load_ms_max": float | None,
                   "js_bytes": int | None,
                   "js_encoding": str | None},
          "medians": <summarize(...) output>,
        }
    Returns exactly one markdown table row, no trailing newline, 12 cells:
        date | label | build | chat ready k/N | chat p->audio done ms
        | choice ready k/N | choice p->audio done ms | audio MB (all)
        | audio MB/s (all) | choice cover KB | JS KB (enc) | page load min-max ms
    Cells:
        date          timestamp[:10]
        label, build  as given; a "|" inside is replaced by "/"
        ready k/N     "{readyBeforeStart}/{songs}" of medians[fmt], e.g. "3/5"
        p->audio done medians[fmt]["prepareToAudioDone_ms"], "{:.0f}";
                      then "*" appended when medians[fmt]["audio_missing"] > 0
                      (so "-*" when the median is missing too)
        audio MB      medians["all"]["audio_bytes"] / 1e6, "{:.2f}"
        audio MB/s    medians["all"]["audio_mb_per_s"], "{:.2f}"
        choice cover KB  medians["choice"]["cover_bytes_median_kb"], "{:.0f}"
        JS KB         js_bytes / 1000 "{:.0f}" then " (<encoding>)";
                      encoding None -> " (identity)"; js_bytes None -> "-"
        page load     "{:.0f}" + EN DASH (U+2013) + "{:.0f}" of load_ms_min and
                      load_ms_max, e.g. "2228<EN DASH>7337"; "-" if either is None
    A format missing from medians gives "-" for both its ready and its
    p->audio done cell; any other missing value (None or missing key) is "-".
    Cells are joined with " | "; the row starts with "| " and ends with " |".
"""

import math

import pytest

from perflib import median, readme_row, strip_query, summarize

DASH = chr(0x2013)  # EN DASH between page load min and max


# --- median -----------------------------------------------------------------

def test_median_odd_count_returns_middle():
    assert median([3, 1, 2]) == 2


def test_median_even_count_is_mean_of_middle_two():
    assert median([4, 1, 3, 2]) == 2.5


def test_median_ignores_none():
    assert median([None, 10, None, 30, 20]) == 20


def test_median_empty_is_none():
    assert median([]) is None


def test_median_all_none_is_none():
    assert median([None, None]) is None


def test_median_single_value():
    assert median([7.5]) == 7.5


def test_median_accepts_generator_and_does_not_mutate_list():
    data = [5, 1, 3]
    assert median(x for x in data) == 3
    assert median(data) == 3
    assert data == [5, 1, 3]


def test_median_even_with_none_mixed():
    assert median([None, 100, 200, None]) == 150


# --- strip_query ------------------------------------------------------------

def test_strip_query_removes_presigned_query():
    url = ("https://bucket.s3.ap-northeast-2.amazonaws.com/audio/abc.ogg"
           "?X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-Signature=deadbeef")
    assert strip_query(url) == \
        "https://bucket.s3.ap-northeast-2.amazonaws.com/audio/abc.ogg"


def test_strip_query_removes_fragment():
    assert strip_query("https://example.com/a/b.js#frag") == \
        "https://example.com/a/b.js"


def test_strip_query_removes_query_and_fragment():
    assert strip_query("https://example.com/p?x=1&y=2#top") == \
        "https://example.com/p"


def test_strip_query_keeps_port_and_path():
    assert strip_query("http://localhost:8080/audio/xyz?t=1") == \
        "http://localhost:8080/audio/xyz"


def test_strip_query_plain_url_unchanged():
    url = "https://animeopguessr.com/assets/index-abc123.js"
    assert strip_query(url) == url


def test_strip_query_root_url_unchanged():
    assert strip_query("https://animeopguessr.com/") == \
        "https://animeopguessr.com/"


def test_strip_query_result_has_no_amz_text():
    url = "https://h.example/x?X-Amz-Credential=a%2Fb&X-Amz-Date=20261109T000000Z"
    out = strip_query(url)
    assert "X-Amz-" not in out
    assert "?" not in out


# --- summarize --------------------------------------------------------------

def song(fmt, index, p2s, p2r, audio_bytes=None, audio_ms=None,
         cover_bytes=None, audio=True, covers=True, ready=None,
         audio_done=None):
    return {
        "format": fmt,
        "index": index,
        "readyBeforeStart": (p2r is not None) if ready is None else ready,
        "prepareToStart_ms": p2s,
        "prepareToReady_ms": p2r,
        "prepareToAudioDone_ms": audio_done,
        "inflight_at_prepare": 0,
        "audio": ({
            "redirect_ms": 10.0,
            "status": 200,
            "bytes": audio_bytes,
            "ttfb_ms": 50.0,
            "total_ms": audio_ms,
            "host": "media.example",
            "path": "/audio/x.ogg",
        } if audio else None),
        "covers": ({
            "count": 2,
            "total_bytes": cover_bytes,
            "slowest_ms": 30.0,
        } if covers else None),
    }


SUMMARY_KEYS = {
    "songs",
    "readyBeforeStart",
    "audio_missing",
    "prepareToStart_ms",
    "prepareToAudioDone_ms",
    "prepareToReady_ms",
    "prepareToReady_censored",
    "audio_bytes",
    "audio_total_ms",
    "audio_mb_per_s",
    "cover_bytes_median_kb",
}


def sample_songs():
    return [
        # chat: 3 songs, all ready
        song("chat", 1, 1000, 400, 2_000_000, 1000, 80_000,
             audio_done=5000),                                   # 2.0 MB/s
        song("chat", 2, 3000, 600, 4_000_000, 1000, 100_000,
             audio_done=9000),                                   # 4.0 MB/s
        song("chat", 3, 2000, 500, 3_000_000, 2000, 90_000,
             audio_done=7000),                                   # 1.5 MB/s
        # choice: 2 songs, all ready
        song("choice", 1, 1500, 700, 1_000_000, 500, 50_000,
             audio_done=4000),                                   # 2.0 MB/s
        song("choice", 2, 2500, 900, 3_000_000, 1000, 70_000,
             audio_done=6000),                                   # 3.0 MB/s
    ]


def test_summarize_has_entry_per_format_and_all():
    out = summarize(sample_songs())
    assert set(out) == {"chat", "choice", "all"}
    for entry in out.values():
        assert set(entry) == SUMMARY_KEYS


def test_summarize_chat_medians():
    chat = summarize(sample_songs())["chat"]
    assert chat["songs"] == 3
    assert chat["readyBeforeStart"] == 3
    assert chat["audio_missing"] == 0
    assert chat["prepareToStart_ms"] == 2000
    assert chat["prepareToAudioDone_ms"] == 7000
    assert chat["prepareToReady_ms"] == 500
    assert chat["prepareToReady_censored"] is False
    assert chat["audio_bytes"] == 3_000_000
    assert chat["audio_total_ms"] == 1000
    assert math.isclose(chat["audio_mb_per_s"], 2.0)
    assert math.isclose(chat["cover_bytes_median_kb"], 90.0)


def test_summarize_choice_medians_even_count():
    choice = summarize(sample_songs())["choice"]
    assert choice["songs"] == 2
    assert choice["readyBeforeStart"] == 2
    assert choice["prepareToStart_ms"] == 2000
    assert choice["prepareToAudioDone_ms"] == 5000
    assert choice["prepareToReady_ms"] == 800
    assert choice["prepareToReady_censored"] is False
    assert choice["audio_bytes"] == 2_000_000
    assert choice["audio_total_ms"] == 750
    # per-song rates 2.0 and 3.0, not 2.0e6 bytes / 750 ms
    assert math.isclose(choice["audio_mb_per_s"], 2.5)
    assert math.isclose(choice["cover_bytes_median_kb"], 60.0)


def test_summarize_all_spans_every_song():
    allm = summarize(sample_songs())["all"]
    assert allm["songs"] == 5
    assert allm["readyBeforeStart"] == 5
    assert allm["prepareToStart_ms"] == 2000
    assert allm["prepareToAudioDone_ms"] == 6000
    assert allm["prepareToReady_ms"] == 600
    assert allm["audio_bytes"] == 3_000_000
    # rates 2.0, 4.0, 1.5, 2.0, 3.0 -> median 2.0
    assert math.isclose(allm["audio_mb_per_s"], 2.0)
    assert math.isclose(allm["cover_bytes_median_kb"], 80.0)


def test_summarize_only_present_formats():
    out = summarize([song("chat", 1, 1000, 400, 1_000_000, 1000, 10_000)])
    assert set(out) == {"chat", "all"}


def test_summarize_ready_count_uses_flag_not_presence():
    songs = [
        song("chat", 1, 25000, None, ready=False),
        song("chat", 2, 3000, 800, ready=True),
        song("chat", 3, 25000, None, ready=False),
    ]
    chat = summarize(songs)["chat"]
    assert chat["songs"] == 3
    assert chat["readyBeforeStart"] == 1


def test_summarize_missing_ready_flag_counts_as_false():
    row = song("chat", 1, 1000, 400)
    del row["readyBeforeStart"]
    chat = summarize([row])["chat"]
    assert chat["songs"] == 1
    assert chat["readyBeforeStart"] == 0


def test_summarize_counts_audio_missing():
    songs = [
        song("choice", 1, 1000, 400, audio=False, audio_done=None),
        song("choice", 2, 1000, 400, 1_000_000, 1000, 10_000,
             audio_done=3000),
        song("choice", 3, 1000, 400, audio=False, audio_done=None),
    ]
    out = summarize(songs)
    assert out["choice"]["audio_missing"] == 2
    assert out["all"]["audio_missing"] == 2
    assert out["choice"]["prepareToAudioDone_ms"] == 3000


def test_summarize_ready_filled_with_start_censored_odd():
    # values after filling: 400 (real), 25000 (filled), 25000 (filled)
    # sorted middle = 25000, a filled value -> censored
    songs = [
        song("chat", 1, 2000, 400),
        song("chat", 2, 25000, None),
        song("chat", 3, 25010, None),
    ]
    chat = summarize(songs)["chat"]
    assert chat["prepareToReady_ms"] == 25000
    assert chat["prepareToReady_censored"] is True


def test_summarize_ready_filled_but_not_at_median_odd():
    # values: 300, 500, 25000 (filled) -> median 500, real -> not censored
    songs = [
        song("chat", 1, 1000, 300),
        song("chat", 2, 1200, 500),
        song("chat", 3, 25000, None),
    ]
    chat = summarize(songs)["chat"]
    assert chat["prepareToReady_ms"] == 500
    assert chat["prepareToReady_censored"] is False


def test_summarize_ready_censored_even_upper_middle():
    # values: 300, 500, 25000 (filled), 26000 (real)
    # middles are 500 (real) and 25000 (filled) -> censored
    songs = [
        song("chat", 1, 1000, 300),
        song("chat", 2, 1200, 500),
        song("chat", 3, 25000, None),
        song("chat", 4, 30000, 26000),
    ]
    chat = summarize(songs)["chat"]
    assert chat["prepareToReady_ms"] == 12750
    assert chat["prepareToReady_censored"] is True


def test_summarize_ready_not_censored_even_outer_filled():
    # values: 300, 500, 700, 25000 (filled) -> middles 500, 700 are real
    songs = [
        song("chat", 1, 1000, 300),
        song("chat", 2, 1200, 500),
        song("chat", 3, 1400, 700),
        song("chat", 4, 25000, None),
    ]
    chat = summarize(songs)["chat"]
    assert chat["prepareToReady_ms"] == 600
    assert chat["prepareToReady_censored"] is False


def test_summarize_ready_both_missing_contributes_nothing():
    songs = [
        song("chat", 1, None, None),
        song("chat", 2, 1200, 500),
    ]
    chat = summarize(songs)["chat"]
    assert chat["prepareToReady_ms"] == 500
    assert chat["prepareToReady_censored"] is False


def test_summarize_skips_missing_values():
    songs = [
        song("chat", 1, None, None, audio=False, covers=False),
        song("chat", 2, 1200, 300, 2_000_000, 1000, 40_000,
             audio_done=4000),
        song("chat", 3, 1800, 450, None, None, None),
    ]
    chat = summarize(songs)["chat"]
    assert chat["prepareToStart_ms"] == 1500
    assert chat["prepareToReady_ms"] == 375
    assert chat["prepareToAudioDone_ms"] == 4000
    assert chat["audio_missing"] == 1
    assert chat["audio_bytes"] == 2_000_000
    assert chat["audio_total_ms"] == 1000
    assert math.isclose(chat["audio_mb_per_s"], 2.0)
    assert math.isclose(chat["cover_bytes_median_kb"], 40.0)


def test_summarize_zero_total_ms_gives_no_rate():
    songs = [song("chat", 1, 1000, 400, 1_000_000, 0, 10_000)]
    chat = summarize(songs)["chat"]
    assert chat["audio_mb_per_s"] is None
    assert chat["audio_total_ms"] == 0


def test_summarize_empty():
    out = summarize([])
    assert set(out) == {"all"}
    allm = out["all"]
    assert set(allm) == SUMMARY_KEYS
    assert allm["songs"] == 0
    assert allm["readyBeforeStart"] == 0
    assert allm["audio_missing"] == 0
    assert allm["prepareToReady_censored"] is False
    for key in SUMMARY_KEYS - {"songs", "readyBeforeStart", "audio_missing",
                               "prepareToReady_censored"}:
        assert allm[key] is None, key


# --- readme_row (review round 1: declared rewrite) -------------------------

def sample_result():
    return {
        "label": "baseline",
        "timestamp": "2026-11-09T12:34:56Z",
        "commit": "0f7eb50",
        "build": "index-abc123.js",
        "page": {"load_ms": 3100.0, "load_ms_min": 2228.4,
                 "load_ms_max": 7337.2, "js_bytes": 512_300,
                 "js_encoding": "gzip"},
        "medians": summarize(sample_songs()),
    }


def test_readme_row_exact():
    assert readme_row(sample_result()) == (
        "| 2026-11-09 | baseline | index-abc123.js | 3/3 | 7000 | 2/2 | 5000"
        " | 3.00 | 2.00 | 60 | 512 (gzip) | 2228" + DASH + "7337 |"
    )


def test_readme_row_is_single_line_without_newline():
    row = readme_row(sample_result())
    assert "\n" not in row
    assert row.startswith("| ") and row.endswith(" |")
    # 12 columns -> 13 pipes
    assert row.count("|") == 13


def test_readme_row_partial_ready_and_missing_audio_star():
    songs = [
        song("chat", 1, 25000, None, audio=False, covers=False),
        song("chat", 2, 3000, 800, 2_000_000, 1000, 10_000,
             audio_done=8000),
        song("chat", 3, 25000, None, 2_000_000, 1000, 10_000,
             audio_done=30000),
        song("choice", 1, 2000, 600, 1_000_000, 500, 40_000,
             audio_done=4000),
    ]
    result = sample_result()
    result["medians"] = summarize(songs)
    cells = readme_row(result).split(" | ")
    assert cells[3] == "1/3"
    assert cells[4] == "19000*"
    assert cells[5] == "1/1"
    assert cells[6] == "4000"
    assert cells[9] == "40"


def test_readme_row_star_on_missing_median():
    result = sample_result()
    result["medians"] = summarize([
        song("chat", 1, 25000, None, audio=False, covers=False),
        song("choice", 1, 2000, 600, 1_000_000, 500, 40_000,
             audio_done=4000),
    ])
    cells = readme_row(result).split(" | ")
    assert cells[3] == "0/1"
    assert cells[4] == "-*"


def test_readme_row_missing_encoding_is_identity():
    result = sample_result()
    result["page"]["js_encoding"] = None
    assert "| 512 (identity) |" in readme_row(result)


def test_readme_row_missing_values_are_dash():
    result = sample_result()
    result["page"] = {"load_ms_min": None, "load_ms_max": 7000.0,
                      "js_bytes": None, "js_encoding": None}
    result["medians"] = summarize(
        [song("chat", 1, 1234, 400, audio_done=None, covers=False,
              audio_bytes=None, audio_ms=None)])
    assert readme_row(result) == (
        "| 2026-11-09 | baseline | index-abc123.js | 1/1 | - | - | -"
        " | - | - | - | - | - |"
    )


def test_readme_row_pipe_in_label_is_replaced():
    result = sample_result()
    result["label"] = "a|b"
    row = readme_row(result)
    assert "| a/b |" in row
    assert row.count("|") == 13
