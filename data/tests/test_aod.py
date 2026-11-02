"""Cycle 019 criteria 8-11: Korean strings from the pinned anime-offline-database.

docs/cycles/019-korean-titles.md is the specification. Downloads go to a fake
transport with a fake clock, so the tests are offline and instant. Imports of
songrush_data.aod stay inside fixtures and tests so that a missing module
fails these tests, not the collection.
"""

import copy
import hashlib
import json
from pathlib import Path

import pytest

from songrush_data.titles import has_hangul

FIXTURES = Path(__file__).resolve().parent / "fixtures"
SAMPLE_PATH = FIXTURES / "aod_sample.json"
FILE_NAME = "anime-offline-database-minified.json"
T0 = 1000.0
WALL0 = 1_800_000_000.0
EXPECTED_URL = ("https://github.com/manami-project/anime-offline-database/releases/download/"
                "2026-27/anime-offline-database-minified.json")


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


class GetTransport:
    """Answers every request with status and body; records method and URL."""

    def __init__(self, http, body, status=200):
        self.http = http
        self.body = body
        self.status = status
        self.calls = []

    def send(self, method, url, headers, body, timeout):
        self.calls.append((method, url))
        return self.http.Response(status=self.status, headers={}, body=self.body)


def make(http, body, status=200):
    transport = GetTransport(http, body, status)
    client = http.PoliteClient(transport, FakeClock(), gap_s=3, max_attempts=1)
    return transport, client


def sample():
    return json.loads(SAMPLE_PATH.read_text(encoding="utf-8"))


def record(sources, title="T", synonyms=()):
    return {"sources": list(sources), "title": title, "synonyms": list(synonyms)}


def anilist(i):
    return "https://anilist.co/anime/%d" % i


def small_document():
    return {
        "lastUpdate": "2026-07-04",
        "data": [
            record([anilist(1535)], "Death Note", ["Death Note", "데스 노트", "데스노트"]),
            record([anilist(21)], "One Piece", ["원피스"]),
            record([anilist(298)], ".hack", ["dot hack"]),
        ],
    }


def encode(document):
    return json.dumps(document, ensure_ascii=False).encode("utf-8")


def digest(body):
    return hashlib.sha256(body).hexdigest()


def target(tmp_path):
    return tmp_path / "aod" / FILE_NAME


def quiet(_line):
    pass


# Interface constants.

def test_release_constants(aod):
    assert aod.AOD_RELEASE == "2026-27"
    assert aod.AOD_URL == EXPECTED_URL
    assert aod.AOD_SIZE == 62331136
    assert aod.AOD_SHA256 == "395be786fb2f98f1fca9e963911b9332110098a080e7e2569902bbc5641d1a41"


# Criterion 8: parse on aod_sample.json.

def test_korean_by_anilist_on_sample(aod):
    result = aod.korean_by_anilist(sample())
    assert result[1535] == ["데스 노트", "데스노트"]
    assert result[98478] == ["3월의 라이온", "3월의 라이온 2기"]
    assert result[5114] == ["강철의 연금술사 리메이크"]
    assert 298 not in result
    assert 17295 not in result
    assert 206843 not in result
    for anilist_id, strings in result.items():
        assert isinstance(anilist_id, int) and not isinstance(anilist_id, bool)
        assert strings
        assert all(has_hangul(s) for s in strings)


def test_korean_by_anilist_does_not_change_document(aod):
    document = sample()
    before = copy.deepcopy(document)
    aod.korean_by_anilist(document)
    assert document == before


# Criterion 9: edge cases on inline documents.

def test_two_anilist_ids_in_one_record_share_the_list(aod):
    document = {"data": [record([anilist(7), anilist(8)], "Latin", ["가나"])]}
    assert aod.korean_by_anilist(document) == {7: ["가나"], 8: ["가나"]}


def test_only_exact_anilist_urls_count(aod):
    document = {"data": [record(["https://anilist.co/anime/7/extra",
                                 "https://myanimelist.net/anime/7"], "Latin", ["다라"])]}
    assert aod.korean_by_anilist(document) == {}


