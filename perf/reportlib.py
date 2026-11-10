"""Pure helpers of the perf report (cycle 103, D-114).

No I/O and no plotting here: perf/report.py reads the result JSONs and
draws the charts; these pick the typical song and build the chart series.
The contract is the docstring of perf/test_report.py.
"""

from perflib import FORMATS, median

KIND_LABELS = {
    "audio": "audio",
    "audio-redirect": "audio redirect",
    "cover-redirect": "cover redirect",
}


def typical_song(songs, fmt):
    """The song of fmt whose prepareToAudioDone_ms is closest to their median.

    A tie in distance goes to the lower song index; None when no song of fmt
    has the value.
    """
    candidates = [s for s in songs
                  if s.get("format") == fmt and s.get("prepareToAudioDone_ms") is not None]
    if not candidates:
        return None
    mid = median(s["prepareToAudioDone_ms"] for s in candidates)
    return min(candidates, key=lambda s: (abs(s["prepareToAudioDone_ms"] - mid), s["index"]))


def _kb(size):
    return None if size is None else size / 1000


def waterfall_rows(song):
    """One row per request of the song, sorted by start (redirects kept apart)."""
    requests = [r for r in song.get("requests") or [] if r.get("start_ms") is not None]
    requests = sorted(requests, key=lambda r: r["start_ms"])
    rows = []
    covers = 0
    for r in requests:
        kind = r.get("kind")
        if kind == "cover":
            covers += 1
            label = f"cover {covers}"
        else:
            label = KIND_LABELS.get(kind, str(kind))
        rows.append({
            "label": label,
            "start_ms": r["start_ms"],
            "end_ms": r.get("end_ms"),
            "kb": _kb(r.get("bytes")),
        })
    return rows


def _part_kb(part, key):
    if not part:
        return 0
    size = part.get(key)
    return 0 if size is None else size / 1000


def bytes_series(songs, fmt):
    """(index, audio_kb, covers_kb) per song of fmt, by index; missing counts as 0."""
    rows = sorted((s for s in songs if s.get("format") == fmt), key=lambda s: s["index"])
    return [(s["index"], _part_kb(s.get("audio"), "bytes"), _part_kb(s.get("covers"), "total_bytes"))
            for s in rows]


def runs_summary(results):
    """Median, min, max and the values of p->audio done across runs, per format."""
    out = {}
    for fmt in FORMATS:
        runs = []
        for result in results:
            entry = (result.get("medians") or {}).get(fmt)
            if entry is None:
                continue
            value = entry.get("prepareToAudioDone_ms")
            if value is not None:
                runs.append(value)
        if runs:
            out[fmt] = {"median": median(runs), "min": min(runs), "max": max(runs), "runs": runs}
        else:
            out[fmt] = {"median": None, "min": None, "max": None, "runs": []}
    return out
