"""Cycle 023 criteria 7-12, 11b, 11c: the build command.

docs/cycles/023-build-cli.md is the specification. build.run talks to a fake
transport that routes by URL (AnimeThemes, AniList, Wikidata, aod) with a fake
clock, and writes into tmp_path, so every test is offline and instant.
Imports of songrush_data.build stay inside fixtures so that a missing module
fails these tests, not the collection.
"""

import copy
import hashlib
import io
import json
import re
import sys
import urllib.parse
from pathlib import Path

import pytest

FIXTURES = Path(__file__).resolve().parent / "fixtures"
GQL_PAGE1_PATH = FIXTURES / "gql_page1.json"
T0 = 1000.0
WALL0 = 1_800_000_000.0

# Korean strings are built from code points (the tools may alter literal escapes).
KO_WIKIDATA = "".join(chr(c) for c in (0xB2F7, 0xD575, 0x0020, 0xC0AC, 0xC778))  # wikidata ko label
KO_AOD = "".join(chr(c) for c in (0xCE60, 0xC790, 0xB9AC, 0xC2A4))  # aod Korean title
ACCENTED = "Pok" + chr(0x00E9) + "mon " + chr(0x014C) + "kami"
KOREAN = "".join(chr(c) for c in (0xC9C4, 0xACA9, 0xC758, 0x0020, 0xAC70, 0xC778))


@pytest.fixture
def build():
    from songrush_data import build as module

    return module


@pytest.fixture
def at():
    from songrush_data import animethemes as module

    return module


@pytest.fixture
def http():
    from songrush_data import http as module

    return module


@pytest.fixture
def urls():
    from songrush_data import anilist, animethemes, aod, wikidata

    return {
        "animethemes": animethemes.ANIMETHEMES_URL,
        "anilist": anilist.ANILIST_URL,
        "wikidata": wikidata.WDQS_URL,
        "aod": aod.AOD_URL,
    }


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


# The synthetic sources.

def _raw_items():
    raw = json.loads(GQL_PAGE1_PATH.read_text(encoding="utf-8"))
    return raw["data"]["animePagination"]["data"]


class Sources:
    """Two AnimeThemes pages from gql_page1.json items, plus AniList, Wikidata and aod data."""

    def __init__(self, at):
        items = copy.deepcopy(_raw_items())
        self.page_items = {1: items[0:3], 2: items[3:6]}
        selected = [at.select_themes(copy.deepcopy(item)) for item in items[0:6]]
        self.anilist_ids = [entry["anilistId"] for entry in selected]
        assert all(isinstance(i, int) for i in self.anilist_ids)
        assert len(set(self.anilist_ids)) == 6
        assert all(entry["themes"] for entry in selected)

        # Give item 2's first theme the audio basename of item 3's first theme,
        # so the draw set must skip one of them.
        donor = selected[2]["themes"][0]["audio"]["basename"]
        target_id = selected[1]["themes"][0]["id"]
        for theme in self.page_items[1][1]["animethemes"]:
            if theme["id"] != target_id:
                continue
            for entry in theme.get("animethemeentries") or []:
                for video in (entry.get("videos") or {}).get("nodes") or []:
                    if video.get("audio") is not None:
                        video["audio"]["basename"] = donor
        self.duplicate_basename = donor

        popularities = (500, 100, 900, 300, 700, 200)
        self.popularity = dict(zip(self.anilist_ids, popularities))
        self.media = {}
        for number, anilist_id in enumerate(self.anilist_ids, start=1):
            self.media[anilist_id] = {
                "id": anilist_id,
                "popularity": self.popularity[anilist_id],
                "title": {
                    "romaji": "Synthetic Romaji Title %d" % number,
                    "english": "Synthetic English Title %d" % number if number % 2 else None,
                    "native": None,
                },
                "synonyms": [],
                "relations": {"edges": []},
            }
        # Korean strings: Wikidata for the most popular id, aod for another.
        self.wikidata_ko_id = self.anilist_ids[2]
        self.aod_ko_id = self.anilist_ids[4]
        document = {
            "data": [
                {
                    "sources": ["https://anilist.co/anime/%d" % self.aod_ko_id],
                    "title": KO_AOD,
                    "synonyms": [],
                },
                {
                    "sources": ["https://anilist.co/anime/%d" % self.anilist_ids[0]],
                    "title": "No Hangul Here",
                    "synonyms": [],
                },
            ]
        }
        self.aod_bytes = json.dumps(document, ensure_ascii=False).encode("utf-8")
        self.aod_size = len(self.aod_bytes)
        self.aod_sha256 = hashlib.sha256(self.aod_bytes).hexdigest()

    def page_body(self, page):
        data = self.page_items[page]
        return {
            "data": {
                "animePagination": {
                    "paginatorInfo": {
                        "count": len(data),
                        "currentPage": page,
                        "hasMorePages": page < 2,
                        "lastPage": 2,
                        "perPage": 100,
                        "total": 6,
                    },
                    "data": data,
                }
            }
        }


