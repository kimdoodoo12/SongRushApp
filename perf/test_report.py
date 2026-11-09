"""Unit tests for the pure helpers of the perf report (cycle 103, D-114).

Run from the repo root: data/.venv/Scripts/python.exe -m pytest perf
pytest's default "prepend" import mode puts perf/ on sys.path, so the module
is imported as plain `reportlib`. The import happens in a fixture, so a
missing module fails these tests one by one instead of stopping collection
of the whole perf folder. No test here touches the network or matplotlib.

Contract for perf/reportlib.py (the implementer builds to this)
---------------------------------------------------------------

reportlib.py is pure: no I/O, and it never imports matplotlib (the plotting
lives in perf/report.py, which imports reportlib). It may import perflib
(e.g. perflib.median).

A song row is what run.py records per song (see perf/test_run.py), plus the
cycle 103 "requests" list:
    {
      "format": "chat" | "choice",
      "index": int,                          # 1-based song number
      "prepareToAudioDone_ms": float | None,
      "audio": None | {"bytes": int | None, ...},
      "covers": None | {"count": int, "total_bytes": int | None, ...},
      "requests": [                          # may be missing or None
          {"kind": "audio" | "cover" | "audio-redirect" | "cover-redirect",
           "path": str,                      # query stripped
           "start_ms": float | None,         # relative to the song's prepare
           "end_ms": float | None,
           "bytes": int | None},
          ...
      ],
    }

typical_song(songs: list[dict], fmt: str) -> dict | None
    Among the songs with song["format"] == fmt and a non-None
    prepareToAudioDone_ms, returns the song (the same dict object, not a
    copy) whose prepareToAudioDone_ms is closest to the median of those
    values (perflib.median: an even count gives the mean of the middle two).
    A tie in distance goes to the song with the lower song["index"].
    No such song -> None.

waterfall_rows(song: dict) -> list[dict]
    One row per entry of song["requests"], each exactly
        {"label": str, "start_ms": float, "end_ms": float | None,
         "kb": float | None}
    kb is bytes / 1000 (None when bytes is None). Requests whose start_ms is
    None are dropped. Rows are sorted by start_ms ascending; equal start_ms
    keep their input order (stable sort).
    Redirects are NOT merged: each redirect hop is its own row (choice made
    by the test writer: pairing a redirect with its final request is
    ambiguous when several covers start together, and the separate row shows
    the redirect's own cost in the waterfall). Labels:
        kind "audio"          -> "audio"
        kind "cover"          -> "cover N", N = 1, 2, ... in sorted
                                 (start_ms) order among the "cover" rows only
        kind "audio-redirect" -> "audio redirect"
        kind "cover-redirect" -> "cover redirect"
    Redirect rows do not consume a cover number. "requests" missing, None or
    empty -> [].

bytes_series(songs: list[dict], fmt: str) -> list[tuple]
    One tuple (index, audio_kb, covers_kb) per song with format fmt, sorted
    by song["index"] ascending.
        audio_kb  = song["audio"]["bytes"] / 1000
        covers_kb = song["covers"]["total_bytes"] / 1000
    Either is 0 when its dict is None or missing, or its byte count is None
    or missing. No songs of fmt -> [].

runs_summary(results: list[dict]) -> dict
    results: run JSONs ({"label": ..., "medians": <perflib.summarize output>,
    ...}). For each fmt in ("chat", "choice") (always both keys, nothing
    else; "all" is not included):
        {"median": perflib.median(runs), "min": min(runs),
         "max": max(runs), "runs": runs}
    where runs = [r["medians"][fmt]["prepareToAudioDone_ms"] for r in
    results] in input order, skipping a None value and a result whose
    "medians" lacks fmt. No values -> {"median": None, "min": None,
    "max": None, "runs": []}.
"""

import importlib
import inspect
import math

import pytest


@pytest.fixture
def rl():
    return importlib.import_module("reportlib")


