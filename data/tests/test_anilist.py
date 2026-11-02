"""Cycle 018 criteria 1-7b: AniList media fetch by id, batched and cached per id.

docs/cycles/018-anilist-enrich.md is the specification. Every request goes to
a fake transport with a fake clock, so the tests are offline and instant.
Imports of songrush_data.anilist stay inside fixtures and tests so that a
missing module fails these tests, not the collection.
"""

import copy
import hashlib
import json
import re
from pathlib import Path

import pytest

FIXTURES = Path(__file__).resolve().parent / "fixtures"
ID_IN_PATH = FIXTURES / "anilist_id_in.json"
RELATIONS_PATH = FIXTURES / "anilist_relations_franchises.json"
T0 = 1000.0
WALL0 = 1_800_000_000.0

EXPECTED_MEDIA_FIELDS = (
    "id popularity title{romaji english native} synonyms "
    "relations{edges{relationType(version:2) node{id type format title{romaji english}}}}"
)


@pytest.fixture
def al():
    from songrush_data import anilist as module

    return module


@pytest.fixture
def http():
    from songrush_data import http as module

    return module


# Fakes (copied from test_animethemes.py, no cross-test imports).

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

    def ids(self):
        match = re.search(r"id_in:\[([^\]]*)\]", self.json()["query"])
        assert match is not None, "request query has no id_in:[...]"
        text = match.group(1).strip()
        if not text:
            return []
        return [int(part) for part in text.split(",")]


class IdTransport:
    """Answers {data:{Page:{media:[...]}}} with the records of the requested ids.

    records maps id -> media record. Ids without a record are left out of the
    response. respond, when set, replaces the body builder: respond(ids) -> dict.
    """

    def __init__(self, clock, http, records, respond=None):
        self.clock = clock
        self.http = http
        self.records = records
        self.respond = respond
        self.calls = []

    def send(self, method, url, headers, body, timeout):
        call = Call(method, url, dict(headers or {}), body, timeout, self.clock.now())
        self.calls.append(call)
        ids = call.ids()
        if self.respond is not None:
            payload = self.respond(ids)
        else:
            media = [copy.deepcopy(self.records[i]) for i in ids if i in self.records]
            payload = {"data": {"Page": {"media": media}}}
        data = json.dumps(payload).encode("utf-8")
        return self.http.Response(status=200, headers={"content-type": "application/json"}, body=data)

    def batches(self):
        return [call.ids() for call in self.calls]


def synthetic(ids):
    return {
        i: {
            "id": i,
            "popularity": i * 10,
            "title": {"romaji": "R%d" % i, "english": "E%d" % i, "native": "N%d" % i},
            "synonyms": ["S%d" % i],
            "relations": {"edges": []},
        }
        for i in ids
    }


def make(http, tmp_path, records, respond=None):
    clock = FakeClock()
    transport = IdTransport(clock, http, records, respond)
    client = http.PoliteClient(transport, clock, gap_s=2)
    cache = http.FileCache(tmp_path / "cache")
    return transport, client, cache


def al_files(cache):
    if not cache.root.is_dir():
        return []
    return sorted(p.name for p in cache.root.iterdir() if p.name.startswith("al-"))


def quiet(_line):
    pass


# Criterion 1 and the interface constants.

def test_anilist_url_and_media_fields(al):
    assert al.ANILIST_URL == "https://graphql.anilist.co"
    assert al.MEDIA_FIELDS == EXPECTED_MEDIA_FIELDS


def test_build_query_literal_ids(al):
    query = al.build_query([5081, 101922])
    assert "id_in:[5081,101922]" in query
    assert "perPage:50" in query
    assert "type:ANIME" in query
    assert "relationType(version:2)" in query
    assert al.MEDIA_FIELDS in query
    assert "$" not in query


def test_build_query_keeps_order(al):
    assert "id_in:[101922,5081]" in al.build_query([101922, 5081])


# Criterion 2: batches.

