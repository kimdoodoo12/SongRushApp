"""Cycle 017 criteria 1-4: AnimeThemes GraphQL page fetch, crawl and theme selection.

docs/cycles/017-animethemes-crawl.md is the specification. Every request goes
to a fake transport with a fake clock, so the tests are offline and instant.
Imports of songrush_data.animethemes stay inside fixtures and tests so that a
missing module fails these tests, not the collection.
"""

import copy
import hashlib
import json
import re
from pathlib import Path

import pytest

FIXTURES = Path(__file__).resolve().parent / "fixtures"
PAGE1_PATH = FIXTURES / "gql_page1.json"
REQUEST_PATH = FIXTURES / "gql_page_request.json"
T0 = 1000.0
WALL0 = 1_800_000_000.0


@pytest.fixture
def at():
    from songrush_data import animethemes as module

    return module


@pytest.fixture
def http():
    from songrush_data import http as module

    return module


def load_page1():
    return json.loads(PAGE1_PATH.read_text(encoding="utf-8"))


# Fakes (copied from test_http.py, no cross-test imports).

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

    def advance(self, seconds):
        self._now += seconds
        self._wall += seconds


class Call:
    def __init__(self, method, url, headers, body, timeout, start):
        self.method = method
        self.url = url
        self.headers = headers
        self.body = body
        self.timeout = timeout
        self.start = start

    def json(self):
        return json.loads(self.body)

    def page(self):
        match = re.search(r"page:(\d+)", self.json()["query"])
        assert match is not None, "request query has no page:N"
        return int(match.group(1))


class ScriptTransport:
    """Returns the scripted Response objects in order."""

    def __init__(self, clock, responses):
        self.clock = clock
        self.responses = list(responses)
        self.calls = []

    def send(self, method, url, headers, body, timeout):
        self.calls.append(Call(method, url, dict(headers or {}), body, timeout, self.clock.now()))
        if not self.responses:
            raise AssertionError("transport called more times than scripted")
        return self.responses.pop(0)


class PageTransport:
    """Serves synthetic pages by the page:N in the request query."""

    def __init__(self, clock, http, pages):
        self.clock = clock
        self.http = http
        self.pages = pages  # page number -> response body dict; may be replaced
        self.calls = []

    def send(self, method, url, headers, body, timeout):
        call = Call(method, url, dict(headers or {}), body, timeout, self.clock.now())
        self.calls.append(call)
        page = call.page()
        if page not in self.pages:
            raise AssertionError("page %d was not expected" % page)
        data = json.dumps(self.pages[page]).encode("utf-8")
        return self.http.Response(status=200, headers={"content-type": "application/json"}, body=data)

    def called_pages(self):
        return [call.page() for call in self.calls]


def make_client(http, transport, clock):
    return http.PoliteClient(transport, clock, gap_s=3.0)


def ok_response(http, body_dict):
    data = json.dumps(body_dict).encode("utf-8")
    return http.Response(status=200, headers={"content-type": "application/json"}, body=data)


def template_text(at):
    return Path(at.QUERY_PATH).read_bytes().decode("utf-8").replace("\r\n", "\n")


def page_key(at, page):
    digest = hashlib.sha256(template_text(at).encode("utf-8")).hexdigest()[:16]
    return "at-%s-p%d.json" % (digest, page)


def synthetic_body(page, ids, has_more, total=9):
    return {
        "data": {
            "animePagination": {
                "paginatorInfo": {
                    "count": len(ids),
                    "currentPage": page,
                    "hasMorePages": has_more,
                    "lastPage": 3,
                    "perPage": 100,
                    "total": total,
                },
                "data": [{"id": i, "slug": "a%d" % i} for i in ids],
            }
        }
    }


def three_pages(total=9):
    return {
        1: synthetic_body(1, [1, 2, 3], True, total),
        2: synthetic_body(2, [4, 5, 6], True, total),
        3: synthetic_body(3, [7, 8, 9], False, total),
    }


def crawl_setup(at, http, tmp_path, pages):
    clock = FakeClock()
    transport = PageTransport(clock, http, pages)
    client = make_client(http, transport, clock)
    cache = http.FileCache(tmp_path)
    return client, transport, cache


def run_crawl(at, client, cache):
    logs = []
    result = at.crawl(client, cache, log=logs.append)
    return result, [str(line) for line in logs]


def item_ids(result):
    return [item["id"] for item in result.items]


# Criterion 1: the query file.

def test_query_file_matches_fixture_request(at):
    raw = Path(at.QUERY_PATH).read_bytes()
    assert raw.isascii()
    assert b"\r" not in raw
    text = raw.decode("ascii")
    assert "{page}" in text
    expected = json.loads(REQUEST_PATH.read_text(encoding="utf-8"))["query"].replace("\r\n", "\n")
    assert text.replace("{page}", "1") == expected


def test_query_path_location_and_url(at):
    path = Path(at.QUERY_PATH)
    assert path.name == "animethemes.graphql"
    assert path.parent.name == "songrush_data"
    assert at.ANIMETHEMES_URL == "https://graphql.animethemes.moe/"


# Criterion 2: fetch_page.

def test_fetch_page_posts_query_and_returns_pagination(at, http, tmp_path):
    body = load_page1()
    clock = FakeClock()
    transport = ScriptTransport(clock, [ok_response(http, body)])
    client = make_client(http, transport, clock)
    cache = http.FileCache(tmp_path)
    got = at.fetch_page(client, cache, 1)
    assert got == body["data"]["animePagination"]
    assert len(transport.calls) == 1
    call = transport.calls[0]
    assert call.method == "POST"
    assert call.url == at.ANIMETHEMES_URL
    content_types = [v for k, v in call.headers.items() if k.lower() == "content-type"]
    assert content_types == ["application/json"]
    sent = call.json()
    assert set(sent) == {"query"}
    assert "page:1" in sent["query"]
    assert sent["query"] == template_text(at).replace("{page}", "1")
    assert cache.get(page_key(at, 1)) is not None