def row(fmt, index, done=None, audio_bytes=None, cover_bytes=None,
        audio=True, covers=True, requests=None):
    out = {
        "format": fmt,
        "index": index,
        "prepareToAudioDone_ms": done,
        "audio": {"bytes": audio_bytes, "total_ms": 1000.0} if audio else None,
        "covers": ({"count": 4, "total_bytes": cover_bytes}
                   if covers else None),
    }
    if requests is not None:
        out["requests"] = requests
    return out


def req(kind, start, end, size, path="/x"):
    return {"kind": kind, "path": path, "start_ms": start, "end_ms": end,
            "bytes": size}


# --- module purity ----------------------------------------------------------

def test_reportlib_does_not_import_matplotlib(rl):
    source = inspect.getsource(rl)
    assert "matplotlib" not in source


# --- typical_song -----------------------------------------------------------

def test_typical_song_odd_count_returns_median_song(rl):
    songs = [row("chat", 1, 9000), row("chat", 2, 3000), row("chat", 3, 5000)]
    assert rl.typical_song(songs, "chat") is songs[2]


def test_typical_song_closest_to_median_not_equal(rl):
    # values 1000, 4000, 4500, 20000 -> median 4250; 4000 and 4500 are both
    # 250 away -> tie -> lower index (2)
    songs = [row("chat", 1, 1000), row("chat", 2, 4000),
             row("chat", 3, 4500), row("chat", 4, 20000)]
    assert rl.typical_song(songs, "chat") is songs[1]


def test_typical_song_tie_goes_to_lower_index_not_list_order(rl):
    # values 6000 (index 5) and 2000 (index 2) -> median 4000, both 2000 away
    songs = [row("chat", 5, 6000), row("chat", 2, 2000)]
    assert rl.typical_song(songs, "chat") is songs[1]


def test_typical_song_even_count_tie_with_index_order_reversed(rl):
    # an even count always ties the two middle values; the lower "index"
    # wins even when it comes later in the list
    songs = [row("choice", 4, 9000), row("choice", 3, 3200),
             row("choice", 2, 2900), row("choice", 1, 1000)]
    # median 3050: 2900 (index 2) and 3200 (index 3) are 150 away
    assert rl.typical_song(songs, "choice") is songs[2]


def test_typical_song_adding_a_song_moves_the_pick(rl):
    songs = [row("chat", 1, 1000), row("chat", 2, 2000),
             row("chat", 3, 3000), row("chat", 4, 3900)]
    # median 2500: 2000 and 3000 are 500 away -> index 2
    assert rl.typical_song(songs, "chat") is songs[1]
    songs.append(row("chat", 5, 3950))
    # odd count 5: median 3000 -> song index 3
    assert rl.typical_song(songs, "chat") is songs[2]


def test_typical_song_filters_by_format(rl):
    songs = [row("chat", 1, 1000), row("choice", 1, 8000),
             row("chat", 2, 2000), row("choice", 2, 9000),
             row("chat", 3, 3000), row("choice", 3, 10000)]
    assert rl.typical_song(songs, "chat") is songs[2]
    assert rl.typical_song(songs, "choice") is songs[3]


def test_typical_song_skips_none_values(rl):
    # None rows are neither candidates nor part of the median
    songs = [row("chat", 1, None), row("chat", 2, 7000),
             row("chat", 3, None), row("chat", 4, 3000),
             row("chat", 5, 5000)]
    assert rl.typical_song(songs, "chat") is songs[4]


def test_typical_song_no_data_is_none(rl):
    assert rl.typical_song([], "chat") is None
    assert rl.typical_song([row("chat", 1, None)], "chat") is None
    assert rl.typical_song([row("choice", 1, 4000)], "chat") is None


def test_typical_song_single_song(rl):
    songs = [row("choice", 3, 12345)]
    assert rl.typical_song(songs, "choice") is songs[0]


# --- waterfall_rows ---------------------------------------------------------

