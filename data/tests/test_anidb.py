"""Cycle 043 criteria 1-3: Korean titles from the AniDB titles dump.

docs/cycles/043-anidb-korean.md is the specification. parse_titles reads a
gzip made in memory from tests/fixtures/anidb_titles_sample.xml, and
load_korean talks to a fake client with an injected now, so the tests are
offline and instant. Imports of songrush_data.anidb stay inside fixtures so
that a missing module fails these tests, not the collection.
"""

import gzip
import json
from datetime import datetime, timedelta, timezone
from pathlib import Path

import pytest

FIXTURES = Path(__file__).resolve().parent / "fixtures"
SAMPLE_PATH = FIXTURES / "anidb_titles_sample.xml"
DUMP_NAME = "anime-titles.xml.gz"
MARKER_NAME = "attempt.json"
KST = timezone(timedelta(hours=9))
NOW = datetime(2026, 11, 10, 12, 0, tzinfo=timezone.utc)  # after NOT_BEFORE


def _ko(*code_points):
    return "".join(chr(c) for c in code_points)


# Korean strings are built from code points (the tools may alter literal escapes).
K1_OFF = _ko(0xCE74, 0xC6B0, 0xBCF4, 0xC774, 0x0020, 0xBE44, 0xBC25)
K1_SYN = _ko(0xCE74, 0xC6B0, 0xBCF4, 0xC774, 0xBE44, 0xBC25)
K1_SHORT = _ko(0xBE44, 0xBC25)
K3_SYN = _ko(0xC9C4, 0xACA9, 0xC758, 0x0020, 0xAC70, 0xC778)
K4_SYN = _ko(0xAC15, 0xCCA0, 0xC758, 0x0020, 0xC5F0, 0xAE08, 0xC220, 0xC0AC)
K4_OFF = _ko(0xAC15, 0xCCA0, 0xC758, 0x0020, 0xC5F0, 0xAE08, 0xC220, 0xC0AC,
             0x0020, 0xBE0C, 0xB77C, 0xB354, 0xD6C4, 0xB4DC)
K4_SHORT = _ko(0xAC15, 0xC5F0)
K6_A = _ko(0xD0A4, 0xBBF8, 0xB178, 0x0020, 0xB098, 0xC640)
K6_B = _ko(0xB108, 0xC758, 0x0020, 0xC774, 0xB984, 0xC740)

SAMPLE_EXPECTED = {
    1: [K1_OFF, K1_SYN, K1_SHORT],
    3: [K3_SYN],
    4: [K4_OFF, K4_SYN, K4_SHORT],
    6: [K6_A, K6_B],
}


@pytest.fixture
def anidb():
    from songrush_data import anidb as module

    return module


@pytest.fixture
def http():
    from songrush_data import http as module

    return module


def sample_xml() -> bytes:
    return SAMPLE_PATH.read_bytes()


def sample_gz() -> bytes:
    return gzip.compress(sample_xml())


def doc(body: str, prolog: str = '<?xml version="1.0" encoding="UTF-8"?>\n') -> bytes:
    return (prolog + body).encode("utf-8")


def anime_xml(aid, titles) -> str:
    lines = ['<anime aid="%s">' % aid]
    for kind, lang, text in titles:
        lines.append('<title xml:lang="%s" type="%s">%s</title>' % (lang, kind, text))
    lines.append("</anime>")
    return "\n".join(lines)


def animetitles(*anime) -> bytes:
    return doc("<animetitles>\n%s\n</animetitles>\n" % "\n".join(anime))


class FakeClient:
    """Stands in for PoliteClient: answers from a list of replies (Response or exception)."""

    def __init__(self, replies, on_request=None):
        self.replies = list(replies)
        self.on_request = on_request
        self.calls = []

    def request(self, method, url, *, headers=None, body=None):
        self.calls.append((method, url))
        if self.on_request is not None:
            self.on_request()
        if not self.replies:
            raise AssertionError("unexpected request %s %s" % (method, url))
        reply = self.replies.pop(0)
        if isinstance(reply, BaseException):
            raise reply
        return reply


