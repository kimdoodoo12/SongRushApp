"""Cycle 022 criteria 1-12: catalog assembly, draw set, Korean gaps, file writing.

docs/cycles/022-catalog-build.md is the specification. build_catalog is pure
and the writers go through FileCache into tmp_path, so every test is offline.
Imports of songrush_data.catalog stay inside fixtures so that a missing
module fails these tests, not the collection.
"""

import copy
import json
from pathlib import Path

import pytest

FIXTURES = Path(__file__).resolve().parent / "fixtures"
GQL_PAGE1_PATH = FIXTURES / "gql_page1.json"

ANIME_KEYS = {"id", "anilistId", "slug", "year", "season", "group", "titles", "reveal"}
THEME_KEYS = {"id", "animeId", "type", "slug", "song", "artists", "audio"}
AUDIO_KEYS = {"basename", "url", "size"}
GAP_KEYS = {"animeId", "anilistId", "popularity", "romaji", "english", "groupHasKorean"}
EMPTY_SLOTS = {"ko": [], "en": [], "ja": [], "romaji": []}

# Layout overhead of write_catalog: the version line, the opening and closing
# line of each of the four arrays, and the closing brace.
CATALOG_OVERHEAD_LINES = 1 + 2 * 4 + 1

ATTACK_KO = "진격의 거인"


@pytest.fixture
def catalog():
    from songrush_data import catalog as module

    return module


@pytest.fixture
def groups():
    from songrush_data import groups as module

    return module


# Input builders in the shapes the spec names.

def _theme(theme_id, basename=None, type_="OP", slug="OP1", song="Song", artists=("Artist",)):
    basename = basename or "Theme-%d.ogg" % theme_id
    return {
        "id": theme_id,
        "type": type_,
        "slug": slug,
        "song": song,
        "artists": list(artists),
        "audio": {
            "id": theme_id + 90000,
            "basename": basename,
            "url": "https://a.animethemes.moe/" + basename,
            "size": 1000 + theme_id,
        },
    }


def _anime(anime_id, anilist_id, themes=(), slug=None):
    return {
        "id": anime_id,
        "slug": slug or "anime-%d" % anime_id,
        "name": "Display Name %d" % anime_id,
        "year": 2020,
        "season": "SPRING",
        "anilistId": anilist_id,
        "anidbIds": [anime_id + 50000],
        "themes": list(themes),
    }


def _media(popularity, romaji=None, english=None, native=None, synonyms=(), relations=()):
    return {
        "popularity": popularity,
        "titles": {"romaji": romaji, "english": english, "native": native},
        "synonyms": list(synonyms),
        "relations": list(relations),
    }


def _edge(relation_type, node_id, romaji=None, english=None):
    return {
        "type": relation_type,
        "node": {
            "id": node_id,
            "type": "ANIME",
            "format": "TV",
            "titles": {"romaji": romaji, "english": english},
        },
    }


def _by_id(records):
    return {record["id"]: record for record in records}


def _gap_input():
    """A (ko), B (no ko, same group as A), C (no ko, alone), D outside the draw set."""
    anime = [
        _anime(1, 2001, [_theme(11)]),
        _anime(2, 2002, [_theme(21)]),
        _anime(3, 2003, [_theme(31)]),
        _anime(4, 2004, [_theme(41)]),
    ]
    media = {
        2001: _media(100, "Shingeki no Kyojin", "Attack on Titan",
                     relations=[_edge("SEQUEL", 2002, "Shingeki no Kyojin Season 2")]),
        2002: _media(50, "Shingeki no Kyojin Season 2", "Attack on Titan Season 2",
                     relations=[_edge("PREQUEL", 2001, "Shingeki no Kyojin")]),
        2003: _media(80, "Kanon"),
        2004: _media(1, "Clannad"),
    }
    korean = {2001: [ATTACK_KO]}
    return anime, media, {}, korean


