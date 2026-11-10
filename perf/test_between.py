"""Unit tests for the between-songs metrics of the perf tool (cycle 107).

Run from the repo root: data/.venv/Scripts/python.exe -m pytest perf
pytest's default "prepend" import mode puts perf/ on sys.path. run.py
imports Playwright at module level and the data venv has no Playwright, so
a stub playwright.sync_api is put in sys.modules before importing run (only
when the real one is missing). No test here starts a browser or touches the
network: frames are fed straight into Recorder's handlers with a fake clock.

Contract (docs/cycles/107-prefetch-on-reveal.md, spec items 12-14)
------------------------------------------------------------------

run.Recorder
    On a received `start` frame for a known round (first start only), the
    round dict keeps the frame's numbers as rounds[index]["startAt"] and
    rounds[index]["serverTime"] (missing or non-numeric -> key absent or
    None), next to "start_t".

run.song_rows(fmt, rec, base, songs) -> rows; each row gains
    betweenSongs_ms       song n >= 2: (start_t(n) + (startAt(n) - serverTime(n)))
                          - reveal_t(n-1), rounded to 1 decimal; None for song 1
                          or when any of those values is missing
    prepareAfterReveal_ms song n >= 2: prepare_t(n) - reveal_t(n-1); None for
                          song 1 or a missing previous reveal
    prevRevealToReady_ms  song n >= 2: ready_t(n) - reveal_t(n-1); None for
                          song 1 or when either is missing
    ("n-1" is the previous song in rec.order, i.e. the previous row.)

perflib.summarize(songs) -> each entry (per format and "all") gains
    betweenSongs_ms             median of the rows' betweenSongs_ms (None if none)
    readyBeforeRevealEnd        rows with index >= 2 whose prevRevealToReady_ms
                                is not None and <= 5000
    readyBeforeRevealEnd_total  rows with index >= 2

comparelib.between_summary(results) -> {"chat": {...}, "choice": {...}, "all": {...}}
    each {"between_s": pooled median of betweenSongs_ms over every song of
    every result of that format (both for "all"), in seconds rounded to 1
    decimal, or None}
    (A separate function, not a summarize_group key: test_compare.py pins
    summarize_group's per-format dict by exact equality.)
"""

import json
import sys
import types

import pytest

from perflib import summarize

# --- importing run.py without Playwright ------------------------------------


def _stub_playwright():
    try:
        import playwright.sync_api  # noqa: F401
        return
    except ImportError:
        pass
    pkg = types.ModuleType("playwright")
    api = types.ModuleType("playwright.sync_api")

    class Error(Exception):
        pass

    def sync_playwright():
        raise RuntimeError("stub: no browser in unit tests")

    api.Error = Error
    api.sync_playwright = sync_playwright
    pkg.sync_api = api
    sys.modules.setdefault("playwright", pkg)
    sys.modules.setdefault("playwright.sync_api", api)


@pytest.fixture
def run(monkeypatch):
    _stub_playwright()
    import run as run_module
    clock = {"t": 0.0}
    monkeypatch.setattr(run_module, "now_ms", lambda: clock["t"])
    monkeypatch.setattr(run_module, "wall_ms", lambda: 1_700_000_000_000.0 + clock["t"])
    monkeypatch.setattr(run_module, "_test_clock", clock, raising=False)
    return run_module


class Feed:
    """Feeds JSON frames into a Recorder at given fake times."""

    def __init__(self, run_module):
        self.run = run_module
        self.rec = run_module.Recorder()

    def recv(self, t, msg):
        self.run._test_clock["t"] = float(t)
        self.rec._on_received(json.dumps(msg))

    def sent(self, t, msg):
        self.run._test_clock["t"] = float(t)
        self.rec._on_sent(json.dumps(msg))

    def prepare(self, t, index):
        self.recv(t, {"type": "prepare", "index": index, "audioUrl": None})

    def ready(self, t, index):
        self.sent(t, {"type": "ready", "index": index})

    def start(self, t, index, start_at=None, server_time=None):
        msg = {"type": "start", "index": index}
        if start_at is not None:
            msg["startAt"] = start_at
            msg["deadlineAt"] = start_at + 90_000
        if server_time is not None:
            msg["serverTime"] = server_time
        self.recv(t, msg)

    def reveal(self, t, index):
        self.recv(t, {"type": "reveal", "index": index})

    def rows(self, songs=10):
        return self.run.song_rows("chat", self.rec, "https://example.test/", songs)


LEAD = 3000