class RoutingTransport:
    """Answers by URL and counts the requests per source."""

    def __init__(self, clock, http, urls, sources, fail=None):
        self.clock = clock
        self.http = http
        self.urls = urls
        self.sources = sources
        self.fail = fail  # a source name that answers 400
        self.calls = []  # (source, detail)

    def _ok(self, body):
        return self.http.Response(status=200, headers={"content-type": "application/json"}, body=body)

    def send(self, method, url, headers, body, timeout):
        source = next((name for name, value in self.urls.items() if value == url), None)
        if source is None:
            raise AssertionError("unexpected url %r" % (url,))
        if source == self.fail:
            self.calls.append((source, None))
            return self.http.Response(status=400, headers={}, body=b"{}")
        if source == "animethemes":
            assert method == "POST"
            match = re.search(r"page:(\d+)", json.loads(body)["query"])
            assert match is not None
            page = int(match.group(1))
            self.calls.append((source, page))
            if page not in self.sources.page_items:
                raise AssertionError("page %d was not expected" % page)
            return self._ok(json.dumps(self.sources.page_body(page)).encode("utf-8"))
        if source == "anilist":
            assert method == "POST"
            match = re.search(r"id_in:\[([0-9,]*)\]", json.loads(body)["query"])
            assert match is not None
            ids = [int(i) for i in match.group(1).split(",") if i]
            self.calls.append((source, tuple(ids)))
            media = [self.sources.media[i] for i in ids if i in self.sources.media]
            return self._ok(json.dumps({"data": {"Page": {"media": media}}}).encode("utf-8"))
        if source == "wikidata":
            assert method == "POST"
            form = urllib.parse.parse_qs(body.decode("utf-8"))
            query = form["query"][0]
            values = re.search(r"VALUES \?id \{([^}]*)\}", query)
            assert values is not None
            ids = [int(i) for i in re.findall(r'"(\d+)"', values.group(1))]
            self.calls.append((source, tuple(ids)))
            bindings = []
            if self.sources.wikidata_ko_id in ids:
                bindings.append({
                    "id": {"value": str(self.sources.wikidata_ko_id)},
                    "ko": {"value": KO_WIKIDATA, "xml:lang": "ko"},
                })
            reply = {"results": {"bindings": bindings}}
            return self._ok(json.dumps(reply, ensure_ascii=False).encode("utf-8"))
        assert method == "GET"
        self.calls.append((source, None))
        return self.http.Response(status=200, headers={}, body=self.sources.aod_bytes)

    def count(self, source):
        return sum(1 for name, _ in self.calls if name == source)

    def pages(self):
        return [detail for name, detail in self.calls if name == "animethemes"]


class Env:
    def __init__(self, build, http, urls, sources, tmp_path):
        self.build = build
        self.http = http
        self.urls = urls
        self.sources = sources
        self.cache_root = tmp_path / "cache"
        self.out_dir = tmp_path / "out"

    def transport(self, fail=None):
        clock = FakeClock()
        return RoutingTransport(clock, self.http, self.urls, self.sources, fail=fail), clock

    def run(self, transport=None, clock=None, out_dir=None, **kwargs):
        if transport is None:
            transport, clock = self.transport()
        logs = []
        report = self.build.run(
            transport=transport,
            clock=clock,
            cache_root=self.cache_root,
            out_dir=self.out_dir if out_dir is None else out_dir,
            aod_size=self.sources.aod_size,
            aod_sha256=self.sources.aod_sha256,
            log=logs.append,
            **kwargs,
        )
        return report, transport, [str(line) for line in logs]

    def files(self):
        return (
            (self.out_dir / "catalog.json").read_bytes(),
            (self.out_dir / "korean_gaps.json").read_bytes(),
        )


@pytest.fixture
def env(build, at, http, urls, tmp_path):
    return Env(build, http, urls, Sources(at), tmp_path)


# Criterion 7: end to end, and a rerun with warm caches.

