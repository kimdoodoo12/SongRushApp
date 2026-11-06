"""Cycle 019 criteria 1-7: Wikidata labels per AniList id, batched and cached raw.

docs/cycles/019-korean-titles.md is the specification. Every request goes to
a fake transport with a fake clock, so the tests are offline and instant.
Imports of songrush_data.wikidata stay inside fixtures and tests so that a
missing module fails these tests, not the collection.
"""

import copy
import hashlib
import json
import re
import urllib.parse
from pathlib import Path

import pytest

FIXTURES = Path(__file__).resolve().parent / "fixtures"
BATCH_JSON = FIXTURES / "wikidata_labels_batch.json"
BATCH_RQ = FIXTURES / "wikidata_labels_batch.rq"
FIXTURE_IDS = [5114, 21, 1535, 101922, 21366, 999999999]
T0 = 1000.0
WALL0 = 1_800_000_000.0


@pytest.fixture
def wd():
    from songrush_data import wikidata as module

    return module


@pytest.fixture
def http():
    from songrush_data import http as module

    return module


# Fakes (copied from test_anilist.py, no cross-test imports).

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
    def __init__(self, method, url, headers, body, timeout):
        self.method = method
        self.url = url
        self.headers = headers
        self.body = body
        self.timeout = timeout

    def form(self):
        return urllib.parse.parse_qs(self.body.decode("utf-8"), keep_blank_values=True)

    def query(self):
        values = self.form()["query"]
        assert len(values) == 1, "request body has %d query fields" % len(values)
        return values[0]

    def header(self, name):
        for key, value in self.headers.items():
            if key.lower() == name.lower():
                return value
        return None

    def ids(self):
        match = re.search(r"VALUES \?id \{([^}]*)\}", self.query())
        assert match is not None, "request query has no VALUES ?id { ... }"
        return [int(part) for part in re.findall(r'"(\d+)"', match.group(1))]


class SparqlTransport:
    """Answers each POST with respond(ids).

    respond returns a dict (sent as JSON), bytes (sent as is) or an int
    (an empty response with that HTTP status).
    """

    def __init__(self, clock, http, respond):
        self.clock = clock
        self.http = http
        self.respond = respond
        self.calls = []

    def send(self, method, url, headers, body, timeout):
        call = Call(method, url, dict(headers or {}), body, timeout)
        self.calls.append(call)
        payload = self.respond(call.ids())
        if isinstance(payload, int):
            return self.http.Response(status=payload, headers={}, body=b"")
        if isinstance(payload, dict):
            payload = json.dumps(payload).encode("utf-8")
        return self.http.Response(
            status=200, headers={"content-type": "application/sparql-results+json"}, body=payload)

    def batches(self):
        return [call.ids() for call in self.calls]


def fixture_body():
    return json.loads(BATCH_JSON.read_text(encoding="utf-8"))


def fixture_bindings():
    return fixture_body()["results"]["bindings"]


def serve_fixture(_ids):
    return fixture_body()


def serve_filtered(ids):
    """The fixture's bindings for the requested ids only."""
    wanted = {str(i) for i in ids}
    bindings = [b for b in fixture_bindings() if b["id"]["value"] in wanted]
    return {"head": {"vars": ["id", "item", "ko", "en", "ja"]}, "results": {"bindings": bindings}}


def serve_empty(_ids):
    return {"head": {"vars": ["id", "item", "ko", "en", "ja"]}, "results": {"bindings": []}}


def make(http, tmp_path, respond, max_attempts=3):
    clock = FakeClock()
    transport = SparqlTransport(clock, http, respond)
    client = http.PoliteClient(transport, clock, gap_s=3, max_attempts=max_attempts)
    cache = http.FileCache(tmp_path / "cache")
    return transport, client, cache


def wd_files(cache):
    if not cache.root.is_dir():
        return []
    return sorted(p.name for p in cache.root.iterdir() if p.name.startswith("wd-"))


def cache_key(wd, anilist_id):
    digest = hashlib.sha256(wd.QUERY_TEMPLATE.encode("utf-8")).hexdigest()[:16]
    return "wd-%s-%d.json" % (digest, anilist_id)


def quiet(_line):
    pass


EMPTY = {"ko": [], "en": [], "ja": []}


# Interface constants.

def test_wdqs_url(wd):
    assert wd.WDQS_URL == "https://query.wikidata.org/sparql"


def test_query_template_has_placeholder_and_no_trailing_newline(wd):
    assert "<values>" in wd.QUERY_TEMPLATE
    assert not wd.QUERY_TEMPLATE.endswith("\n")