def _report_input():
    """Two groups, one anime without AniList id, one theme with a duplicate basename."""
    anime = [
        _anime(1, 101, [_theme(11), _theme(12)]),
        _anime(2, 102, [_theme(21)]),
        _anime(3, None, [_theme(31)]),
        _anime(4, 104, [_theme(41, basename="Theme-11.ogg")]),
    ]
    media = {
        101: _media(30, "Shingeki no Kyojin", "Attack on Titan", native="進撃の巨人",
                    relations=[_edge("SEQUEL", 102, "Shingeki no Kyojin Season 2")]),
        102: _media(20, "Shingeki no Kyojin Season 2", "Attack on Titan Season 2",
                    relations=[_edge("PREQUEL", 101, "Shingeki no Kyojin")]),
        104: _media(10, "Kanon"),
    }
    wikidata = {101: {"ko": [ATTACK_KO], "en": [], "ja": []}}
    korean = {104: ["카논"]}
    return anime, media, wikidata, korean


# Criterion 1

def test_catalog_anime_record_has_exact_keys_and_titles(catalog, groups):
    anime = [_anime(100, 7, [_theme(1)], slug="kanon")]
    media = {7: _media(50, "Kanon", "Kanon (2006)", native="カノン")}
    built, _ = catalog.build_catalog(anime, media, {}, {})
    expected = groups.build_titles([7], media, {}, {})[7]
    assert built["anime"] == [{
        "id": 100,
        "anilistId": 7,
        "slug": "kanon",
        "year": 2020,
        "season": "SPRING",
        "group": 7,
        "titles": expected["slots"],
        "reveal": expected["reveal"],
    }]
    record = built["anime"][0]
    assert set(record) == ANIME_KEYS
    assert "popularity" not in record
    assert "name" not in record
    assert set(record["titles"]) == set(EMPTY_SLOTS)
    assert record["titles"]["romaji"] == ["Kanon"]


def test_catalog_anime_without_anilist_id(catalog):
    anime = [_anime(200, None, [_theme(2)])]
    built, _ = catalog.build_catalog(anime, {}, {}, {})
    assert built["anime"] == [{
        "id": 200,
        "anilistId": None,
        "slug": "anime-200",
        "year": 2020,
        "season": "SPRING",
        "group": None,
        "titles": EMPTY_SLOTS,
        "reveal": None,
    }]


def test_catalog_anime_sorted_by_id(catalog):
    anime = [_anime(9, 90, [_theme(1)]), _anime(2, 20, [_theme(2)]), _anime(5, None, [_theme(3)])]
    media = {90: _media(1, "Nine"), 20: _media(1, "Two")}
    built, _ = catalog.build_catalog(anime, media, {}, {})
    assert [a["id"] for a in built["anime"]] == [2, 5, 9]


def test_null_popularity_counts_as_zero(catalog):
    anime = [
        _anime(1, 11, [_theme(10)]),
        _anime(2, 12, [_theme(20)]),
        _anime(3, 13, [_theme(30)]),
    ]
    media = {
        11: _media(None, "Alpha Story"),
        12: _media(0, "Bravo Tale"),
        13: _media(1, "Charlie Song"),
    }
    built, report = catalog.build_catalog(anime, media, {}, {})
    # 13 (popularity 1) first; 11 (null, so 0) ties with 12 (0) and wins by anime id.
    assert built["drawSet"] == [30, 10, 20]
    assert "popularity" not in built["anime"][0]


# Criterion 2

def test_catalog_theme_record_shape(catalog):
    theme = _theme(77, basename="Kanon-OP1.ogg", song="Last regrets", artists=("Ayana",))
    anime = [_anime(100, 7, [theme])]
    built, _ = catalog.build_catalog(anime, {7: _media(5, "Kanon")}, {}, {})
    assert built["themes"] == [{
        "id": 77,
        "animeId": 100,
        "type": "OP",
        "slug": "OP1",
        "song": "Last regrets",
        "artists": ["Ayana"],
        "audio": {
            "basename": "Kanon-OP1.ogg",
            "url": "https://a.animethemes.moe/Kanon-OP1.ogg",
            "size": 1077,
        },
    }]
    record = built["themes"][0]
    assert set(record) == THEME_KEYS
    assert set(record["audio"]) == AUDIO_KEYS