def test_fetch_page_second_call_is_cached(at, http, tmp_path):
    body = load_page1()
    clock = FakeClock()
    transport = ScriptTransport(clock, [ok_response(http, body)])
    client = make_client(http, transport, clock)
    cache = http.FileCache(tmp_path)
    first = at.fetch_page(client, cache, 1)
    second = at.fetch_page(client, cache, 1)
    assert len(transport.calls) == 1
    assert second == first


def _bad_with_errors():
    body = load_page1()
    body["errors"] = [{"message": "something failed"}]
    return body, 1


def _bad_wrong_page():
    return load_page1(), 2  # currentPage 1 for a page-2 request


def _bad_missing_pagination():
    return {"data": {}}, 1


def _bad_per_page():
    body = load_page1()
    body["data"]["animePagination"]["paginatorInfo"]["perPage"] = 50
    return body, 1


@pytest.mark.parametrize(
    "make_bad",
    [_bad_with_errors, _bad_wrong_page, _bad_missing_pagination, _bad_per_page],
    ids=["errors", "wrong-current-page", "missing-pagination", "per-page"],
)
def test_fetch_page_invalid_response_raises_and_caches_nothing(at, http, tmp_path, make_bad):
    body, page = make_bad()
    clock = FakeClock()
    transport = ScriptTransport(clock, [ok_response(http, body)])
    client = make_client(http, transport, clock)
    cache = http.FileCache(tmp_path)
    with pytest.raises(ValueError):
        at.fetch_page(client, cache, page)
    assert len(transport.calls) == 1
    assert cache.get(page_key(at, page)) is None


def test_validate_page_accepts_fixture_and_rejects_wrong_page(at):
    body = load_page1()
    at.validate_page(body, 1)
    with pytest.raises(ValueError):
        at.validate_page(body, 2)
    with pytest.raises(ValueError):
        at.validate_page({"data": {}}, 1)


def test_fetch_page_refresh_fetches_and_overwrites(at, http, tmp_path):
    body = load_page1()
    fresh = copy.deepcopy(body)
    fresh["data"]["animePagination"]["data"] = fresh["data"]["animePagination"]["data"][:-1]
    clock = FakeClock()
    transport = ScriptTransport(clock, [ok_response(http, body), ok_response(http, fresh)])
    client = make_client(http, transport, clock)
    cache = http.FileCache(tmp_path)
    at.fetch_page(client, cache, 1)
    before = cache.get(page_key(at, 1))
    got = at.fetch_page(client, cache, 1, refresh=True)
    assert len(transport.calls) == 2
    assert got == fresh["data"]["animePagination"]
    assert cache.get(page_key(at, 1)) != before
    again = at.fetch_page(client, cache, 1)
    assert len(transport.calls) == 2
    assert again == fresh["data"]["animePagination"]


def test_fetch_page_refresh_invalid_keeps_old_entry(at, http, tmp_path):
    body = load_page1()
    bad = copy.deepcopy(body)
    bad["errors"] = [{"message": "boom"}]
    clock = FakeClock()
    transport = ScriptTransport(clock, [ok_response(http, body), ok_response(http, bad)])
    client = make_client(http, transport, clock)
    cache = http.FileCache(tmp_path)
    at.fetch_page(client, cache, 1)
    before = cache.get(page_key(at, 1))
    with pytest.raises(ValueError):
        at.fetch_page(client, cache, 1, refresh=True)
    assert cache.get(page_key(at, 1)) == before


# Criterion 3: crawl.

def test_crawl_fetches_all_pages(at, http, tmp_path):
    client, transport, cache = crawl_setup(at, http, tmp_path, three_pages())
    result, _ = run_crawl(at, client, cache)
    assert transport.called_pages() == [1, 2, 3]
    assert item_ids(result) == list(range(1, 10))
    assert result.pages == 3
    assert result.total == 9


# Cycle 023 criterion 1 replaces cycle 017's test_crawl_rerun_fully_cached_makes_no_calls:
# a fully cached rerun now refetches the last page once to see source changes.

def test_crawl_rerun_fully_cached_refetches_only_last_page(at, http, tmp_path):
    client, transport, cache = crawl_setup(at, http, tmp_path, three_pages())
    first, _ = run_crawl(at, client, cache)
    client2, transport2, _ = crawl_setup(at, http, tmp_path, three_pages())
    result, logs = run_crawl(at, client2, cache)
    assert transport2.called_pages() == [3]
    assert item_ids(result) == list(range(1, 10))
    assert result.items == first.items
    assert result.pages == 3
    assert result.total == 9
    assert not any("cleared" in line for line in logs), logs


def _prime_pages_1_2(at, http, tmp_path):
    clock = FakeClock()
    transport = PageTransport(clock, http, three_pages())
    client = make_client(http, transport, clock)
    cache = http.FileCache(tmp_path)
    at.fetch_page(client, cache, 1)
    at.fetch_page(client, cache, 2)
    assert cache.get(page_key(at, 3)) is None
    return cache


