"""Cycle 090 criteria 1-8b: the cover tool (C-019).

docs/cycles/090-cover-tool.md is the specification. The tests serve saved
GraphQL pages (tests/fixtures/covers/) and tiny JPEG-like bodies through a fake
transport with a fake clock, and write only under tmp_path, so every test is
offline and instant. Imports of songrush_data.covers stay inside fixtures so
that a missing module fails these tests, not the collection.

Public interface pinned for songrush_data.covers (the implementer must provide it):

- Constants: ``LOCK_NAME = ".covers.lock"``, ``INDEX_NAME = "index.json"``,
  ``MAX_BYTES = 2_000_000``, ``MAX_CONSECUTIVE_FAILURES = 5``,
  ``PAGE_KEY_PREFIX = "ci-"``, ``ANIMETHEMES_URL = "https://graphql.animethemes.moe/"``,
  ``QUERY_TEMPLATE``: the lean query text of the spec with the literal
  placeholder ``{page}`` (it contains ``animePagination(first:100,page:{page})``).
- ``page_key(page: int) -> str``: ``"ci-" + sha256(QUERY_TEMPLATE.encode("utf-8")).hexdigest()[:16]
  + "-p%d.json" % page``.
- ``parse_page(body: bytes) -> tuple[dict[int, str | None], bool, int]``: returns
  ``(links, has_more, current_page)``; ``links`` maps every listed anime id to its
  LARGE_COVER link, or None when it has no LARGE_COVER or the link is not https.
  SMALL_COVER is ignored. Invalid JSON, a GraphQL ``errors`` body or a body without
  ``data.animePagination`` raises ValueError.
- ``run(*, client, cache, catalog_path, covers_dir, refresh_pages=False, log=print,
  new_id=None) -> dict``: ``client`` is a PoliteClient, ``cache`` a FileCache for the
  ``ci-`` pages, ``new_id`` a zero-argument callable returning a new id (None means
  ``secrets.token_urlsafe(16)``). Pages are POSTed to ANIMETHEMES_URL with a JSON body
  ``{"query": QUERY_TEMPLATE with {page} replaced}``; covers are fetched with GET.
  Every page is read (cache or fetch) before the first cover GET. Draw-set anime
  are processed in order of their first appearance in ``drawSet``.
  Returns a dict with at least the int counts ``drawSetAnime``, ``cached``,
  ``downloaded``, ``noCover``, ``notListed``, ``failed``. The last log line is
  ``covers drawSetAnime=<n> cached=<n> downloaded=<n> noCover=<n> notListed=<n> failed=<n>``.
  Raises ``CoversLocked`` when ``<covers_dir>/.covers.lock`` exists (no request sent,
  the lock left in place), ``CoversStopped`` after 5 consecutive download failures
  and for a malformed ``index.json`` (before any request, file untouched), and
  ``ValueError`` (not wrapped) for a bad page (errors body, missing animePagination,
  wrong currentPage, cached or fetched).
- ``CoversLocked`` and ``CoversStopped``: exception classes.
- ``main(argv=None) -> int``: ``[--cache DIR] [--covers DIR] [--catalog PATH]
  [--refresh-pages]``; calls ``run`` with keyword arguments only (client, cache,
  catalog_path, covers_dir, refresh_pages, log). Returns 0 on success, 2 on a usage
  error (including a bare positional argument, without calling run), 3 on
  CoversLocked and 1 on CoversStopped or any other error.

Cycle 095 (docs/cycles/095-png-covers.md, C-022) accepts PNG covers: a body
starting with the PNG signature 89 50 4E 47 0D 0A 1A 0A is saved as <id>.png
with index ``file: "<id>.png"``, a JPEG body as <id>.jpg; any other body still
fails. An index entry whose ``file`` is not exactly ``<id>.jpg`` or ``<id>.png``
stops the run (CoversStopped) before any request, leaving the index untouched.
"""

import hashlib
import io
import json
import re
import sys
from pathlib import Path

import pytest

T0 = 1000.0
WALL0 = 1_800_000_000.0
FIXTURES = Path(__file__).resolve().parent / "fixtures" / "covers"
DATA_DIR = Path(__file__).resolve().parents[1]
GRAPHQL_URL = "https://graphql.animethemes.moe/"
COUNT_KEYS = ("drawSetAnime", "cached", "downloaded", "noCover", "notListed", "failed")
ID_PATTERN = re.compile(r"^[A-Za-z0-9_-]{22}$")


@pytest.fixture
def covers():
    from songrush_data import covers as module

    return module


@pytest.fixture
def http():
    from songrush_data import http as module

    return module


# Builders.

def fixture_bytes(name):
    return (FIXTURES / name).read_bytes()


def large(anime_id):
    return "https://pub-test.r2.dev/anime/large-cover/L%d.jpg" % anime_id


def jpeg(tag, size=64):
    """JPEG-like bytes: the FF D8 FF signature, then filler that makes bodies differ."""
    head = b"\xff\xd8\xff\xe0" + tag.encode("ascii")
    return (head + b"\x00" * size)[:size]


PNG_SIGNATURE = b"\x89PNG\r\n\x1a\n"


def png(tag, size=64):
    """PNG-like bytes: the 89 50 4E 47 0D 0A 1A 0A signature, then filler that makes bodies differ."""
    head = PNG_SIGNATURE + tag.encode("ascii")
    return (head + b"\x00" * size)[:size]


