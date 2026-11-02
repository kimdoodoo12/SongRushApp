"""Cycle 016 criteria 7-8: FileCache and cached_json, on tmp_path.

FileCache takes its os.replace retry sleep as the keyword argument `sleep`
so tests never really sleep.
"""

import json
import os

import pytest


@pytest.fixture
def http():
    from songrush_data import http as module

    return module


def temp_files(root):
    return [p for p in root.rglob("*") if p.name.startswith("~tmp")]


# Criterion 7: FileCache.

def test_round_trip(http, tmp_path):
    cache = http.FileCache(tmp_path)
    assert cache.get("anilist-page-1.json") is None
    cache.put("anilist-page-1.json", b"\x00\x01payload")
    assert cache.get("anilist-page-1.json") == b"\x00\x01payload"
    cache.put("anilist-page-1.json", b"second")
    assert cache.get("anilist-page-1.json") == b"second"


def test_delete(http, tmp_path):
    cache = http.FileCache(tmp_path)
    cache.put("k1", b"x")
    cache.delete("k1")
    assert cache.get("k1") is None


def test_longest_valid_key(http, tmp_path):
    cache = http.FileCache(tmp_path)
    key = "a" * 120
    cache.put(key, b"ok")
    assert cache.get(key) == b"ok"


INVALID_KEYS = ["ABC", "Key.json", ".", "..", "a.", "nul", "com1.json", "lpt9", "con.txt", "a" * 121, "", "a/b", "a\\b", "~tmp-1"]


@pytest.mark.parametrize("key", INVALID_KEYS)
def test_invalid_key_put_raises(http, tmp_path, key):
    cache = http.FileCache(tmp_path)
    with pytest.raises(ValueError):
        cache.put(key, b"x")


@pytest.mark.parametrize("key", INVALID_KEYS)
def test_invalid_key_get_raises(http, tmp_path, key):
    cache = http.FileCache(tmp_path)
    with pytest.raises(ValueError):
        cache.get(key)


def test_clear_empty_prefix_raises(http, tmp_path):
    cache = http.FileCache(tmp_path)
    cache.put("a-1", b"1")
    with pytest.raises(ValueError):
        cache.clear("")
    assert cache.get("a-1") == b"1"


def test_clear_prefix_keeps_others(http, tmp_path):
    cache = http.FileCache(tmp_path)
    cache.put("a-1", b"1")
    cache.put("a-2", b"2")
    cache.put("b-1", b"3")
    assert cache.clear("a-") == 2
    assert cache.get("a-1") is None
    assert cache.get("a-2") is None
    assert cache.get("b-1") == b"3"


def test_put_leaves_no_temp_files(http, tmp_path):
    cache = http.FileCache(tmp_path)
    for i in range(5):
        cache.put("k-%d" % i, b"v" * (i + 1))
    assert temp_files(tmp_path) == []


def test_permission_error_on_first_replace_is_retried(http, tmp_path, monkeypatch):
    sleeps = []
    real_replace = os.replace
    state = {"calls": 0}

    def flaky_replace(src, dst):
        state["calls"] += 1
        if state["calls"] == 1:
            raise PermissionError("locked by scanner")
        return real_replace(src, dst)

    monkeypatch.setattr(os, "replace", flaky_replace)
    cache = http.FileCache(tmp_path, sleep=sleeps.append)
    cache.put("k", b"value")
    assert state["calls"] == 2
    assert sleeps == [0.1]
    assert cache.get("k") == b"value"
    assert temp_files(tmp_path) == []


def test_permission_error_every_time_cleans_up_and_reraises(http, tmp_path, monkeypatch):
    sleeps = []
    state = {"calls": 0}

    def locked_replace(src, dst):
        state["calls"] += 1
        raise PermissionError("locked by scanner")

    monkeypatch.setattr(os, "replace", locked_replace)
    cache = http.FileCache(tmp_path, sleep=sleeps.append)
    with pytest.raises(PermissionError):
        cache.put("k", b"value")
    assert state["calls"] == 4
    assert sleeps == [0.1, 0.3, 1.0]
    assert temp_files(tmp_path) == []
    assert cache.get("k") is None


# Criterion 8: cached_json.

class Fetch:
    def __init__(self, *payloads):
        self.payloads = list(payloads)
        self.calls = 0

    def __call__(self):
        self.calls += 1
        if not self.payloads:
            raise AssertionError("fetch called more times than expected")
        return self.payloads.pop(0)


def test_cached_json_valid_entry_does_not_fetch(http, tmp_path):
    cache = http.FileCache(tmp_path)
    cache.put("doc.json", b'{"a": 1}')
    fetch = Fetch()
    assert http.cached_json(cache, "doc.json", fetch) == {"a": 1}
    assert fetch.calls == 0


def test_cached_json_missing_entry_fetches_and_stores(http, tmp_path):
    cache = http.FileCache(tmp_path)
    fetch = Fetch(b'{"x": [1, 2]}')
    assert http.cached_json(cache, "doc.json", fetch) == {"x": [1, 2]}
    assert fetch.calls == 1
    assert json.loads(cache.get("doc.json")) == {"x": [1, 2]}
    # A second call is served from the cache.
    assert http.cached_json(cache, "doc.json", Fetch()) == {"x": [1, 2]}


def test_cached_json_corrupt_entry_is_refetched(http, tmp_path):
    cache = http.FileCache(tmp_path)
    cache.put("doc.json", b"{not json")
    fetch = Fetch(b'{"ok": true}')
    assert http.cached_json(cache, "doc.json", fetch) == {"ok": True}
    assert fetch.calls == 1
    assert json.loads(cache.get("doc.json")) == {"ok": True}


def test_cached_json_double_corruption_raises_with_key(http, tmp_path):
    cache = http.FileCache(tmp_path)
    cache.put("doc-broken.json", b"{not json")
    fetch = Fetch(b"<html>also not json")
    with pytest.raises(ValueError, match="doc-broken.json"):
        http.cached_json(cache, "doc-broken.json", fetch)
    assert fetch.calls == 1
