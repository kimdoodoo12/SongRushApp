"""Pure helpers of the perf tool (cycle 102, D-113).

No I/O here: run.py measures, these compute medians, strip URL queries and
format the README row. The contract is the docstring of perf/test_run.py.
"""

from urllib.parse import urlsplit, urlunsplit

FORMATS = ("chat", "choice")

SUMMARY_KEYS = (
    "prepareToStart_ms",
    "prepareToReady_ms",
    "audio_bytes",
    "audio_total_ms",
    "audio_mb_per_s",
    "cover_bytes_median_kb",
)


def median(values):
    """The median of the numbers in values, None entries ignored; None if none."""
    nums = sorted(v for v in values if v is not None)
    n = len(nums)
    if n == 0:
        return None
    mid = n // 2
    if n % 2 == 1:
        return nums[mid]
    return (nums[mid - 1] + nums[mid]) / 2


def strip_query(url):
    """The URL without its ?query and #fragment (presigned URLs never get saved)."""
    parts = urlsplit(url)
    if parts.query == "" and parts.fragment == "" and "?" not in url and "#" not in url:
        return url
    return urlunsplit((parts.scheme, parts.netloc, parts.path, "", ""))


def _audio_rate(audio):
    """MB/s of one song's audio, or None when bytes or a positive time is missing."""
    size = audio.get("bytes")
    total = audio.get("total_ms")
    if size is None or total is None or total <= 0:
        return None
    return (size / 1e6) / (total / 1000)


def _summary(songs):
    audios = [s["audio"] for s in songs if s.get("audio") is not None]
    covers = [s["covers"] for s in songs if s.get("covers") is not None]
    cover_kb = [None if c.get("total_bytes") is None else c["total_bytes"] / 1000 for c in covers]
    return {
        "prepareToStart_ms": median(s.get("prepareToStart_ms") for s in songs),
        "prepareToReady_ms": median(s.get("prepareToReady_ms") for s in songs),
        "audio_bytes": median(a.get("bytes") for a in audios),
        "audio_total_ms": median(a.get("total_ms") for a in audios),
        "audio_mb_per_s": median(_audio_rate(a) for a in audios),
        "cover_bytes_median_kb": median(cover_kb),
    }


def summarize(songs):
    """Per-format medians of the song rows, plus "all" over every song."""
    out = {}
    present = []
    for s in songs:
        if s["format"] not in present:
            present.append(s["format"])
    for fmt in present:
        out[fmt] = _summary([s for s in songs if s["format"] == fmt])
    out["all"] = _summary(songs)
    return out


def _fmt(value, spec):
    return "-" if value is None else spec.format(value)


def _cell_text(text):
    return str(text).replace("|", "/")


def readme_row(result):
    """One markdown table row for perf/README.md, without a trailing newline."""
    medians = result.get("medians") or {}
    page = result.get("page") or {}
    allm = medians.get("all") or {}

    def p2s(fmt):
        return _fmt((medians.get(fmt) or {}).get("prepareToStart_ms"), "{:.0f}")

    audio_bytes = allm.get("audio_bytes")
    cover_kb = allm.get("cover_bytes_median_kb")
    js_bytes = page.get("js_bytes")
    if js_bytes is None:
        js = "-"
    else:
        encoding = page.get("js_encoding")
        js = "{:.0f} ({})".format(js_bytes / 1000, "identity" if encoding is None else _cell_text(encoding))
    cells = [
        result["timestamp"][:10],
        _cell_text(result["label"]),
        _cell_text(result["build"]),
        p2s("chat"),
        p2s("choice"),
        "-" if audio_bytes is None else "{:.2f}".format(audio_bytes / 1e6),
        _fmt(allm.get("audio_mb_per_s"), "{:.2f}"),
        _fmt(cover_kb, "{:.0f}"),
        js,
        _fmt(page.get("load_ms"), "{:.0f}"),
    ]
    return "| " + " | ".join(cells) + " |"