def test_catalog_themes_sorted_by_theme_id(catalog):
    anime = [
        _anime(1, None, [_theme(40), _theme(10)]),
        _anime(2, None, [_theme(30), _theme(20)]),
    ]
    built, _ = catalog.build_catalog(anime, {}, {}, {})
    assert [t["id"] for t in built["themes"]] == [10, 20, 30, 40]
    assert [t["animeId"] for t in built["themes"]] == [1, 2, 2, 1]


# Criterion 3

def _draw_input():
    anime = [
        _anime(5, 50, [_theme(201), _theme(200)]),
        _anime(3, 30, [_theme(301), _theme(300)]),
        _anime(9, 90, [_theme(102, basename="Theme-300.ogg"), _theme(100)]),
    ]
    media = {
        50: _media(30, "Bravo Tale"),
        30: _media(30, "Alpha Story"),
        90: _media(10, "Charlie Song"),
    }
    return anime, media, {}, {}


def test_draw_set_order_and_basename_dedupe(catalog):
    built, report = catalog.build_catalog(*_draw_input())
    assert built["drawSet"] == [300, 301, 200, 201, 100]
    # The skipped duplicate stays in themes.
    assert 102 in {t["id"] for t in built["themes"]}
    assert report["counts"]["drawSet"] == 5


def test_draw_set_size_limit(catalog):
    built, report = catalog.build_catalog(*_draw_input(), draw_size=2)
    assert built["drawSet"] == [300, 301]
    assert report["counts"]["drawSet"] == 2
    assert report["counts"]["drawSetAnime"] == 1


def test_default_draw_size_constant(catalog):
    assert catalog.DRAW_SET_SIZE == 3000  # D-107 (cycle 089)
    assert catalog.CATALOG_VERSION == 1
    assert catalog.SIZE_CAP_BYTES == 12_000_000


# Cycle 089 (docs/cycles/089-draw-set-3000.md) criteria 2 and 3.

RANKED_ANIME = 320


def _theme_count(rank):
    return rank % 3 + 1


def _ranked_input():
    """RANKED_ANIME playable anime, one per series; rank r has popularity 10000 - r
    and _theme_count(r) themes, so draw-set order is rank order."""
    anime, media = [], {}
    for rank in range(RANKED_ANIME):
        anime_id = rank + 1
        anilist_id = 5000 + rank
        themes = [_theme(anime_id * 10 + k) for k in range(_theme_count(rank))]
        anime.append(_anime(anime_id, anilist_id, themes))
        media[anilist_id] = _media(10000 - rank, "Series %03d" % rank)
    return anime, media, {}, {}


def _ranked_themes(cut):
    return sum(_theme_count(rank) for rank in range(cut))


def test_draw_set_prefix_stable(catalog):
    full, _ = catalog.build_catalog(*_draw_input(), draw_size=5)
    for k in range(1, 5):
        built, _ = catalog.build_catalog(*_draw_input(), draw_size=k)
        assert full["drawSet"][:k] == built["drawSet"], k

    large, _ = catalog.build_catalog(*_ranked_input(), draw_size=500)
    for k in (1, 100, 199, 300):
        built, _ = catalog.build_catalog(*_ranked_input(), draw_size=k)
        assert len(built["drawSet"]) == k
        assert large["drawSet"][:k] == built["drawSet"], k


def test_anime_rank_themes(catalog):
    built, report = catalog.build_catalog(*_ranked_input(), draw_size=10_000)
    assert report["counts"]["drawSetAnime"] == RANKED_ANIME
    assert report["animeRankThemes"] == {"100": _ranked_themes(100), "300": _ranked_themes(300)}
    assert report["animeRankThemes"] == {"100": 199, "300": 600}


def test_anime_rank_themes_counts_only_draw_set_themes(catalog):
    # 150 themes cover fewer than the first 100 anime: both cuts count all 150.
    _, report = catalog.build_catalog(*_ranked_input(), draw_size=150)
    assert report["animeRankThemes"] == {"100": 150, "300": 150}
    # 400 themes reach past the first 100 anime but not the first 300.
    _, report = catalog.build_catalog(*_ranked_input(), draw_size=400)
    assert report["animeRankThemes"] == {"100": 199, "300": 400}