def test_crawl_resume_refetches_last_cached_page(at, http, tmp_path):
    cache = _prime_pages_1_2(at, http, tmp_path)
    client, transport, _ = crawl_setup(at, http, tmp_path, three_pages())
    result, logs = run_crawl(at, client, cache)
    assert transport.called_pages() == [2, 3]
    assert item_ids(result) == list(range(1, 10))
    assert not any("shifted" in line for line in logs)


def test_crawl_resume_shift_clears_and_restarts(at, http, tmp_path):
    cache = _prime_pages_1_2(at, http, tmp_path)
    shifted = {
        1: synthetic_body(1, [2, 3, 4], True),
        2: synthetic_body(2, [5, 6, 7], True),
        3: synthetic_body(3, [8, 9, 10], False),
    }
    client, transport, _ = crawl_setup(at, http, tmp_path, shifted)
    result, logs = run_crawl(at, client, cache)
    assert transport.called_pages() == [2, 1, 2, 3]
    assert item_ids(result) == list(range(2, 11))
    assert sum(1 for line in logs if "shifted" in line) == 1
    assert result.pages == 3


def _two_pages(first_ids, second_ids):
    return {
        1: synthetic_body(1, first_ids, True, total=4),
        2: synthetic_body(2, second_ids, False, total=4),
    }


def test_crawl_order_violation_warns_once(at, http, tmp_path):
    good_client, _, good_cache = crawl_setup(at, http, tmp_path / "good", _two_pages([1, 2], [3, 4]))
    _, baseline = run_crawl(at, good_client, good_cache)
    client, _, cache = crawl_setup(at, http, tmp_path / "bad", _two_pages([1, 3], [2, 4]))
    result, logs = run_crawl(at, client, cache)
    assert item_ids(result) == [1, 3, 2, 4]
    assert len(logs) == len(baseline) + 1
    naming = [
        line for line in logs
        if re.search(r"\b1\b", line) and re.search(r"\b2\b", line) and not line.startswith("crawl pages=")
    ]
    assert naming, logs


def test_crawl_duplicate_raises_and_deletes_later_page(at, http, tmp_path):
    pages = {
        1: synthetic_body(1, [1, 2, 3], True),
        2: synthetic_body(2, [4, 5, 6], True),
        3: synthetic_body(3, [7, 8, 2], False),
    }
    client, _, cache = crawl_setup(at, http, tmp_path, pages)
    with pytest.raises(ValueError) as info:
        run_crawl(at, client, cache)
    message = str(info.value)
    assert re.search(r"\b1\b", message) and re.search(r"\b3\b", message), message
    assert cache.get(page_key(at, 3)) is None
    assert cache.get(page_key(at, 1)) is not None
    assert cache.get(page_key(at, 2)) is not None


def test_crawl_summary_and_total_warning(at, http, tmp_path):
    client, _, cache = crawl_setup(at, http, tmp_path / "nine", three_pages(total=9))
    result9, logs9 = run_crawl(at, client, cache)
    client, _, cache = crawl_setup(at, http, tmp_path / "ten", three_pages(total=10))
    result10, logs10 = run_crawl(at, client, cache)
    assert sum(1 for line in logs9 if "crawl pages=3 anime=9 total=9" in line) == 1
    assert sum(1 for line in logs10 if "crawl pages=3 anime=9 total=10" in line) == 1
    assert len(logs10) == len(logs9) + 1
    assert result9.pages == 3
    assert result10.pages == 3
    assert result9.total == 9
    assert result10.total == 10
    assert len(result10.items) == 9


def test_crawl_result_type(at, http, tmp_path):
    client, _, cache = crawl_setup(at, http, tmp_path, three_pages())
    result, _ = run_crawl(at, client, cache)
    assert isinstance(result, at.CrawlResult)


# Cycle 023 criteria 2-4: last-page freshness, total check, refresh_all.
# docs/cycles/023-build-cli.md is the specification.

class SequenceTransport(PageTransport):
    """Serves pages[n] as a list of bodies: one per request, the last one repeats."""

    def send(self, method, url, headers, body, timeout):
        call = Call(method, url, dict(headers or {}), body, timeout, self.clock.now())
        self.calls.append(call)
        page = call.page()
        if page not in self.pages:
            raise AssertionError("page %d was not expected" % page)
        bodies = self.pages[page]
        chosen = bodies.pop(0) if len(bodies) > 1 else bodies[0]
        data = json.dumps(chosen).encode("utf-8")
        return self.http.Response(status=200, headers={"content-type": "application/json"}, body=data)


def _cached_three_pages(at, http, tmp_path):
    """Run a full crawl of three_pages() and return its cache."""
    client, _, cache = crawl_setup(at, http, tmp_path, three_pages())
    run_crawl(at, client, cache)
    for page in (1, 2, 3):
        assert cache.get(page_key(at, page)) is not None
    return cache


def test_crawl_last_page_gained_one_anime_keeps_cache(at, http, tmp_path):
    cache = _cached_three_pages(at, http, tmp_path)
    page1_before = cache.get(page_key(at, 1))
    page2_before = cache.get(page_key(at, 2))
    grown = {
        1: synthetic_body(1, [1, 2, 3], True, total=10),
        2: synthetic_body(2, [4, 5, 6], True, total=10),
        3: synthetic_body(3, [7, 8, 9, 10], False, total=10),
    }
    client, transport, _ = crawl_setup(at, http, tmp_path, grown)
    result, logs = run_crawl(at, client, cache)
    assert transport.called_pages() == [3]
    assert item_ids(result) == list(range(1, 11))
    assert sum(1 for line in logs if "gained 1 anime" in line) == 1, logs
    assert not any("cleared" in line for line in logs), logs
    assert not any("changed since it was cached" in line for line in logs), logs
    assert not any("total changed" in line for line in logs), logs
    # Nothing was cleared: pages 1 and 2 keep their entries, page 3 holds the fresh page.
    assert cache.get(page_key(at, 1)) == page1_before
    assert cache.get(page_key(at, 2)) == page2_before
    cached3 = json.loads(cache.get(page_key(at, 3)))
    assert [item["id"] for item in cached3["data"]["animePagination"]["data"]] == [7, 8, 9, 10]
    assert result.total == 10