def ok(http, body):
    return http.Response(status=200, headers={}, body=body)


def crawled():
    """AnimeThemes records (only the fields load_korean reads) for the sample aids."""
    return [
        {"anilistId": 101, "anidbIds": [1]},
        {"anilistId": 102, "anidbIds": [2]},
        {"anilistId": 104, "anidbIds": [4, 6]},
        {"anilistId": None, "anidbIds": [3]},
    ]


CRAWLED_EXPECTED = {101: [K1_OFF, K1_SYN, K1_SHORT], 104: [K4_OFF, K4_SYN, K4_SHORT, K6_A, K6_B]}


def write_marker(cache_dir: Path, at) -> Path:
    cache_dir.mkdir(parents=True, exist_ok=True)
    path = cache_dir / MARKER_NAME
    if isinstance(at, bytes):
        path.write_bytes(at)
    else:
        path.write_text(json.dumps({"at": at}), encoding="utf-8")
    return path


def read_marker(cache_dir: Path) -> datetime:
    value = json.loads((cache_dir / MARKER_NAME).read_text(encoding="utf-8"))["at"]
    parsed = datetime.fromisoformat(value)
    assert parsed.tzinfo is not None, value
    return parsed


def run(anidb, client, cache_dir, now=NOW, anime=None):
    logs = []
    result = anidb.load_korean(client, cache_dir, crawled() if anime is None else anime, now=now, log=logs.append)
    return result, [str(line) for line in logs]


# Interface constants.

def test_constants(anidb):
    assert anidb.ANIDB_URL == "https://anidb.net/api/anime-titles.xml.gz"
    assert anidb.MIN_INTERVAL == timedelta(hours=24)
    assert anidb.MAX_XML_BYTES == 100 * 1024 * 1024
    assert anidb.NOT_BEFORE == datetime(2026, 11, 3, 18, 34, tzinfo=KST)
    assert anidb.NOT_BEFORE.tzinfo is not None


def test_fixture_holds_the_expected_korean_strings():
    text = sample_xml().decode("utf-8")
    for titles in SAMPLE_EXPECTED.values():
        for title in titles:
            assert title in text, title


# Criterion 1: parsing.

def test_parse_sample(anidb):
    assert anidb.parse_titles(sample_gz()) == SAMPLE_EXPECTED


def test_parse_drops_duplicates_within_an_aid(anidb):
    result = anidb.parse_titles(sample_gz())
    assert result[1].count(K1_OFF) == 1


def test_parse_drops_korean_without_hangul(anidb):
    result = anidb.parse_titles(sample_gz())
    assert "Cowboy Bebop" not in result[1]
    assert "Jinkyeok-ui Geoin" not in result[3]
    assert 5 not in result  # only a Latin ko title


def test_parse_aid_without_korean_is_absent(anidb):
    assert 2 not in anidb.parse_titles(sample_gz())


def test_parse_keeps_document_order_within_a_type_group(anidb):
    body = animetitles(anime_xml(7, [
        ("syn", "ko", K6_B),
        ("syn", "ko", K6_A),
        ("short", "ko", K1_SHORT),
    ]))
    assert anidb.parse_titles(gzip.compress(body)) == {7: [K6_B, K6_A, K1_SHORT]}


def test_parse_official_and_main_come_first(anidb):
    body = animetitles(anime_xml(8, [
        ("syn", "ko", K4_SYN),
        ("short", "ko", K4_SHORT),
        ("main", "ko", K3_SYN),
        ("syn", "ko", K6_A),
        ("official", "ko", K4_OFF),
    ]))
    assert anidb.parse_titles(gzip.compress(body)) == {8: [K3_SYN, K4_OFF, K4_SYN, K4_SHORT, K6_A]}