def test_anime_rank_themes_fewer_anime_than_cut(catalog):
    _, report = catalog.build_catalog(*_draw_input())
    assert report["counts"]["drawSetAnime"] == 3
    assert report["animeRankThemes"] == {"100": 5, "300": 5}
    _, report = catalog.build_catalog(*_draw_input(), draw_size=2)
    assert report["animeRankThemes"] == {"100": 2, "300": 2}


# Criterion 4

def test_unplayable_anime_not_in_draw_set(catalog):
    anime = [
        _anime(40, None, [_theme(400)]),
        _anime(41, 410, [_theme(410)]),
        _anime(42, 420, [_theme(420)]),
        _anime(43, 430, [_theme(430)]),
        _anime(44, None, []),
    ]
    media = {420: None, 430: _media(5, "Kanon")}
    built, report = catalog.build_catalog(anime, media, {}, {})
    assert built["drawSet"] == [430]
    assert report["unplayable"] == [40, 41, 42]
    assert {t["id"] for t in built["themes"]} == {400, 410, 420, 430}
    assert report["counts"]["playableAnime"] == 1


# Criterion 5

def test_one_group_entry_for_one_series(catalog, groups):
    anime, media, wikidata, korean = _gap_input()
    anime = anime[:2]
    built, report = catalog.build_catalog(anime, media, wikidata, korean)
    expected = groups.build_titles([2001, 2002], media, wikidata, korean)
    assert built["groups"] == [{"id": 2001, "accepted": expected[2001]["accepted"]}]
    assert [a["group"] for a in built["anime"]] == [2001, 2001]
    assert report["counts"]["groups"] == 1


def test_groups_sorted_by_id(catalog):
    anime, media, wikidata, korean = _gap_input()
    built, _ = catalog.build_catalog(anime, media, wikidata, korean)
    assert [g["id"] for g in built["groups"]] == [2001, 2003, 2004]
    for g in built["groups"]:
        assert set(g) == {"id", "accepted"}


# Criterion 6

def test_korean_gaps(catalog):
    built, report = catalog.build_catalog(*_gap_input(), draw_size=3)
    assert built["drawSet"] == [11, 31, 21]
    assert report["koreanGaps"] == [
        {
            "animeId": 3,
            "anilistId": 2003,
            "popularity": 80,
            "romaji": "Kanon",
            "english": None,
            "groupHasKorean": False,
        },
        {
            "animeId": 2,
            "anilistId": 2002,
            "popularity": 50,
            "romaji": "Shingeki no Kyojin Season 2",
            "english": "Attack on Titan Season 2",
            "groupHasKorean": True,
        },
    ]
    for gap in report["koreanGaps"]:
        assert set(gap) == GAP_KEYS


def test_korean_gaps_one_entry_per_anime(catalog):
    anime = [_anime(1, 10, [_theme(1), _theme(2), _theme(3)])]
    built, report = catalog.build_catalog(anime, {10: _media(5, "Kanon")}, {}, {})
    assert built["drawSet"] == [1, 2, 3]
    assert [g["animeId"] for g in report["koreanGaps"]] == [1]


def test_korean_gaps_ties_by_anime_id(catalog):
    anime = [_anime(8, 80, [_theme(1)]), _anime(6, 60, [_theme(2)])]
    media = {80: _media(5, "Kanon"), 60: _media(5, "Clannad")}
    _, report = catalog.build_catalog(anime, media, {}, {})
    assert [g["animeId"] for g in report["koreanGaps"]] == [6, 8]


# Criterion 7

def test_report_counts(catalog):
    _, report = catalog.build_catalog(*_report_input())
    assert report["counts"] == {
        "anime": 4,
        "themes": 5,
        "groups": 2,
        "playableAnime": 3,
        "drawSet": 3,
        "drawSetAnime": 2,
    }