def test_120_ids_make_three_posts_in_order(al, http, tmp_path):
    ids = list(range(1001, 1121))
    transport, client, cache = make(http, tmp_path, synthetic(ids))
    result = al.fetch_media(client, cache, ids, log=quiet)
    assert transport.batches() == [ids[0:50], ids[50:100], ids[100:120]]
    for call in transport.calls:
        assert call.method == "POST"
        assert call.url == al.ANILIST_URL
        assert call.json() == {"query": al.build_query(call.ids())}
    assert set(result) == set(ids)
    assert all(result[i] is not None for i in ids)


def test_rerun_makes_no_posts(al, http, tmp_path):
    ids = list(range(1001, 1121))
    transport, client, cache = make(http, tmp_path, synthetic(ids))
    first = al.fetch_media(client, cache, ids, log=quiet)
    transport.calls.clear()
    second = al.fetch_media(client, cache, ids, log=quiet)
    assert transport.calls == []
    assert second == first


def test_batch_size_25_gives_five_posts(al, http, tmp_path):
    ids = list(range(1001, 1121))
    transport, client, cache = make(http, tmp_path, synthetic(ids))
    al.fetch_media(client, cache, ids, batch_size=25, log=quiet)
    assert [len(b) for b in transport.batches()] == [25, 25, 25, 25, 20]
    assert [i for b in transport.batches() for i in b] == ids


@pytest.mark.parametrize("size", [0, 51, -1])
def test_batch_size_out_of_range_raises(al, http, tmp_path, size):
    transport, client, cache = make(http, tmp_path, synthetic([1]))
    with pytest.raises(ValueError):
        al.fetch_media(client, cache, [1], batch_size=size, log=quiet)
    assert transport.calls == []


@pytest.mark.parametrize("size", [1, 50])
def test_batch_size_bounds_accepted(al, http, tmp_path, size):
    transport, client, cache = make(http, tmp_path, synthetic([1, 2]))
    result = al.fetch_media(client, cache, [1, 2], batch_size=size, log=quiet)
    assert set(result) == {1, 2}
    assert len(transport.calls) == (2 if size == 1 else 1)


@pytest.mark.parametrize("bad", ["5081", True, False, 1.0, "1]){x}"])
def test_non_int_id_raises(al, http, tmp_path, bad):
    transport, client, cache = make(http, tmp_path, synthetic([5081]))
    with pytest.raises(ValueError):
        al.fetch_media(client, cache, [5081, bad], log=quiet)
    assert transport.calls == []
    assert al_files(cache) == []


# Criterion 3: missing ids, None and duplicates.

def test_missing_ids_map_to_none_and_are_cached(al, http, tmp_path):
    ids = [1, 2, 3, 4]
    transport, client, cache = make(http, tmp_path, synthetic([1, 3]))
    result = al.fetch_media(client, cache, ids, log=quiet)
    assert set(result) == {1, 2, 3, 4}
    assert result[2] is None and result[4] is None
    assert result[1] is not None and result[3] is not None
    transport.calls.clear()
    again = al.fetch_media(client, cache, ids, log=quiet)
    assert transport.calls == []
    assert again == result


def test_null_record_is_stored_under_spec_key(al, http, tmp_path):
    transport, client, cache = make(http, tmp_path, {})
    al.fetch_media(client, cache, [42], log=quiet)
    digest = hashlib.sha256(al.MEDIA_FIELDS.encode("utf-8")).hexdigest()[:16]
    key = "al-%s-42.json" % digest
    assert al_files(cache) == [key]
    assert json.loads(cache.get(key)) == {"media": None}


def test_none_and_duplicates_are_ignored(al, http, tmp_path):
    transport, client, cache = make(http, tmp_path, synthetic([5, 6, 7]))
    result = al.fetch_media(client, cache, [5, None, 6, 5, None, 7, 6], log=quiet)
    assert transport.batches() == [[5, 6, 7]]
    assert set(result) == {5, 6, 7}
    assert None not in result