def test_parse_needs_xml_lang_ko(anidb):
    body = animetitles(
        anime_xml(9, [("official", "en", K1_OFF), ("syn", "ko", K1_SYN)]),
        '<anime aid="10">\n<title lang="ko" type="official">%s</title>\n</anime>' % K3_SYN,
    )
    assert anidb.parse_titles(gzip.compress(body)) == {9: [K1_SYN]}


def test_parse_skips_invalid_aids(anidb):
    body = animetitles(
        anime_xml(0, [("syn", "ko", K1_OFF)]),
        anime_xml(-3, [("syn", "ko", K1_OFF)]),
        anime_xml("abc", [("syn", "ko", K1_OFF)]),
        anime_xml("", [("syn", "ko", K1_OFF)]),
        '<anime>\n<title xml:lang="ko" type="syn">%s</title>\n</anime>' % K1_OFF,
        anime_xml(11, [("syn", "ko", K3_SYN)]),
    )
    result = anidb.parse_titles(gzip.compress(body))
    assert result == {11: [K3_SYN]}
    assert all(isinstance(aid, int) for aid in result)


def test_parse_empty_dump(anidb):
    assert anidb.parse_titles(gzip.compress(doc("<animetitles></animetitles>"))) == {}


@pytest.mark.parametrize("data", [b"", b"not gzip at all", sample_xml()], ids=["empty", "text", "plain-xml"])
def test_parse_rejects_non_gzip(anidb, data):
    with pytest.raises(ValueError):
        anidb.parse_titles(data)


DOCTYPE = '<!DOCTYPE animetitles>\n<animetitles></animetitles>\n'
ENTITY = ('<!DOCTYPE animetitles [\n<!ENTITY ko "%s">\n]>\n'
          '<animetitles><anime aid="1"><title xml:lang="ko" type="syn">&ko;</title></anime></animetitles>\n'
          % K1_OFF)
EXTERNAL = ('<!DOCTYPE animetitles [\n<!ENTITY ext SYSTEM "file:///etc/passwd">\n]>\n'
            '<animetitles><anime aid="1"><title xml:lang="ko" type="syn">&ext;</title></anime></animetitles>\n')


@pytest.mark.parametrize("body", [DOCTYPE, ENTITY, EXTERNAL], ids=["doctype", "entity", "external-entity"])
def test_parse_rejects_doctype_and_entities(anidb, body):
    with pytest.raises(ValueError):
        anidb.parse_titles(gzip.compress(doc(body)))


def test_parse_rejects_doctype_in_utf16(anidb):
    text = '<?xml version="1.0" encoding="UTF-16"?>\n' + ENTITY
    with pytest.raises(ValueError):
        anidb.parse_titles(gzip.compress(text.encode("utf-16")))


@pytest.mark.parametrize("body", [
    '<animetitles><anime aid="1"><title xml:lang="ko" type="syn">x</title></anime>',
    '<animetitles><anime aid="1"></title></anime></animetitles>',
    "",
], ids=["unclosed", "mismatched", "empty-document"])
def test_parse_rejects_malformed_xml(anidb, body):
    with pytest.raises(ValueError):
        anidb.parse_titles(gzip.compress(doc(body)))


def test_parse_rejects_wrong_root(anidb):
    body = doc('<anime aid="1"><title xml:lang="ko" type="syn">%s</title></anime>' % K1_OFF)
    with pytest.raises(ValueError):
        anidb.parse_titles(gzip.compress(body))


def test_parse_rejects_over_cap(anidb, monkeypatch):
    data = sample_xml()
    monkeypatch.setattr(anidb, "MAX_XML_BYTES", len(data) - 1)
    with pytest.raises(ValueError):
        anidb.parse_titles(gzip.compress(data))


def test_parse_accepts_exactly_the_cap(anidb, monkeypatch):
    data = sample_xml()
    monkeypatch.setattr(anidb, "MAX_XML_BYTES", len(data))
    assert anidb.parse_titles(gzip.compress(data)) == SAMPLE_EXPECTED