def page_body(page, has_more, entries):
    """A page in the real response shape; entries: list of (anime id, large link or None)."""
    data = []
    for anime_id, link in entries:
        nodes = [{"facet": "SMALL_COVER", "link": "https://pub-test.r2.dev/anime/small-cover/s%d.avif" % anime_id}]
        if link is not None:
            nodes.append({"facet": "LARGE_COVER", "link": link})
        data.append({"id": anime_id, "images": {"nodes": nodes}})
    body = {"data": {"animePagination": {
        "paginatorInfo": {"currentPage": page, "hasMorePages": has_more, "lastPage": page + (1 if has_more else 0),
                          "total": len(entries)},
        "data": data,
    }}}
    return json.dumps(body).encode("utf-8")


def write_catalog(path, draw_anime, extra_anime=()):
    """A tiny catalog: one theme per anime (two for the first), drawSet in draw_anime order."""
    themes = []
    draw = []
    next_id = 1
    for anime_id in draw_anime:
        themes.append({"id": next_id, "animeId": anime_id, "slug": "OP1", "type": "OP"})
        draw.append(next_id)
        next_id += 1
    if draw_anime:
        # A second draw-set theme of the first anime: the anime counts once.
        themes.append({"id": next_id, "animeId": draw_anime[0], "slug": "ED1", "type": "ED"})
        draw.append(next_id)
        next_id += 1
    for anime_id in extra_anime:
        # Themes outside the draw set: their anime must never be downloaded.
        themes.append({"id": next_id, "animeId": anime_id, "slug": "OP1", "type": "OP"})
        next_id += 1
    anime = [{"id": a, "slug": "show%d" % a} for a in list(draw_anime) + list(extra_anime)]
    catalog = {"version": 1, "anime": anime, "themes": themes, "drawSet": draw}
    path.write_text(json.dumps(catalog), encoding="utf-8")
    return path


# Fakes.

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
    def __init__(self, method, url, headers, body, page):
        self.method = method
        self.url = url
        self.headers = headers
        self.body = body
        self.page = page


_PAGE_IN_QUERY = re.compile(r"page\s*:\s*(\d+)")


class CoverTransport:
    """POSTs to the GraphQL URL are answered from pages (page number -> value);
    GETs from covers (url -> value). A value is bytes (200), an int (that status
    with no body), an http.Response, an Exception instance (raised), or a list of
    those served in turn."""

    def __init__(self, http, pages=None, covers=None):
        self.http = http
        self.pages = dict(pages or {})
        self.covers = dict(covers or {})
        self.calls = []

    def send(self, method, url, headers, body, timeout):
        page = None
        if method == "POST":
            assert url == GRAPHQL_URL, url
            query = json.loads(body)["query"]
            match = _PAGE_IN_QUERY.search(query)
            assert match, query
            page = int(match.group(1))
            table, key = self.pages, page
        else:
            table, key = self.covers, url
        self.calls.append(Call(method, url, dict(headers or {}), body, page))
        value = table.get(key, 404)
        if isinstance(value, list):
            value = value.pop(0) if len(value) > 1 else value[0]
        if isinstance(value, Exception):
            raise value
        if isinstance(value, int):
            return self.http.Response(status=value, headers={}, body=b"not found")
        if isinstance(value, self.http.Response):
            return value
        return self.http.Response(status=200, headers={}, body=value)

    def posts(self):
        return [call.page for call in self.calls if call.method == "POST"]

    def gets(self):
        return [call.url for call in self.calls if call.method == "GET"]

    def reset(self):
        self.calls = []


def ids_from(values):
    pending = list(values)

    def new_id():
        assert pending, "new_id called more often than expected"
        return pending.pop(0)

    return new_id


def no_new_id():
    raise AssertionError("new_id must not be called when every anime has an id")


# Standard scenario: pages 1 and 2 from the fixtures.
# Draw-set anime in order: 106, 101, 102, 103, 107, 108.
#   101, 106, 107: LARGE_COVER https -> downloaded
#   102: SMALL_COVER only -> noCover;  103: http LARGE_COVER -> noCover
#   108: on no page -> notListed;  104, 105: listed but outside the draw set
DRAW = [106, 101, 102, 103, 107, 108]
OUTSIDE = [104, 105]
GOOD = [106, 101, 107]


class Rig:
    def __init__(self, covers, http, tmp_path, *, draw=None, outside=None, pages=None, routes=None):
        self.covers = covers
        self.http = http
        self.clock = FakeClock()
        if pages is None:
            pages = {1: fixture_bytes("page-1.json"), 2: fixture_bytes("page-2.json")}
        if routes is None:
            routes = {large(a): jpeg("a%d" % a, 50 + a) for a in GOOD + [104]}
        self.routes = routes
        self.transport = CoverTransport(http, pages, routes)
        self.client = http.PoliteClient(self.transport, self.clock, gap_s=3.0)
        self.cache = http.FileCache(tmp_path / "cache")
        self.catalog_path = write_catalog(tmp_path / "catalog.json", DRAW if draw is None else draw,
                                          OUTSIDE if outside is None else outside)
        self.covers_dir = tmp_path / "cover-cache"
        self.lines = []

    def run(self, **kwargs):
        kwargs.setdefault("log", self.lines.append)
        return self.covers.run(client=self.client, cache=self.cache, catalog_path=self.catalog_path,
                               covers_dir=self.covers_dir, **kwargs)

    def index_path(self):
        return self.covers_dir / "index.json"

    def index(self):
        return json.loads(self.index_path().read_text(encoding="utf-8"))

    def entries(self):
        return self.index()["covers"]

    def names(self):
        if not self.covers_dir.is_dir():
            return []
        return sorted(p.name for p in self.covers_dir.iterdir())


def counts(result):
    return {key: result[key] for key in COUNT_KEYS}


def expected_digest_key(covers, page):
    digest = hashlib.sha256(covers.QUERY_TEMPLATE.encode("utf-8")).hexdigest()[:16]
    return "ci-%s-p%d.json" % (digest, page)


