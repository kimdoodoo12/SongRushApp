"""Cycle 015 title-rule acceptance tests (criteria 1-4).

The vectors in contracts/normalize-vectors.json are shared with the Java
backend. The titles module is imported inside each test so that the
vector-file checks still run when it is missing.
"""

import json
from pathlib import Path

import pytest

REPO_ROOT = Path(__file__).resolve().parents[2]
VECTORS_PATH = REPO_ROOT / "contracts" / "normalize-vectors.json"
BACKSLASH_U = chr(92) + "u"


def _load_vectors():
    return json.loads(VECTORS_PATH.read_text(encoding="utf-8"))


VECTORS = _load_vectors()["vectors"]


# Criterion 1: the vector file and normalize.

def test_vector_file_version_is_1():
    assert _load_vectors()["version"] == 1


def test_vector_file_has_at_least_40_pairs():
    vectors = _load_vectors()["vectors"]
    assert len(vectors) >= 40
    for v in vectors:
        assert set(v) == {"input", "expected"}
        assert isinstance(v["input"], str)
        assert isinstance(v["expected"], str)


def test_vector_inputs_have_no_literal_backslash_u():
    for v in _load_vectors()["vectors"]:
        assert BACKSLASH_U not in v["input"], v["input"]
        assert BACKSLASH_U not in v["expected"], v["expected"]


@pytest.mark.parametrize(
    "vector", VECTORS, ids=[f"v{i:03d}" for i in range(len(VECTORS))]
)
def test_normalize_vector(vector):
    from songrush_data.titles import normalize

    assert normalize(vector["input"]) == vector["expected"]


# Criterion 2: strip_qualifier.

STRIP_CASES = [
    ("Shingeki no Kyojin Season 3", "Shingeki no Kyojin"),
    ("Attack on Titan Final Season", "Attack on Titan"),
    ("Hunter x Hunter (2011)", "Hunter x Hunter"),
    ("나루토 (애니메이션)", "나루토"),
    ("귀멸의 칼날 2기", "귀멸의 칼날"),
    ("進撃の巨人 第2期", "進撃の巨人"),
    ("Re:Zero 2nd Season", "Re:Zero"),
    ("Attack on Titan: The Final Season", "Attack on Titan"),
    ("銀魂 第2期", "銀魂"),
    ("86 Season 2", "86 Season 2"),
    ("Monogatari Second Season", "Monogatari Second Season"),
    ("Steins;Gate 0", "Steins;Gate 0"),
    ("Mob Psycho 100", "Mob Psycho 100"),
    ("86", "86"),
    ("Kaguya-sama: Love is War", "Kaguya-sama: Love is War"),
    ("ABC Season 2", "ABC Season 2"),
]


@pytest.mark.parametrize("raw,expected", STRIP_CASES)
def test_strip_qualifier(raw, expected):
    from songrush_data.titles import strip_qualifier

    assert strip_qualifier(raw) == expected


# Criterion 3: has_hangul.

HANGUL_CASES = [
    ("귀멸", True),
    ("ㄱ", False),
    ("ガ", False),
    ("abc", False),
]


@pytest.mark.parametrize("s,expected", HANGUL_CASES)
def test_has_hangul(s, expected):
    from songrush_data.titles import has_hangul

    assert has_hangul(s) is expected


# Criterion 4: head.

HEAD_CASES = [
    ("Fate/Zero", "fate"),
    ("Demon Slayer: Kimetsu no Yaiba", "demonslayer"),
    ("Bakemonogatari", None),
    ("K: Return of Kings", None),
]


@pytest.mark.parametrize("s,expected", HEAD_CASES)
def test_head(s, expected):
    from songrush_data.titles import head

    assert head(s) == expected