def test_parse_streaming_cap_on_a_bomb(anidb, monkeypatch):
    # 16 MiB of spaces compress to about 16 KiB; the cap stops it early.
    bomb = gzip.compress(doc("<animetitles>" + " " * (16 * 1024 * 1024) + "</animetitles>"), compresslevel=9)
    assert len(bomb) < 1024 * 1024
    monkeypatch.setattr(anidb, "MAX_XML_BYTES", 4096)
    with pytest.raises(ValueError):
        anidb.parse_titles(bomb)


# Criterion 2: mapping.

def test_korean_by_anilist_merges_two_aids_without_duplicates(anidb):
    titles = {1: [K1_OFF, K1_SYN], 2: [K1_SYN, K3_SYN]}
    anime = [{"anilistId": 50, "anidbIds": [1, 2]}]
    assert anidb.korean_by_anilist(titles, anime) == {50: [K1_OFF, K1_SYN, K3_SYN]}


def test_korean_by_anilist_leaves_out_unmapped(anidb):
    titles = {1: [K1_OFF], 2: [K3_SYN]}
    anime = [
        {"anilistId": None, "anidbIds": [1]},
        {"anilistId": 60, "anidbIds": []},
        {"anilistId": 61, "anidbIds": [99]},
        {"anilistId": 62, "anidbIds": [2]},
    ]
    assert anidb.korean_by_anilist(titles, anime) == {62: [K3_SYN]}


def test_korean_by_anilist_on_sample(anidb):
    assert anidb.korean_by_anilist(anidb.parse_titles(sample_gz()), crawled()) == CRAWLED_EXPECTED


# Criterion 3: throttle and cache.

def test_cached_file_makes_no_request(anidb, tmp_path):
    cache_dir = tmp_path / "anidb"
    cache_dir.mkdir()
    (cache_dir / DUMP_NAME).write_bytes(sample_gz())
    client = FakeClient([])
    result, logs = run(anidb, client, cache_dir)
    assert client.calls == []
    assert result == CRAWLED_EXPECTED
    assert not (cache_dir / MARKER_NAME).exists()
    assert "anidb aids_with_korean=4 anilist_ids_with_korean=2" in logs, logs


def test_cached_file_is_used_even_before_not_before(anidb, tmp_path):
    cache_dir = tmp_path / "anidb"
    cache_dir.mkdir()
    (cache_dir / DUMP_NAME).write_bytes(sample_gz())
    client = FakeClient([])
    result, _ = run(anidb, client, cache_dir, now=anidb.NOT_BEFORE - timedelta(days=1))
    assert client.calls == []
    assert result == CRAWLED_EXPECTED


def test_no_file_no_marker_makes_one_request_after_writing_marker(anidb, http, tmp_path):
    cache_dir = tmp_path / "anidb"
    seen = []

    def check_marker():
        seen.append(read_marker(cache_dir))

    body = sample_gz()
    client = FakeClient([ok(http, body)], on_request=check_marker)
    result, logs = run(anidb, client, cache_dir)
    assert client.calls == [("GET", anidb.ANIDB_URL)]
    assert seen == [NOW]
    assert read_marker(cache_dir) == NOW
    assert (cache_dir / DUMP_NAME).read_bytes() == body
    assert result == CRAWLED_EXPECTED
    assert "anidb aids_with_korean=4 anilist_ids_with_korean=2" in logs, logs


def test_second_call_uses_the_stored_dump(anidb, http, tmp_path):
    cache_dir = tmp_path / "anidb"
    run(anidb, FakeClient([ok(http, sample_gz())]), cache_dir)
    client = FakeClient([])
    result, _ = run(anidb, client, cache_dir, now=NOW + timedelta(days=3))
    assert client.calls == []
    assert result == CRAWLED_EXPECTED


