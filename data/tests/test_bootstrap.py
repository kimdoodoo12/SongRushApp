"""Cycle 013 data-bootstrap acceptance tests (criteria 1-3)."""

import json
import socket
import urllib.request
from pathlib import Path

import pytest

FIXTURES_DIR = Path(__file__).resolve().parent / "fixtures"
FIXTURE_FILES = sorted(p for p in FIXTURES_DIR.iterdir() if p.is_file())
REPO_URL = "https://github.com/kimdoodoo12/SongRushApp"
UTF8_BOM = b"\xef\xbb\xbf"


# Criterion 1: package metadata. Imports stay inside the tests so that a
# missing package fails only these tests, not the whole module.

def test_version_is_non_empty_string():
    import songrush_data

    assert isinstance(songrush_data.__version__, str)
    assert songrush_data.__version__ != ""


def test_user_agent_names_bot_and_repo():
    from songrush_data import common

    assert common.USER_AGENT.startswith("SongRushBot/")
    assert REPO_URL in common.USER_AGENT


# Criterion 2: fixtures are UTF-8 JSON without a BOM.

def test_fixtures_dir_is_not_empty():
    assert FIXTURE_FILES, f"no fixture files found in {FIXTURES_DIR}"


@pytest.mark.parametrize("path", FIXTURE_FILES, ids=lambda p: p.name)
def test_fixture_is_utf8_json_without_bom(path):
    raw = path.read_bytes()
    assert not raw.startswith(UTF8_BOM), f"{path.name} starts with a UTF-8 BOM"
    json.loads(raw.decode("utf-8"))


# Criterion 3: no test can reach the network.

def test_getaddrinfo_is_blocked():
    with pytest.raises(RuntimeError, match="network disabled"):
        socket.getaddrinfo("example.com", 80)


def test_socket_connect_is_blocked():
    with socket.socket() as sock:
        with pytest.raises(RuntimeError, match="network disabled"):
            sock.connect(("10.0.0.1", 9))


def test_socket_connect_ex_is_blocked():
    with socket.socket() as sock:
        with pytest.raises(RuntimeError, match="network disabled"):
            sock.connect_ex(("10.0.0.1", 9))


def test_urlopen_is_blocked():
    with pytest.raises(RuntimeError, match="network disabled"):
        urllib.request.urlopen("http://example.com")
