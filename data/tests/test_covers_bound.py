"""Cycle 091 criteria 1-3: the cover page loop is bounded (C-021).

docs/cycles/091-cover-page-bound.md is the specification. A run never
requests more than page 1's lastPage + MAX_PAGE_SLACK pages, page 1 must carry
a positive integer lastPage, and a page that claims more pages but lists no
anime stops the run. Pages are served by a fake transport with a fake clock,
and everything is written under tmp_path, so the tests are offline and
instant. Imports of songrush_data.covers stay inside fixtures so that a
missing name fails these tests, not the collection.
"""

import io
import json
import re
import sys

import pytest

T0 = 1000.0
WALL0 = 1_800_000_000.0
GRAPHQL_URL = "https://graphql.animethemes.moe/"
_MISSING = object()


@pytest.fixture
def covers():
    from songrush_data import covers as module

    return module


@pytest.fixture
def http():
    from songrush_data import http as module

    return module


# Builders and fakes (copied from test_covers.py, no cross-test imports).

def large(anime_id):
    return "https://pub-test.r2.dev/anime/large-cover/L%d.jpg" % anime_id


def page_body(page, has_more, entries, last_page=_MISSING):
    """A page in the real response shape; entries: list of (anime id, large link or None).

    last_page defaults to page + 1 while has_more, else page; last_page=_OMIT
    leaves lastPage out; any other value is written as lastPage as is."""
    data = []
    for anime_id, link in entries:
        nodes = [{"facet": "SMALL_COVER", "link": "https://pub-test.r2.dev/anime/small-cover/s%d.avif" % anime_id}]
        if link is not None:
            nodes.append({"facet": "LARGE_COVER", "link": link})
        data.append({"id": anime_id, "images": {"nodes": nodes}})
    info = {"currentPage": page, "hasMorePages": has_more, "total": len(entries)}
    if last_page is _MISSING:
        info["lastPage"] = page + (1 if has_more else 0)
    elif last_page is not _OMIT:
        info["lastPage"] = last_page
    body = {"data": {"animePagination": {"paginatorInfo": info, "data": data}}}
    return json.dumps(body).encode("utf-8")


_OMIT = object()  # last_page=_OMIT leaves lastPage out of paginatorInfo


def write_catalog(path, draw_anime):
    """A tiny catalog: one theme per anime, drawSet in draw_anime order."""
    themes = [{"id": i + 1, "animeId": a, "slug": "OP1", "type": "OP"} for i, a in enumerate(draw_anime)]
    anime = [{"id": a, "slug": "show%d" % a} for a in draw_anime]
    catalog = {"version": 1, "anime": anime, "themes": themes, "drawSet": [t["id"] for t in themes]}
    path.write_text(json.dumps(catalog), encoding="utf-8")
    return path


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


_PAGE_IN_QUERY = re.compile(r"page\s*:\s*(\d+)")


class PageTransport:
    """POSTs to the GraphQL URL are answered by pages(page number) -> bytes;
    GETs (cover downloads) answer 404. Every call is recorded."""

    def __init__(self, http, pages):
        self.http = http
        self.pages = pages
        self.calls = []

    def send(self, method, url, headers, body, timeout):
        if method == "POST":
            assert url == GRAPHQL_URL, url
            query = json.loads(body)["query"]
            match = _PAGE_IN_QUERY.search(query)
            assert match, query
            page = int(match.group(1))
            self.calls.append(("POST", page))
            # A hard stop so an unbounded loop fails the test instead of hanging it.
            assert page <= 50, "page loop is not bounded"
            return self.http.Response(status=200, headers={}, body=self.pages(page))
        self.calls.append(("GET", url))
        return self.http.Response(status=404, headers={}, body=b"not found")

    def posts(self):
        return [value for method, value in self.calls if method == "POST"]

    def gets(self):
        return [value for method, value in self.calls if method == "GET"]


class Rig:
    def __init__(self, covers, http, tmp_path, pages, draw=(101,)):
        self.covers = covers
        self.clock = FakeClock()
        self.transport = PageTransport(http, pages)
        self.client = http.PoliteClient(self.transport, self.clock, gap_s=3.0)
        self.cache = http.FileCache(tmp_path / "cache")
        self.catalog_path = write_catalog(tmp_path / "catalog.json", list(draw))
        self.covers_dir = tmp_path / "cover-cache"
        self.lines = []

    def run(self):
        return self.covers.run(client=self.client, cache=self.cache, catalog_path=self.catalog_path,
                               covers_dir=self.covers_dir, log=self.lines.append)


def endless(last_page=2, later_last_page=None):
    """Every page says hasMorePages true; page 1 says lastPage last_page."""

    def pages(page):
        claimed = last_page if page == 1 or later_last_page is None else later_last_page
        return page_body(page, True, [(1000 + page, large(1000 + page))], last_page=claimed)

    return pages


