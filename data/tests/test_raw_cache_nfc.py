"""Cycle 020 criteria 1-5: raw AniList cache normalized on read, NFC Hangul in aod.

docs/cycles/020-raw-cache-nfc.md is the specification. Every request goes to
a fake transport with a fake clock, so the tests are offline and instant.
Imports of songrush_data modules stay inside fixtures and tests so that a
missing module fails these tests, not the collection.
"""

import copy
import hashlib
import json
import re
import unicodedata
from pathlib import Path

import pytest

FIXTURES = Path(__file__).resolve().parent / "fixtures"
ID_IN_PATH = FIXTURES / "anilist_id_in.json"
T0 = 1000.0
WALL0 = 1_800_000_000.0


@pytest.fixture
def al():
    from songrush_data import anilist as module

    return module


@pytest.fixture
def aod():
    from songrush_data import aod as module

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


class IdTransport:
    """Answers {data:{Page:{media:[...]}}} with the records of the requested ids."""

    def __init__(self, http, records):
        self.http = http
        self.records = records
        self.calls = []

    def send(self, method, url, headers, body, timeout):
        self.calls.append(body)
        query = json.loads(body)["query"]
        match = re.search(r"id_in:\[([^\]]*)\]", query)
        assert match is not None, "request query has no id_in:[...]"
        text = match.group(1).strip()
        ids = [int(part) for part in text.split(",")] if text else []
        media = [copy.deepcopy(self.records[i]) for i in ids if i in self.records]
        data = json.dumps({"data": {"Page": {"media": media}}}).encode("utf-8")
        return self.http.Response(status=200, headers={"content-type": "application/json"}, body=data)


def make(http, tmp_path, records):
    transport = IdTransport(http, records)
    client = http.PoliteClient(transport, FakeClock(), gap_s=2)
    cache = http.FileCache(tmp_path / "cache")
    return transport, client, cache


def key(al, media_id):
    digest = hashlib.sha256(al.MEDIA_FIELDS.encode("utf-8")).hexdigest()[:16]
    return "al-%s-%d.json" % (digest, media_id)


def stored(al, cache, media_id):
    data = cache.get(key(al, media_id))
    assert data is not None, "no cache entry for id %d" % media_id
    return json.loads(data)


def quiet(_line):
    pass


def id_in_records():
    body = json.loads(ID_IN_PATH.read_text(encoding="utf-8"))
    return {m["id"]: m for m in body["data"]["Page"]["media"]}


RAW_7 = {
    "id": 7,
    "popularity": 5,
    "title": {"romaji": "R", "english": None, "native": "N"},
    "synonyms": ["S"],
    "relations": {"edges": [{"relationType": "SEQUEL",
                             "node": {"id": 8, "type": "ANIME", "format": "TV",
                                      "title": {"romaji": "R2"}}}]},
}

NORMALIZED_7 = {
    "popularity": 5,
    "titles": {"romaji": "R", "english": None, "native": "N"},
    "synonyms": ["S"],
    "relations": [{"type": "SEQUEL",
                   "node": {"id": 8, "type": "ANIME", "format": "TV",
                            "titles": {"romaji": "R2", "english": None}}}],
}


def seed(al, cache, media_id, data):
    cache.put(key(al, media_id), data)


# Criterion 1: raw storage.

def test_cache_stores_raw_media(al, http, tmp_path):
    records = id_in_records()
    transport, client, cache = make(http, tmp_path, records)
    al.fetch_media(client, cache, [101922], log=quiet)
    entry = stored(al, cache, 101922)
    assert entry == {"media": records[101922]}
    assert "title" in entry["media"]
    assert "titles" not in entry["media"]


# Criterion 2: normalize on read.

def test_cached_raw_entry_is_normalized_on_read(al, http, tmp_path):
    transport, client, cache = make(http, tmp_path, {})
    seed(al, cache, 7, json.dumps({"media": RAW_7}).encode("utf-8"))
    result = al.fetch_media(client, cache, [7], log=quiet)
    assert transport.calls == []
    assert result == {7: NORMALIZED_7}


# Criterion 3: normalizer changes apply to cached records.

def test_patched_normalizer_applies_to_cached_record(al, http, tmp_path, monkeypatch):
    transport, client, cache = make(http, tmp_path, {})
    seed(al, cache, 7, json.dumps({"media": RAW_7}).encode("utf-8"))
    monkeypatch.setattr(al, "_normalize", lambda raw: {"marker": raw["id"]})
    result = al.fetch_media(client, cache, [7], log=quiet)
    assert transport.calls == []
    assert result == {7: {"marker": 7}}


# Criterion 4: damaged entries are refetched and replaced with the raw object.

@pytest.mark.parametrize("data", [
    json.dumps({"media": 3}).encode("utf-8"),
    json.dumps({"other": 1}).encode("utf-8"),
    b"not json",
], ids=["media-number", "no-media", "not-json"])
def test_damaged_entry_is_refetched_and_stored_raw(al, http, tmp_path, data):
    transport, client, cache = make(http, tmp_path, {7: RAW_7})
    seed(al, cache, 7, data)
    result = al.fetch_media(client, cache, [7], log=quiet)
    assert len(transport.calls) == 1
    assert stored(al, cache, 7) == {"media": RAW_7}
    assert result == {7: NORMALIZED_7}


# Criterion 5: aod NFC.

def aod_record(anilist_id, title, synonyms):
    return {"sources": ["https://anilist.co/anime/%d" % anilist_id],
            "title": title, "synonyms": list(synonyms)}


def test_nfd_only_synonym_counts_as_korean(aod):
    text = "사정을 모르는 전학생이 엄청나게 들이댄다"
    nfc = unicodedata.normalize("NFC", text)
    nfd = unicodedata.normalize("NFD", text)
    assert nfd != nfc
    assert not unicodedata.is_normalized("NFC", nfd)
    document = {"data": [aod_record(157295, "Jijou wo Shiranai Tenkousei ga Guigui Kuru.", [nfd])]}
    result = aod.korean_by_anilist(document)
    assert result == {157295: [nfc]}
    assert unicodedata.is_normalized("NFC", result[157295][0])


@pytest.mark.parametrize("order", ["nfd-first", "nfc-first"])
def test_nfd_and_nfc_copies_collapse(aod, order):
    text = "괴짜가족"
    nfc = unicodedata.normalize("NFC", text)
    nfd = unicodedata.normalize("NFD", text)
    assert nfd != nfc
    synonyms = [nfd, nfc] if order == "nfd-first" else [nfc, nfd]
    document = {"data": [aod_record(2716, "Latin", synonyms)]}
    result = aod.korean_by_anilist(document)
    assert result == {2716: [nfc]}
    assert unicodedata.is_normalized("NFC", result[2716][0])