def three_songs(run_module):
    """Song 1: plain. Song 2: early prepare, ready 2.0 s into the reveal.
    Song 3: late prepare (5 s after reveal), ready 6.5 s after the reveal."""
    f = Feed(run_module)
    # song 1 (server index 0)
    f.prepare(0, 0)
    f.ready(800, 0)
    f.start(1000, 0, start_at=101_000, server_time=98_000)  # audible at 4000
    f.reveal(20_000, 0)
    # song 2 (server index 1): prepare 10 ms after the reveal
    f.prepare(20_010, 1)
    f.ready(22_000, 1)
    f.start(25_050, 1, start_at=130_000, server_time=130_000 - LEAD)  # audible 28050
    f.reveal(45_000, 1)
    # song 3 (server index 2): prepare 5000 ms after the reveal
    f.prepare(50_000, 2)
    f.ready(51_500, 2)
    f.start(52_000, 2, start_at=160_000, server_time=160_000 - LEAD)  # audible 55000
    f.reveal(70_000, 2)
    return f


# --- (a) Recorder keeps startAt / serverTime ---------------------------------

def test_recorder_keeps_start_at_and_server_time(run):
    f = Feed(run)
    f.prepare(0, 4)
    f.start(1000, 4, start_at=101_000, server_time=98_000)
    r = f.rec.rounds[4]
    assert r["start_t"] == 1000.0
    assert r["startAt"] == 101_000
    assert r["serverTime"] == 98_000


def test_recorder_keeps_first_start_only(run):
    f = Feed(run)
    f.prepare(0, 0)
    f.start(1000, 0, start_at=101_000, server_time=98_000)
    f.start(9000, 0, start_at=999_000, server_time=1)  # a repeated start
    r = f.rec.rounds[0]
    assert r["start_t"] == 1000.0
    assert r["startAt"] == 101_000
    assert r["serverTime"] == 98_000


def test_recorder_start_without_numbers_has_no_values(run):
    f = Feed(run)
    f.prepare(0, 0)
    f.start(1000, 0)
    r = f.rec.rounds[0]
    assert r["start_t"] == 1000.0
    assert r.get("startAt") is None
    assert r.get("serverTime") is None


# --- (b) song_rows: betweenSongs_ms, prepareAfterReveal_ms -------------------

def test_song_rows_between_songs(run):
    rows = three_songs(run).rows()
    assert [r["index"] for r in rows] == [1, 2, 3]
    assert rows[0]["betweenSongs_ms"] is None
    # (25050 + 3000) - 20000
    assert rows[1]["betweenSongs_ms"] == pytest.approx(8050.0)
    # (52000 + 3000) - 45000
    assert rows[2]["betweenSongs_ms"] == pytest.approx(10000.0)


def test_song_rows_between_uses_frame_lead_not_constant(run):
    f = Feed(run)
    f.prepare(0, 0)
    f.start(1000, 0, start_at=10_000, server_time=9_000)
    f.reveal(5000, 0)
    f.prepare(5000, 1)
    f.start(6000, 1, start_at=50_000, server_time=48_750)  # lead 1250
    rows = f.rows()
    assert rows[1]["betweenSongs_ms"] == pytest.approx(6000 + 1250 - 5000)


def test_song_rows_prepare_after_reveal(run):
    rows = three_songs(run).rows()
    assert rows[0]["prepareAfterReveal_ms"] is None
    assert rows[1]["prepareAfterReveal_ms"] == pytest.approx(10.0)
    assert rows[2]["prepareAfterReveal_ms"] == pytest.approx(5000.0)


def test_song_rows_prev_reveal_to_ready(run):
    rows = three_songs(run).rows()
    assert rows[0]["prevRevealToReady_ms"] is None
    assert rows[1]["prevRevealToReady_ms"] == pytest.approx(2000.0)
    assert rows[2]["prevRevealToReady_ms"] == pytest.approx(6500.0)


def test_song_rows_between_none_without_start_numbers(run):
    f = Feed(run)
    f.prepare(0, 0)
    f.start(1000, 0, start_at=10_000, server_time=7_000)
    f.reveal(5000, 0)
    f.prepare(5000, 1)
    f.start(9000, 1)  # no startAt / serverTime
    rows = f.rows()
    assert rows[1]["betweenSongs_ms"] is None
    assert rows[1]["prepareAfterReveal_ms"] == pytest.approx(0.0)


def test_song_rows_none_without_previous_reveal(run):
    f = Feed(run)
    f.prepare(0, 0)
    f.ready(500, 0)
    f.start(1000, 0, start_at=10_000, server_time=7_000)
    # no reveal for song 1
    f.prepare(30_000, 1)
    f.ready(31_000, 1)
    f.start(32_000, 1, start_at=50_000, server_time=47_000)
    rows = f.rows()
    assert rows[1]["betweenSongs_ms"] is None
    assert rows[1]["prepareAfterReveal_ms"] is None
    assert rows[1]["prevRevealToReady_ms"] is None


