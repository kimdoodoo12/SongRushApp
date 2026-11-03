"""Cycle 030 criteria 1-8: audio prefetch with verification and a duration index.

docs/cycles/030-audio-prefetch.md is the specification. The tests build tiny
valid Ogg Opus files in code, serve them through a fake transport with a fake
clock, and write only under tmp_path, so every test is offline and instant.
Imports of songrush_data.audio stay inside fixtures so that a missing module
fails these tests, not the collection.
"""

import io
import json
import shutil
import struct
import subprocess
import sys
from pathlib import Path

import pytest

T0 = 1000.0
WALL0 = 1_800_000_000.0
PRE_SKIP = 312
NO_GRANULE = 0xFFFFFFFFFFFFFFFF
REPO_ROOT = Path(__file__).resolve().parents[2]


@pytest.fixture
def audio():
    from songrush_data import audio as module

    return module


@pytest.fixture
def http():
    from songrush_data import http as module

    return module


@pytest.fixture
def user_agent():
    from songrush_data.common import USER_AGENT

    return USER_AGENT


# Ogg builders.

def ogg_page(payload, *, granule, serial=1, sequence=0, header_type=0):
    """One Ogg page holding one complete packet (payload < 255 bytes)."""
    assert len(payload) < 255
    return (
        b"OggS"
        + bytes([0, header_type])
        + struct.pack("<QII", granule, serial, sequence)
        + struct.pack("<I", 0)  # CRC is not checked by ogg_info
        + bytes([1, len(payload)])
        + payload
    )


def opus_head(pre_skip=PRE_SKIP):
    return b"OpusHead" + bytes([1, 2]) + struct.pack("<HIhB", pre_skip, 48000, 0, 0)


def opus_tags():
    vendor = b"songrush-test"
    return b"OpusTags" + struct.pack("<I", len(vendor)) + vendor + struct.pack("<I", 0)


def opus_file(granules, *, pre_skip=PRE_SKIP, serial=1, extra_pages=b"", filler=b"\xfc\xff\xfe"):
    """OpusHead page, OpusTags page, then one audio page per granule."""
    data = ogg_page(opus_head(pre_skip), granule=0, serial=serial, sequence=0, header_type=0x02)
    data += ogg_page(opus_tags(), granule=0, serial=serial, sequence=1)
    for index, granule in enumerate(granules):
        last = index == len(granules) - 1
        data += ogg_page(filler, granule=granule, serial=serial, sequence=2 + index,
                         header_type=0x04 if last else 0)
    return data + extra_pages


def opus_seconds(seconds, *, tag=b""):
    """A valid Opus file of the given whole duration; tag makes bodies differ."""
    return opus_file([PRE_SKIP + 48000 * seconds], filler=b"\xfc" + tag)


def vorbis_file():
    head = b"\x01vorbis" + struct.pack("<IBIiiiBB", 0, 2, 44100, 0, 128000, 0, 0xB8, 1)
    data = ogg_page(head, granule=0, header_type=0x02)
    data += ogg_page(b"\x03vorbis" + b"\x00" * 8, granule=0, sequence=1)
    data += ogg_page(b"\x00\x01\x02", granule=441000, sequence=2, header_type=0x04)
    return data


# Fakes (patterns of test_build.py and test_anilist.py, no cross-test imports).

class FakeClock:
    def __init__(self):
        self._now = T0
        self._wall = WALL0
        self.sleeps = []

    def now(self):
        return self._now

    def wall(self):
        return self._wall

    def sleep(self, seconds):
        self.sleeps.append(seconds)
        if seconds > 0:
            self._now += seconds
            self._wall += seconds


class Call:
    def __init__(self, method, url, headers, start):
        self.method = method
        self.url = url
        self.headers = headers
        self.start = start