# Criterion 4: shapes on anilist_id_in.json.

def id_in_records():
    body = json.loads(ID_IN_PATH.read_text(encoding="utf-8"))
    return {m["id"]: m for m in body["data"]["Page"]["media"]}


def test_shape_on_id_in_fixture(al, http, tmp_path):
    records = id_in_records()
    transport, client, cache = make(http, tmp_path, records)
    result = al.fetch_media(client, cache, [5081, 101922], log=quiet)
    media = result[101922]
    assert media["titles"]["english"] == "Demon Slayer: Kimetsu no Yaiba"
    assert media["titles"]["romaji"] == "Kimetsu no Yaiba"
    assert media["titles"]["native"] == records[101922]["title"]["native"]
    assert isinstance(media["popularity"], int) and not isinstance(media["popularity"], bool)
    assert media["popularity"] == records[101922]["popularity"]
    assert media["synonyms"] == records[101922]["synonyms"]
    assert result[5081]["synonyms"] == records[5081]["synonyms"]
    # The fixture has no relations key, so the default applies.
    assert media["relations"] == []


def test_shape_survives_cache_reload(al, http, tmp_path):
    records = id_in_records()
    transport, client, cache = make(http, tmp_path, records)
    first = al.fetch_media(client, cache, [101922, 5081], log=quiet)
    transport.calls.clear()
    second = al.fetch_media(client, cache, [101922, 5081], log=quiet)
    assert transport.calls == []
    assert second[101922] == first[101922]
    assert second[5081]["synonyms"] == records[5081]["synonyms"]


# Criterion 5: relations on anilist_relations_franchises.json.

def relation_records():
    return {m["id"]: m for m in json.loads(RELATIONS_PATH.read_text(encoding="utf-8"))}


def test_relations_shape_and_defaults(al, http, tmp_path):
    records = relation_records()
    transport, client, cache = make(http, tmp_path, records)
    result = al.fetch_media(client, cache, [356], log=quiet)
    assert transport.batches() == [[356]]
    media = result[356]
    relations = media["relations"]
    assert {"type": "PREQUEL",
            "node": {"id": 11741, "type": "ANIME", "format": "TV",
                     "titles": {"romaji": "Fate/Zero 2nd Season", "english": None}}} in relations
    prequel = [r for r in relations if r["node"]["id"] == 11741][0]
    assert prequel["type"] == "PREQUEL"
    assert prequel["node"]["titles"]["english"] is None
    edges = records[356]["relations"]["edges"]
    assert [r["type"] for r in relations] == [e["relationType"] for e in edges]
    assert [r["node"]["id"] for r in relations] == [e["node"]["id"] for e in edges]
    assert [r["node"]["type"] for r in relations] == [e["node"]["type"] for e in edges]
    assert [r["node"]["format"] for r in relations] == [e["node"]["format"] for e in edges]
    assert media["synonyms"] == []
    assert media["popularity"] is None
    assert media["titles"]["romaji"] == "Fate/stay night"


def test_missing_title_and_node_keys_default_to_none(al, http, tmp_path):
    record = {
        "id": 77,
        "relations": {"edges": [{"relationType": "SEQUEL", "node": {"id": 78}}]},
    }
    transport, client, cache = make(http, tmp_path, {77: record})
    media = al.fetch_media(client, cache, [77], log=quiet)[77]
    assert media["popularity"] is None
    assert media["titles"] == {"romaji": None, "english": None, "native": None}
    assert media["synonyms"] == []
    assert media["relations"] == [
        {"type": "SEQUEL",
         "node": {"id": 78, "type": None, "format": None,
                  "titles": {"romaji": None, "english": None}}}
    ]


# Criterion 6: validation caches nothing for the failed batch.