def test_recent_marker_blocks_the_request(anidb, tmp_path):
    cache_dir = tmp_path / "anidb"
    at = NOW - timedelta(hours=23, minutes=59)
    write_marker(cache_dir, at.isoformat())
    client = FakeClient([])
    result, logs = run(anidb, client, cache_dir)
    assert client.calls == []
    assert result == {}
    assert any(line.startswith("anidb skipped: last attempt ") and "next allowed" in line for line in logs), logs
    assert read_marker(cache_dir) == at


def test_recent_marker_in_another_offset_blocks_the_request(anidb, tmp_path):
    cache_dir = tmp_path / "anidb"
    write_marker(cache_dir, (NOW - timedelta(hours=1)).astimezone(KST).isoformat())
    client = FakeClient([])
    assert run(anidb, client, cache_dir)[0] == {}
    assert client.calls == []


def test_marker_exactly_24h_old_allows_one_request(anidb, http, tmp_path):
    cache_dir = tmp_path / "anidb"
    write_marker(cache_dir, (NOW - timedelta(hours=24)).isoformat())
    client = FakeClient([ok(http, sample_gz())])
    result, _ = run(anidb, client, cache_dir)
    assert client.calls == [("GET", anidb.ANIDB_URL)]
    assert result == CRAWLED_EXPECTED
    assert read_marker(cache_dir) == NOW


def test_failing_request_http_503(anidb, http, tmp_path):
    cache_dir = tmp_path / "anidb"
    client = FakeClient([http.HttpError(503, "https://anidb.net/api/anime-titles.xml.gz", 1)])
    result, logs = run(anidb, client, cache_dir)
    assert len(client.calls) == 1
    assert result == {}
    assert read_marker(cache_dir) == NOW
    assert not (cache_dir / DUMP_NAME).exists()
    assert any(line.startswith("warning: anidb skipped: ") for line in logs), logs


def test_failing_request_transport_error(anidb, http, tmp_path):
    cache_dir = tmp_path / "anidb"
    client = FakeClient([http.TransportError("connection reset")])
    result, logs = run(anidb, client, cache_dir)
    assert len(client.calls) == 1
    assert result == {}
    assert read_marker(cache_dir) == NOW
    assert not (cache_dir / DUMP_NAME).exists()
    assert any(line.startswith("warning: anidb skipped: ") for line in logs), logs


def test_failure_then_retry_within_24h_makes_no_request(anidb, http, tmp_path):
    cache_dir = tmp_path / "anidb"
    run(anidb, FakeClient([http.TransportError("down")]), cache_dir)
    client = FakeClient([])
    assert run(anidb, client, cache_dir, now=NOW + timedelta(hours=1))[0] == {}
    assert client.calls == []


def test_invalid_body_is_not_cached(anidb, http, tmp_path):
    cache_dir = tmp_path / "anidb"
    client = FakeClient([ok(http, b"<html>maintenance</html>")])
    result, logs = run(anidb, client, cache_dir)
    assert len(client.calls) == 1
    assert result == {}
    assert not (cache_dir / DUMP_NAME).exists()
    assert read_marker(cache_dir) == NOW
    assert any(line.startswith("warning: anidb skipped: ") for line in logs), logs


def test_before_not_before_makes_no_request(anidb, tmp_path):
    cache_dir = tmp_path / "anidb"
    client = FakeClient([])
    result, logs = run(anidb, client, cache_dir, now=anidb.NOT_BEFORE - timedelta(minutes=1))
    assert client.calls == []
    assert result == {}
    assert not (cache_dir / MARKER_NAME).exists()
    assert any(line.startswith("anidb skipped: not before ") for line in logs), logs


def test_exactly_not_before_makes_one_request(anidb, http, tmp_path):
    cache_dir = tmp_path / "anidb"
    client = FakeClient([ok(http, sample_gz())])
    result, _ = run(anidb, client, cache_dir, now=anidb.NOT_BEFORE)
    assert len(client.calls) == 1
    assert result == CRAWLED_EXPECTED