class AudioTransport:
    """Serves bodies by URL. A value that is an int is answered as that HTTP status."""

    def __init__(self, clock, http, routes):
        self.clock = clock
        self.http = http
        self.routes = routes
        self.calls = []

    def send(self, method, url, headers, body, timeout):
        self.calls.append(Call(method, url, dict(headers or {}), self.clock.now()))
        value = self.routes.get(url, 404)
        if isinstance(value, int):
            return self.http.Response(status=value, headers={}, body=b"not found")
        return self.http.Response(status=200, headers={"content-type": "audio/ogg"}, body=value)

    def urls(self):
        return [call.url for call in self.calls]


def url_of(basename):
    return "https://a.animethemes.moe/" + basename


def make_catalog(entries, draw=None):
    """entries: list of (theme id, basename, body, size or None for len(body))."""
    themes = []
    for theme_id, basename, body, size in entries:
        themes.append({
            "id": theme_id,
            "slug": "OP1",
            "type": "OP",
            "audio": {"basename": basename, "url": url_of(basename),
                      "size": len(body) if size is None else size},
        })
    # A theme outside the draw set must never be fetched.
    themes.append({"id": 999, "slug": "ED1", "type": "ED",
                   "audio": {"basename": "Outside-ED1.ogg", "url": url_of("Outside-ED1.ogg"), "size": 10}})
    return {
        "version": 1,
        "drawSet": [e[0] for e in entries] if draw is None else draw,
        "themes": themes,
    }


def routes_of(entries, served=None):
    routes = {url_of(basename): body for _, basename, body, _ in entries}
    routes.update(served or {})
    return routes


class Rig:
    def __init__(self, audio, http, tmp_path, entries, served=None, draw=None):
        self.audio = audio
        self.clock = FakeClock()
        self.transport = AudioTransport(self.clock, http, routes_of(entries, served))
        self.client = http.PoliteClient(self.transport, self.clock, gap_s=3.0)
        self.catalog = make_catalog(entries, draw)
        self.cache_dir = tmp_path / "audio-cache"
        self.lines = []

    def run(self):
        return self.audio.prefetch(self.client, self.catalog, self.cache_dir, log=self.lines.append)

    def index(self):
        return json.loads((self.cache_dir / "index.json").read_text(encoding="utf-8"))

    def names(self):
        if not self.cache_dir.is_dir():
            return []
        return sorted(p.name for p in self.cache_dir.iterdir())


def three_entries():
    return [
        (11, "ShowA-OP1.ogg", opus_seconds(3, tag=b"a"), None),
        (12, "ShowB-ED1.ogg", opus_seconds(5, tag=b"b"), None),
        (13, "ShowC-OP2v2.ogg", opus_seconds(7, tag=b"c"), None),
    ]


def expected_index(entries, durations):
    return {
        str(theme_id): {"basename": basename, "size": len(body), "durationMs": duration}
        for (theme_id, basename, body, _), duration in zip(entries, durations)
    }


def assert_index_has(index, expected):
    assert set(index) == set(expected)
    for key, entry in expected.items():
        for field, value in entry.items():
            assert index[key][field] == value, (key, field)


# Criterion 1: ogg_info.

def test_ogg_info_duration_from_last_granule(audio):
    info = audio.ogg_info(opus_file([PRE_SKIP + 2_000_000, 4_320_312]))
    assert info["opus"] is True
    assert info["preSkip"] == 312
    assert info["lastGranule"] == 4_320_312
    assert info["durationMs"] == 90_000


def test_ogg_info_skips_trailing_no_granule_page(audio):
    trailing = ogg_page(b"\xfc\x00", granule=NO_GRANULE, serial=1, sequence=9)
    info = audio.ogg_info(opus_file([4_320_312], extra_pages=trailing))
    assert info["lastGranule"] == 4_320_312
    assert info["durationMs"] == 90_000


def test_ogg_info_ignores_other_serial(audio):
    other = ogg_page(b"\x00other", granule=9_000_000_000, serial=7, sequence=0)
    info = audio.ogg_info(opus_file([4_320_312], extra_pages=other))
    assert info["lastGranule"] == 4_320_312
    assert info["durationMs"] == 90_000