def _alias_input(english=None, wiki_en=()):
    anime = [
        _anime(1, 11, [_theme(10)]),
        _anime(2, 12, [_theme(20)]),
    ]
    media = {
        11: _media(50, "Kanon", english),
        12: _media(10, "Clannad", "Clannad", synonyms=["Other"]),
    }
    media[11]["synonyms"] = ["Alias"]
    media[11]["titles"]["english"] = english
    wikidata = {11: {"ko": [], "en": list(wiki_en), "ja": []}}
    return anime, media, wikidata, {}


def test_english_from_synonyms_in_draw_set(catalog):
    built, report = catalog.build_catalog(*_alias_input())
    assert _by_id(built["anime"])[1]["titles"]["en"] == ["Alias"]
    assert report["englishFromSynonyms"] == {"catalog": 1, "drawSet": 1}


def test_english_from_synonyms_outside_draw_set(catalog):
    anime, media, wikidata, korean = _alias_input()
    media[11]["popularity"] = 1
    built, report = catalog.build_catalog(anime, media, wikidata, korean, draw_size=1)
    assert built["drawSet"] == [20]
    assert report["englishFromSynonyms"] == {"catalog": 1, "drawSet": 0}


def test_english_from_synonyms_zero_with_official_english(catalog):
    _, report = catalog.build_catalog(*_alias_input(english="Kanon"))
    assert report["englishFromSynonyms"] == {"catalog": 0, "drawSet": 0}


def test_english_from_synonyms_zero_with_wikidata_english(catalog):
    _, report = catalog.build_catalog(*_alias_input(wiki_en=("Kanon (TV)",)))
    assert report["englishFromSynonyms"] == {"catalog": 0, "drawSet": 0}


def _largest_groups_input():
    anime, media = [], {}
    # One group of 12 members (AniList 3001-3012); anime ids run the other way.
    media[3001] = _media(5, "Big Saga")
    for k in range(1, 13):
        anilist_id = 3000 + k
        anime.append(_anime(4000 - k, anilist_id, [_theme(10000 + k)]))
        if k > 1:
            media[anilist_id] = _media(5, "Big Saga %d" % k,
                                       relations=[_edge("PREQUEL", 3001, "Big Saga")])
    # One group of 2 members.
    media[5001] = _media(5, "Mid Story", relations=[_edge("SEQUEL", 5002, "Mid Story 2")])
    media[5002] = _media(5, "Mid Story 2")
    anime.append(_anime(5001, 5001, [_theme(20001)]))
    anime.append(_anime(5002, 5002, [_theme(20002)]))
    # 21 single-member groups, listed in reverse.
    for k in range(21, 0, -1):
        anilist_id = 6000 + k
        media[anilist_id] = _media(5, "Solo Title Number %d" % k)
        anime.append(_anime(anilist_id, anilist_id, [_theme(30000 + k)]))
    return anime, media, {}, {}


def test_largest_groups(catalog):
    _, report = catalog.build_catalog(*_largest_groups_input())
    largest = report["largestGroups"]
    assert len(largest) == 20
    for entry in largest:
        assert set(entry) == {"id", "size", "titles"}
    assert largest[0] == {
        "id": 3001,
        "size": 12,
        "titles": ["Big Saga"] + ["Big Saga %d" % k for k in range(2, 11)],
    }
    assert largest[1] == {"id": 5001, "size": 2, "titles": ["Mid Story", "Mid Story 2"]}
    assert [e["id"] for e in largest[2:]] == [6000 + k for k in range(1, 19)]
    assert all(e["size"] == 1 for e in largest[2:])
    assert largest[2]["titles"] == ["Solo Title Number 1"]
    assert report["counts"]["groups"] == 23


def test_largest_groups_fewer_than_twenty(catalog):
    _, report = catalog.build_catalog(*_report_input())
    assert [(e["id"], e["size"]) for e in report["largestGroups"]] == [(101, 2), (104, 1)]


# Criterion 8

def _reverse_dicts(value):
    if isinstance(value, dict):
        return {k: _reverse_dicts(value[k]) for k in reversed(list(value))}
    if isinstance(value, list):
        return [_reverse_dicts(v) for v in value]
    return value