def test_run_writes_catalog_and_gaps(env):
    report, transport, _ = env.run()
    catalog_bytes, gaps_bytes = env.files()
    catalog = json.loads(catalog_bytes.decode("utf-8"))
    gaps = json.loads(gaps_bytes.decode("utf-8"))
    assert catalog["version"] == 1
    assert sorted(a["id"] for a in catalog["anime"]) == [1, 2, 3, 4, 5, 6]
    assert gaps == report["koreanGaps"]
    assert report["catalogBytes"] == len(catalog_bytes)
    assert transport.pages() == [1, 2]
    assert transport.count("anilist") >= 1
    assert transport.count("wikidata") >= 1
    assert transport.count("aod") == 1
    requested = sorted(i for name, ids in transport.calls if name == "anilist" for i in ids)
    assert requested == sorted(env.sources.anilist_ids)
    assert (env.cache_root / "aod" / "anime-offline-database-minified.json").read_bytes() == env.sources.aod_bytes


def test_second_run_is_identical_and_only_checks_last_page(env):
    env.run()
    first = env.files()
    _, transport, _ = env.run()
    assert env.files() == first
    assert transport.calls == [("animethemes", 2)]


# Criterion 8: the draw set and the Korean gaps of that run.

def test_run_draw_set_order_and_gaps(env):
    report, _, _ = env.run()
    catalog = json.loads(env.files()[0].decode("utf-8"))
    themes = {t["id"]: t for t in catalog["themes"]}
    anime = {a["id"]: a for a in catalog["anime"]}
    popularity = env.sources.popularity

    draw = catalog["drawSet"]
    assert draw
    pops = [popularity[anime[themes[tid]["animeId"]]["anilistId"]] for tid in draw]
    assert pops == sorted(pops, reverse=True)
    basenames = [themes[tid]["audio"]["basename"] for tid in draw]
    assert len(basenames) == len(set(basenames))
    assert env.sources.duplicate_basename in basenames

    # Reference: popularity descending, anime id, theme id; one theme per basename.
    candidates = sorted(
        catalog["themes"],
        key=lambda t: (-popularity[anime[t["animeId"]]["anilistId"]], t["animeId"], t["id"]),
    )
    expected, seen = [], set()
    for theme in candidates:
        if theme["audio"]["basename"] in seen:
            continue
        seen.add(theme["audio"]["basename"])
        expected.append(theme["id"])
    assert draw == expected

    draw_anilist = {anime[themes[tid]["animeId"]]["anilistId"] for tid in draw}
    with_korean = {env.sources.wikidata_ko_id, env.sources.aod_ko_id}
    expected_gaps = sorted(draw_anilist - with_korean, key=lambda i: -popularity[i])
    gaps = json.loads(env.files()[1].decode("utf-8"))
    assert [g["anilistId"] for g in gaps] == expected_gaps
    assert len(expected_gaps) == 4


# Criterion 9: the lock file.

def test_existing_lock_blocks_run(env, build):
    env.out_dir.mkdir(parents=True)
    lock = env.out_dir / ".build.lock"
    lock.write_bytes(b"4242")
    catalog = env.out_dir / "catalog.json"
    catalog.write_bytes(b"old catalog")
    gaps = env.out_dir / "korean_gaps.json"
    gaps.write_bytes(b"old gaps")
    transport, clock = env.transport()
    with pytest.raises(build.BuildLocked) as info:
        env.run(transport=transport, clock=clock)
    assert isinstance(info.value, RuntimeError)
    assert ".build.lock" in str(info.value)
    assert transport.calls == []
    assert lock.read_bytes() == b"4242"
    assert catalog.read_bytes() == b"old catalog"
    assert gaps.read_bytes() == b"old gaps"
    assert not env.cache_root.exists() or list(env.cache_root.iterdir()) == []


def test_lock_removed_after_success(env):
    env.run()
    assert not (env.out_dir / ".build.lock").exists()


def test_lock_removed_after_source_failure(env, http):
    transport, clock = env.transport(fail="anilist")
    with pytest.raises(http.HttpError):
        env.run(transport=transport, clock=clock)
    assert transport.count("anilist") == 1
    assert not (env.out_dir / ".build.lock").exists()


# Criterion 10: refresh_all end to end.

def test_refresh_all_refetches_anilist_wikidata_and_every_page(env):
    env.run()
    first = env.files()
    aod_file = env.cache_root / "aod" / "anime-offline-database-minified.json"
    aod_before = aod_file.read_bytes()
    _, transport, _ = env.run(refresh_all=True)
    assert transport.pages() == [1, 2]
    assert transport.count("anilist") >= 1
    assert transport.count("wikidata") >= 1
    assert transport.count("aod") == 0
    assert aod_file.read_bytes() == aod_before
    assert env.files() == first


# Criterion 11c: a missing output folder is created.

