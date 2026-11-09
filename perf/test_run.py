"""Unit tests for the pure helpers of the perf tool (cycle 102, D-113).

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
    One song row (what run.py records per song; extra keys are allowed):
        {
          "format": "chat" | "choice",
          "index": int,                       # 1-based song number
          "prepareToReady_ms": float | None,
          "prepareToStart_ms": float | None,
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
    Returns one entry per format present in songs, plus "all" over every song
    (when songs is empty: {"all": <all values None>}):
        {
          "chat":   {...}, "choice": {...}, "all": {...},
        }
    each entry having exactly these keys (all medians, None if no data):
        prepareToStart_ms   median of song["prepareToStart_ms"]
        prepareToReady_ms   median of song["prepareToReady_ms"]
        audio_bytes         median of song["audio"]["bytes"]
        audio_total_ms      median of song["audio"]["total_ms"]
        audio_mb_per_s      per song (bytes / 1e6) / (total_ms / 1000), then
                            the median; a song whose bytes is None or whose
                            total_ms is None or <= 0 contributes no value
        cover_bytes_median_kb  median of song["covers"]["total_bytes"] / 1000
    A song whose "audio" or "covers" is None contributes nothing to those keys.

readme_row(result: dict) -> str
    result (the run's JSON, extra keys allowed):
        {
          "label": str,
          "timestamp": "2026-11-09T12:34:56Z",   # ISO, UTC
          "build": str,                           # site JS asset name
          "page": {"load_ms": float | None,
                   "js_bytes": int | None,
                   "js_encoding": str | None},
          "medians": <summarize(...) output>,
        }
    Returns exactly one markdown table row, no trailing newline:
        "| date | label | build | chat p->s | choice p->s | audio MB | MB/s"
        " | cover KB | JS KB (enc) | page load ms |"
    Cells, in this order:
        date          timestamp[:10]
        label, build  as given
        chat / choice medians[fmt]["prepareToStart_ms"], format "{:.0f}"
        audio MB      medians["all"]["audio_bytes"] / 1e6, format "{:.2f}"
        audio MB/s    medians["all"]["audio_mb_per_s"], format "{:.2f}"
        cover KB      medians["all"]["cover_bytes_median_kb"], format "{:.0f}"
        JS KB         js_bytes / 1000 "{:.0f}" then " (<encoding>)";
                      encoding None -> " (identity)"
        page load ms  page["load_ms"], format "{:.0f}"
    Any missing value (None, or a missing format/key) is written as "-"
    (for JS: "-" when js_bytes is None). Cells are joined with " | " and the
    row starts with "| " and ends with " |". A "|" inside label or build is
    replaced by "/" so the table stays intact.
"""

import math

import pytest

from perflib import median, readme_row, strip_query, summarize


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
         cover_bytes=None, audio=True, covers=True):
    return {
        "format": fmt,
        "index": index,
        "prepareToStart_ms": p2s,
        "prepareToReady_ms": p2r,
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
    "prepareToStart_ms",
    "prepareToReady_ms",
    "audio_bytes",
    "audio_total_ms",
    "audio_mb_per_s",
    "cover_bytes_median_kb",
}


def sample_songs():
    return [
        # chat: 3 songs
        song("chat", 1, 1000, 400, 2_000_000, 1000, 80_000),   # 2.0 MB/s
        song("chat", 2, 3000, 600, 4_000_000, 1000, 100_000),  # 4.0 MB/s
        song("chat", 3, 2000, 500, 3_000_000, 2000, 90_000),   # 1.5 MB/s
        # choice: 2 songs
        song("choice", 1, 1500, 700, 1_000_000, 500, 50_000),  # 2.0 MB/s
        song("choice", 2, 2500, 900, 3_000_000, 1000, 70_000),  # 3.0 MB/s
    ]


def test_summarize_has_entry_per_format_and_all():
    out = summarize(sample_songs())
    assert set(out) == {"chat", "choice", "all"}
    for entry in out.values():
        assert set(entry) == SUMMARY_KEYS