def from_table(table):
    def pages(page):
        assert page in table, "page %d was not expected to be requested" % page
        return table[page]

    return pages


# Criterion 1.

def test_max_page_slack_is_5(covers):
    assert covers.MAX_PAGE_SLACK == 5


# Criterion 2: the bound.

def test_endless_pages_stop_after_last_page_plus_slack(covers, http, tmp_path):
    rig = Rig(covers, http, tmp_path, endless(last_page=2))
    with pytest.raises(ValueError):
        rig.run()
    # lastPage 2 + slack 5 = 7 pages; page 8 is never requested.
    assert rig.transport.posts() == [1, 2, 3, 4, 5, 6, 7]
    assert rig.transport.gets() == []
    assert not (rig.covers_dir / ".covers.lock").exists()


def test_bound_uses_page_one_last_page(covers, http, tmp_path):
    # Later pages claim a far larger lastPage; the bound still comes from page 1.
    rig = Rig(covers, http, tmp_path, endless(last_page=2, later_last_page=40))
    with pytest.raises(ValueError):
        rig.run()
    assert rig.transport.posts() == [1, 2, 3, 4, 5, 6, 7]


def test_pages_within_the_slack_are_accepted(covers, http, tmp_path):
    # Page 1 says lastPage 2, but the source grew: pages up to 7 are still read.
    table = {p: page_body(p, p < 7, [(1000 + p, None)], last_page=2) for p in range(1, 8)}
    rig = Rig(covers, http, tmp_path, from_table(table), draw=[1001, 1007])
    result = rig.run()
    assert rig.transport.posts() == [1, 2, 3, 4, 5, 6, 7]
    assert result["noCover"] == 2
    assert result["notListed"] == 0


def test_main_returns_1_on_endless_pages(covers, http, monkeypatch, tmp_path):
    clock = FakeClock()
    transport = PageTransport(http, endless(last_page=2))
    monkeypatch.setattr(covers, "UrllibTransport", lambda *a, **k: transport)
    monkeypatch.setattr(covers, "SystemClock", lambda *a, **k: clock)
    catalog = write_catalog(tmp_path / "catalog.json", [101])
    out = io.TextIOWrapper(io.BytesIO(), encoding="cp949")
    err = io.TextIOWrapper(io.BytesIO(), encoding="cp949")
    monkeypatch.setattr(sys, "stdout", out)
    monkeypatch.setattr(sys, "stderr", err)
    code = covers.main(["--cache", str(tmp_path / "c"), "--covers", str(tmp_path / "k"),
                        "--catalog", str(catalog)])
    _restore_console()
    assert code == 1
    assert transport.posts() == [1, 2, 3, 4, 5, 6, 7]
    assert transport.gets() == []


def _restore_console():
    for stream in (sys.stdout, sys.stderr):
        reconfigure = getattr(stream, "reconfigure", None)
        if reconfigure is not None:
            reconfigure(encoding="utf-8", errors="backslashreplace")


# Criterion 2: page 1's lastPage must be a positive integer.

@pytest.mark.parametrize("last_page", [_OMIT, None, 0, -1, "2", 1.5, True],
                         ids=["missing", "null", "zero", "negative", "string", "float", "bool"])
def test_page_one_without_positive_last_page_raises(covers, http, tmp_path, last_page):
    table = {1: page_body(1, False, [(101, large(101))], last_page=last_page)}
    rig = Rig(covers, http, tmp_path, from_table(table))
    with pytest.raises(ValueError):
        rig.run()
    assert rig.transport.posts() == [1]
    assert rig.cache.get(covers.page_key(1)) is None
    assert rig.transport.gets() == []


# Criterion 3: a page that claims more pages but lists no anime.

def test_empty_first_page_with_more_pages_raises_and_is_not_cached(covers, http, tmp_path):
    table = {1: page_body(1, True, [], last_page=3)}
    rig = Rig(covers, http, tmp_path, from_table(table))
    with pytest.raises(ValueError):
        rig.run()
    assert rig.transport.posts() == [1]
    assert rig.cache.get(covers.page_key(1)) is None
    assert rig.transport.gets() == []


def test_empty_later_page_with_more_pages_raises_and_is_not_cached(covers, http, tmp_path):
    table = {
        1: page_body(1, True, [(101, large(101))], last_page=3),
        2: page_body(2, True, [], last_page=3),
    }
    rig = Rig(covers, http, tmp_path, from_table(table))
    with pytest.raises(ValueError):
        rig.run()
    assert rig.transport.posts() == [1, 2]
    assert rig.cache.get(covers.page_key(1)) is not None
    assert rig.cache.get(covers.page_key(2)) is None
    assert rig.transport.gets() == []