def _reversed_input(anime, media, wikidata, korean):
    anime = copy.deepcopy(anime)
    for item in anime:
        item["themes"].reverse()
    anime.reverse()
    return (
        _reverse_dicts(anime),
        _reverse_dicts(copy.deepcopy(media)),
        _reverse_dicts(copy.deepcopy(wikidata)),
        _reverse_dicts(copy.deepcopy(korean)),
    )


@pytest.mark.parametrize("make_input", [_report_input, _gap_input, _largest_groups_input, _draw_input])
def test_build_is_order_independent(catalog, make_input):
    first = catalog.build_catalog(*make_input(), draw_size=4)
    second = catalog.build_catalog(*_reversed_input(*make_input()), draw_size=4)
    assert first[0] == second[0]
    assert first[1] == second[1]


def test_write_gives_identical_bytes_for_equal_catalogs(catalog, tmp_path):
    built_a, _ = catalog.build_catalog(*_report_input())
    built_b, _ = catalog.build_catalog(*_reversed_input(*_report_input()))
    path_a = tmp_path / "a" / "catalog.json"
    path_b = tmp_path / "b" / "catalog.json"
    catalog.write_catalog(path_a, built_a)
    catalog.write_catalog(path_b, built_b)
    assert path_a.read_bytes() == path_b.read_bytes()


# Criterion 9