def test_run_creates_missing_out_dir(env, tmp_path):
    out_dir = tmp_path / "new" / "nested" / "out"
    assert not out_dir.exists()
    env.run(out_dir=out_dir)
    assert (out_dir / "catalog.json").is_file()
    assert (out_dir / "korean_gaps.json").is_file()
    assert not (out_dir / ".build.lock").exists()


# Criterion 12: the log lines.

def test_run_log_lines(env):
    report, _, logs = env.run()
    assert sum(1 for line in logs if line.startswith("build counts ")) == 1, logs
    assert sum(1 for line in logs if line.startswith("build catalog bytes=")) == 1, logs
    group_lines = [line for line in logs if line.startswith("build group ")]
    assert len(group_lines) == len(report["largestGroups"]) == 6
    # Titles are dumped with ensure_ascii=False, so the Korean reveal is readable.
    assert any(KO_WIKIDATA in line for line in group_lines), group_lines
    counts_line = next(line for line in logs if line.startswith("build counts "))
    assert json.loads(counts_line[len("build counts "):]) == report["counts"]
    assert "build catalog bytes=%d" % report["catalogBytes"] in logs


# Criteria 11 and 11b: the command line.

@pytest.fixture
def streams(monkeypatch):
    """Return install(), which replaces stdout and stderr with cp949 streams over byte buffers.

    Tests call install() in the test body: pytest's capture reinstalls its own streams
    when the body starts, so a swap made during fixture setup would be undone.
    install() returns a reader that flushes both streams and decodes their bytes as UTF-8.
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


@pytest.fixture
def recorded_run(build, monkeypatch):
    calls = []

    def fake_run(**kwargs):
        calls.append(kwargs)
        return {}

    monkeypatch.setattr(build, "run", fake_run)
    return calls


@pytest.mark.parametrize("argv", [["unexpected"], ["--nope"]], ids=["positional", "unknown-flag"])
def test_main_usage_error_returns_2(build, streams, recorded_run, argv):
    streams()
    assert build.main(argv) == 2
    assert recorded_run == []


def test_main_passes_parsed_arguments(build, http, streams, recorded_run, tmp_path):
    cache_dir = tmp_path / "c"
    out_dir = tmp_path / "o"
    streams()
    argv = ["--refresh-all", "--cache", str(cache_dir), "--out", str(out_dir), "--anilist-batch", "25"]
    assert build.main(argv) == 0
    assert len(recorded_run) == 1
    kwargs = recorded_run[0]
    assert kwargs["refresh_all"] is True
    assert Path(kwargs["cache_root"]).resolve() == cache_dir.resolve()
    assert Path(kwargs["out_dir"]).resolve() == out_dir.resolve()
    assert kwargs["anilist_batch"] == 25
    assert isinstance(kwargs["transport"], http.UrllibTransport)
    assert isinstance(kwargs["clock"], http.SystemClock)


def test_main_defaults_resolve_from_module_location(build, streams, recorded_run):
    streams()
    assert build.main([]) == 0
    kwargs = recorded_run[0]
    data_dir = Path(build.__file__).resolve().parent.parent
    assert Path(kwargs["cache_root"]).resolve() == (data_dir / "cache").resolve()
    assert Path(kwargs["out_dir"]).resolve() == data_dir.resolve()
    assert kwargs.get("refresh_all", False) is False
    assert kwargs.get("anilist_batch", 50) == 50


def test_main_build_locked_returns_3(build, streams, monkeypatch):
    def locked_run(**kwargs):
        raise build.BuildLocked("lock held")

    monkeypatch.setattr(build, "run", locked_run)
    streams()
    assert build.main([]) == 3


def test_main_other_exception_returns_1(build, streams, monkeypatch):
    def failing_run(**kwargs):
        raise RuntimeError("source failed")

    monkeypatch.setattr(build, "run", failing_run)
    captured = streams()
    assert build.main([]) == 1
    assert "source failed" in captured()


def test_main_console_output_is_utf8(build, streams, monkeypatch):
    def logging_run(**kwargs):
        log = kwargs.get("log", print)
        log("build group 1 size=1 titles=" + json.dumps([ACCENTED, KOREAN], ensure_ascii=False))
        print(ACCENTED)
        print(KOREAN)
        return {}

    monkeypatch.setattr(build, "run", logging_run)
    captured = streams()
    assert build.main([]) == 0
    text = captured()
    assert ACCENTED in text
    assert KOREAN in text


def test_main_non_ascii_error_returns_1(build, streams, monkeypatch):
    message = "bad title " + ACCENTED + " " + KOREAN

    def failing_run(**kwargs):
        raise ValueError(message)

    monkeypatch.setattr(build, "run", failing_run)
    captured = streams()
    assert build.main([]) == 1
    assert message in captured()