# Criterion 1: build_query matches the saved query.

def test_build_query_matches_saved_rq(wd):
    query = wd.build_query(FIXTURE_IDS)
    assert query.rstrip() == BATCH_RQ.read_text(encoding="utf-8").rstrip()
    assert not query.endswith("\n")


def test_build_query_is_template_with_quoted_ids_in_order(wd):
    assert wd.build_query([101922, 5]) == wd.QUERY_TEMPLATE.replace("<values>", '"101922" "5"')


# Criterion 2: the request.

def test_request_is_one_form_post(wd, http, tmp_path):
    transport, client, cache = make(http, tmp_path, serve_fixture)
    wd.fetch_labels(client, cache, FIXTURE_IDS, log=quiet)
    assert len(transport.calls) == 1
    call = transport.calls[0]
    assert call.method == "POST"
    assert call.url == wd.WDQS_URL
    assert call.header("Content-Type") == "application/x-www-form-urlencoded"
    assert call.header("Accept") == "application/sparql-results+json"
    form = call.form()
    assert set(form) == {"query", "format"}
    assert form["format"] == ["json"]
    assert form["query"] == [wd.build_query(FIXTURE_IDS)]
    expected = urllib.parse.urlencode(
        {"query": wd.build_query(FIXTURE_IDS), "format": "json"}).encode("utf-8")
    assert call.body == expected


# Criterion 3: shapes on wikidata_labels_batch.json.

def test_shape_on_fixture(wd, http, tmp_path):
    transport, client, cache = make(http, tmp_path, serve_fixture)
    result = wd.fetch_labels(client, cache, FIXTURE_IDS, log=quiet)
    assert set(result) == set(FIXTURE_IDS)
    fma = result[5114]
    assert fma["ko"] == ["강철의 연금술사 BROTHERHOOD"]
    assert fma["en"] and fma["ja"]
    assert fma["en"] == ["Fullmetal Alchemist: Brotherhood"]
    kny = result[101922]
    assert kny["ko"] == []
    assert kny["en"] == ["Demon Slayer: Kimetsu no Yaiba, season 1"]
    assert result[999999999] == EMPTY
    for labels in result.values():
        assert set(labels) == {"ko", "en", "ja"}


def binding(anilist_id, item, **labels):
    out = {"item": {"type": "uri", "value": "http://www.wikidata.org/entity/%s" % item},
           "id": {"type": "literal", "value": str(anilist_id)}}
    for lang, value in labels.items():
        out[lang] = {"xml:lang": lang, "type": "literal", "value": value}
    return out


def test_several_bindings_keep_order_and_drop_duplicates(wd, http, tmp_path):
    body = {"results": {"bindings": [
        binding(7, "Q1", ko="A", en="E1"),
        binding(7, "Q2", ko="B", en="E1"),
        binding(7, "Q3", ko="A", ja="J"),
        binding(7, "Q4", en="E2"),
    ]}}
    transport, client, cache = make(http, tmp_path, lambda ids: body)
    result = wd.fetch_labels(client, cache, [7], log=quiet)
    assert result[7]["ko"] == ["A", "B"]
    assert result[7]["en"] == ["E1", "E2"]
    assert result[7]["ja"] == ["J"]


def test_ko_is_not_filtered_for_hangul(wd, http, tmp_path):
    body = {"results": {"bindings": [binding(7, "Q1", ko="Latin only")]}}
    transport, client, cache = make(http, tmp_path, lambda ids: body)
    assert wd.fetch_labels(client, cache, [7], log=quiet)[7]["ko"] == ["Latin only"]


def test_summary_and_batch_log_lines(wd, http, tmp_path):
    transport, client, cache = make(http, tmp_path, serve_fixture)
    lines = []
    wd.fetch_labels(client, cache, FIXTURE_IDS, log=lines.append)
    assert any("wikidata batch 1/1 ids=6" in line for line in lines)
    assert any("wikidata ids=6 with_ko=3 cached=0" in line for line in lines)
    lines.clear()
    wd.fetch_labels(client, cache, FIXTURE_IDS, log=lines.append)
    assert any("wikidata ids=6 with_ko=3 cached=6" in line for line in lines)
    assert not any("wikidata batch" in line for line in lines)


# Criterion 4: batching, cache reuse and arguments.

