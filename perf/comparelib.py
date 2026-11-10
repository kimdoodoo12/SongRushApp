"""Pure numbers for the before/after comparison chart of an improvement step.

No matplotlib here (perf/compare.py draws; the data venv that runs the tests
has no matplotlib). load() reads result JSONs; summarize_group() is pure and
is pinned by perf/test_compare.py.
"""

import json
from datetime import datetime
from pathlib import Path

from perflib import FORMATS, median, _audio_rate


def load(paths):
    """The perf result dicts of the given JSON paths, in order.

    Raises SystemExit naming the file when one cannot be read or has no
    "songs" list, so the CLI stops with a readable message.
    """
    results = []
    for path in paths:
        try:
            result = json.loads(Path(path).read_text(encoding="utf-8"))
        except (OSError, ValueError) as err:
            raise SystemExit(f"cannot read {path}: {err}")
        if not isinstance(result, dict) or not isinstance(result.get("songs"), list):
            raise SystemExit(f"{path} is not a perf result (no songs list)")
        results.append(result)
    return results


def _seconds(ms):
    return None if ms is None else round(ms / 1000, 1)


def _format_summary(results, formats):
    pooled_ms = []
    rates = []
    run_medians = []
    ready = 0
    total = 0
    for result in results:
        songs = [s for s in result["songs"] if s.get("format") in formats]
        run_ms = [s.get("prepareToAudioDone_ms") for s in songs]
        pooled_ms.extend(run_ms)
        run_median = median(run_ms)
        if run_median is not None:
            run_medians.append(_seconds(run_median))
        for song in songs:
            total += 1
            if song.get("readyBeforeStart") is True:
                ready += 1
            audio = song.get("audio")
            if isinstance(audio, dict):
                rates.append(_audio_rate(audio))
    rate = median(rates)
    return {
        "audio_done_s": _seconds(median(pooled_ms)),
        "run_medians_s": run_medians,
        "run_min_s": min(run_medians) if run_medians else None,
        "run_max_s": max(run_medians) if run_medians else None,
        "mb_per_s": None if rate is None else round(rate, 2),
        "ready": ready,
        "songs": total,
    }


def summarize_group(results):
    """The comparison numbers of one group (all runs before, or all after).

    Per format ("chat", "choice"):
      audio_done_s   pooled median of prepareToAudioDone_ms over every song of
                     every run, in seconds rounded to 1 decimal (None if none)
      run_medians_s  each run's own median, seconds rounded to 1 decimal; a run
                     with no value is left out
      run_min_s / run_max_s  min and max of run_medians_s (None if empty)
      mb_per_s       median of per-song audio MB/s (bytes / total_ms), pooled,
                     rounded to 2 decimals; songs without audio are skipped
      ready / songs  readyBeforeStart true count / songs of the format
    "all" holds the same fields over both formats' songs pooled. Plus "runs"
    (number of results) and "times": the earliest and latest measurement time
    (local_time, else timestamp) as ISO strings, or None.
    """
    summary = {fmt: _format_summary(results, (fmt,)) for fmt in FORMATS}
    summary["all"] = _format_summary(results, FORMATS)
    summary["runs"] = len(results)
    stamps = [(r.get("environment") or {}).get("local_time") or r.get("timestamp") for r in results]
    # Ordered as instants, so a "Z" stamp and a "+09:00" stamp compare correctly.
    stamps = sorted((t for t in stamps if t),
                    key=lambda t: datetime.fromisoformat(t.replace("Z", "+00:00")))
    summary["times"] = (stamps[0], stamps[-1]) if stamps else None
    return summary


def between_summary(results):
    """The between-songs wait of one group (cycle 107, D-119).

    {"chat": {...}, "choice": {...}, "all": {...}}, each {"between_s": pooled
    median of betweenSongs_ms over every song of every result of that format
    (both formats for "all"), in seconds rounded to 1 decimal, or None;
    "values": how many songs had a value (the first song of a game has none)}.
    Kept apart from summarize_group, whose per-format dict is pinned exactly.
    """
    def pooled(formats):
        values = [s.get("betweenSongs_ms")
                  for result in results for s in result["songs"]
                  if s.get("format") in formats]
        return {"between_s": _seconds(median(values)),
                "values": sum(1 for v in values if v is not None)}

    summary = {fmt: pooled((fmt,)) for fmt in FORMATS}
    summary["all"] = pooled(FORMATS)
    return summary


def ratio(numerator, denominator):
    """numerator / denominator rounded to 1 decimal ("약 N배"), or None when
    either is missing or the denominator is not positive."""
    if numerator is None or denominator is None or denominator <= 0:
        return None
    return round(numerator / denominator, 1)


PANELS = ("download", "speed", "between")
DEFAULT_PANELS = ("download", "speed")


def parse_panels(text, between=False):
    """The --panels comma list as a tuple in the given order, duplicates dropped.

    between=True (the --between alias) appends "between" when missing. Raises
    ValueError on an empty list or an unknown name.
    """
    names = [n.strip() for n in text.split(",") if n.strip()]
    if not names:
        raise ValueError("--panels needs at least one of " + ", ".join(PANELS))
    unknown = [n for n in names if n not in PANELS]
    if unknown:
        raise ValueError(f"unknown panel(s) {', '.join(unknown)}; choose from {', '.join(PANELS)}")
    out = []
    for n in names + (["between"] if between else []):
        if n not in out:
            out.append(n)
    return tuple(out)


def saving(before, after):
    """before - after rounded to 1 decimal (seconds saved), or None if either is missing."""
    if before is None or after is None:
        return None
    return round(before - after, 1)


def ready_before_reveal_end(results, fmt):
    """(ready, total) summed over the results' perflib summaries ("medians") for
    fmt: songs 2..N whose audio was ready within the reveal card. None when no
    result has the fields (recorded before cycle 107)."""
    ready = total = 0
    found = False
    for result in results:
        summary = (result.get("medians") or {}).get(fmt) or {}
        if summary.get("readyBeforeRevealEnd") is None or summary.get("readyBeforeRevealEnd_total") is None:
            continue
        found = True
        ready += summary["readyBeforeRevealEnd"]
        total += summary["readyBeforeRevealEnd_total"]
    return (ready, total) if found else None


def wrap_label(text, width=12):
    """A long "name (detail)" label on two lines, broken before the parenthesis."""
    if len(text) <= width or " (" not in text:
        return text
    head, _, tail = text.partition(" (")
    return f"{head}\n({tail}"
