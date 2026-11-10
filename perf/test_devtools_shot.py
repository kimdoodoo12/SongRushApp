"""Unit tests for the pure helpers of perf/devtools_shot.py (cycle 104, D-115).

Run from the repo root: data/.venv/Scripts/python.exe -m pytest perf
pytest's default "prepend" import mode puts perf/ on sys.path, so the module
is imported as plain `devtools_shot`. No test here starts a browser or
touches the network.

Contract (see docs/cycles/104-devtools-captures.md, Acceptance 1):
    edge_args(profile, port, url) -> list[str]
    shot_paths(label) -> dict[str, path-like]
    deep_text_has_secret(texts: list[str]) -> bool   (uses perflib.contains_secret)
    FONT_FILTER: str
    parse_args(argv: list[str]) -> argparse.Namespace (base, views, label)
"""

import pytest

import devtools_shot as ds
import perflib

ELLIPSIS = chr(0x2026)


# --- edge_args ---------------------------------------------------------------

def test_edge_args_has_each_flag_once_and_url_last():
    url = "https://animeopguessr.com/"
    args = ds.edge_args("C:/tmp/profile", 9333, url)
    required = [
        "--remote-debugging-port=9333",
        "--user-data-dir=C:/tmp/profile",
        "--auto-open-devtools-for-tabs",
        "--disable-extensions",
        "--no-first-run",
        "--lang=ko-KR",
    ]
    for flag in required:
        assert args.count(flag) == 1, flag
    assert args[-1] == url


# --- shot_paths --------------------------------------------------------------

# cycle 107: declared change (between view)
DEVTOOLS_KEYS = ["pageload", "choice-loading", "choice-done",
                 "chat-loading", "chat-done",
                 "between-reveal", "between-done"]
GAME_KEYS = ["game-choice-loading", "game-choice-done",
             "game-chat-loading", "game-chat-done",
             "game-between-reveal"]


def test_shot_paths_names():
    paths = ds.shot_paths("run7")
    assert set(paths) == set(DEVTOOLS_KEYS + GAME_KEYS)
    for key in DEVTOOLS_KEYS:
        got = str(paths[key]).replace("\\", "/")
        assert got.endswith(f"perf/results/shots/run7-devtools-{key}.png"), got
    for key in GAME_KEYS:
        got = str(paths[key]).replace("\\", "/")
        assert got.endswith(f"perf/results/shots/run7-{key}.png"), got


# --- deep_text_has_secret ----------------------------------------------------

PRESIGNED_NAME = "abc?X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-Credential=x"

SECRET_SAMPLES = [
    [PRESIGNED_NAME],
    ["ordinary", "a.ogg?Signature=deadbeef"],
    ["Credential=AK%2F1"],
    ["X-Amz-Date"],
]
CLEAN_SAMPLES = [
    ["abc?" + ELLIPSIS],
    ["Network", "Disable cache", "200", "audio/ogg"],
    [],
]


@pytest.mark.parametrize("texts", SECRET_SAMPLES)
def test_deep_text_has_secret_true(texts):
    assert ds.deep_text_has_secret(texts) is True


@pytest.mark.parametrize("texts", CLEAN_SAMPLES)
def test_deep_text_has_secret_false(texts):
    assert ds.deep_text_has_secret(texts) is False


@pytest.mark.parametrize("texts", SECRET_SAMPLES + CLEAN_SAMPLES)
def test_deep_text_has_secret_agrees_with_perflib(texts):
    expected = any(perflib.contains_secret(t) for t in texts)
    assert ds.deep_text_has_secret(texts) == expected


# --- FONT_FILTER -------------------------------------------------------------

def test_font_filter():
    assert ds.FONT_FILTER == "-domain:fonts.gstatic.com -domain:fonts.googleapis.com"


# --- parse_args --------------------------------------------------------------

def test_parse_args_defaults():
    ns = ds.parse_args(["--label", "x"])
    assert ns.label == "x"
    assert ns.base == "https://animeopguessr.com/"
    # cycle 107: declared change (between view)
    assert ns.views == ["pageload", "choice", "chat", "between"]


def test_parse_args_views_subset():
    ns = ds.parse_args(["--label", "x", "--views", "choice,chat"])
    assert ns.views == ["choice", "chat"]


# cycle 107: declared change (between view)
def test_parse_args_accepts_between_view():
    ns = ds.parse_args(["--label", "x", "--views", "between"])
    assert ns.views == ["between"]
    assert "between" in ds.VIEWS


@pytest.mark.parametrize("argv", [
    [],                                    # --label is required
    ["--label", "x", "--views", "foo"],    # unknown view
    ["--label", "x", "stray"],             # bare positional argument
])
def test_parse_args_rejects(argv):
    with pytest.raises(SystemExit):
        ds.parse_args(argv)