def test_450_ids_make_three_posts_in_order(wd, http, tmp_path):
    ids = list(range(5001, 5451))
    transport, client, cache = make(http, tmp_path, serve_empty)
    lines = []
    result = wd.fetch_labels(client, cache, ids, log=lines.append)
    assert transport.batches() == [ids[0:200], ids[200:400], ids[400:450]]
    assert all(call.method == "POST" for call in transport.calls)
    assert set(result) == set(ids)
    assert all(result[i] == EMPTY for i in ids)
    assert any("wikidata batch 1/3 ids=200" in line for line in lines)
    assert any("wikidata batch 3/3 ids=50" in line for line in lines)


def test_rerun_makes_no_posts(wd, http, tmp_path):
    ids = list(range(5001, 5451))
    transport, client, cache = make(http, tmp_path, serve_empty)
    first = wd.fetch_labels(client, cache, ids, log=quiet)
    transport.calls.clear()
    second = wd.fetch_labels(client, cache, ids, log=quiet)
    assert transport.calls == []
    assert second == first


def test_partial_reuse_fetches_only_uncached(wd, http, tmp_path):
    transport, client, cache = make(http, tmp_path, serve_filtered)
    wd.fetch_labels(client, cache, [5114, 21], log=quiet)
    transport.calls.clear()
    lines = []
    result = wd.fetch_labels(client, cache, FIXTURE_IDS, log=lines.append)
    assert transport.batches() == [[1535, 101922, 21366, 999999999]]
    assert set(result) == set(FIXTURE_IDS)
    assert any("cached=2" in line for line in lines)


@pytest.mark.parametrize("size", [0, 201, -1])
def test_batch_size_out_of_range_raises(wd, http, tmp_path, size):
    transport, client, cache = make(http, tmp_path, serve_empty)
    with pytest.raises(ValueError):
        wd.fetch_labels(client, cache, [1], batch_size=size, log=quiet)
    assert transport.calls == []


@pytest.mark.parametrize("size", [1, 200])
def test_batch_size_bounds_accepted(wd, http, tmp_path, size):
    transport, client, cache = make(http, tmp_path, serve_empty)
    result = wd.fetch_labels(client, cache, [1, 2], batch_size=size, log=quiet)
    assert set(result) == {1, 2}
    assert len(transport.calls) == (2 if size == 1 else 1)


@pytest.mark.parametrize("bad", ["5114", True, False, 1.0])
def test_non_int_id_raises(wd, http, tmp_path, bad):
    transport, client, cache = make(http, tmp_path, serve_empty)
    with pytest.raises(ValueError):
        wd.fetch_labels(client, cache, [5114, bad], log=quiet)
    assert transport.calls == []
    assert wd_files(cache) == []


def test_none_and_duplicates_are_ignored(wd, http, tmp_path):
    transport, client, cache = make(http, tmp_path, serve_empty)
    result = wd.fetch_labels(client, cache, [5, None, 6, 5, None, 7, 6], log=quiet)
    assert transport.batches() == [[5, 6, 7]]
    assert set(result) == {5, 6, 7}
    assert None not in result


# Criterion 5: raw cache entries.

def test_cache_entry_is_raw_bindings_under_spec_key(wd, http, tmp_path):
    transport, client, cache = make(http, tmp_path, serve_fixture)
    first = wd.fetch_labels(client, cache, FIXTURE_IDS, log=quiet)
    matches = [name for name in wd_files(cache) if re.fullmatch(r"wd-.*-5114\.json", name)]
    assert matches == [cache_key(wd, 5114)]
    stored = json.loads(cache.get(cache_key(wd, 5114)))
    raw = [b for b in fixture_bindings() if b["id"]["value"] == "5114"]
    assert stored == {"bindings": raw}
    assert "item" in stored["bindings"][0]
    assert json.loads(cache.get(cache_key(wd, 999999999))) == {"bindings": []}
    transport.calls.clear()
    second = wd.fetch_labels(client, cache, FIXTURE_IDS, log=quiet)
    assert transport.calls == []
    assert second == first


def test_cached_empty_list_is_a_hit(wd, http, tmp_path):
    transport, client, cache = make(http, tmp_path, serve_empty)
    cache.put(cache_key(wd, 42), json.dumps({"bindings": []}).encode("utf-8"))
    result = wd.fetch_labels(client, cache, [42], log=quiet)
    assert transport.calls == []
    assert result == {42: EMPTY}


def test_parsing_applies_to_seeded_raw_entry(wd, http, tmp_path):
    transport, client, cache = make(http, tmp_path, serve_empty)
    seeded = {"bindings": [binding(42, "Q9", ko="가", en="G"), binding(42, "Q10", ko="가")]}
    cache.put(cache_key(wd, 42), json.dumps(seeded).encode("utf-8"))
    result = wd.fetch_labels(client, cache, [42], log=quiet)
    assert transport.calls == []
    assert result[42] == {"ko": ["가"], "en": ["G"], "ja": []}


