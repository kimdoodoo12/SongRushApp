"""Catalog assembly, draw set, Korean gaps and the catalog file (cycle 022).

docs/cycles/022-catalog-build.md is the specification. build_catalog is pure:
no I/O, and the output depends only on the input values, not on list or dict
ordering. The writers go through FileCache, so each file is replaced
atomically. Equal catalogs give identical bytes (no timestamps).
"""

import json
from pathlib import Path

from songrush_data.groups import build_titles
from songrush_data.http import FileCache

CATALOG_VERSION = 1
DRAW_SET_SIZE = 1500  # D-019, D-027, D-034
SIZE_CAP_BYTES = 12_000_000

_EMPTY_SLOTS = ("ko", "en", "ja", "romaji")
_LARGEST_GROUPS = 20
_LARGEST_GROUP_TITLES = 10


def _popularity(anilist_id, media) -> int:
    """AniList popularity, or 0 without an id, media or a value (design 014 decision 6)."""
    if anilist_id is None:
        return 0
    value = (media.get(anilist_id) or {}).get("popularity")
    return value if isinstance(value, int) else 0


def _theme_record(theme: dict, anime_id) -> dict:
    audio = theme.get("audio") or {}
    return {
        "id": theme.get("id"),
        "animeId": anime_id,
        "type": theme.get("type"),
        "slug": theme.get("slug"),
        "song": theme.get("song"),
        "artists": list(theme.get("artists") or []),
        "audio": {
            "basename": audio.get("basename"),
            "url": audio.get("url"),
            "size": audio.get("size"),
        },
    }


def _first(strings):
    return strings[0] if strings else None


def _english_from_synonyms(record: dict, media, wikidata) -> bool:
    """The en slot is filled only by the D-035 alias fallback."""
    anilist_id = record["anilistId"]
    if anilist_id is None or not record["titles"]["en"]:
        return False
    titles = (media.get(anilist_id) or {}).get("titles") or {}
    if titles.get("english"):
        return False
    labels = (wikidata.get(anilist_id) or {}).get("en") or []
    return not any(isinstance(s, str) and s for s in labels)


