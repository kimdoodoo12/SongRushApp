"""Unit tests for perf/comparelib.py (before/after comparison numbers).

Run from the repo root: data/.venv/Scripts/python.exe -m pytest perf
Hand-made dicts only; no files, no network, no matplotlib.
"""

import pytest


@pytest.fixture
def lib():
    import comparelib
    return comparelib


def song(fmt, done_ms, ready=True, size=None, total_ms=None):
    audio = None if size is None else {"bytes": size, "total_ms": total_ms}
    return {"format": fmt, "prepareToAudioDone_ms": done_ms,
            "readyBeforeStart": ready, "audio": audio}


RUN_A = {
    "timestamp": "2026-11-10T01:00:00Z",
    "environment": {"local_time": "2026-11-10T10:00:00+09:00"},
    "songs": [
        song("chat", 1000.0, size=2_000_000, total_ms=1000.0),   # 2.0 MB/s
        song("chat", 3000.0, size=3_000_000, total_ms=1000.0),   # 3.0 MB/s
        song("chat", None, ready=False),                         # skipped value
        song("choice", 4000.0, size=1_000_000, total_ms=1000.0),  # 1.0 MB/s
    ],
}
RUN_B = {
    "timestamp": "2026-11-10T02:30:00Z",
    "environment": {"local_time": "2026-11-10T11:30:00+09:00"},
    "songs": [
        song("chat", 5000.0, size=4_000_000, total_ms=1000.0),   # 4.0 MB/s
        song("chat", 6000.0, ready=False, size=None),
        song("choice", 2000.0, size=1_000_000, total_ms=0.0),    # bad time -> skipped
        song("choice", 2600.0, size=3_000_000, total_ms=1000.0),  # 3.0 MB/s
    ],
}


def test_chat_pooled_and_per_run(lib):
    s = lib.summarize_group([RUN_A, RUN_B])["chat"]
    # pooled: 1000, 3000, 5000, 6000 -> median 4000 ms
    assert s["audio_done_s"] == 4.0
    # run A median(1000, 3000) = 2000; run B median(5000, 6000) = 5500
    assert s["run_medians_s"] == [2.0, 5.5]
    assert s["run_min_s"] == 2.0
    assert s["run_max_s"] == 5.5
    # rates 2, 3, 4 -> 3.0
    assert s["mb_per_s"] == 3.0
    assert (s["ready"], s["songs"]) == (3, 5)


def test_choice_skips_bad_rate(lib):
    s = lib.summarize_group([RUN_A, RUN_B])["choice"]
    # pooled 4000, 2000, 2600 -> 2600 ms -> 2.6 s
    assert s["audio_done_s"] == 2.6
    assert s["run_medians_s"] == [4.0, 2.3]
    assert (s["run_min_s"], s["run_max_s"]) == (2.3, 4.0)
    # rates 1.0 and 3.0 (zero-time song skipped) -> 2.0
    assert s["mb_per_s"] == 2.0
    assert (s["ready"], s["songs"]) == (3, 3)


def test_runs_and_time_range(lib):
    out = lib.summarize_group([RUN_B, RUN_A])
    assert out["runs"] == 2
    assert out["times"] == ("2026-11-10T10:00:00+09:00", "2026-11-10T11:30:00+09:00")


def test_all_pools_both_formats(lib):
    s = lib.summarize_group([RUN_A, RUN_B])["all"]
    # rates 2, 3, 1 | 4, 3 -> sorted 1, 2, 3, 3, 4 -> 3.0
    assert s["mb_per_s"] == 3.0
    # A: 1000, 3000, 4000 -> 3000; B: 5000, 6000, 2000, 2600 -> 3800
    assert s["run_medians_s"] == [3.0, 3.8]
    assert (s["ready"], s["songs"]) == (6, 8)


def test_time_range_orders_mixed_offsets(lib):
    late_utc = {"timestamp": "2026-11-10T03:00:00Z", "songs": []}  # 12:00 KST
    out = lib.summarize_group([late_utc, RUN_A])
    assert out["times"] == ("2026-11-10T10:00:00+09:00", "2026-11-10T03:00:00Z")


def test_run_without_values_is_left_out(lib):
    empty = {"timestamp": "2026-11-10T03:00:00Z",
             "songs": [song("chat", None, ready=False)]}
    s = lib.summarize_group([RUN_A, empty])["chat"]
    assert s["run_medians_s"] == [2.0]
    assert s["audio_done_s"] == 2.0  # median(1000, 3000)
    assert (s["ready"], s["songs"]) == (2, 4)
    choice = lib.summarize_group([empty])["choice"]
    assert choice == {"audio_done_s": None, "run_medians_s": [], "run_min_s": None,
                      "run_max_s": None, "mb_per_s": None, "ready": 0, "songs": 0}