def test_crawl_last_page_gained_and_has_more_fetches_next_page(at, http, tmp_path):
    cache = _cached_three_pages(at, http, tmp_path)
    # Page 3 gained id 10 and now says more pages follow; total grew by exactly
    # the one new id, so page 4 is empty and carries the same total.
    grown = {
        1: synthetic_body(1, [1, 2, 3], True, total=10),
        2: synthetic_body(2, [4, 5, 6], True, total=10),
        3: synthetic_body(3, [7, 8, 9, 10], True, total=10),
        4: synthetic_body(4, [], False, total=10),
    }
    client, transport, _ = crawl_setup(at, http, tmp_path, grown)
    result, logs = run_crawl(at, client, cache)
    assert transport.called_pages() == [3, 4]
    assert item_ids(result) == list(range(1, 11))
    assert sum(1 for line in logs if "gained 1 anime" in line) == 1, logs
    assert not any("cleared" in line for line in logs), logs
    assert result.pages == 4
    assert cache.get(page_key(at, 1)) is not None
    assert cache.get(page_key(at, 4)) is not None


@pytest.mark.parametrize(
    "last_ids, total",
    [([7, 9], 8), ([7, 9, 8], 9)],
    ids=["lost-id", "reordered"],
)
def test_crawl_last_page_changed_clears_and_restarts(at, http, tmp_path, last_ids, total):
    cache = _cached_three_pages(at, http, tmp_path)
    # Every fresh page carries the same new total, so the restarted pass is consistent.
    changed = {
        1: synthetic_body(1, [1, 2, 3], True, total=total),
        2: synthetic_body(2, [4, 5, 6], True, total=total),
        3: synthetic_body(3, last_ids, False, total=total),
    }
    client, transport, _ = crawl_setup(at, http, tmp_path, changed)
    result, logs = run_crawl(at, client, cache)
    assert transport.called_pages() == [3, 1, 2, 3]
    assert item_ids(result) == [1, 2, 3, 4, 5, 6] + last_ids
    assert sum(1 for line in logs if "changed since it was cached" in line) == 1, logs
    assert not any("gained" in line for line in logs), logs
    assert result.total == total


# Cycle 025: incremental crawl with real-size pages (100 items).
# docs/cycles/025-incremental-crawl.md is the specification. Criteria 4-6
# replace cycle 023's test_crawl_total_changed_mid_pass_clears_and_restarts and
# test_crawl_total_keeps_changing_raises_after_two_restarts (criterion 8).

def source_pages(ids, total=None):
    """Split ids into 100-item synthetic pages; hasMorePages is true on all but the last."""
    ids = list(ids)
    if total is None:
        total = len(ids)
    chunks = [ids[start:start + 100] for start in range(0, len(ids), 100)] or [[]]
    return {
        number: synthetic_body(number, chunk, number < len(chunks), total)
        for number, chunk in enumerate(chunks, start=1)
    }


def _cache_source(at, http, tmp_path, ids):
    """Run a full crawl of source_pages(ids) and return its cache."""
    pages = source_pages(ids)
    client, transport, cache = crawl_setup(at, http, tmp_path, pages)
    run_crawl(at, client, cache)
    assert transport.called_pages() == list(range(1, len(pages) + 1))
    return cache


def _count(logs, text):
    return sum(1 for line in logs if text in line)


def _sequence_setup(http, tmp_path, pages):
    clock = FakeClock()
    transport = SequenceTransport(clock, http, pages)
    client = make_client(http, transport, clock)
    cache = http.FileCache(tmp_path)
    return client, transport, cache


@pytest.mark.parametrize(
    "cached_count, gained",
    [(300, 0), (299, 1)],
    ids=["crit1-full-last-page", "crit2-partly-filled-last-page"],
)
def test_crawl_spill_onto_new_page_appends_and_rerun_checks_only_last(at, http, tmp_path, cached_count, gained):
    # Criteria 1 and 2: the source grew to 301, spilling onto a new page 4.
    cache = _cache_source(at, http, tmp_path, range(1, cached_count + 1))
    page1_before = cache.get(page_key(at, 1))
    page2_before = cache.get(page_key(at, 2))
    grown = source_pages(range(1, 302))
    assert grown[3]["data"]["animePagination"]["paginatorInfo"]["hasMorePages"] is True
    assert len(grown[3]["data"]["animePagination"]["data"]) == 100
    assert len(grown[4]["data"]["animePagination"]["data"]) == 1

    client, transport, _ = crawl_setup(at, http, tmp_path, grown)
    result, logs = run_crawl(at, client, cache)
    assert transport.called_pages() == [3, 4]
    assert item_ids(result) == list(range(1, 302))
    assert result.total == 301
    assert result.pages == 4
    assert _count(logs, "gained %d anime" % gained) == 1, logs
    assert not any("cleared" in line for line in logs), logs
    assert not any("total changed" in line for line in logs), logs
    assert cache.get(page_key(at, 1)) == page1_before
    assert cache.get(page_key(at, 2)) == page2_before

    # Second run, same cache, unchanged source: only the last page is checked.
    client2, transport2, _ = crawl_setup(at, http, tmp_path, source_pages(range(1, 302)))
    again, logs2 = run_crawl(at, client2, cache)
    assert transport2.called_pages() == [4]
    assert item_ids(again) == list(range(1, 302))
    assert again.total == 301
    assert not any("cleared" in line for line in logs2), logs2
    assert not any("total changed" in line for line in logs2), logs2
    assert not any("total grew" in line for line in logs2), logs2