def build_catalog(anime, media, wikidata, korean, *, draw_size=DRAW_SET_SIZE) -> tuple[dict, dict]:
    """Return (catalog, report) for the selected AnimeThemes anime."""
    anime = sorted(anime, key=lambda a: a["id"])
    titles = build_titles(
        [a["anilistId"] for a in anime if a.get("anilistId") is not None],
        media, wikidata, korean,
    )

    records, themes, popularity = [], [], {}
    group_accepted = {}
    for item in anime:
        anilist_id = item.get("anilistId")
        entry = titles.get(anilist_id) if anilist_id is not None else None
        if entry is None:
            group, slots, reveal = None, {key: [] for key in _EMPTY_SLOTS}, None
        else:
            group, slots, reveal = entry["group"], entry["slots"], entry["reveal"]
            group_accepted.setdefault(group, entry["accepted"])
        records.append({
            "id": item["id"],
            "anilistId": anilist_id,
            "slug": item.get("slug"),
            "year": item.get("year"),
            "season": item.get("season"),
            "group": group,
            "titles": slots,
            "reveal": reveal,
        })
        popularity[item["id"]] = _popularity(anilist_id, media)
        for theme in item.get("themes") or []:
            themes.append(_theme_record(theme, item["id"]))
    themes.sort(key=lambda t: t["id"])

    groups = [{"id": gid, "accepted": group_accepted[gid]} for gid in sorted(group_accepted)]
    by_id = {r["id"]: r for r in records}
    playable = {r["id"] for r in records if r["group"] is not None and group_accepted[r["group"]]}
    with_themes = {t["animeId"] for t in themes}

    # Draw set: popularity descending, then anime id, then theme id; one theme per basename.
    candidates = sorted(
        (t for t in themes if t["animeId"] in playable),
        key=lambda t: (-popularity[t["animeId"]], t["animeId"], t["id"]),
    )
    draw_set, seen = [], set()
    for theme in candidates:
        if len(draw_set) >= draw_size:
            break
        basename = theme["audio"]["basename"]
        if basename in seen:
            continue
        seen.add(basename)
        draw_set.append(theme["id"])
    theme_anime = {t["id"]: t["animeId"] for t in themes}
    draw_anime = {theme_anime[tid] for tid in draw_set}

    catalog = {
        "version": CATALOG_VERSION,
        "anime": records,
        "themes": themes,
        "groups": groups,
        "drawSet": draw_set,
    }

    groups_with_korean = {}
    for r in records:
        if r["group"] is not None and r["titles"]["ko"]:
            groups_with_korean.setdefault(r["group"], set()).add(r["id"])
    korean_gaps, gap_anilist = [], set()
    for anime_id in sorted(draw_anime, key=lambda a: (-popularity[a], a)):
        r = by_id[anime_id]
        if r["titles"]["ko"]:
            continue
        # One entry per AniList id: the first in this order wins (cycle 023).
        if r["anilistId"] is not None:
            if r["anilistId"] in gap_anilist:
                continue
            gap_anilist.add(r["anilistId"])
        others = groups_with_korean.get(r["group"], set()) - {anime_id}
        korean_gaps.append({
            "animeId": anime_id,
            "anilistId": r["anilistId"],
            "popularity": popularity[anime_id],
            "romaji": _first(r["titles"]["romaji"]),
            "english": _first(r["titles"]["en"]),
            "groupHasKorean": bool(others),
        })

    members = {}
    for r in records:
        if r["group"] is not None:
            members.setdefault(r["group"], []).append(r)
    largest = sorted(members, key=lambda gid: (-len(members[gid]), gid))[:_LARGEST_GROUPS]
    largest_groups = []
    for gid in largest:
        ordered = sorted(members[gid], key=lambda r: (r["anilistId"], r["id"]))
        largest_groups.append({
            "id": gid,
            "size": len(ordered),
            "titles": [r["reveal"] for r in ordered[:_LARGEST_GROUP_TITLES]],
        })

    from_synonyms = {r["id"] for r in records if _english_from_synonyms(r, media, wikidata)}

    report = {
        "counts": {
            "anime": len(records),
            "themes": len(themes),
            "groups": len(groups),
            "playableAnime": len(playable),
            "drawSet": len(draw_set),
            "drawSetAnime": len(draw_anime),
        },
        "koreanGaps": korean_gaps,
        "unplayable": sorted(with_themes - playable),
        "englishFromSynonyms": {
            "catalog": len(from_synonyms),
            "drawSet": len(from_synonyms & draw_anime),
        },
        "largestGroups": largest_groups,
        "drawSetNoReveal": sum(1 for anime_id in draw_anime if by_id[anime_id]["reveal"] is None),
    }
    return catalog, report


def _line(value) -> str:
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def _array_lines(items) -> list[str]:
    lines = [_line(item) + "," for item in items]
    if lines:
        lines[-1] = lines[-1][:-1]
    return lines


def write_catalog(path, catalog, *, size_cap=SIZE_CAP_BYTES) -> int:
    """Write the catalog one element per line; return the byte count."""
    path = Path(path)
    keys = sorted(k for k in catalog if k != "version")
    lines = ['{"version": %s,' % _line(catalog["version"])]
    for i, key in enumerate(keys):
        lines.append(_line(key) + ": [")
        lines.extend(_array_lines(catalog[key]))
        lines.append("]," if i < len(keys) - 1 else "]")
    lines.append("}")
    data = ("\n".join(lines) + "\n").encode("utf-8")
    if len(data) > size_cap:
        raise ValueError("catalog is %d bytes, over the cap of %d" % (len(data), size_cap))
    FileCache(path.parent).put(path.name, data)
    return len(data)


def write_gaps(path, report) -> None:
    """Write report["koreanGaps"] one entry per line."""
    path = Path(path)
    lines = ["["] + _array_lines(report["koreanGaps"]) + ["]"]
    FileCache(path.parent).put(path.name, ("\n".join(lines) + "\n").encode("utf-8"))