# Constants.

def test_constants(covers):
    assert covers.LOCK_NAME == ".covers.lock"
    assert covers.INDEX_NAME == "index.json"
    assert covers.MAX_BYTES == 2_000_000
    assert covers.MAX_CONSECUTIVE_FAILURES == 5
    assert covers.PAGE_KEY_PREFIX == "ci-"
    assert covers.ANIMETHEMES_URL == GRAPHQL_URL
    assert "{page}" in covers.QUERY_TEMPLATE
    compact = re.sub(r"\s+", "", covers.QUERY_TEMPLATE)
    assert "animePagination(first:100,page:{page})" in compact
    for field in ("currentPage", "hasMorePages", "lastPage", "total", "images", "facet", "link"):
        assert field in compact


# Criterion 1: page parser.

def test_parse_page_returns_large_cover_links(covers):
    links, has_more, current = covers.parse_page(fixture_bytes("page-1.json"))
    assert links == {
        101: large(101),
        102: None,   # SMALL_COVER only
        103: None,   # LARGE_COVER over http
        104: large(104),
        105: None,   # no images
    }
    assert has_more is True
    assert current == 1


def test_parse_page_last_page(covers):
    links, has_more, current = covers.parse_page(fixture_bytes("page-2.json"))
    assert links == {106: large(106), 107: large(107)}
    assert has_more is False
    assert current == 2


def test_parse_page_ignores_small_cover(covers):
    links, _, _ = covers.parse_page(page_body(1, False, [(7, None)]))
    assert links == {7: None}
    assert all(link is None or "small-cover" not in link
               for link in covers.parse_page(fixture_bytes("page-1.json"))[0].values())


@pytest.mark.parametrize("body", [
    fixture_bytes("page-errors.json"),
    b'{"data": {}}',
    b'{"data": {"animePagination": null}}',
    b'{"data": null}',
    b"[]",
    b"<html>busy</html>",
], ids=["errors", "no-pagination", "null-pagination", "null-data", "array", "not-json"])
def test_parse_page_rejects_bad_bodies(covers, body):
    with pytest.raises(ValueError):
        covers.parse_page(body)


# Criterion 2: pages and their cache.

def test_page_key_uses_ci_prefix_and_query_digest(covers):
    assert covers.page_key(1) == expected_digest_key(covers, 1)
    assert covers.page_key(12) == expected_digest_key(covers, 12)
    assert not covers.page_key(1).startswith("at-")


def test_pages_fetched_until_has_more_is_false_and_cached(covers, http, tmp_path):
    rig = Rig(covers, http, tmp_path)
    rig.run(new_id=ids_from(["A" * 22, "B" * 22, "C" * 22]))
    assert rig.transport.posts() == [1, 2]
    for call in rig.transport.calls:
        if call.method == "POST":
            query = json.loads(call.body)["query"]
            assert query == covers.QUERY_TEMPLATE.replace("{page}", str(call.page))
    assert rig.cache.get(expected_digest_key(covers, 1)) == fixture_bytes("page-1.json")
    assert rig.cache.get(expected_digest_key(covers, 2)) == fixture_bytes("page-2.json")
    assert rig.cache.get(expected_digest_key(covers, 3)) is None


def test_second_run_sends_no_request(covers, http, tmp_path):
    rig = Rig(covers, http, tmp_path)
    rig.run()
    rig.transport.reset()
    result = rig.run(new_id=no_new_id)
    assert rig.transport.calls == []
    assert result["cached"] == 3
    assert result["downloaded"] == 0


def test_cached_pages_are_used_without_requests(covers, http, tmp_path):
    rig = Rig(covers, http, tmp_path, pages={})
    rig.cache.put(expected_digest_key(covers, 1), fixture_bytes("page-1.json"))
    rig.cache.put(expected_digest_key(covers, 2), fixture_bytes("page-2.json"))
    result = rig.run()
    assert rig.transport.posts() == []
    assert result["downloaded"] == 3


def test_pages_of_another_query_are_not_reused(covers, http, tmp_path):
    rig = Rig(covers, http, tmp_path)
    stale_key = "ci-0000000000000000-p1.json"
    assert stale_key != expected_digest_key(covers, 1)
    rig.cache.put(stale_key, page_body(1, False, []))
    rig.run()
    assert rig.transport.posts() == [1, 2]


def test_fetched_page_with_wrong_current_page_raises(covers, http, tmp_path):
    pages = {1: fixture_bytes("page-1.json"), 2: page_body(1, False, [(106, large(106))])}
    rig = Rig(covers, http, tmp_path, pages=pages)
    with pytest.raises(ValueError):
        rig.run()
    assert rig.cache.get(expected_digest_key(covers, 2)) is None
    assert rig.transport.gets() == []


def test_cached_page_with_wrong_current_page_raises(covers, http, tmp_path):
    rig = Rig(covers, http, tmp_path, pages={})
    rig.cache.put(expected_digest_key(covers, 1), page_body(2, False, [(106, large(106))]))
    with pytest.raises(ValueError):
        rig.run()
    assert rig.transport.gets() == []


def test_errors_page_raises_and_is_not_cached(covers, http, tmp_path):
    rig = Rig(covers, http, tmp_path, pages={1: fixture_bytes("page-errors.json")})
    with pytest.raises(ValueError):
        rig.run()
    assert rig.cache.get(expected_digest_key(covers, 1)) is None
    assert rig.transport.gets() == []


def test_page_without_pagination_raises_and_is_not_cached(covers, http, tmp_path):
    rig = Rig(covers, http, tmp_path, pages={1: b'{"data": {}}'})
    with pytest.raises(ValueError):
        rig.run()
    assert rig.cache.get(expected_digest_key(covers, 1)) is None