def test_crawl_addition_during_resumed_crawl_continues(at, http, tmp_path):
    # Criterion 3: pages 1 and 2 cached at total 250; the source now has 251.
    clock = FakeClock()
    prime = PageTransport(clock, http, source_pages(range(1, 251)))
    prime_client = make_client(http, prime, clock)
    cache = http.FileCache(tmp_path)
    at.fetch_page(prime_client, cache, 1)
    at.fetch_page(prime_client, cache, 2)
    assert cache.get(page_key(at, 3)) is None
    page1_before = cache.get(page_key(at, 1))

    grown = source_pages(range(1, 252))
    assert len(grown[3]["data"]["animePagination"]["data"]) == 51
    client, transport, _ = crawl_setup(at, http, tmp_path, grown)
    result, logs = run_crawl(at, client, cache)
    assert transport.called_pages() == [2, 3]
    assert _count(logs, "total grew") == 1, logs
    assert not any("cleared" in line for line in logs), logs
    assert not any("total changed" in line for line in logs), logs
    assert item_ids(result) == list(range(1, 252))
    assert result.total == 251
    assert cache.get(page_key(at, 1)) == page1_before


def test_crawl_addition_during_first_crawl_verifies_previous_page(at, http, tmp_path):
    # Criterion 4 (replaces test_crawl_total_changed_mid_pass_clears_and_restarts):
    # page 1 reports 250, pages 2 and 3 report 251; page 1's ids do not change.
    pages = source_pages(range(1, 252))
    pages[1] = synthetic_body(1, list(range(1, 101)), True, total=250)
    client, transport, cache = crawl_setup(at, http, tmp_path, pages)
    result, logs = run_crawl(at, client, cache)
    assert transport.called_pages() == [1, 2, 1, 3]
    assert _count(logs, "total grew") == 1, logs
    assert not any("cleared" in line for line in logs), logs
    assert not any("total changed" in line for line in logs), logs
    assert item_ids(result) == list(range(1, 252))
    assert result.total == 251
    for page in (1, 2, 3):
        assert cache.get(page_key(at, page)) is not None


def test_crawl_deletion_total_shrinks_clears_and_restarts(at, http, tmp_path):
    # Criterion 5: id 50 is deleted after page 1 is read, so the total shrinks
    # from 300 to 299 and every later page shifts by one.
    current = [i for i in range(1, 301) if i != 50]
    now = source_pages(current)
    pages = {
        1: [synthetic_body(1, list(range(1, 101)), True, total=300), now[1]],
        2: [now[2]],
        3: [now[3]],
    }
    client, transport, cache = _sequence_setup(http, tmp_path, pages)
    result, logs = run_crawl(at, client, cache)
    assert transport.called_pages() == [1, 2, 1, 2, 3]
    assert _count(logs, "total changed") == 1, logs
    assert _count(logs, "cleared") == 1, logs
    assert item_ids(result) == current
    assert result.total == 299


def test_crawl_deletion_plus_additions_during_first_crawl_restarts(at, http, tmp_path):
    # Criterion 5b (spec review H1): after page 1 is read, id 50 is deleted and
    # ids 301 and 302 are added, so pages 2-4 report 301 and page 1's ids changed.
    current = [i for i in range(1, 303) if i != 50]
    now = source_pages(current)
    assert len(now) == 4
    pages = {
        1: [synthetic_body(1, list(range(1, 101)), True, total=300), now[1]],
        2: [now[2]],
        3: [now[3]],
        4: [now[4]],
    }
    client, transport, cache = _sequence_setup(http, tmp_path, pages)
    result, logs = run_crawl(at, client, cache)
    assert transport.called_pages() == [1, 2, 1, 1, 2, 3, 4]
    assert _count(logs, "cleared") == 1, logs
    assert item_ids(result) == current
    assert 50 not in item_ids(result)
    assert result.total == 301


class FailingTransport(PageTransport):
    """Like PageTransport, but every request from call number fail_at on raises TransportError."""

    def __init__(self, clock, http, pages, fail_at):
        super().__init__(clock, http, pages)
        self.fail_at = fail_at

    def send(self, method, url, headers, body, timeout):
        if len(self.calls) + 1 >= self.fail_at:
            call = Call(method, url, dict(headers or {}), body, timeout, self.clock.now())
            self.calls.append(call)
            raise self.http.TransportError("simulated failure on call %d" % len(self.calls))
        return super().send(method, url, headers, body, timeout)


def test_crawl_failed_verification_leaves_no_unverified_page(at, http, tmp_path):
    # Criterion 5c (spec review round 2, M1): page 2 reports a larger total and the
    # refetch of page 1 (the 3rd request, and every retry after it) fails.
    pages = source_pages(range(1, 252))
    pages[1] = synthetic_body(1, list(range(1, 101)), True, total=250)
    clock = FakeClock()
    transport = FailingTransport(clock, http, pages, fail_at=3)
    client = make_client(http, transport, clock)
    cache = http.FileCache(tmp_path)
    logs = []
    with pytest.raises(http.TransportError):
        at.crawl(client, cache, log=logs.append)
    assert transport.called_pages()[:3] == [1, 2, 1]
    assert cache.get(page_key(at, 2)) is None
    assert cache.get(page_key(at, 1)) == json.dumps(pages[1]).encode("utf-8")