def test_errors_raise_and_cache_nothing(al, http, tmp_path):
    records = synthetic([1, 2])

    def respond(ids):
        return {"errors": [{"message": "Max query complexity"}],
                "data": {"Page": {"media": [records[i] for i in ids]}}}

    transport, client, cache = make(http, tmp_path, records, respond)
    with pytest.raises(ValueError):
        al.fetch_media(client, cache, [1, 2], log=quiet)
    assert al_files(cache) == []


@pytest.mark.parametrize("payload", [
    {"data": None},
    {"data": {}},
    {"data": {"Page": None}},
    {"data": {"Page": {}}},
    {"data": {"Page": {"media": None}}},
    {"data": {"Page": {"media": {"id": 1}}}},
    {},
])
def test_missing_media_list_raises_and_caches_nothing(al, http, tmp_path, payload):
    transport, client, cache = make(http, tmp_path, {}, lambda ids: payload)
    with pytest.raises(ValueError):
        al.fetch_media(client, cache, [1, 2], log=quiet)
    assert al_files(cache) == []


def test_unrequested_id_raises_and_caches_nothing(al, http, tmp_path):
    records = synthetic([1, 2, 999])

    def respond(ids):
        return {"data": {"Page": {"media": [records[1], records[2], records[999]]}}}

    transport, client, cache = make(http, tmp_path, records, respond)
    with pytest.raises(ValueError):
        al.fetch_media(client, cache, [1, 2], log=quiet)
    assert al_files(cache) == []


def test_failed_second_batch_keeps_first_batch_only(al, http, tmp_path):
    records = synthetic([1, 2, 3, 4])

    def respond(ids):
        if 3 in ids:
            return {"errors": [{"message": "boom"}], "data": None}
        return {"data": {"Page": {"media": [records[i] for i in ids]}}}

    transport, client, cache = make(http, tmp_path, records, respond)
    with pytest.raises(ValueError):
        al.fetch_media(client, cache, [1, 2, 3, 4], batch_size=2, log=quiet)
    names = al_files(cache)
    assert len(names) == 2
    assert all(n.endswith("-1.json") or n.endswith("-2.json") for n in names)


# Criterion 7: partial reuse.

def test_partial_reuse_fetches_only_uncached(al, http, tmp_path):
    ids = list(range(2001, 2061))
    transport, client, cache = make(http, tmp_path, synthetic(ids))
    al.fetch_media(client, cache, ids[:30], log=quiet)
    transport.calls.clear()
    lines = []
    result = al.fetch_media(client, cache, ids, log=lines.append)
    assert transport.batches() == [ids[30:]]
    assert set(result) == set(ids)
    assert any("cached=30" in line for line in lines)


# Criterion 7b and the log lines.

def test_missing_ids_logged_on_one_line(al, http, tmp_path):
    transport, client, cache = make(http, tmp_path, synthetic([5, 6, 8]))
    lines = []
    result = al.fetch_media(client, cache, [5, 6, 7, 8, 9], log=lines.append)
    assert result[7] is None and result[9] is None
    missing = [line for line in lines if "missing ids" in line]
    assert len(missing) == 1
    numbers = re.findall(r"\d+", missing[0])
    assert "7" in numbers and "9" in numbers
    assert "5" not in numbers and "6" not in numbers and "8" not in numbers


def test_batch_and_summary_log_lines(al, http, tmp_path):
    ids = list(range(1, 61))
    transport, client, cache = make(http, tmp_path, synthetic(ids[:-2]))
    lines = []
    al.fetch_media(client, cache, ids, log=lines.append)
    assert any("anilist batch 1/2 ids=50" in line for line in lines)
    assert any("anilist batch 2/2 ids=10" in line for line in lines)
    assert any("anilist media=58 missing=2 cached=0" in line for line in lines)


def test_no_missing_line_when_all_found(al, http, tmp_path):
    transport, client, cache = make(http, tmp_path, synthetic([1, 2]))
    lines = []
    al.fetch_media(client, cache, [1, 2], log=lines.append)
    assert not any("missing ids" in line for line in lines)
    assert any("anilist media=2 missing=0 cached=0" in line for line in lines)