def test_refresh_pages_refetches_every_page(covers, http, tmp_path):
    rig = Rig(covers, http, tmp_path)
    rig.cache.put("at-1234567890abcdef-p1.json", b'{"keep": true}')
    first = rig.run()
    assert first["notListed"] == 1
    # The source now lists 108 on page 2.
    rig.transport.pages[2] = page_body(2, False, [(106, large(106)), (107, large(107)), (108, large(108))])
    rig.transport.covers[large(108)] = jpeg("a108", 70)
    rig.transport.reset()
    result = rig.run(refresh_pages=True)
    assert rig.transport.posts() == [1, 2]
    assert rig.transport.gets() == [large(108)]
    assert result["notListed"] == 0
    assert result["downloaded"] == 1
    assert result["cached"] == 3
    assert json.loads(rig.cache.get(expected_digest_key(covers, 2)))["data"]["animePagination"]["data"][-1]["id"] == 108
    # The main crawl's pages are untouched.
    assert rig.cache.get("at-1234567890abcdef-p1.json") == b'{"keep": true}'


def test_without_refresh_cached_pages_win(covers, http, tmp_path):
    rig = Rig(covers, http, tmp_path)
    rig.run()
    rig.transport.pages[2] = page_body(2, False, [(108, large(108))])
    rig.transport.reset()
    result = rig.run()
    assert rig.transport.posts() == []
    assert result["notListed"] == 1


# Criterion 3: which anime.

def test_only_draw_set_anime_are_downloaded(covers, http, tmp_path):
    rig = Rig(covers, http, tmp_path)
    result = rig.run()
    assert counts(result) == {"drawSetAnime": 6, "cached": 0, "downloaded": 3, "noCover": 2,
                              "notListed": 1, "failed": 0}
    assert rig.transport.gets() == [large(106), large(101), large(107)]
    assert large(104) not in rig.transport.gets()
    assert set(rig.entries()) == {"101", "106", "107"}


def test_no_cover_and_not_listed_send_no_request(covers, http, tmp_path):
    pages = {1: page_body(1, False, [(102, None), (103, "http://pub-test.r2.dev/anime/large-cover/L103.jpg"),
                                     (104, large(104))])}
    rig = Rig(covers, http, tmp_path, draw=[102, 103, 108], outside=[104], pages=pages)
    result = rig.run(new_id=no_new_id)
    assert counts(result) == {"drawSetAnime": 3, "cached": 0, "downloaded": 0, "noCover": 2,
                              "notListed": 1, "failed": 0}
    assert rig.transport.gets() == []


def test_not_listed_logs_refresh_hint(covers, http, tmp_path):
    rig = Rig(covers, http, tmp_path)
    rig.run()
    assert any("--refresh-pages" in line for line in rig.lines), rig.lines


def test_no_refresh_hint_when_everything_listed(covers, http, tmp_path):
    rig = Rig(covers, http, tmp_path, draw=[106, 101, 107])
    result = rig.run()
    assert result["notListed"] == 0
    assert not any("--refresh-pages" in line for line in rig.lines), rig.lines


# Criterion 4: files and index.

def test_download_writes_files_and_index(covers, http, tmp_path):
    rig = Rig(covers, http, tmp_path)
    ids = {106: "Id106" + "x" * 17, 101: "Id101" + "y" * 17, 107: "Id107" + "z" * 17}
    rig.run(new_id=ids_from([ids[106], ids[101], ids[107]]))
    index = rig.index()
    assert index["version"] == 1
    assert set(index["covers"]) == {"101", "106", "107"}
    for anime_id in GOOD:
        entry = index["covers"][str(anime_id)]
        assert entry["id"] == ids[anime_id]
        assert entry["file"] == ids[anime_id] + ".jpg"
        body = rig.routes[large(anime_id)]
        assert entry["size"] == len(body)
        assert (rig.covers_dir / entry["file"]).read_bytes() == body
    assert rig.names() == sorted(["index.json"] + [ids[a] + ".jpg" for a in GOOD])


def test_default_ids_are_url_safe_22_chars_and_unique(covers, http, tmp_path):
    rig = Rig(covers, http, tmp_path)
    rig.run()
    entries = rig.entries()
    ids = [entry["id"] for entry in entries.values()]
    assert len(ids) == 3
    assert all(ID_PATTERN.fullmatch(i) for i in ids), ids
    assert len(set(ids)) == 3


def test_duplicate_new_id_is_replaced(covers, http, tmp_path):
    rig = Rig(covers, http, tmp_path)
    a, b, c = "A" * 22, "B" * 22, "C" * 22
    rig.run(new_id=ids_from([a, a, b, b, a, c]))
    ids = sorted(entry["id"] for entry in rig.entries().values())
    assert ids == [a, b, c]


def test_new_id_unique_against_existing_index(covers, http, tmp_path):
    rig = Rig(covers, http, tmp_path, draw=[106])
    rig.run(new_id=ids_from(["A" * 22]))
    rig2 = Rig(covers, http, tmp_path, draw=[106, 101])
    rig2.run(new_id=ids_from(["A" * 22, "D" * 22]))
    assert rig2.entries()["106"]["id"] == "A" * 22
    assert rig2.entries()["101"]["id"] == "D" * 22


def test_nothing_written_outside_covers_and_cache(covers, http, tmp_path):
    rig = Rig(covers, http, tmp_path)
    rig.run()
    assert sorted(p.name for p in tmp_path.iterdir()) == ["cache", "catalog.json", "cover-cache"]
    names = rig.names()
    assert all(name == "index.json" or name.endswith(".jpg") for name in names), names
    assert len(names) == 4
    assert all(p.name.startswith("ci-") for p in (tmp_path / "cache").iterdir())