def test_ogg_info_reads_granule_unsigned(audio):
    granule = (1 << 63) + 5  # negative if read as signed
    info = audio.ogg_info(opus_file([granule]))
    assert info["lastGranule"] == granule
    assert info["durationMs"] > 0


def test_ogg_info_vorbis_is_not_opus(audio):
    assert audio.ogg_info(vorbis_file())["opus"] is False


@pytest.mark.parametrize("data", [b"ID3\x03\x00" + b"\x00" * 60, b"<html>not audio</html>", b""],
                         ids=["id3", "html", "empty"])
def test_ogg_info_rejects_non_ogg(audio, data):
    with pytest.raises(ValueError):
        audio.ogg_info(data)


def test_ogg_info_zero_duration(audio):
    info = audio.ogg_info(opus_file([PRE_SKIP]))
    assert info["opus"] is True
    assert info["durationMs"] == 0


def test_prefetch_rejects_zero_duration(audio, http, tmp_path):
    entries = [(21, "Zero-OP1.ogg", opus_file([PRE_SKIP]), None),
               (22, "Fine-OP1.ogg", opus_seconds(2), None)]
    rig = Rig(audio, http, tmp_path, entries)
    result = rig.run()
    assert result["failed"] == 1
    assert result["failedIds"] == [21]
    assert result["done"] == 1
    assert not (rig.cache_dir / "Zero-OP1.ogg").exists()
    assert not (rig.cache_dir / "Zero-OP1.ogg.part").exists()
    assert "21" not in rig.index()


# Criterion 2: download and index.

def test_download_writes_files_and_index(audio, http, tmp_path, user_agent):
    entries = three_entries()
    rig = Rig(audio, http, tmp_path, entries)
    result = rig.run()

    for _, basename, body, _ in entries:
        assert (rig.cache_dir / basename).read_bytes() == body
    assert_index_has(rig.index(), expected_index(entries, [3000, 5000, 7000]))

    total = sum(len(e[2]) for e in entries)
    assert result == {"done": 3, "skipped": 0, "failed": 0, "failedIds": [], "bytes": total}
    assert "audio done=3 skipped=0 failed=0 bytes=%d" % total in rig.lines

    calls = rig.transport.calls
    assert [c.url for c in calls] == [url_of(e[1]) for e in entries]
    assert all(c.method == "GET" for c in calls)
    assert all(c.headers.get("User-Agent") == user_agent for c in calls)
    gaps = [b.start - a.start for a, b in zip(calls, calls[1:])]
    assert all(gap >= 3.0 for gap in gaps), gaps


def test_download_follows_draw_set_order(audio, http, tmp_path):
    entries = three_entries()
    rig = Rig(audio, http, tmp_path, entries, draw=[13, 11, 12])
    rig.run()
    assert rig.transport.urls() == [url_of(entries[2][1]), url_of(entries[0][1]), url_of(entries[1][1])]


# Criterion 3: resume.

def test_second_run_skips_everything(audio, http, tmp_path):
    entries = three_entries()
    rig = Rig(audio, http, tmp_path, entries)
    rig.run()
    first_index = rig.index()
    rig.transport.calls.clear()

    result = rig.run()
    assert rig.transport.calls == []
    assert result["skipped"] == 3
    assert result["done"] == 0
    assert result["failed"] == 0
    assert rig.index() == first_index


def test_deleted_file_is_refetched_alone(audio, http, tmp_path):
    entries = three_entries()
    rig = Rig(audio, http, tmp_path, entries)
    rig.run()
    (rig.cache_dir / entries[1][1]).unlink()
    rig.transport.calls.clear()

    result = rig.run()
    assert rig.transport.urls() == [url_of(entries[1][1])]
    assert result["done"] == 1
    assert result["skipped"] == 2
    assert (rig.cache_dir / entries[1][1]).read_bytes() == entries[1][2]


def test_lost_index_is_rebuilt_without_requests(audio, http, tmp_path):
    entries = three_entries()
    rig = Rig(audio, http, tmp_path, entries)
    rig.run()
    first_index = rig.index()
    (rig.cache_dir / "index.json").unlink()
    rig.transport.calls.clear()

    result = rig.run()
    assert rig.transport.calls == []
    assert result["skipped"] == 3
    assert rig.index() == first_index