def test_summarize_chat_medians():
    chat = summarize(sample_songs())["chat"]
    assert chat["prepareToStart_ms"] == 2000
    assert chat["prepareToReady_ms"] == 500
    assert chat["audio_bytes"] == 3_000_000
    assert chat["audio_total_ms"] == 1000
    assert math.isclose(chat["audio_mb_per_s"], 2.0)
    assert math.isclose(chat["cover_bytes_median_kb"], 90.0)


def test_summarize_choice_medians_even_count():
    choice = summarize(sample_songs())["choice"]
    assert choice["prepareToStart_ms"] == 2000
    assert choice["prepareToReady_ms"] == 800
    assert choice["audio_bytes"] == 2_000_000
    assert choice["audio_total_ms"] == 750
    # per-song rates 2.0 and 3.0, not 2.0e6 bytes / 750 ms
    assert math.isclose(choice["audio_mb_per_s"], 2.5)
    assert math.isclose(choice["cover_bytes_median_kb"], 60.0)


def test_summarize_all_spans_every_song():
    allm = summarize(sample_songs())["all"]
    assert allm["prepareToStart_ms"] == 2000
    assert allm["prepareToReady_ms"] == 600
    assert allm["audio_bytes"] == 3_000_000
    # rates 2.0, 4.0, 1.5, 2.0, 3.0 -> median 2.0
    assert math.isclose(allm["audio_mb_per_s"], 2.0)
    assert math.isclose(allm["cover_bytes_median_kb"], 80.0)


def test_summarize_only_present_formats():
    out = summarize([song("chat", 1, 1000, 400, 1_000_000, 1000, 10_000)])
    assert set(out) == {"chat", "all"}


def test_summarize_skips_missing_values():
    songs = [
        song("chat", 1, None, None, audio=False, covers=False),
        song("chat", 2, 1200, 300, 2_000_000, 1000, 40_000),
        song("chat", 3, 1800, None, None, None, None),
    ]
    chat = summarize(songs)["chat"]
    assert chat["prepareToStart_ms"] == 1500
    assert chat["prepareToReady_ms"] == 300
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
    assert set(out["all"]) == SUMMARY_KEYS
    assert all(v is None for v in out["all"].values())


# --- readme_row -------------------------------------------------------------

def sample_result():
    return {
        "label": "baseline",
        "timestamp": "2026-11-09T12:34:56Z",
        "commit": "0f7eb50",
        "build": "index-abc123.js",
        "page": {"load_ms": 912.3, "js_bytes": 512_300,
                 "js_encoding": "gzip"},
        "medians": summarize(sample_songs()),
    }


def test_readme_row_exact():
    assert readme_row(sample_result()) == (
        "| 2026-11-09 | baseline | index-abc123.js | 2000 | 2000"
        " | 3.00 | 2.00 | 80 | 512 (gzip) | 912 |"
    )


def test_readme_row_is_single_line_without_newline():
    row = readme_row(sample_result())
    assert "\n" not in row
    assert row.startswith("| ") and row.endswith(" |")
    # 10 columns -> 11 pipes
    assert row.count("|") == 11


def test_readme_row_missing_encoding_is_identity():
    result = sample_result()
    result["page"]["js_encoding"] = None
    assert "| 512 (identity) |" in readme_row(result)


def test_readme_row_missing_values_are_dash():
    result = sample_result()
    result["page"] = {"load_ms": None, "js_bytes": None, "js_encoding": None}
    result["medians"] = summarize(
        [song("chat", 1, 1234, 400, audio=False, covers=False)])
    assert readme_row(result) == (
        "| 2026-11-09 | baseline | index-abc123.js | 1234 | -"
        " | - | - | - | - | - |"
    )


def test_readme_row_pipe_in_label_is_replaced():
    result = sample_result()
    result["label"] = "a|b"
    row = readme_row(result)
    assert "| a/b |" in row
    assert row.count("|") == 11