# Criterion 5: resume.

def test_rerun_keeps_ids_and_skips_without_requests(covers, http, tmp_path):
    rig = Rig(covers, http, tmp_path)
    rig.run()
    before = rig.index()
    rig.transport.reset()
    result = rig.run(new_id=no_new_id)
    assert rig.transport.calls == []
    assert counts(result) == {"drawSetAnime": 6, "cached": 3, "downloaded": 0, "noCover": 2,
                              "notListed": 1, "failed": 0}
    assert rig.index() == before


def test_wrong_size_file_is_refetched_under_same_id(covers, http, tmp_path):
    rig = Rig(covers, http, tmp_path)
    rig.run()
    entry = rig.entries()["101"]
    path = rig.covers_dir / entry["file"]
    path.write_bytes(path.read_bytes()[:10])
    rig.transport.reset()
    result = rig.run(new_id=no_new_id)
    assert rig.transport.gets() == [large(101)]
    assert result["downloaded"] == 1
    assert result["cached"] == 2
    assert rig.entries()["101"] == entry
    assert path.read_bytes() == rig.routes[large(101)]


def test_missing_file_is_refetched_under_same_id(covers, http, tmp_path):
    rig = Rig(covers, http, tmp_path)
    rig.run()
    entry = rig.entries()["107"]
    (rig.covers_dir / entry["file"]).unlink()
    rig.transport.reset()
    rig.run(new_id=no_new_id)
    assert rig.transport.gets() == [large(107)]
    assert rig.entries()["107"]["id"] == entry["id"]
    assert (rig.covers_dir / entry["file"]).read_bytes() == rig.routes[large(107)]


def test_failed_cover_is_fetched_next_run_and_other_ids_kept(covers, http, tmp_path):
    rig = Rig(covers, http, tmp_path)
    rig.transport.covers[large(101)] = covers_error(http)
    rig.run()
    assert set(rig.entries()) == {"106", "107"}
    kept = rig.entries()
    rig.transport.covers[large(101)] = rig.routes[large(101)]
    rig.transport.reset()
    rig.run(new_id=ids_from(["N" * 22]))
    assert rig.transport.gets() == [large(101)]
    assert rig.entries()["106"] == kept["106"]
    assert rig.entries()["107"] == kept["107"]
    assert rig.entries()["101"]["id"] == "N" * 22


def covers_error(http):
    return http.TransportError("connection reset")


# Criterion 6: bodies and failures.

@pytest.mark.parametrize("case", ["empty", "too-large", "png-too-large", "gif", "png-short-signature",
                                  "html"])
def test_bad_body_is_a_failure_and_leaves_no_file(covers, http, tmp_path, case):
    bodies = {
        "empty": b"",
        "too-large": b"\xff\xd8\xff" + b"\x00" * (2_000_000 - 2),
        "png-too-large": PNG_SIGNATURE + b"\x00" * (2_000_000 - 7),
        "gif": b"GIF89a" + b"\x00" * 40,
        # The first four PNG bytes alone are not the signature.
        "png-short-signature": b"\x89PNG" + b"\x00" * 40,
        "html": b"<html>error</html>",
    }
    rig = Rig(covers, http, tmp_path, draw=[101])
    rig.transport.covers[large(101)] = bodies[case]
    result = rig.run()
    assert result["failed"] == 1
    assert result["downloaded"] == 0
    names = rig.names()
    assert all(name == "index.json" for name in names), names
    if (rig.covers_dir / "index.json").exists():
        assert rig.entries() == {}


def test_body_of_exactly_max_bytes_is_accepted(covers, http, tmp_path):
    rig = Rig(covers, http, tmp_path, draw=[101])
    body = b"\xff\xd8\xff" + b"\x00" * (2_000_000 - 3)
    assert len(body) == 2_000_000
    rig.transport.covers[large(101)] = body
    result = rig.run()
    assert result["downloaded"] == 1
    assert rig.entries()["101"]["size"] == 2_000_000


# Cycle 095 criteria 1-2: PNG covers.

def test_png_body_is_saved_as_png(covers, http, tmp_path):
    rig = Rig(covers, http, tmp_path, draw=[101])
    body = png("p101", 80)
    rig.transport.covers[large(101)] = body
    cover_id = "P" * 22
    result = rig.run(new_id=ids_from([cover_id]))
    assert result["downloaded"] == 1
    assert result["failed"] == 0
    assert rig.entries()["101"] == {"id": cover_id, "file": cover_id + ".png", "size": len(body)}
    assert (rig.covers_dir / (cover_id + ".png")).read_bytes() == body
    assert rig.names() == sorted(["index.json", cover_id + ".png"])


def test_png_body_of_exactly_max_bytes_is_accepted(covers, http, tmp_path):
    rig = Rig(covers, http, tmp_path, draw=[101])
    body = PNG_SIGNATURE + b"\x00" * (2_000_000 - len(PNG_SIGNATURE))
    assert len(body) == 2_000_000
    rig.transport.covers[large(101)] = body
    result = rig.run()
    assert result["downloaded"] == 1
    entry = rig.entries()["101"]
    assert entry["size"] == 2_000_000
    assert entry["file"] == entry["id"] + ".png"


