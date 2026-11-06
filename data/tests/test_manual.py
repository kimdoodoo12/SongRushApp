"""Cycle 028 criteria 1-5 and 8: the hand-edited Korean title file.

docs/cycles/028-manual-korean.md is the specification. Test files are written to
tmp_path as bytes, so each test controls the BOM, line endings and encoding.
The import of songrush_data.manual stays inside a fixture so that a missing
module fails these tests, not the collection.
"""

import json
import unicodedata
from pathlib import Path

import pytest

DATA_DIR = Path(__file__).resolve().parent.parent
COMMITTED = DATA_DIR / "korean_titles_manual.json"
BOM = b"\xef\xbb\xbf"


@pytest.fixture
def manual():
    from songrush_data import manual as module

    return module


@pytest.fixture
def titles():
    from songrush_data import titles as module

    return module


def _write(tmp_path, data, name="korean_titles_manual.json"):
    path = tmp_path / name
    path.write_bytes(data)
    return path


def _load(manual, path):
    logs = []
    result = manual.load_manual(path, log=logs.append)
    return result, [str(line) for line in logs]


# Criterion 1: a missing file.

def test_missing_file_returns_empty_and_logs(manual, tmp_path):
    path = tmp_path / "absent.json"
    result, logs = _load(manual, path)
    assert result == {}
    assert any("not found" in line for line in logs), logs


# Criterion 2: empty and comment-only files.

@pytest.mark.parametrize(
    "text",
    ["{}", '{"_how_to": "x", "_example": {"1": ["가"]}}'],
    ids=["empty", "comments-only"],
)
def test_empty_and_comment_only_files(manual, tmp_path, text):
    path = _write(tmp_path, text.encode("utf-8"))
    result, _ = _load(manual, path)
    assert result == {}


# Criterion 3: parsing.

PARSE_TEXT = '{"20464": ["하이큐!!", " 하이큐!! ", "하이큐!!"], "97940": ["블랙 클로버"]}'
PARSE_EXPECTED = {20464: ["하이큐!!"], 97940: ["블랙 클로버"]}


def test_parses_strips_and_dedupes(manual, tmp_path):
    path = _write(tmp_path, PARSE_TEXT.encode("utf-8"))
    result, logs = _load(manual, path)
    assert result == PARSE_EXPECTED
    assert any(line.startswith("manual korean: ") and "ids" in line for line in logs), logs


def test_parses_with_utf8_bom(manual, tmp_path):
    path = _write(tmp_path, BOM + PARSE_TEXT.encode("utf-8"))
    result, _ = _load(manual, path)
    assert result == PARSE_EXPECTED


def test_nfd_hangul_comes_back_nfc(manual, tmp_path):
    nfc = "블랙 클로버"
    nfd = unicodedata.normalize("NFD", nfc)
    assert nfd != nfc
    text = json.dumps({"97940": [nfd]}, ensure_ascii=False)
    path = _write(tmp_path, text.encode("utf-8"))
    result, _ = _load(manual, path)
    assert result == {97940: [nfc]}


# Criterion 4: errors name the file, the key and the problem.

@pytest.mark.parametrize(
    "data, key",
    [
        ('{"abc": ["가"]}'.encode("utf-8"), "abc"),
        ('{"123": "가"}'.encode("utf-8"), "123"),
        ('{"124": ["가", 5]}'.encode("utf-8"), "124"),
        ('{"125": ["Haikyuu"]}'.encode("utf-8"), "125"),
        ('{"1": ["가"], "1": ["나"]}'.encode("utf-8"), "1"),
        ('{"1": ["가"],\r\n}'.encode("utf-8"), None),
        (b'{"126": ["' + "가".encode("cp949") + b'"]}', None),
    ],
    ids=["key-not-digits", "value-not-list", "item-not-string", "no-hangul",
         "duplicate-key", "trailing-comma", "cp949-bytes"],
)
def test_invalid_file_raises_value_error(manual, tmp_path, data, key):
    path = _write(tmp_path, data, name="manual_broken.json")
    with pytest.raises(ValueError) as info:
        manual.load_manual(path, log=lambda line: None)
    message = str(info.value)
    assert "manual_broken.json" in message, message
    if key is not None:
        assert key in message, message


def test_duplicate_key_message_names_key(manual, tmp_path):
    path = _write(tmp_path, '{"777": ["가"], "777": ["나"]}'.encode("utf-8"), name="dup.json")
    with pytest.raises(ValueError) as info:
        manual.load_manual(path, log=lambda line: None)
    assert "777" in str(info.value)
    assert "dup.json" in str(info.value)


def test_trailing_comma_message_has_line(manual, tmp_path):
    path = _write(tmp_path, '{"1": ["가"],\r\n}'.encode("utf-8"), name="comma.json")
    with pytest.raises(ValueError) as info:
        manual.load_manual(path, log=lambda line: None)
    assert "line 2" in str(info.value), str(info.value)


# Criterion 5: merge_korean.

def test_merge_korean_order_and_dedupe(manual):
    merged = manual.merge_korean({1: ["가", "나"]}, {1: ["나", "다"], 2: ["라"]})
    assert merged == {1: ["가", "나", "다"], 2: ["라"]}


# Criterion 8: the committed file.

def test_committed_file_loads_empty_with_korean_how_to(manual, titles):
    assert COMMITTED.is_file(), COMMITTED
    result, _ = _load(manual, COMMITTED)
    assert result == {}
    document = json.loads(COMMITTED.read_bytes().decode("utf-8-sig"))
    assert isinstance(document.get("_how_to"), str)
    assert titles.has_hangul(document["_how_to"])
