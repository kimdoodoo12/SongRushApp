"""Cycle 021 criteria 1-12, 14, 15: series groups, title slots, accepted answers, reveal.

docs/cycles/021-groups-titles.md is the specification. build_titles is pure,
so every test runs on fixtures and inline dicts. Imports of
songrush_data.groups stay inside fixtures so that a missing module fails
these tests, not the collection.
"""

import copy
import json
from pathlib import Path

import pytest

from songrush_data.anilist import _normalize
from songrush_data.titles import normalize, strip_qualifier

FIXTURES = Path(__file__).resolve().parent / "fixtures"
RELATIONS_PATH = FIXTURES / "anilist_relations_franchises.json"
ID_IN_PATH = FIXTURES / "anilist_id_in.json"

SLOT_KEYS = {"ko", "en", "ja", "romaji"}


@pytest.fixture
def groups():
    from songrush_data import groups as module

    return module


def _load_relations():
    raw = json.loads(RELATIONS_PATH.read_text(encoding="utf-8"))
    return {record["id"]: _normalize(record) for record in raw}


def _fixture_inputs():
    media = _load_relations()
    return list(media), media, {}, {}


@pytest.fixture
def fixture_run(groups):
    catalog, media, wikidata, korean = _fixture_inputs()
    return groups.build_titles(catalog, media, wikidata, korean)


def _anime(romaji=None, english=None, native=None, synonyms=(), relations=()):
    return {
        "popularity": 1000,
        "titles": {"romaji": romaji, "english": english, "native": native},
        "synonyms": list(synonyms),
        "relations": list(relations),
    }


def _edge(relation_type, node_id, romaji=None, english=None, node_type="ANIME"):
    return {
        "type": relation_type,
        "node": {
            "id": node_id,
            "type": node_type,
            "format": "TV",
            "titles": {"romaji": romaji, "english": english},
        },
    }


def _check_shape(out, catalog):
    assert set(out) == set(catalog)
    for record in out.values():
        assert set(record) == {"group", "slots", "accepted", "reveal"}
        assert isinstance(record["group"], int)
        assert set(record["slots"]) == SLOT_KEYS
        assert record["accepted"] == sorted(set(record["accepted"]))


# Criterion 1

def test_fate_zero_and_stay_night_share_group(fixture_run):
    _check_shape(fixture_run, fixture_run.keys())
    assert fixture_run[10087]["group"] == fixture_run[356]["group"]


# Criterion 2

def test_bakemonogatari_nisemonogatari_koyomimonogatari_separate(fixture_run):
    assert fixture_run[5081]["group"] != fixture_run[11597]["group"]
    assert fixture_run[21520]["group"] != fixture_run[5081]["group"]


# Criterion 3

def test_attack_on_titan_movies_join_series(fixture_run):
    assert fixture_run[20691]["group"] == fixture_run[16498]["group"]
    assert fixture_run[20692]["group"] == fixture_run[16498]["group"]


def test_re_zero_ovas_join_series(fixture_run):
    assert fixture_run[100049]["group"] == fixture_run[21355]["group"]


# Criterion 4

def test_kimetsu_accepts_titles_not_partial_or_synonyms(groups):
    catalog, media, wikidata, korean = _fixture_inputs()
    body = json.loads(ID_IN_PATH.read_text(encoding="utf-8"))
    raw = [m for m in body["data"]["Page"]["media"] if m["id"] == 101922][0]
    assert "KnY" in raw["synonyms"]
    assert "Demon Slayer: Kimetsu no Yaiba: Bonds of Siblings" in raw["synonyms"]
    media[101922] = _normalize(raw)

    out = groups.build_titles(catalog, media, wikidata, korean)
    accepted = out[101922]["accepted"]
    assert normalize("Kimetsu no Yaiba") in accepted
    assert normalize("Demon Slayer: Kimetsu no Yaiba") in accepted
    assert normalize("Demon Slayer") not in accepted
    assert normalize("KnY") not in accepted


# Criterion 5

def test_attack_on_titan_season_3_accepts_series_title(fixture_run):
    assert normalize("Attack on Titan") in fixture_run[99147]["accepted"]


# Criterion 6