def test_final_file_without_index_entry_is_adopted(audio, http, tmp_path):
    entries = three_entries()
    rig = Rig(audio, http, tmp_path, entries)
    rig.cache_dir.mkdir(parents=True)
    (rig.cache_dir / entries[0][1]).write_bytes(entries[0][2])

    result = rig.run()
    assert rig.transport.urls() == [url_of(entries[1][1]), url_of(entries[2][1])]
    assert result["skipped"] == 1
    assert result["done"] == 2
    assert_index_has(rig.index(), expected_index(entries, [3000, 5000, 7000]))


def test_leftover_part_is_overwritten(audio, http, tmp_path):
    entries = three_entries()[:1]
    rig = Rig(audio, http, tmp_path, entries)
    rig.cache_dir.mkdir(parents=True)
    part = rig.cache_dir / (entries[0][1] + ".part")
    part.write_bytes(b"stale bytes from a killed run" * 100)

    result = rig.run()
    assert result["done"] == 1
    assert (rig.cache_dir / entries[0][1]).read_bytes() == entries[0][2]
    assert not part.exists()


# Criterion 4: verification.

def _bad_body_cases():
    good = opus_seconds(4, tag=b"x")
    return {
        "short": (good, good[:-1]),
        "vorbis": (vorbis_file(), None),
        "not-ogg": (b"<html><body>gateway page</body></html>", None),
    }


@pytest.mark.parametrize("case", ["short", "vorbis", "not-ogg"])
def test_bad_body_fails_that_theme_only(audio, http, tmp_path, case):
    catalog_body, served = _bad_body_cases()[case]
    entries = [
        (31, "Before-OP1.ogg", opus_seconds(2, tag=b"p"), None),
        (32, "Bad-OP1.ogg", catalog_body, None),
        (33, "After-OP1.ogg", opus_seconds(3, tag=b"q"), None),
    ]
    extra = {} if served is None else {url_of("Bad-OP1.ogg"): served}
    rig = Rig(audio, http, tmp_path, entries, served=extra)

    result = rig.run()
    assert result["failed"] == 1
    assert result["failedIds"] == [32]
    assert result["done"] == 2
    assert not (rig.cache_dir / "Bad-OP1.ogg").exists()
    assert not (rig.cache_dir / "Bad-OP1.ogg.part").exists()
    assert set(rig.index()) == {"31", "33"}
    assert url_of("After-OP1.ogg") in rig.transport.urls()
    assert any(line.startswith("audio failed 32: ") for line in rig.lines), rig.lines
    assert any(line.startswith("audio failed ids: ") and "32" in line for line in rig.lines), rig.lines


# Criterion 5: errors.

def test_http_404_is_reported_and_run_continues(audio, http, tmp_path):
    entries = three_entries()
    rig = Rig(audio, http, tmp_path, entries, served={url_of(entries[1][1]): 404})

    result = rig.run()
    assert result["failedIds"] == [12]
    assert result["failed"] == 1
    assert result["done"] == 2
    assert url_of(entries[2][1]) in rig.transport.urls()
    assert any(line.startswith("audio failed 12: ") for line in rig.lines), rig.lines
    assert not (rig.cache_dir / entries[1][1]).exists()


def test_five_consecutive_failures_stop_the_run(audio, http, tmp_path):
    entries = [(40 + i, "Show%d-OP1.ogg" % i, opus_seconds(i + 1, tag=bytes([65 + i])), None)
               for i in range(8)]
    served = {url_of(entries[i][1]): 404 for i in range(2, 8)}
    rig = Rig(audio, http, tmp_path, entries, served=served)

    with pytest.raises(audio.PrefetchStopped) as raised:
        rig.run()
    assert issubclass(audio.PrefetchStopped, Exception)
    assert any("audio stopped after 5 consecutive failures" in text
               for text in [str(raised.value)] + rig.lines), (str(raised.value), rig.lines)
    # Two done, then five failures; the eighth theme is never requested.
    assert len(rig.transport.calls) == 7
    assert url_of(entries[7][1]) not in rig.transport.urls()
    assert_index_has(rig.index(), expected_index(entries[:2], [1000, 2000]))
    assert not (rig.cache_dir / ".prefetch.lock").exists()