def test_mixed_formats_get_their_own_suffix(covers, http, tmp_path):
    rig = Rig(covers, http, tmp_path)
    # 106 and 107 are JPEG; 101 is PNG behind a link that ends in .jpg, as on AnimeThemes.
    rig.transport.covers[large(101)] = png("p101", 90)
    ids = {106: "J106" + "a" * 18, 101: "P101" + "b" * 18, 107: "J107" + "c" * 18}
    result = rig.run(new_id=ids_from([ids[106], ids[101], ids[107]]))
    assert result["downloaded"] == 3
    entries = rig.entries()
    assert entries["106"]["file"] == ids[106] + ".jpg"
    assert entries["101"]["file"] == ids[101] + ".png"
    assert entries["107"]["file"] == ids[107] + ".jpg"
    for anime_id in GOOD:
        entry = entries[str(anime_id)]
        assert (rig.covers_dir / entry["file"]).read_bytes() == rig.transport.covers[large(anime_id)]
        assert entry["size"] == len(rig.transport.covers[large(anime_id)])
    assert rig.names() == sorted(["index.json", ids[106] + ".jpg", ids[101] + ".png", ids[107] + ".jpg"])
    assert not any(name.endswith(".part") for name in rig.names())


def test_rerun_skips_a_png_entry_with_the_recorded_size(covers, http, tmp_path):
    rig = Rig(covers, http, tmp_path, draw=[101, 106])
    rig.transport.covers[large(101)] = png("p101", 90)
    rig.run()
    before = rig.index()
    assert before["covers"]["101"]["file"].endswith(".png")
    rig.transport.reset()
    result = rig.run(new_id=no_new_id)
    assert rig.transport.calls == []
    assert result["cached"] == 2
    assert result["downloaded"] == 0
    assert rig.index() == before


def test_wrong_size_png_is_refetched_under_same_id(covers, http, tmp_path):
    rig = Rig(covers, http, tmp_path, draw=[101])
    body = png("p101", 90)
    rig.transport.covers[large(101)] = body
    rig.run()
    entry = rig.entries()["101"]
    path = rig.covers_dir / entry["file"]
    path.write_bytes(body[:10])
    rig.transport.reset()
    result = rig.run(new_id=no_new_id)
    assert rig.transport.gets() == [large(101)]
    assert result["downloaded"] == 1
    assert rig.entries()["101"] == entry
    assert path.read_bytes() == body


def test_jpeg_refetched_as_png_updates_the_file_and_keeps_the_id(covers, http, tmp_path):
    rig = Rig(covers, http, tmp_path, draw=[101])
    cover_id = "K" * 22
    rig.run(new_id=ids_from([cover_id]))
    assert rig.entries()["101"]["file"] == cover_id + ".jpg"
    (rig.covers_dir / (cover_id + ".jpg")).unlink()
    body = png("p101", 77)
    rig.transport.covers[large(101)] = body
    rig.transport.reset()
    result = rig.run(new_id=no_new_id)
    assert rig.transport.gets() == [large(101)]
    assert result["downloaded"] == 1
    assert rig.entries()["101"] == {"id": cover_id, "file": cover_id + ".png", "size": len(body)}
    assert (rig.covers_dir / (cover_id + ".png")).read_bytes() == body
    assert not any(name.endswith(".part") for name in rig.names())


def test_png_refetched_as_jpeg_updates_the_file_and_keeps_the_id(covers, http, tmp_path):
    rig = Rig(covers, http, tmp_path, draw=[101])
    cover_id = "Q" * 22
    old = png("p101", 77)
    rig.transport.covers[large(101)] = old
    rig.run(new_id=ids_from([cover_id]))
    assert rig.entries()["101"]["file"] == cover_id + ".png"
    # A broken file: its size no longer matches the index.
    (rig.covers_dir / (cover_id + ".png")).write_bytes(old[:5])
    body = jpeg("j101", 66)
    rig.transport.covers[large(101)] = body
    rig.transport.reset()
    rig.run(new_id=no_new_id)
    assert rig.transport.gets() == [large(101)]
    assert rig.entries()["101"] == {"id": cover_id, "file": cover_id + ".jpg", "size": len(body)}
    assert (rig.covers_dir / (cover_id + ".jpg")).read_bytes() == body
    # The old file is not deleted (spec: nothing reads files the index does not name).
    assert (rig.covers_dir / (cover_id + ".png")).exists()


# Cycle 095 criterion 3: the index entry rule is file in {<id>.jpg, <id>.png}.

ENTRY_ID = "E" * 22
OTHER_ID = "F" * 22


def entry_rule_index(bad_file):
    """An index with one valid .jpg entry and one entry whose file is bad_file."""
    return json.dumps({"version": 1, "covers": {
        "106": {"id": "G" * 22, "file": "G" * 22 + ".jpg", "size": 10},
        "101": {"id": ENTRY_ID, "file": bad_file, "size": 10},
    }}, indent=1).encode("ascii")


BAD_ENTRY_FILES = {
    "other-id-png": OTHER_ID + ".png",
    "other-id-jpg": OTHER_ID + ".jpg",
    "dot-dot-png": "../x.png",
    "upper-png": ENTRY_ID + ".PNG",
    "upper-jpg": ENTRY_ID + ".JPG",
    "jpeg": ENTRY_ID + ".jpeg",
    "gif": ENTRY_ID + ".gif",
    "webp": ENTRY_ID + ".webp",
    "part": ENTRY_ID + ".png.part",
    "no-suffix": ENTRY_ID,
}


@pytest.mark.parametrize("case", sorted(BAD_ENTRY_FILES))
def test_bad_entry_file_stops_before_any_request(covers, http, tmp_path, case):
    rig = Rig(covers, http, tmp_path)
    rig.covers_dir.mkdir()
    content = entry_rule_index(BAD_ENTRY_FILES[case])
    rig.index_path().write_bytes(content)
    with pytest.raises(covers.CoversStopped):
        rig.run()
    assert rig.transport.calls == []
    assert rig.index_path().read_bytes() == content
    assert [n for n in rig.names() if n != ".covers.lock"] == ["index.json"]