def test_synonyms_never_enter_slot_with_official_title(groups):
    media = {21: _anime("ONE PIECE", "One Piece", "ONE PIECE", ["OP", "ワンピ"])}
    out = groups.build_titles([21], media, {}, {})
    record = out[21]
    assert "op" not in record["accepted"]
    assert normalize("One Piece") in record["accepted"]
    assert "OP" not in record["slots"]["en"]
    assert "OP" not in record["slots"]["romaji"]
    assert "ワンピ" not in record["slots"]["ja"]


def test_latin_alias_fills_empty_english_slot(groups):
    synonyms = ["Latin Alias", "日本語別名", "한글별명"]
    media = {30: _anime("Romaji Title", None, None, synonyms)}
    out = groups.build_titles([30], media, {}, {})
    record = out[30]
    assert record["slots"]["en"] == ["Latin Alias"]
    assert record["slots"]["romaji"] == ["Romaji Title"]
    assert normalize("Latin Alias") in record["accepted"]


# Criterion 7

def test_accepted_strings_come_from_group_sources(fixture_run):
    catalog, media, _, _ = _fixture_inputs()
    out = fixture_run
    by_group = {}
    for anime_id in catalog:
        by_group.setdefault(out[anime_id]["group"], []).append(anime_id)

    for gid, members in by_group.items():
        sources = set()
        for member in members:
            for slot in out[member]["slots"].values():
                sources.update(slot)
            for edge in (media[member] or {}).get("relations", []):
                node = edge["node"]
                if node.get("type") != "ANIME":
                    continue
                for key in ("romaji", "english"):
                    if node["titles"].get(key):
                        sources.add(node["titles"][key])
        allowed = set()
        for s in sources:
            allowed.add(normalize(s))
            allowed.add(normalize(strip_qualifier(s)))
        allowed.discard("")
        first = out[members[0]]["accepted"]
        for member in members:
            accepted = out[member]["accepted"]
            assert accepted == first, (gid, member)
            assert "" not in accepted
            extra = set(accepted) - allowed
            assert not extra, (gid, member, sorted(extra))


# Criterion 8

def test_compilation_edge_joins_different_titles(groups):
    media = {
        1: _anime("Alpha Story", relations=[_edge("COMPILATION", 2, "Totally Different")]),
        2: _anime("Totally Different"),
    }
    out = groups.build_titles([1, 2], media, {}, {})
    assert out[1]["group"] == out[2]["group"] == 1


def test_sequel_edge_with_different_titles_stays_separate(groups):
    media = {
        1: _anime("Alpha Story", relations=[_edge("SEQUEL", 2, "Totally Different")]),
        2: _anime("Totally Different"),
    }
    out = groups.build_titles([1, 2], media, {}, {})
    assert out[1]["group"] == 1
    assert out[2]["group"] == 2


def test_character_edge_is_ignored(groups):
    media = {
        1: _anime("Fate/A", relations=[_edge("CHARACTER", 2, "Fate/B")]),
        2: _anime("Fate/B"),
    }
    out = groups.build_titles([1, 2], media, {}, {})
    assert out[1]["group"] == 1
    assert out[2]["group"] == 2


# Criterion 9

def test_out_of_catalog_node_links_catalog_anime(groups):
    media = {
        10: _anime("Gamma Saga", relations=[_edge("SEQUEL", 12, "Gamma Saga 2")]),
        11: _anime("Gamma Saga Final", relations=[_edge("PREQUEL", 12, "Gamma Saga 2")]),
    }
    out = groups.build_titles([10, 11], media, {}, {})
    assert out[10]["group"] == out[11]["group"] == 10
    assert normalize("Gamma Saga 2") in out[10]["accepted"]
    assert out[10]["accepted"] == out[11]["accepted"]
    assert 12 not in out


# Criterion 10

def test_korean_slot_keeps_hangul_wikidata_then_korean(groups):
    media = {20: _anime("Naruto", "Naruto", "NARUTO -ナルト-")}
    wikidata = {20: {"ko": ["나루토 (애니메이션)", "Naruto"], "en": [], "ja": []}}
    korean = {20: ["나루토", "나루토 질풍전"]}
    out = groups.build_titles([20], media, wikidata, korean)
    record = out[20]
    assert record["slots"]["ko"] == ["나루토 (애니메이션)", "나루토", "나루토 질풍전"]
    assert normalize("나루토") in record["accepted"]
    assert normalize("나루토 질풍전") in record["accepted"]