def test_crawl_growth_that_does_not_add_up_restarts_then_raises(at, http, tmp_path):
    # Criterion 6 (replaces test_crawl_total_keeps_changing_raises_after_two_restarts):
    # pages 2 and 3 report 301 but the pages hold only 300 items.
    pages = source_pages(range(1, 301), total=301)
    pages[1] = synthetic_body(1, list(range(1, 101)), True, total=300)
    client, transport, cache = crawl_setup(at, http, tmp_path, pages)
    logs = []
    with pytest.raises(ValueError) as info:
        at.crawl(client, cache, log=logs.append)
    logs = [str(line) for line in logs]
    assert transport.called_pages() == [1, 2, 1, 3] * 3
    assert _count(logs, "does not match total") >= 2, logs
    assert _count(logs, "cleared") == 3, logs
    assert _count(logs, "giving up") == 1, logs
    assert not any("total changed" in line for line in logs), logs
    assert "total" in str(info.value).lower(), str(info.value)


def test_crawl_resume_changed_id_in_earlier_page_restarts(at, http, tmp_path):
    # Criterion 7: pages 1 and 2 cached at total 250; in the source id 150 was
    # replaced by id 251 (same total), so the fresh page 2 differs from the cache.
    clock = FakeClock()
    prime = PageTransport(clock, http, source_pages(range(1, 251)))
    prime_client = make_client(http, prime, clock)
    cache = http.FileCache(tmp_path)
    at.fetch_page(prime_client, cache, 1)
    at.fetch_page(prime_client, cache, 2)
    assert cache.get(page_key(at, 3)) is None

    current = [i for i in range(1, 252) if i != 150]
    client, transport, _ = crawl_setup(at, http, tmp_path, source_pages(current))
    result, logs = run_crawl(at, client, cache)
    assert transport.called_pages() == [2, 1, 2, 3]
    assert _count(logs, "shifted") == 1, logs
    assert _count(logs, "cleared") == 1, logs
    assert item_ids(result) == current
    assert result.total == 250


# Cycle 025 attempt 2, criteria 10-12 (rules 5 and 6): verification refetches
# are not cached, and giving up at the restart cap clears the page prefix.

def _page_entries(at, cache):
    """Names of the cache files under the page key prefix."""
    prefix = page_key(at, 1)[: -len("1.json")]
    return sorted(path.name for path in Path(cache.root).iterdir() if path.name.startswith(prefix))


class ChurningSource:
    """Offset pagination over a mutable id list, PER_PAGE items per page.

    While churning, every request for page 3 that directly follows a request
    for page 2 first deletes the smallest id and appends two new ids, so page 3
    reports a larger total and the verification refetch of page 2 sees shifted ids.
    """

    def __init__(self, clock, http, ids, per_page):
        self.clock = clock
        self.http = http
        self.ids = list(ids)
        self.per_page = per_page
        self.next_id = 100
        self.churning = True
        self.calls = []

    def _churn(self):
        self.ids.pop(0)
        self.ids += [self.next_id, self.next_id + 1]
        self.next_id += 2

    def body(self, page):
        chunk = self.ids[(page - 1) * self.per_page: page * self.per_page]
        body = synthetic_body(page, chunk, len(self.ids) > page * self.per_page, total=len(self.ids))
        body["data"]["animePagination"]["paginatorInfo"]["perPage"] = self.per_page
        return body

    def send(self, method, url, headers, body, timeout):
        call = Call(method, url, dict(headers or {}), body, timeout, self.clock.now())
        previous = self.calls[-1].page() if self.calls else None
        self.calls.append(call)
        page = call.page()
        if self.churning and page == 3 and previous == 2:
            self._churn()
        data = json.dumps(self.body(page)).encode("utf-8")
        return self.http.Response(status=200, headers={"content-type": "application/json"}, body=data)

    def called_pages(self):
        return [call.page() for call in self.calls]


def test_crawl_failed_verification_at_cap_leaves_no_page_entry(at, http, tmp_path, monkeypatch):
    # Criterion 10 (code review H1): every pass, the source deletes its smallest
    # id and adds two right before page 3 is fetched, so page 3 reports a larger
    # total and the refetch of page 2 sees shifted ids; the third pass gives up.
    monkeypatch.setattr(at, "PER_PAGE", 3)
    clock = FakeClock()
    source = ChurningSource(clock, http, range(1, 13), per_page=3)
    client = make_client(http, source, clock)
    cache = http.FileCache(tmp_path)
    logs = []
    with pytest.raises(ValueError):
        at.crawl(client, cache, log=logs.append)
    logs = [str(line) for line in logs]
    assert source.called_pages() == [1, 2, 3, 2] * 3
    assert _count(logs, "giving up") == 1, logs
    assert _page_entries(at, cache) == [], logs
    for page in range(1, 11):
        assert cache.get(page_key(at, page)) is None, page

    # The source is now stable; the next run starts from an empty cache.
    source.churning = False
    expected = list(source.ids)
    start = len(source.calls)
    again, logs2 = run_crawl(at, client, cache)
    assert item_ids(again) == expected
    assert again.total == len(expected)
    pages = (len(expected) + 2) // 3
    assert source.called_pages()[start:] == list(range(1, pages + 1))