def test_song_rows_none_when_song_never_started(run):
    f = Feed(run)
    f.prepare(0, 0)
    f.start(1000, 0, start_at=10_000, server_time=7_000)
    f.reveal(5000, 0)
    f.prepare(5010, 1)  # the game ended before song 2 started
    rows = f.rows()
    assert rows[1]["betweenSongs_ms"] is None
    assert rows[1]["prepareAfterReveal_ms"] == pytest.approx(10.0)
    assert rows[1]["prevRevealToReady_ms"] is None


# --- (c) summarize: betweenSongs_ms median, readyBeforeRevealEnd -------------

def row(fmt, index, between=None, to_ready=None):
    return {
        "format": fmt,
        "index": index,
        "readyBeforeStart": True,
        "prepareToStart_ms": None,
        "prepareToReady_ms": None,
        "prepareToAudioDone_ms": None,
        "audio": None,
        "covers": None,
        "betweenSongs_ms": between,
        "prevRevealToReady_ms": to_ready,
    }


def between_rows():
    return [
        row("chat", 1),
        row("chat", 2, 8000.0, 2000.0),
        row("chat", 3, 9000.0, 5000.0),   # exactly 5000 counts
        row("chat", 4, 12000.0, 6500.0),  # too late
        row("choice", 1),
        row("choice", 2, 7000.0, 1000.0),
        row("choice", 3, None, None),     # counted in total, not ready
    ]


def test_summarize_between_median_per_format_and_all():
    out = summarize(between_rows())
    assert out["chat"]["betweenSongs_ms"] == 9000.0
    assert out["choice"]["betweenSongs_ms"] == 7000.0
    # 7000, 8000, 9000, 12000 -> 8500
    assert out["all"]["betweenSongs_ms"] == 8500.0


def test_summarize_ready_before_reveal_end_counts():
    out = summarize(between_rows())
    assert (out["chat"]["readyBeforeRevealEnd"],
            out["chat"]["readyBeforeRevealEnd_total"]) == (2, 3)
    assert (out["choice"]["readyBeforeRevealEnd"],
            out["choice"]["readyBeforeRevealEnd_total"]) == (1, 2)
    assert (out["all"]["readyBeforeRevealEnd"],
            out["all"]["readyBeforeRevealEnd_total"]) == (3, 5)


def test_summarize_ready_before_reveal_end_ignores_song_one():
    # a song-1 row never counts, even with a value
    out = summarize([row("chat", 1, None, 100.0)])
    assert out["chat"]["readyBeforeRevealEnd"] == 0
    assert out["chat"]["readyBeforeRevealEnd_total"] == 0
    assert out["chat"]["betweenSongs_ms"] is None


def test_summarize_between_rows_without_keys():
    # rows from older result files have no between keys
    old = {"format": "chat", "index": 2, "readyBeforeStart": True,
           "audio": None, "covers": None}
    out = summarize([old])
    assert out["chat"]["betweenSongs_ms"] is None
    assert out["chat"]["readyBeforeRevealEnd"] == 0
    assert out["chat"]["readyBeforeRevealEnd_total"] == 1


def test_summarize_empty_has_between_keys():
    allm = summarize([])["all"]
    assert allm["betweenSongs_ms"] is None
    assert allm["readyBeforeRevealEnd"] == 0
    assert allm["readyBeforeRevealEnd_total"] == 0


# --- (d) comparelib.between_summary ------------------------------------------

def result(*rows_):
    return {"timestamp": "2026-11-10T01:00:00Z", "songs": list(rows_)}


def test_compare_between_pooled_median_seconds():
    import comparelib
    before = [
        result(row("chat", 1), row("chat", 2, 9000.0), row("chat", 3, 11000.0),
               row("choice", 2, 10400.0)),
        result(row("chat", 2, 13000.0), row("choice", 2, 12600.0)),
    ]
    out = comparelib.between_summary(before)
    # chat 9000, 11000, 13000 -> 11.0 s
    assert out["chat"]["between_s"] == 11.0
    # choice 10400, 12600 -> 11500 -> 11.5 s
    assert out["choice"]["between_s"] == 11.5
    # all 9000, 10400, 11000, 12600, 13000 -> 11.0 s
    assert out["all"]["between_s"] == 11.0


def test_compare_between_rounds_to_one_decimal():
    import comparelib
    out = comparelib.between_summary([result(row("chat", 2, 8049.0))])
    assert out["chat"]["between_s"] == 8.0


def test_compare_between_none_without_values():
    import comparelib
    out = comparelib.between_summary([result(row("chat", 1)),
                                      {"timestamp": "x", "songs": [{"format": "choice"}]}])
    assert out["chat"]["between_s"] is None
    assert out["choice"]["between_s"] is None
    assert out["all"]["between_s"] is None