def _record_line(record):
    return json.dumps(record, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def test_write_catalog_format(catalog, tmp_path):
    built, _ = catalog.build_catalog(*_report_input())
    path = tmp_path / "catalog.json"
    size = catalog.write_catalog(path, built)
    data = path.read_bytes()
    assert size == len(data)
    assert not data.startswith(b"\xef\xbb\xbf")
    text = data.decode("utf-8")
    assert "\r" not in text
    assert text.endswith("}\n")
    assert json.loads(text) == built
    assert ATTACK_KO in text
    assert "\\u" not in text

    elements = sum(len(built[key]) for key in ("anime", "themes", "groups", "drawSet"))
    assert elements > 0
    lines = text.split("\n")
    assert lines[-1] == ""
    lines = lines[:-1]
    assert len(lines) == elements + CATALOG_OVERHEAD_LINES

    expected = ['{"version": 1,']
    for i, key in enumerate(("anime", "drawSet", "groups", "themes")):
        expected.append('"%s": [' % key)
        records = [_record_line(r) for r in built[key]]
        expected.extend(r + "," for r in records[:-1])
        expected.extend(records[-1:])
        expected.append("]," if i < 3 else "]")
    expected.append("}")
    assert lines == expected


def test_write_catalog_empty_arrays(catalog, tmp_path):
    built, _ = catalog.build_catalog([], {}, {}, {})
    assert built == {"version": 1, "anime": [], "themes": [], "groups": [], "drawSet": []}
    path = tmp_path / "catalog.json"
    catalog.write_catalog(path, built)
    text = path.read_bytes().decode("utf-8")
    assert text == (
        '{"version": 1,\n'
        '"anime": [\n],\n'
        '"drawSet": [\n],\n'
        '"groups": [\n],\n'
        '"themes": [\n]\n'
        '}\n'
    )
    assert text.count("\n") == CATALOG_OVERHEAD_LINES
    assert json.loads(text) == built


# Criterion 10

def test_size_cap_raises_and_writes_nothing(catalog, tmp_path):
    built, _ = catalog.build_catalog(*_report_input())
    out_dir = tmp_path / "out"
    out_dir.mkdir()
    path = out_dir / "catalog.json"
    size = catalog.write_catalog(tmp_path / "probe" / "catalog.json", built)
    with pytest.raises(ValueError):
        catalog.write_catalog(path, built, size_cap=size - 1)
    assert not path.exists()
    assert list(out_dir.iterdir()) == []


def test_size_cap_equal_size_is_allowed(catalog, tmp_path):
    built, _ = catalog.build_catalog(*_report_input())
    size = catalog.write_catalog(tmp_path / "probe" / "catalog.json", built)
    path = tmp_path / "catalog.json"
    assert catalog.write_catalog(path, built, size_cap=size) == size
    assert path.exists()


# Criterion 11

def test_write_gaps(catalog, tmp_path):
    _, report = catalog.build_catalog(*_gap_input(), draw_size=3)
    gaps = report["koreanGaps"]
    assert len(gaps) == 2
    path = tmp_path / "korean_gaps.json"
    assert catalog.write_gaps(path, report) is None
    data = path.read_bytes()
    assert not data.startswith(b"\xef\xbb\xbf")
    text = data.decode("utf-8")
    assert "\r" not in text
    assert text.endswith("]\n")
    assert json.loads(text) == gaps
    lines = text.split("\n")[:-1]
    assert lines[0] == "["
    assert lines[-1] == "]"
    assert len(lines) == len(gaps) + 2
    for line in lines[1:-1]:
        assert json.loads(line.rstrip(",")) in gaps


# Criterion 12

def test_fixture_smoke(catalog):
    from songrush_data.animethemes import select_themes

    raw = json.loads(GQL_PAGE1_PATH.read_text(encoding="utf-8"))
    anime = [select_themes(item) for item in raw["data"]["animePagination"]["data"]]
    built, report = catalog.build_catalog(anime, {}, {}, {})
    with_themes = sorted(a["id"] for a in anime if a["themes"])
    assert len(with_themes) == 100
    assert report["unplayable"] == with_themes
    assert built["drawSet"] == []
    assert len(built["anime"]) == len(anime)
    assert len(built["themes"]) == sum(len(a["themes"]) for a in anime)
    assert report["counts"]["playableAnime"] == 0
    assert report["koreanGaps"] == []


# Cycle 023 criterion 6: the cycle 022 review items.
# docs/cycles/023-build-cli.md is the specification.

def test_draw_set_no_reveal_counts_anime_without_media(catalog):
    # AniList 20 has no media, so its reveal is None; the COMPILATION edge from
    # 10 joins it into a group with accepted titles, so it is playable.
    anime = [_anime(1, 10, [_theme(11)]), _anime(2, 20, [_theme(21)])]
    media = {10: _media(50, "Kanon", relations=[_edge("COMPILATION", 20, "Kanon Recap")])}
    built, report = catalog.build_catalog(anime, media, {}, {})
    records = _by_id(built["anime"])
    assert records[2]["reveal"] is None
    assert records[2]["group"] == records[1]["group"]
    assert 21 in built["drawSet"]
    assert report["drawSetNoReveal"] == 1
    assert set(report["counts"]) == {"anime", "themes", "groups", "playableAnime", "drawSet", "drawSetAnime"}


def test_draw_set_no_reveal_zero_when_every_reveal_is_set(catalog):
    built, report = catalog.build_catalog(*_report_input())
    assert built["drawSet"]
    assert report["drawSetNoReveal"] == 0


def test_korean_gaps_one_entry_per_anilist_id(catalog):
    anime = [
        _anime(5, 10, [_theme(51)]),
        _anime(3, 10, [_theme(31)]),
    ]
    media = {10: _media(40, "Kanon")}
    built, report = catalog.build_catalog(anime, media, {}, {})
    assert sorted(built["drawSet"]) == [31, 51]
    assert [(g["animeId"], g["anilistId"]) for g in report["koreanGaps"]] == [(3, 10)]


def test_size_cap_leaves_existing_catalog_unchanged(catalog, tmp_path):
    small, _ = catalog.build_catalog(*_draw_input())
    big, _ = catalog.build_catalog(*_report_input())
    path = tmp_path / "catalog.json"
    small_size = catalog.write_catalog(path, small)
    before = path.read_bytes()
    big_size = catalog.write_catalog(tmp_path / "probe" / "catalog.json", big)
    assert big_size > small_size
    with pytest.raises(ValueError):
        catalog.write_catalog(path, big, size_cap=big_size - 1)
    assert path.read_bytes() == before
    assert sorted(p.name for p in tmp_path.iterdir()) == ["catalog.json", "probe"]