def test_crawl_count_mismatch_at_cap_leaves_no_page_entry(at, http, tmp_path):
    # Criterion 11 (code review M1): criterion 6's static source; rule 3 raises at
    # the cap, nothing stays cached, and the next run raises again.
    pages = source_pages(range(1, 301), total=301)
    pages[1] = synthetic_body(1, list(range(1, 101)), True, total=300)
    client, transport, cache = crawl_setup(at, http, tmp_path, pages)
    with pytest.raises(ValueError):
        at.crawl(client, cache, log=[].append)
    assert _page_entries(at, cache) == []
    for page in range(1, 5):
        assert cache.get(page_key(at, page)) is None, page

    client2, transport2, _ = crawl_setup(at, http, tmp_path, pages)
    logs = []
    with pytest.raises(ValueError):
        at.crawl(client2, cache, log=logs.append)
    assert transport2.called_pages() == [1, 2, 1, 3] * 3
    assert _page_entries(at, cache) == []


def test_crawl_verification_refetch_does_not_rewrite_previous_page(at, http, tmp_path):
    # Criterion 12 (code review N1): criterion 4's first crawl, but the refetch of
    # page 1 returns the same ids with the grown total. Page 1's cache entry must
    # stay the bytes of the first page 1 response.
    pages = source_pages(range(1, 252))
    first_page1 = synthetic_body(1, list(range(1, 101)), True, total=250)
    sequence = {
        1: [first_page1, pages[1]],
        2: [pages[2]],
        3: [pages[3]],
    }
    client, transport, cache = _sequence_setup(http, tmp_path, sequence)
    result, logs = run_crawl(at, client, cache)
    assert transport.called_pages() == [1, 2, 1, 3]
    assert _count(logs, "total grew") == 1, logs
    assert not any("cleared" in line for line in logs), logs
    assert item_ids(result) == list(range(1, 252))
    assert cache.get(page_key(at, 1)) == json.dumps(first_page1).encode("utf-8")


def test_crawl_refresh_all_fetches_every_page_once(at, http, tmp_path):
    cache = _cached_three_pages(at, http, tmp_path)
    # The source changed page 3; refresh_all reads no cache, so the result is fresh.
    fresh = three_pages()
    fresh[3] = synthetic_body(3, [7, 8, 10], False)
    client, transport, _ = crawl_setup(at, http, tmp_path, fresh)
    logs = []
    result = at.crawl(client, cache, refresh_all=True, log=logs.append)
    assert transport.called_pages() == [1, 2, 3]
    assert item_ids(result) == [1, 2, 3, 4, 5, 6, 7, 8, 10]
    assert result.pages == 3
    assert not any("cleared" in str(line) for line in logs), logs


# Criterion 4: select_themes. The reference below applies the stated rules
# independently of the implementation.

def _ref_audio(theme):
    entries = list(enumerate(theme.get("animethemeentries") or []))
    entries.sort(key=lambda pair: (pair[1].get("version") is not None, pair[1].get("version") or 0, pair[0]))
    for _, entry in entries:
        videos = list(((entry.get("videos") or {}).get("nodes")) or [])
        videos.sort(key=lambda video: video.get("basename") or "")
        for video in videos:
            audio = video.get("audio")
            if audio is not None:
                return {
                    "id": audio["id"],
                    "basename": audio["basename"],
                    "url": audio["link"],
                    "size": audio["size"],
                }
    return None


def _ref_theme(theme):
    song = theme.get("song")
    title = None
    artists = []
    if song is not None:
        title = (song.get("title") or {}).get("romaji")
        for performance in song.get("performances") or []:
            artist = performance.get("artist") or {}
            name = (artist.get("name") or {}).get("main")
            if name is not None:
                artists.append(name)
    return {
        "id": theme["id"],
        "type": theme["type"],
        "slug": theme["slug"],
        "song": title,
        "artists": artists,
        "audio": _ref_audio(theme),
    }


def _ref_select(item):
    resources = (item.get("resources") or {}).get("nodes") or []
    anilist = None
    for node in resources:
        if node.get("site") == "ANILIST":
            value = node.get("externalId")
            anilist = int(value) if value is not None else None
            break
    anidb = []
    for node in resources:
        if node.get("site") == "ANIDB" and node.get("externalId") is not None:
            value = int(node["externalId"])
            if value not in anidb:
                anidb.append(value)
    themes = []
    for theme in item.get("animethemes") or []:
        if theme.get("type") not in ("OP", "ED"):
            continue
        ref = _ref_theme(theme)
        if ref["audio"] is None:
            continue
        themes.append(ref)
    return {
        "id": item["id"],
        "slug": item["slug"],
        "name": (item.get("title") or {}).get("romaji"),
        "year": item.get("year"),
        "season": item.get("season"),
        "anilistId": anilist,
        "anidbIds": anidb,
        "themes": themes,
    }


def test_select_themes_matches_reference_on_fixture(at):
    items = load_page1()["data"]["animePagination"]["data"]
    assert items
    for item in items:
        got = at.select_themes(copy.deepcopy(item))
        expected = _ref_select(item)
        for key, value in expected.items():
            assert got.get(key) == value, (item["id"], key)


def test_select_themes_kept_themes_are_op_ed_with_audio(at):
    items = load_page1()["data"]["animePagination"]["data"]
    kept = 0
    for item in items:
        for theme in at.select_themes(item)["themes"]:
            kept += 1
            assert theme["type"] in ("OP", "ED")
            assert theme["audio"] is not None
            assert theme["audio"]["url"].startswith("https://")
    assert kept > 0


def _audio(audio_id, name):
    return {
        "id": audio_id,
        "basename": name + ".ogg",
        "filename": name,
        "link": "https://a.animethemes.moe/" + name + ".ogg",
        "size": 1000 + audio_id,
    }


def _video(basename, audio):
    return {"basename": basename, "link": "https://v.animethemes.moe/" + basename, "size": 1, "audio": audio}