def test_waterfall_rows_labels_and_sort(rl):
    song = row("choice", 1, requests=[
        req("cover", 120.0, 900.0, 300_000, "/cover/b"),
        req("audio", 10.0, 8000.0, 2_500_000, "/audio/a"),
        req("cover", 100.0, 700.0, 250_000, "/cover/a"),
        req("cover", 130.0, 1000.0, 350_500, "/cover/c"),
    ])
    assert rl.waterfall_rows(song) == [
        {"label": "audio", "start_ms": 10.0, "end_ms": 8000.0, "kb": 2500.0},
        {"label": "cover 1", "start_ms": 100.0, "end_ms": 700.0, "kb": 250.0},
        {"label": "cover 2", "start_ms": 120.0, "end_ms": 900.0, "kb": 300.0},
        {"label": "cover 3", "start_ms": 130.0, "end_ms": 1000.0, "kb": 350.5},
    ]


def test_waterfall_rows_redirects_are_separate_rows(rl):
    song = row("choice", 2, requests=[
        req("audio", 60.0, 9000.0, 2_000_000, "/s3/a.ogg"),
        req("audio-redirect", 5.0, 55.0, 0, "/api/audio/t1"),
        req("cover-redirect", 6.0, 40.0, None, "/api/cover/t1"),
        req("cover", 45.0, 600.0, 100_000, "/s3/c1.jpg"),
        req("cover-redirect", 7.0, 42.0, 0, "/api/cover/t2"),
        req("cover", 48.0, 650.0, 120_000, "/s3/c2.jpg"),
    ])
    rows = rl.waterfall_rows(song)
    assert [r["label"] for r in rows] == [
        "audio redirect", "cover redirect", "cover redirect",
        "cover 1", "cover 2", "audio",
    ]
    assert [r["start_ms"] for r in rows] == [5.0, 6.0, 7.0, 45.0, 48.0, 60.0]
    assert rows[0]["kb"] == 0
    assert rows[1]["kb"] is None
    assert math.isclose(rows[4]["kb"], 120.0)


def test_waterfall_rows_have_exact_keys(rl):
    song = row("chat", 1, requests=[req("audio", 1.0, 2.0, 1000)])
    rows = rl.waterfall_rows(song)
    assert len(rows) == 1
    assert set(rows[0]) == {"label", "start_ms", "end_ms", "kb"}


def test_waterfall_rows_none_bytes_and_end(rl):
    song = row("chat", 1, requests=[req("audio", 3.0, None, None)])
    assert rl.waterfall_rows(song) == [
        {"label": "audio", "start_ms": 3.0, "end_ms": None, "kb": None},
    ]


def test_waterfall_rows_drop_missing_start(rl):
    song = row("choice", 1, requests=[
        req("cover", None, 500.0, 1000),
        req("cover", 20.0, 400.0, 2000),
        req("audio", 10.0, 900.0, 3000),
    ])
    rows = rl.waterfall_rows(song)
    assert [r["label"] for r in rows] == ["audio", "cover 1"]


def test_waterfall_rows_stable_on_equal_start(rl):
    song = row("choice", 1, requests=[
        req("cover", 50.0, 300.0, 1000, "/c/first"),
        req("cover", 50.0, 200.0, 2000, "/c/second"),
    ])
    rows = rl.waterfall_rows(song)
    assert [(r["label"], r["kb"]) for r in rows] == [
        ("cover 1", 1.0), ("cover 2", 2.0)]


def test_waterfall_rows_no_requests(rl):
    assert rl.waterfall_rows(row("chat", 1)) == []
    song = row("chat", 1)
    song["requests"] = None
    assert rl.waterfall_rows(song) == []
    assert rl.waterfall_rows(row("chat", 1, requests=[])) == []


def test_waterfall_rows_does_not_mutate_input(rl):
    reqs = [req("cover", 9.0, 10.0, 1), req("audio", 1.0, 2.0, 2)]
    song = row("chat", 1, requests=reqs)
    rl.waterfall_rows(song)
    assert [r["kind"] for r in song["requests"]] == ["cover", "audio"]
    assert set(song["requests"][0]) == {"kind", "path", "start_ms",
                                        "end_ms", "bytes"}


# --- bytes_series -----------------------------------------------------------