def test_failure_streak_resets_after_success(audio, http, tmp_path):
    entries = [(60 + i, "Mix%d-OP1.ogg" % i, opus_seconds(i + 1, tag=bytes([97 + i])), None)
               for i in range(9)]
    # Four failures, a success, four failures: never five in a row.
    failing = [0, 1, 2, 3, 5, 6, 7, 8]
    rig = Rig(audio, http, tmp_path, entries, served={url_of(entries[i][1]): 404 for i in failing})
    result = rig.run()
    assert result["done"] == 1
    assert result["failed"] == 8
    assert result["failedIds"] == [entries[i][0] for i in failing]


def test_write_error_stops_at_once_without_part(audio, http, tmp_path, monkeypatch):
    def broken_write(path, data):
        with open(path, "wb") as handle:
            handle.write(data[: len(data) // 2])
        raise OSError(28, "No space left on device")

    monkeypatch.setattr(audio, "_write_part", broken_write)
    entries = three_entries()
    rig = Rig(audio, http, tmp_path, entries)

    with pytest.raises(audio.PrefetchStopped):
        rig.run()
    assert rig.transport.urls() == [url_of(entries[0][1])]
    assert not (rig.cache_dir / entries[0][1]).exists()
    assert not (rig.cache_dir / (entries[0][1] + ".part")).exists()
    assert not (rig.cache_dir / ".prefetch.lock").exists()


@pytest.fixture
def streams(monkeypatch):
    """Return install(), which swaps stdout and stderr for cp949 streams over byte buffers.

    Call install() in the test body: pytest's capture reinstalls its own streams
    when the body starts, so a swap made during fixture setup would be undone.
    """

    def install():
        out = io.TextIOWrapper(io.BytesIO(), encoding="cp949")
        err = io.TextIOWrapper(io.BytesIO(), encoding="cp949")
        monkeypatch.setattr(sys, "stdout", out)
        monkeypatch.setattr(sys, "stderr", err)

        def captured():
            text = ""
            for stream in (out, err):
                stream.flush()
                text += stream.buffer.getvalue().decode("utf-8")
            return text

        return captured

    return install


def _restore_console():
    for stream in (sys.stdout, sys.stderr):
        reconfigure = getattr(stream, "reconfigure", None)
        if reconfigure is not None:
            reconfigure(encoding="utf-8", errors="backslashreplace")


def test_main_prefetch_stopped_returns_1(audio, streams, monkeypatch, tmp_path):
    def stopped(*args, **kwargs):
        raise audio.PrefetchStopped("audio stopped after 5 consecutive failures")

    monkeypatch.setattr(audio, "prefetch", stopped)
    streams()
    assert audio.main(["--cache", str(tmp_path / "c")]) == 1
    _restore_console()


# Criterion 6: lock.

def test_existing_lock_raises_without_requests(audio, http, tmp_path):
    rig = Rig(audio, http, tmp_path, three_entries())
    rig.cache_dir.mkdir(parents=True)
    lock = rig.cache_dir / ".prefetch.lock"
    lock.write_text("held", encoding="utf-8")

    with pytest.raises(audio.PrefetchLocked) as raised:
        rig.run()
    assert ".prefetch.lock" in str(raised.value)
    assert rig.transport.calls == []
    # Someone else's lock is left alone.
    assert lock.exists()


def test_no_lock_remains_after_run(audio, http, tmp_path):
    rig = Rig(audio, http, tmp_path, three_entries())
    rig.run()
    assert not (rig.cache_dir / ".prefetch.lock").exists()


def test_cache_dir_is_created(audio, http, tmp_path):
    rig = Rig(audio, http, tmp_path, three_entries()[:1])
    rig.cache_dir = tmp_path / "deep" / "nested" / "audio-cache"
    assert rig.run()["done"] == 1
    assert (rig.cache_dir / "ShowA-OP1.ogg").is_file()


# Criterion 7: safety.

@pytest.mark.parametrize("basename", ["../Escape-OP1.ogg", "sub/Nested-OP1.ogg", "..\\Back-OP1.ogg",
                                      "Show OP1.ogg", "Show-OP1.webm"],
                         ids=["dotdot", "slash", "backslash", "space", "not-ogg-ext"])
def test_bad_basename_fails_without_request(audio, http, tmp_path, basename):
    entries = [(71, basename, opus_seconds(2, tag=b"e"), None),
               (72, "Good-OP1.ogg", opus_seconds(3, tag=b"g"), None)]
    rig = Rig(audio, http, tmp_path, entries)

    result = rig.run()
    assert result["failedIds"] == [71]
    assert result["done"] == 1
    assert rig.transport.urls() == [url_of("Good-OP1.ogg")]
    # Nothing outside cache_dir.
    assert sorted(p.name for p in tmp_path.iterdir()) == ["audio-cache"]
    assert set(rig.index()) == {"72"}


def test_nothing_written_outside_cache_dir(audio, http, tmp_path):
    rig = Rig(audio, http, tmp_path, three_entries())
    rig.run()
    assert sorted(p.name for p in tmp_path.iterdir()) == ["audio-cache"]
    names = rig.names()
    assert all(not name.startswith("~tmp-") and not name.endswith(".part") for name in names), names
    assert sorted(names) == sorted(["index.json"] + [e[1] for e in three_entries()])


@pytest.mark.parametrize("relative", ["data/audio-cache/x.ogg.part", "data/audio-cache/index.json"])
def test_audio_cache_is_git_ignored(relative):
    git = shutil.which("git")
    if git is None:
        pytest.skip("git is not available; cannot check .gitignore for %s" % relative)
    inside = subprocess.run([git, "rev-parse", "--is-inside-work-tree"], cwd=REPO_ROOT,
                            capture_output=True, text=True)
    if inside.returncode != 0:
        pytest.skip("%s is not a git work tree; cannot check .gitignore" % REPO_ROOT)
    result = subprocess.run([git, "check-ignore", "-q", "--no-index", relative], cwd=REPO_ROOT,
                            capture_output=True, text=True)
    assert result.returncode == 0, "%s is not ignored by git (%s)" % (relative, result.stderr.strip())


# Criterion 8: command line.

@pytest.mark.parametrize("argv", [["stray"], ["--nope"]], ids=["positional", "unknown-flag"])
def test_main_usage_error_returns_2(audio, streams, monkeypatch, argv):
    calls = []
    monkeypatch.setattr(audio, "prefetch", lambda *a, **k: calls.append((a, k)) or {})
    streams()
    assert audio.main(argv) == 2
    assert calls == []
    _restore_console()


def test_main_prefetch_locked_returns_3(audio, streams, monkeypatch, tmp_path):
    def locked(*args, **kwargs):
        raise audio.PrefetchLocked("lock held")

    monkeypatch.setattr(audio, "prefetch", locked)
    streams()
    assert audio.main(["--cache", str(tmp_path / "c")]) == 3
    _restore_console()


def test_main_finished_run_with_failures_returns_0(audio, http, streams, monkeypatch, tmp_path):
    seen = []

    def finished(*args, **kwargs):
        seen.append((args, kwargs))
        return {"done": 1, "skipped": 0, "failed": 1, "failedIds": [5], "bytes": 10}

    monkeypatch.setattr(audio, "prefetch", finished)
    cache = tmp_path / "c"
    streams()
    assert audio.main(["--cache", str(cache)]) == 0
    _restore_console()
    assert len(seen) == 1
    args, kwargs = seen[0]
    values = list(args) + list(kwargs.values())
    assert any(isinstance(v, http.PoliteClient) for v in values)
    assert any(isinstance(v, (str, Path)) and Path(v).resolve() == cache.resolve() for v in values)