@pytest.mark.parametrize("marker", [
    b"not json",
    b"\xff\xfe\x00garbage",
    json.dumps({"at": "2026-11-05T00:00:00"}).encode("ascii"),  # no offset
    json.dumps({"at": (NOW + timedelta(hours=1)).isoformat()}).encode("ascii"),  # future
    json.dumps({"at": "yesterday"}).encode("ascii"),
    json.dumps({"when": "2026-11-01T00:00:00+00:00"}).encode("ascii"),
    json.dumps(["2026-11-01T00:00:00+00:00"]).encode("ascii"),
], ids=["not-json", "not-utf8", "no-offset", "future", "not-a-date", "no-at", "not-object"])
def test_corrupt_marker_blocks_the_request(anidb, tmp_path, marker):
    cache_dir = tmp_path / "anidb"
    write_marker(cache_dir, marker)
    client = FakeClient([])
    result, logs = run(anidb, client, cache_dir)
    assert client.calls == []
    assert result == {}
    assert any("warning" in line for line in logs), logs


def test_corrupt_cached_file_is_deleted_and_refetched(anidb, http, tmp_path):
    cache_dir = tmp_path / "anidb"
    cache_dir.mkdir()
    (cache_dir / DUMP_NAME).write_bytes(b"truncated garbage")
    body = sample_gz()
    client = FakeClient([ok(http, body)])
    result, logs = run(anidb, client, cache_dir)
    assert client.calls == [("GET", anidb.ANIDB_URL)]
    assert result == CRAWLED_EXPECTED
    assert (cache_dir / DUMP_NAME).read_bytes() == body
    assert any("warning" in line for line in logs), logs


def test_corrupt_cached_file_with_recent_marker_is_deleted_without_request(anidb, tmp_path):
    cache_dir = tmp_path / "anidb"
    write_marker(cache_dir, (NOW - timedelta(hours=2)).isoformat())
    (cache_dir / DUMP_NAME).write_bytes(gzip.compress(b"<animetitles><anime"))
    client = FakeClient([])
    result, _ = run(anidb, client, cache_dir)
    assert client.calls == []
    assert result == {}
    assert not (cache_dir / DUMP_NAME).exists()


def test_store_failure_still_returns_titles(anidb, http, tmp_path, monkeypatch):
    cache_dir = tmp_path / "anidb"

    def failing_put(self, key, data):
        raise OSError("disk full")

    monkeypatch.setattr(http.FileCache, "put", failing_put)
    client = FakeClient([ok(http, sample_gz())])
    result, logs = run(anidb, client, cache_dir)
    assert len(client.calls) == 1
    assert result == CRAWLED_EXPECTED
    assert not (cache_dir / DUMP_NAME).exists()
    assert any("warning" in line for line in logs), logs


def test_streaming_cap_in_load_korean(anidb, http, tmp_path, monkeypatch):
    cache_dir = tmp_path / "anidb"
    monkeypatch.setattr(anidb, "MAX_XML_BYTES", 64)
    client = FakeClient([ok(http, sample_gz())])
    result, _ = run(anidb, client, cache_dir)
    assert len(client.calls) == 1
    assert result == {}
    assert not (cache_dir / DUMP_NAME).exists()


def test_ordering_through_load_korean(anidb, http, tmp_path):
    cache_dir = tmp_path / "anidb"
    body = gzip.compress(animetitles(anime_xml(4, [
        ("syn", "ko", K4_SYN),
        ("short", "ko", K4_SHORT),
        ("official", "ko", K4_OFF),
    ])))
    client = FakeClient([ok(http, body)])
    result, _ = run(anidb, client, cache_dir, anime=[{"anilistId": 104, "anidbIds": [4]}])
    assert result == {104: [K4_OFF, K4_SYN, K4_SHORT]}