# Criterion 11

def test_reveal_skips_episode_label_and_strips_animation(groups):
    media = {40: _anime("Shingeki no Kyojin", "Attack on Titan")}
    wikidata = {40: {"ko": ["진격의 거인 1화", "진격의 거인 (애니메이션)"], "en": [], "ja": []}}
    out = groups.build_titles([40], media, wikidata, {})
    assert out[40]["reveal"] == "진격의 거인"


def test_reveal_uses_korean_strings_without_ko_labels(groups):
    media = {41: _anime("Kimetsu no Yaiba", "Demon Slayer: Kimetsu no Yaiba")}
    out = groups.build_titles([41], media, {}, {41: ["귀멸의 칼날"]})
    assert out[41]["reveal"] == "귀멸의 칼날"


def test_reveal_falls_back_to_english_then_romaji(groups):
    media = {
        42: _anime("Romaji Name", "English Title"),
        43: _anime("Romaji Title", None),
    }
    out = groups.build_titles([42, 43], media, {}, {})
    assert out[42]["reveal"] == "English Title"
    assert out[43]["reveal"] == "Romaji Title"


def test_reveal_without_anilist_uses_any_slot(groups):
    wikidata = {44: {"ko": [], "en": ["Only English"], "ja": []}}
    out = groups.build_titles([44], {44: None}, wikidata, {})
    assert out[44]["reveal"] == "Only English"
    assert out[44]["slots"]["en"] == ["Only English"]


def test_reveal_none_without_any_data(groups):
    out = groups.build_titles([45], {}, {}, {})
    record = out[45]
    assert record["reveal"] is None
    assert record["group"] == 45
    assert record["accepted"] == []
    assert record["slots"] == {"ko": [], "en": [], "ja": [], "romaji": []}


# Criterion 12

def test_output_is_deterministic(groups):
    catalog, media, wikidata, korean = _fixture_inputs()
    first = groups.build_titles(catalog, copy.deepcopy(media), wikidata, korean)
    second = groups.build_titles(catalog, copy.deepcopy(media), wikidata, korean)
    assert first == second

    reversed_media = {}
    for anime_id in reversed(list(media)):
        record = copy.deepcopy(media[anime_id])
        record["relations"] = list(reversed(record["relations"]))
        reversed_media[anime_id] = record
    third = groups.build_titles(list(reversed(catalog)), reversed_media, wikidata, korean)
    assert third == first


def test_group_id_is_smallest_catalog_id(fixture_run):
    by_group = {}
    for anime_id, record in fixture_run.items():
        by_group.setdefault(record["group"], []).append(anime_id)
    for gid, members in by_group.items():
        assert gid == min(members), (gid, sorted(members))


# Criterion 14

@pytest.mark.parametrize(
    "ids",
    [
        (21262, 21745),
        (21399, 21400, 181970),
        (11757, 21403, 100182),
        (6213, 104462),
        (104454, 194447),
        (20464, 106625),
    ],
)
def test_series_that_must_join(fixture_run, ids):
    assert len({fixture_run[i]["group"] for i in ids}) == 1, [
        (i, fixture_run[i]["group"]) for i in ids
    ]


def test_joined_series_accept_series_title(fixture_run):
    assert normalize("Kizumonogatari I: Tekketsu-hen") in fixture_run[21400]["accepted"]
    assert normalize("Kizumonogatari") not in fixture_run[21400]["accepted"]
    assert normalize("Sword Art Online") in fixture_run[100182]["accepted"]


# Criterion 15

@pytest.mark.parametrize(
    "a, b",
    [
        (4654, 6213),
        (104454, 21355),
        (5081, 17074),
        (5081, 11597),
        (5081, 21520),
    ],
)
def test_series_that_must_stay_separate(fixture_run, a, b):
    assert fixture_run[a]["group"] != fixture_run[b]["group"]