def test_index_mixing_jpg_and_png_entries_loads(covers, http, tmp_path):
    rig = Rig(covers, http, tmp_path, draw=[106, 101])
    rig.covers_dir.mkdir()
    jpg_body, png_body = jpeg("j106", 40), png("p101", 50)
    (rig.covers_dir / ("J" * 22 + ".jpg")).write_bytes(jpg_body)
    (rig.covers_dir / ("P" * 22 + ".png")).write_bytes(png_body)
    content = json.dumps({"version": 1, "covers": {
        "106": {"id": "J" * 22, "file": "J" * 22 + ".jpg", "size": len(jpg_body)},
        "101": {"id": "P" * 22, "file": "P" * 22 + ".png", "size": len(png_body)},
    }}, indent=1).encode("ascii")
    rig.index_path().write_bytes(content)
    result = rig.run(new_id=no_new_id)
    assert rig.transport.gets() == []
    assert result["cached"] == 2
    assert result["downloaded"] == 0
    assert rig.index() == json.loads(content)


@pytest.mark.parametrize("failure", ["404", "500", "transport"])
def test_failed_download_is_counted_and_run_continues(covers, http, tmp_path, failure):
    values = {"404": 404, "500": 500, "transport": http.TransportError("refused")}
    rig = Rig(covers, http, tmp_path)
    rig.transport.covers[large(106)] = values[failure]
    result = rig.run()
    assert result["failed"] == 1
    assert result["downloaded"] == 2
    assert set(rig.entries()) == {"101", "107"}
    assert rig.lines[-1].endswith("failed=1")


def failing_rig(covers, http, tmp_path, outcomes):
    """Draw-set anime 201.. in order; outcomes[i] True serves a cover, False a 404."""
    anime = [201 + i for i in range(len(outcomes))]
    pages = {1: page_body(1, False, [(a, large(a)) for a in anime])}
    routes = {large(a): jpeg("b%d" % a) for a, ok in zip(anime, outcomes) if ok}
    rig = Rig(covers, http, tmp_path, draw=anime, outside=[], pages=pages, routes=routes)
    return rig, anime


def test_five_consecutive_failures_stop_the_run(covers, http, tmp_path):
    rig, anime = failing_rig(covers, http, tmp_path, [True] + [False] * 6)
    with pytest.raises(covers.CoversStopped):
        rig.run()
    assert rig.transport.gets() == [large(a) for a in anime[:6]]
    # The cover finished before the stop is kept.
    assert set(rig.entries()) == {"201"}
    assert not (rig.covers_dir / ".covers.lock").exists()


def test_success_resets_the_failure_count(covers, http, tmp_path):
    rig, anime = failing_rig(covers, http, tmp_path, [False] * 4 + [True] + [False] * 4)
    result = rig.run()
    assert result["failed"] == 8
    assert result["downloaded"] == 1
    assert rig.transport.gets() == [large(a) for a in anime]


# Criterion 7: 429 through PoliteClient.

def test_429_retry_after_is_waited_out(covers, http, tmp_path):
    rig = Rig(covers, http, tmp_path, draw=[101])
    body = rig.routes[large(101)]
    rig.transport.covers[large(101)] = [
        http.Response(status=429, headers={"retry-after": "7"}, body=b""),
        body,
    ]
    result = rig.run()
    assert result["downloaded"] == 1
    assert rig.transport.gets() == [large(101), large(101)]
    assert 7 in rig.clock.sleeps or 7.0 in rig.clock.sleeps
    assert all(call.headers.get("User-Agent", "").startswith("SongRushBot/") for call in rig.transport.calls)


def test_requests_are_spaced_by_the_client_gap(covers, http, tmp_path):
    rig = Rig(covers, http, tmp_path)
    rig.run()
    # 2 pages + 3 covers: every request after the first waited the 3 s gap.
    assert len(rig.transport.calls) == 5
    assert [s for s in rig.clock.sleeps if s > 0] == [3.0] * 4


# Criterion 8: log line and CLI.

def test_final_log_line_has_the_counts(covers, http, tmp_path):
    rig = Rig(covers, http, tmp_path)
    rig.run()
    assert rig.lines[-1] == "covers drawSetAnime=6 cached=0 downloaded=3 noCover=2 notListed=1 failed=0"
    rig.lines.clear()
    rig.run()
    assert rig.lines[-1] == "covers drawSetAnime=6 cached=3 downloaded=0 noCover=2 notListed=1 failed=0"


@pytest.fixture
def streams(monkeypatch):
    """Return install(), which swaps stdout and stderr for cp949 streams over byte buffers."""

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


@pytest.mark.parametrize("argv", [["stray"], ["--nope"], ["--covers", "x", "extra"]],
                         ids=["positional", "unknown-flag", "trailing-positional"])
def test_main_usage_error_returns_2(covers, streams, monkeypatch, argv):
    calls = []
    monkeypatch.setattr(covers, "run", lambda *a, **k: calls.append((a, k)) or {})
    streams()
    assert covers.main(argv) == 2
    _restore_console()
    assert calls == []


