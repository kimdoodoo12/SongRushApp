"""Pure helpers of the perf tool (cycle 102, D-113).

No I/O here: run.py measures, these compute medians, strip URL queries
(also from a recorded HAR), check text for presigned-URL secrets and format
the README row. The contract is the docstring of perf/test_run.py
(review round 1: honest, uncapped metrics).
"""

from urllib.parse import urlsplit, urlunsplit

FORMATS = ("chat", "choice")

# Between page load min and max in the README row; built from its code point
# so this file stays ASCII.
EN_DASH = chr(0x2013)

# Text that would mean a presigned URL leaked into a result or a HAR. Case
# matters: S3's lowercase x-amz-request-id response header is harmless.
SECRET_MARKERS = ("X-Amz-", "Signature=", "Credential=")

# Headers whose value can carry a presigned URL (compared lowercased). The
# HTTP/2 pseudo-header :path holds the request path with its query.
URL_HEADERS = ("location", "referer", ":path")


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


def contains_secret(text):
    """True when text holds a presigned-URL marker (the acceptance grep's strings)."""
    return any(marker in text for marker in SECRET_MARKERS)


def _strip_url_headers(headers):
    for header in headers or []:
        name = header.get("name")
        value = header.get("value")
        if isinstance(name, str) and name.lower() in URL_HEADERS and isinstance(value, str):
            header["value"] = strip_query(value)


def strip_har(har):
    """Strips URL queries from a HAR dict in place and returns it.

    Covers request.url, request.queryString (emptied), response.redirectURL
    and the Location, Referer and HTTP/2 :path headers; any other field stays
    as is.
    """
    for entry in (har.get("log") or {}).get("entries") or []:
        request = entry.get("request")
        if request is not None:
            if isinstance(request.get("url"), str):
                request["url"] = strip_query(request["url"])
            if "queryString" in request:
                request["queryString"] = []
            _strip_url_headers(request.get("headers"))
        response = entry.get("response")
        if response is not None:
            redirect = response.get("redirectURL")
            if isinstance(redirect, str) and redirect != "":
                response["redirectURL"] = strip_query(redirect)
            _strip_url_headers(response.get("headers"))
    return har


def _audio_rate(audio):
    """MB/s of one song's audio, or None when bytes or a positive time is missing."""
    size = audio.get("bytes")
    total = audio.get("total_ms")
    if size is None or total is None or total <= 0:
        return None
    return (size / 1e6) / (total / 1000)


def _ready_median(songs):
    """Median prepare->ready, a song without a ready filled with its start time.

    The filled value is a lower bound (the server started the song at its
    prepare timeout), so the median is censored when a filled value sits at
    the median position.
    """
    values = []
    for s in songs:
        ready = s.get("prepareToReady_ms")
        start = s.get("prepareToStart_ms")
        if ready is not None:
            values.append((ready, False))
        elif start is not None:
            values.append((start, True))
    if not values:
        return None, False
    nums = sorted(v for v, _ in values)
    n = len(nums)
    middle = {nums[n // 2]} if n % 2 == 1 else {nums[n // 2 - 1], nums[n // 2]}
    censored = any(filled and v in middle for v, filled in values)
    return median(nums), censored


# The reveal card's length (the server's REVEAL_PAUSE): a song whose audio was
# ready within this time of the previous reveal could start at reveal end.
REVEAL_MS = 5000


def _ready_before_reveal_end(songs):
    """(count, total) over songs 2..N: ready within REVEAL_MS of the previous reveal."""
    later = [s for s in songs if (s.get("index") or 0) >= 2]
    ready = sum(1 for s in later
                if s.get("prevRevealToReady_ms") is not None
                and s["prevRevealToReady_ms"] <= REVEAL_MS)
    return ready, len(later)


def _summary(songs):
    audios = [s["audio"] for s in songs if s.get("audio") is not None]
    covers = [s["covers"] for s in songs if s.get("covers") is not None]
    cover_kb = [None if c.get("total_bytes") is None else c["total_bytes"] / 1000 for c in covers]
    ready_ms, censored = _ready_median(songs)
    before_end, before_end_total = _ready_before_reveal_end(songs)
    return {
        "songs": len(songs),
        "readyBeforeStart": sum(1 for s in songs if s.get("readyBeforeStart") is True),
        "audio_missing": sum(1 for s in songs if s.get("audio") is None),
        "prepareToStart_ms": median(s.get("prepareToStart_ms") for s in songs),
        "prepareToAudioDone_ms": median(s.get("prepareToAudioDone_ms") for s in songs),
        "prepareToReady_ms": ready_ms,
        "prepareToReady_censored": censored,
        "audio_bytes": median(a.get("bytes") for a in audios),
        "audio_total_ms": median(a.get("total_ms") for a in audios),
        "audio_mb_per_s": median(_audio_rate(a) for a in audios),
        "cover_bytes_median_kb": median(cover_kb),
        "betweenSongs_ms": median(s.get("betweenSongs_ms") for s in songs),
        "readyBeforeRevealEnd": before_end,
        "readyBeforeRevealEnd_total": before_end_total,
    }


def summarize(songs):
    """Per-format medians and counts of the song rows, plus "all" over every song."""
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


def _format_cells(entry):
    """The ready k/N and p->audio done cells of one format's medians."""
    if entry is None:
        return ["-", "-"]
    songs = entry.get("songs")
    ready = entry.get("readyBeforeStart")
    ready_cell = "-" if songs is None or ready is None else f"{ready}/{songs}"
    done_cell = _fmt(entry.get("prepareToAudioDone_ms"), "{:.0f}")
    if (entry.get("audio_missing") or 0) > 0:
        done_cell += "*"
    return [ready_cell, done_cell]


def readme_row(result):
    """One markdown table row (12 cells) for perf/README.md, no trailing newline."""
    medians = result.get("medians") or {}
    page = result.get("page") or {}
    allm = medians.get("all") or {}
    choice = medians.get("choice") or {}

    audio_bytes = allm.get("audio_bytes")
    js_bytes = page.get("js_bytes")
    if js_bytes is None:
        js = "-"
    else:
        encoding = page.get("js_encoding")
        js = "{:.0f} ({})".format(js_bytes / 1000, "identity" if encoding is None else _cell_text(encoding))
    load_min = page.get("load_ms_min")
    load_max = page.get("load_ms_max")
    if load_min is None or load_max is None:
        load = "-"
    else:
        load = "{:.0f}{}{:.0f}".format(load_min, EN_DASH, load_max)
    cells = [
        result["timestamp"][:10],
        _cell_text(result["label"]),
        _cell_text(result["build"]),
        *_format_cells(medians.get("chat")),
        *_format_cells(medians.get("choice")),
        "-" if audio_bytes is None else "{:.2f}".format(audio_bytes / 1e6),
        _fmt(allm.get("audio_mb_per_s"), "{:.2f}"),
        _fmt(choice.get("cover_bytes_median_kb"), "{:.0f}"),
        js,
        load,
    ]
    return "| " + " | ".join(cells) + " |"
