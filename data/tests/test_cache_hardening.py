"""Cycle 016 attempt 2 (N2): FileCache and cached_json hardening, on tmp_path.

cached_json validates before it stores, a fetch that raises leaves no
corrupt entry behind, clear never removes temp files, and delete validates
its key.
"""

import pytest


@pytest.fixture
def http():
    from songrush_data import http as module

    return module


def temp_files(root):
    return [p for p in root.rglob("*") if p.name.startswith("~tmp")]


def test_cached_json_invalid_fetch_stores_nothing(http, tmp_path):
    cache = http.FileCache(tmp_path)
    with pytest.raises(ValueError):
        http.cached_json(cache, "doc.json", lambda: b"{not json")
    assert cache.get("doc.json") is None
    assert not (tmp_path / "doc.json").exists()
    assert temp_files(tmp_path) == []


def test_cached_json_corrupt_entry_and_failing_fetch(http, tmp_path):
    cache = http.FileCache(tmp_path)
    cache.put("doc.json", b"{corrupt")

    class FetchFailed(Exception):
        pass

    def fetch():
        raise FetchFailed("source down")

    with pytest.raises(FetchFailed):
        http.cached_json(cache, "doc.json", fetch)
    assert cache.get("doc.json") is None
    assert temp_files(tmp_path) == []


@pytest.mark.parametrize("prefix", ["~", "~tmp", "~tmp-", "a", "d"])
def test_clear_never_removes_temp_files(http, tmp_path, prefix):
    cache = http.FileCache(tmp_path)
    cache.put("a-1", b"x")
    cache.put("doc.json", b"y")
    stray = tmp_path / "~tmp-0123456789abcdef"
    stray.write_bytes(b"partial")
    try:
        cache.clear(prefix)
    except ValueError:
        # Rejecting a prefix that can never match a key is also acceptable.
        pass
    assert stray.exists()
    assert stray.read_bytes() == b"partial"


@pytest.mark.parametrize("key", ["BAD KEY", "../escape", "~tmp-1", ""])
def test_delete_invalid_key_raises(http, tmp_path, key):
    cache = http.FileCache(tmp_path)
    with pytest.raises(ValueError):
        cache.delete(key)