@pytest.mark.parametrize("damaged", [b"not json", b"[]", b'{"bindings": "x"}'])
def test_damaged_entry_is_refetched_and_replaced(wd, http, tmp_path, damaged):
    transport, client, cache = make(http, tmp_path, serve_filtered)
    cache.put(cache_key(wd, 5114), damaged)
    result = wd.fetch_labels(client, cache, [5114], log=quiet)
    assert transport.batches() == [[5114]]
    assert result[5114]["ko"] == ["강철의 연금술사 BROTHERHOOD"]
    stored = json.loads(cache.get(cache_key(wd, 5114)))
    raw = [b for b in fixture_bindings() if b["id"]["value"] == "5114"]
    assert stored == {"bindings": raw}


# Criterion 6: validation skips the batch and caches nothing from it.

def unrequested_binding(_ids):
    return {"results": {"bindings": [binding(1, "Q1", ko="가"), binding(999, "Q2", ko="나")]}}


@pytest.mark.parametrize("respond", [
    lambda ids: b"not json",
    lambda ids: {},
    lambda ids: {"results": {}},
    lambda ids: {"results": {"bindings": None}},
    lambda ids: {"results": {"bindings": {"id": {"value": "1"}}}},
    lambda ids: {"results": {"bindings": [{"item": {"value": "Q1"}}]}},
    unrequested_binding,
], ids=["not-json", "no-results", "no-bindings", "null-bindings", "object-bindings",
        "binding-without-id", "unrequested-id"])
def test_invalid_body_skips_batch(wd, http, tmp_path, respond):
    transport, client, cache = make(http, tmp_path, respond)
    lines = []
    result = wd.fetch_labels(client, cache, [1, 2], log=lines.append)
    assert 1 not in result and 2 not in result
    assert wd_files(cache) == []
    skipped = [line for line in lines if "wikidata skipped" in line]
    assert len(skipped) == 1
    assert "ValueError" in skipped[0]


def test_invalid_batch_keeps_cached_hits(wd, http, tmp_path):
    transport, client, cache = make(http, tmp_path, lambda ids: b"not json")
    cache.put(cache_key(wd, 1), json.dumps({"bindings": []}).encode("utf-8"))
    lines = []
    result = wd.fetch_labels(client, cache, [1, 2], log=lines.append)
    assert result == {1: EMPTY}
    assert transport.batches() == [[2]]
    assert wd_files(cache) == [cache_key(wd, 1)]


# Criterion 7: a failing batch stops the fetch and keeps earlier results.

def test_second_batch_404_keeps_first_batch(wd, http, tmp_path):
    def respond(ids):
        return 404 if 3 in ids else serve_empty(ids)

    transport, client, cache = make(http, tmp_path, respond, max_attempts=1)
    lines = []
    result = wd.fetch_labels(client, cache, [1, 2, 3, 4], batch_size=2, log=lines.append)
    assert transport.batches() == [[1, 2], [3, 4]]
    assert result == {1: EMPTY, 2: EMPTY}
    assert wd_files(cache) == sorted([cache_key(wd, 1), cache_key(wd, 2)])
    skipped = [line for line in lines if "wikidata skipped" in line]
    assert len(skipped) == 1
    assert "HttpError" in skipped[0]


def test_failure_stops_requesting(wd, http, tmp_path):
    def respond(ids):
        return 404 if 3 in ids else serve_empty(ids)

    transport, client, cache = make(http, tmp_path, respond, max_attempts=1)
    result = wd.fetch_labels(client, cache, [1, 2, 3, 4, 5, 6], batch_size=2, log=quiet)
    assert transport.batches() == [[1, 2], [3, 4]]
    assert set(result) == {1, 2}


def test_transport_error_is_skipped(wd, http, tmp_path):
    clock = FakeClock()

    class Broken:
        calls = 0

        def send(self, method, url, headers, body, timeout):
            Broken.calls += 1
            raise http.TransportError("connection refused")

    client = http.PoliteClient(Broken(), clock, gap_s=3, max_attempts=1)
    cache = http.FileCache(tmp_path / "cache")
    lines = []
    result = wd.fetch_labels(client, cache, [1, 2], log=lines.append)
    assert result == {}
    assert Broken.calls == 1
    skipped = [line for line in lines if "wikidata skipped" in line]
    assert len(skipped) == 1
    assert "TransportError" in skipped[0]