def test_records_sharing_an_id_merge_in_order(aod):
    document = {"data": [
        record([anilist(9)], "Latin", ["가", "나"]),
        record([anilist(9)], "Other", ["나", "다"]),
    ]}
    assert aod.korean_by_anilist(document) == {9: ["가", "나", "다"]}


def test_title_comes_first_and_duplicates_drop(aod):
    document = {"data": [record([anilist(10)], "한글 제목", ["English", "가", "한글 제목", "가"])]}
    assert aod.korean_by_anilist(document) == {10: ["한글 제목", "가"]}


def test_record_without_anilist_id_is_left_out(aod):
    document = {"data": [record(["https://myanimelist.net/anime/1"], "Latin", ["가"])]}
    assert aod.korean_by_anilist(document) == {}


@pytest.mark.parametrize("bad", [
    "not a record",
    3,
    None,
    {"sources": anilist(11), "title": "T", "synonyms": ["가"]},
    {"sources": [anilist(11)], "title": "T", "synonyms": "가"},
    {"sources": [anilist(11)], "title": 5, "synonyms": ["가"]},
], ids=["string", "number", "null", "sources-not-list", "synonyms-not-list", "title-not-string"])
def test_malformed_records_are_skipped(aod, bad):
    document = {"data": [bad, record([anilist(12)], "Latin", ["나"])]}
    assert aod.korean_by_anilist(document) == {12: ["나"]}


@pytest.mark.parametrize("document", [{"data": 3}, [], {}, {"data": None}, "data"],
                         ids=["data-number", "list", "no-data", "data-null", "string"])
def test_bad_document_raises(aod, document):
    with pytest.raises(ValueError):
        aod.korean_by_anilist(document)


# Criterion 10: download, write and reuse.

def test_download_writes_file_and_returns_mapping(aod, http, tmp_path):
    document = small_document()
    body = encode(document)
    transport, client = make(http, body)
    path = target(tmp_path)
    lines = []
    result = aod.load_korean(client, path, expected_size=len(body),
                             expected_sha256=digest(body), log=lines.append)
    assert transport.calls == [("GET", EXPECTED_URL)]
    assert path.read_bytes() == body
    assert sorted(p.name for p in path.parent.iterdir()) == [FILE_NAME]
    assert result == aod.korean_by_anilist(document)
    assert result == {1535: ["데스 노트", "데스노트"], 21: ["원피스"]}
    assert any("aod downloaded %d bytes" % len(body) in line for line in lines)
    assert any("aod release=2026-27 records=3 anilist_ids_with_korean=2" in line for line in lines)


def test_second_call_makes_no_request(aod, http, tmp_path):
    document = small_document()
    body = encode(document)
    transport, client = make(http, body)
    path = target(tmp_path)
    first = aod.load_korean(client, path, expected_size=len(body),
                            expected_sha256=digest(body), log=quiet)
    transport.calls.clear()
    lines = []
    second = aod.load_korean(client, path, expected_size=len(body),
                             expected_sha256=digest(body), log=lines.append)
    assert transport.calls == []
    assert second == first
    assert not any("aod downloaded" in line for line in lines)


def test_existing_valid_file_makes_no_request(aod, http, tmp_path):
    document = small_document()
    body = encode(document)
    path = target(tmp_path)
    path.parent.mkdir(parents=True)
    path.write_bytes(body)
    transport, client = make(http, b"unused")
    result = aod.load_korean(client, path, expected_size=len(body),
                             expected_sha256=digest(body), log=quiet)
    assert transport.calls == []
    assert result == aod.korean_by_anilist(document)
    assert path.read_bytes() == body


# Criterion 11: failures skip aod and leave no file.

def assert_skipped(lines, result, path):
    assert result == {}
    assert not path.exists()
    skipped = [line for line in lines if "aod skipped" in line]
    assert len(skipped) == 1
    return skipped[0]