def test_main_passes_arguments_to_run(covers, http, streams, monkeypatch, tmp_path):
    seen = []

    def finished(*args, **kwargs):
        seen.append((args, kwargs))
        return {key: 0 for key in COUNT_KEYS}

    monkeypatch.setattr(covers, "run", finished)
    streams()
    code = covers.main(["--cache", str(tmp_path / "c"), "--covers", str(tmp_path / "k"),
                        "--catalog", str(tmp_path / "cat.json"), "--refresh-pages"])
    _restore_console()
    assert code == 0
    assert len(seen) == 1
    args, kwargs = seen[0]
    assert args == ()
    assert isinstance(kwargs["client"], http.PoliteClient)
    assert kwargs["client"]._gap_s >= 3.0
    assert kwargs["client"]._timeout_s >= 60.0
    assert isinstance(kwargs["cache"], http.FileCache)
    assert Path(kwargs["cache"].root).resolve() == (tmp_path / "c").resolve()
    assert Path(kwargs["covers_dir"]).resolve() == (tmp_path / "k").resolve()
    assert Path(kwargs["catalog_path"]).resolve() == (tmp_path / "cat.json").resolve()
    assert kwargs["refresh_pages"] is True


def test_main_defaults(covers, http, streams, monkeypatch):
    seen = []
    monkeypatch.setattr(covers, "run", lambda *a, **k: seen.append(k) or {key: 0 for key in COUNT_KEYS})
    streams()
    assert covers.main([]) == 0
    _restore_console()
    kwargs = seen[0]
    assert Path(kwargs["cache"].root).resolve() == (DATA_DIR / "cache").resolve()
    assert Path(kwargs["covers_dir"]).resolve() == (DATA_DIR / "cover-cache").resolve()
    assert Path(kwargs["catalog_path"]).resolve() == (DATA_DIR / "catalog.json").resolve()
    assert kwargs["refresh_pages"] is False


def test_main_stopped_returns_1(covers, streams, monkeypatch, tmp_path):
    def stopped(*args, **kwargs):
        raise covers.CoversStopped("covers stopped after 5 consecutive failures")

    monkeypatch.setattr(covers, "run", stopped)
    streams()
    assert covers.main(["--covers", str(tmp_path / "k"), "--cache", str(tmp_path / "c")]) == 1
    _restore_console()


def test_main_page_error_returns_1(covers, streams, monkeypatch, tmp_path):
    def bad_page(*args, **kwargs):
        raise ValueError("page 2: response has currentPage 1")

    monkeypatch.setattr(covers, "run", bad_page)
    streams()
    assert covers.main(["--covers", str(tmp_path / "k"), "--cache", str(tmp_path / "c")]) == 1
    _restore_console()


def test_main_locked_returns_3(covers, streams, monkeypatch, tmp_path):
    def locked(*args, **kwargs):
        raise covers.CoversLocked("lock held")

    monkeypatch.setattr(covers, "run", locked)
    streams()
    assert covers.main(["--covers", str(tmp_path / "k"), "--cache", str(tmp_path / "c")]) == 3
    _restore_console()


# Criterion 8a: lock.

def test_existing_lock_sends_no_request(covers, http, tmp_path):
    rig = Rig(covers, http, tmp_path)
    rig.covers_dir.mkdir()
    lock = rig.covers_dir / ".covers.lock"
    lock.write_text("4242", encoding="ascii")
    with pytest.raises(covers.CoversLocked):
        rig.run()
    assert rig.transport.calls == []
    assert lock.read_text(encoding="ascii") == "4242"
    assert rig.names() == [".covers.lock"]


def test_main_with_lock_present_exits_3(covers, streams, tmp_path):
    covers_dir = tmp_path / "k"
    covers_dir.mkdir()
    (covers_dir / ".covers.lock").write_text("4242", encoding="ascii")
    catalog = write_catalog(tmp_path / "catalog.json", DRAW, OUTSIDE)
    streams()
    code = covers.main(["--cache", str(tmp_path / "c"), "--covers", str(covers_dir), "--catalog", str(catalog)])
    _restore_console()
    assert code == 3
    assert (covers_dir / ".covers.lock").exists()
    assert not (tmp_path / "c").exists() or list((tmp_path / "c").iterdir()) == []


def test_lock_removed_after_normal_run(covers, http, tmp_path):
    rig = Rig(covers, http, tmp_path)
    rig.run()
    assert not (rig.covers_dir / ".covers.lock").exists()


def test_covers_dir_is_created(covers, http, tmp_path):
    rig = Rig(covers, http, tmp_path)
    assert not rig.covers_dir.exists()
    rig.run()
    assert rig.covers_dir.is_dir()


# Criterion 8b: broken index.

BROKEN_INDEXES = {
    "not-json": b"{not json",
    "array": b"[]",
    "string": b'"covers"',
    "wrong-version": b'{"version": 2, "covers": {}}',
    "no-version": b'{"covers": {}}',
    "covers-array": b'{"version": 1, "covers": []}',
    "covers-missing": b'{"version": 1}',
}


@pytest.mark.parametrize("case", sorted(BROKEN_INDEXES))
def test_broken_index_stops_before_any_request(covers, http, tmp_path, case):
    rig = Rig(covers, http, tmp_path)
    rig.covers_dir.mkdir()
    rig.index_path().write_bytes(BROKEN_INDEXES[case])
    with pytest.raises(covers.CoversStopped):
        rig.run()
    assert rig.transport.calls == []
    assert rig.index_path().read_bytes() == BROKEN_INDEXES[case]
    assert [n for n in rig.names() if n != ".covers.lock"] == ["index.json"]


def test_main_broken_index_exits_nonzero(covers, streams, tmp_path):
    covers_dir = tmp_path / "k"
    covers_dir.mkdir()
    (covers_dir / "index.json").write_bytes(b'{"version": 2, "covers": {}}')
    catalog = write_catalog(tmp_path / "catalog.json", DRAW, OUTSIDE)
    streams()
    code = covers.main(["--cache", str(tmp_path / "c"), "--covers", str(covers_dir), "--catalog", str(catalog)])
    _restore_console()
    assert code != 0
    assert (covers_dir / "index.json").read_bytes() == b'{"version": 2, "covers": {}}'