def _entry(version, videos):
    return {"version": version, "episodes": "1", "nsfw": False, "spoiler": False, "videos": {"nodes": videos}}


def _theme(theme_id, theme_type, entries, song="Song", artists=("Singer",)):
    return {
        "id": theme_id,
        "type": theme_type,
        "sequence": 1,
        "slug": theme_type + "1",
        "song": {
            "title": {"romaji": song},
            "performances": [
                {"alias": None, "as": None, "artist": {"name": {"main": name}, "slug": "s"}} for name in artists
            ],
        },
        "animethemeentries": entries,
    }


def _anime(themes, resources=None):
    if resources is None:
        resources = [{"site": "ANILIST", "externalId": 77, "link": "https://anilist.co/anime/77"}]
    return {
        "id": 900,
        "title": {"romaji": "Synthetic"},
        "slug": "synthetic",
        "year": 2020,
        "season": "FALL",
        "format": "TV",
        "synonyms": [],
        "animethemes": themes,
        "resources": {"nodes": resources},
        "series": {"nodes": []},
    }


def test_version_null_without_audio_falls_to_version_2(at):
    theme = _theme(1, "OP", [
        _entry(2, [_video("X-OP1v2.webm", _audio(22, "X-OP1v2"))]),
        _entry(None, [_video("X-OP1.webm", None)]),
    ])
    got = at.select_themes(_anime([theme]))
    assert [t["audio"]["id"] for t in got["themes"]] == [22]


def test_version_null_entry_wins_over_version_1(at):
    theme = _theme(1, "OP", [
        _entry(1, [_video("A.webm", _audio(11, "A"))]),
        _entry(None, [_video("B.webm", _audio(10, "B"))]),
    ])
    got = at.select_themes(_anime([theme]))
    assert got["themes"][0]["audio"]["id"] == 10


def test_videos_sorted_by_basename(at):
    theme = _theme(1, "ED", [
        _entry(None, [
            _video("Z-ED1-NCBD.webm", _audio(31, "Z-ED1b")),
            _video("Z-ED1-BD.webm", _audio(30, "Z-ED1a")),
        ]),
    ])
    got = at.select_themes(_anime([theme]))
    audio = got["themes"][0]["audio"]
    assert audio == {
        "id": 30,
        "basename": "Z-ED1a.ogg",
        "url": "https://a.animethemes.moe/Z-ED1a.ogg",
        "size": 1030,
    }


def test_in_theme_and_no_audio_theme_are_dropped(at):
    themes = [
        _theme(1, "IN", [_entry(None, [_video("I.webm", _audio(1, "I"))])]),
        _theme(2, "OP", [_entry(None, [_video("O.webm", None)])]),
        _theme(3, "ED", [_entry(None, [_video("E.webm", _audio(3, "E"))])]),
    ]
    got = at.select_themes(_anime(themes))
    assert [t["id"] for t in got["themes"]] == [3]


def test_theme_fields_and_null_artist_skipped(at):
    theme = _theme(5, "OP", [_entry(None, [_video("S.webm", _audio(5, "S"))])], song="Title", artists=("One",))
    theme["song"]["performances"].append({"alias": None, "as": None, "artist": None})
    got = at.select_themes(_anime([theme]))["themes"][0]
    assert got["id"] == 5
    assert got["type"] == "OP"
    assert got["slug"] == "OP1"
    assert got["song"] == "Title"
    assert got["artists"] == ["One"]


def test_null_song_gives_null_and_empty_artists(at):
    theme = _theme(6, "ED", [_entry(None, [_video("N.webm", _audio(6, "N"))])])
    theme["song"] = None
    got = at.select_themes(_anime([theme]))["themes"][0]
    assert got["song"] is None
    assert got["artists"] == []


def test_anime_without_themes_returns_empty_list(at):
    got = at.select_themes(_anime([]))
    assert got["themes"] == []
    assert got["id"] == 900
    assert got["slug"] == "synthetic"
    assert got["name"] == "Synthetic"
    assert got["year"] == 2020
    assert got["season"] == "FALL"
    assert got["anilistId"] == 77


def test_missing_anilist_gives_null(at):
    resources = [{"site": "MAL", "externalId": 5, "link": "https://myanimelist.net/anime/5"}]
    got = at.select_themes(_anime([], resources))
    assert got["anilistId"] is None


def test_anilist_null_external_id_gives_null(at):
    resources = [{"site": "ANILIST", "externalId": None, "link": "https://anilist.co/anime/x"}]
    got = at.select_themes(_anime([], resources))
    assert got["anilistId"] is None


def test_fixture_anime_15_has_three_anidb_ids_in_order(at):
    items = load_page1()["data"]["animePagination"]["data"]
    item = next(entry for entry in items if entry["id"] == 15)
    expected = [node["externalId"] for node in item["resources"]["nodes"] if node["site"] == "ANIDB"]
    assert len(expected) == 3
    got = at.select_themes(item)
    assert got["anidbIds"] == expected


def test_anidb_null_and_duplicate_external_ids_skipped(at):
    resources = [
        {"site": "ANIDB", "externalId": None, "link": "https://anidb.net/anime/x"},
        {"site": "ANIDB", "externalId": 42, "link": "https://anidb.net/anime/42"},
        {"site": "ANIDB", "externalId": 42, "link": "https://anidb.net/anime/42"},
        {"site": "ANIDB", "externalId": 7, "link": "https://anidb.net/anime/7"},
    ]
    got = at.select_themes(_anime([], resources))
    assert got["anidbIds"] == [42, 7]