def test_404_is_skipped(aod, http, tmp_path):
    body = encode(small_document())
    transport, client = make(http, b"Not Found", status=404)
    path = target(tmp_path)
    lines = []
    result = aod.load_korean(client, path, expected_size=len(body),
                             expected_sha256=digest(body), log=lines.append)
    assert transport.calls == [("GET", EXPECTED_URL)]
    line = assert_skipped(lines, result, path)
    assert "HttpError" in line


def test_not_json_is_skipped(aod, http, tmp_path):
    body = b"this is not json"
    transport, client = make(http, body)
    path = target(tmp_path)
    lines = []
    result = aod.load_korean(client, path, expected_size=len(body),
                             expected_sha256=digest(body), log=lines.append)
    line = assert_skipped(lines, result, path)
    assert "ValueError" in line or "JSONDecodeError" in line


@pytest.mark.parametrize("document", [{"records": []}, [], {"data": 3}],
                         ids=["no-data", "list", "data-not-list"])
def test_json_without_data_list_is_skipped(aod, http, tmp_path, document):
    body = encode(document)
    transport, client = make(http, body)
    path = target(tmp_path)
    lines = []
    result = aod.load_korean(client, path, expected_size=len(body),
                             expected_sha256=digest(body), log=lines.append)
    line = assert_skipped(lines, result, path)
    assert "ValueError" in line


def test_wrong_size_is_skipped(aod, http, tmp_path):
    body = encode(small_document())
    transport, client = make(http, body)
    path = target(tmp_path)
    lines = []
    result = aod.load_korean(client, path, expected_size=len(body) + 1,
                             expected_sha256=digest(body), log=lines.append)
    line = assert_skipped(lines, result, path)
    assert "ValueError" in line


def test_wrong_sha256_is_skipped(aod, http, tmp_path):
    body = encode(small_document())
    transport, client = make(http, body)
    path = target(tmp_path)
    lines = []
    result = aod.load_korean(client, path, expected_size=len(body),
                             expected_sha256=digest(body + b" "), log=lines.append)
    line = assert_skipped(lines, result, path)
    assert "ValueError" in line


def test_failed_download_leaves_no_temp_file(aod, http, tmp_path):
    body = encode(small_document())
    transport, client = make(http, body)
    path = target(tmp_path)
    aod.load_korean(client, path, expected_size=len(body),
                    expected_sha256=digest(body + b" "), log=quiet)
    leftovers = list(path.parent.iterdir()) if path.parent.is_dir() else []
    assert leftovers == []


def damaged_variants(body):
    truncated = body[:10]
    # Same length, different bytes, so only the hash catches it.
    swapped = body.replace(b"One Piece", b"One Pixce")
    assert len(swapped) == len(body) and swapped != body
    return {"truncated": truncated, "wrong-hash": swapped}


@pytest.mark.parametrize("kind", ["truncated", "wrong-hash"])
def test_damaged_existing_file_is_refetched_once(aod, http, tmp_path, kind):
    document = small_document()
    body = encode(document)
    path = target(tmp_path)
    path.parent.mkdir(parents=True)
    path.write_bytes(damaged_variants(body)[kind])
    transport, client = make(http, body)
    lines = []
    result = aod.load_korean(client, path, expected_size=len(body),
                             expected_sha256=digest(body), log=lines.append)
    assert transport.calls == [("GET", EXPECTED_URL)]
    assert any("refetching" in line for line in lines)
    assert path.read_bytes() == body
    assert sorted(p.name for p in path.parent.iterdir()) == [FILE_NAME]
    assert result == aod.korean_by_anilist(document)


def test_damaged_file_and_failed_refetch_leaves_no_file(aod, http, tmp_path):
    body = encode(small_document())
    path = target(tmp_path)
    path.parent.mkdir(parents=True)
    path.write_bytes(body[:10])
    transport, client = make(http, b"Not Found", status=404)
    lines = []
    result = aod.load_korean(client, path, expected_size=len(body),
                             expected_sha256=digest(body), log=lines.append)
    assert len(transport.calls) == 1
    assert any("refetching" in line for line in lines)
    assert_skipped(lines, result, path)