def test_bytes_series_basic(rl):
    songs = [row("chat", 1, audio_bytes=2_000_000, cover_bytes=80_000),
             row("chat", 2, audio_bytes=3_500_500, cover_bytes=90_000)]
    out = rl.bytes_series(songs, "chat")
    assert len(out) == 2
    assert out[0] == (1, 2000.0, 80.0)
    assert out[1][0] == 2
    assert math.isclose(out[1][1], 3500.5)
    assert math.isclose(out[1][2], 90.0)


def test_bytes_series_filters_format_and_sorts_by_index(rl):
    songs = [row("choice", 3, audio_bytes=3000, cover_bytes=300),
             row("chat", 1, audio_bytes=9000, cover_bytes=900),
             row("choice", 1, audio_bytes=1000, cover_bytes=100),
             row("choice", 2, audio_bytes=2000, cover_bytes=200)]
    assert rl.bytes_series(songs, "choice") == [
        (1, 1.0, 0.1), (2, 2.0, 0.2), (3, 3.0, 0.3)]


def test_bytes_series_missing_is_zero(rl):
    no_audio_key = row("chat", 4, cover_bytes=4000)
    del no_audio_key["audio"]
    no_covers_key = row("chat", 5, audio_bytes=5000)
    del no_covers_key["covers"]
    songs = [
        row("chat", 1, audio=False, cover_bytes=1000),
        row("chat", 2, audio_bytes=None, cover_bytes=None),
        row("chat", 3, audio_bytes=3000, covers=False),
        no_audio_key,
        no_covers_key,
    ]
    assert rl.bytes_series(songs, "chat") == [
        (1, 0, 1.0), (2, 0, 0), (3, 3.0, 0), (4, 0, 4.0), (5, 5.0, 0)]


def test_bytes_series_no_songs(rl):
    assert rl.bytes_series([], "chat") == []
    assert rl.bytes_series([row("choice", 1, audio_bytes=1)], "chat") == []


# --- runs_summary -----------------------------------------------------------

def result(label, chat=None, choice=None, has_chat=True, has_choice=True):
    medians = {"all": {"prepareToAudioDone_ms": 1.0}}
    if has_chat:
        medians["chat"] = {"prepareToAudioDone_ms": chat}
    if has_choice:
        medians["choice"] = {"prepareToAudioDone_ms": choice}
    return {"label": label, "medians": medians}


def test_runs_summary_three_runs(rl):
    out = rl.runs_summary([
        result("baseline-1", 7000, 12000),
        result("baseline-2", 5000, 15000),
        result("baseline-3", 9000, 11000),
    ])
    assert set(out) == {"chat", "choice"}
    assert out["chat"] == {"median": 7000, "min": 5000, "max": 9000,
                           "runs": [7000, 5000, 9000]}
    assert out["choice"] == {"median": 12000, "min": 11000, "max": 15000,
                             "runs": [12000, 15000, 11000]}


def test_runs_summary_even_count_mean_median(rl):
    out = rl.runs_summary([result("a", 4000, 1000), result("b", 6000, 2000),
                           result("c", 1000, 3000), result("d", 9000, 4000)])
    assert out["chat"]["median"] == 5000
    assert out["choice"]["median"] == 2500
    assert out["chat"]["min"] == 1000
    assert out["chat"]["max"] == 9000


def test_runs_summary_skips_none_and_missing_format(rl):
    out = rl.runs_summary([
        result("baseline", 8000, None),
        result("baseline-1", None, 14000),
        result("baseline-2", 6000, 10000, has_choice=False),
        result("baseline-3", 7000, 12000, has_chat=False),
    ])
    assert out["chat"] == {"median": 7000, "min": 6000, "max": 8000,
                           "runs": [8000, 6000]}
    assert out["choice"] == {"median": 13000, "min": 12000, "max": 14000,
                             "runs": [14000, 12000]}


def test_runs_summary_no_values(rl):
    empty = {"median": None, "min": None, "max": None, "runs": []}
    assert rl.runs_summary([]) == {"chat": empty, "choice": empty}
    assert rl.runs_summary([result("x", None, None)]) == {
        "chat": empty, "choice": empty}


def test_runs_summary_excludes_all(rl):
    out = rl.runs_summary([result("x", 1000, 2000)])
    assert "all" not in out
