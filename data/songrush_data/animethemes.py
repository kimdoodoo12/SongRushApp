"""AnimeThemes GraphQL crawl and theme selection (cycle 017).

fetch_page posts the saved page query with a literal page number, validates
the response and caches it. crawl walks every page with resume, order,
duplicate and total checks. select_themes turns one raw anime item into a
plain record with one chosen audio file per OP/ED theme.
docs/cycles/017-animethemes-crawl.md is the specification.
"""

import hashlib
import json
from dataclasses import dataclass
from pathlib import Path
from typing import Callable

from songrush_data.http import FileCache, PoliteClient

QUERY_PATH = Path(__file__).resolve().parent / "animethemes.graphql"
ANIMETHEMES_URL = "https://graphql.animethemes.moe/"
PER_PAGE = 100


@dataclass
class CrawlResult:
    items: list
    pages: int
    total: int | None


def _template() -> str:
    return QUERY_PATH.read_bytes().decode("utf-8").replace("\r\n", "\n")


def _key_prefix() -> str:
    digest = hashlib.sha256(_template().encode("utf-8")).hexdigest()[:16]
    return "at-%s-" % digest


def _page_key(page: int) -> str:
    return "%sp%d.json" % (_key_prefix(), page)


def validate_page(body, page: int) -> dict:
    """Return data.animePagination of a response body, or raise ValueError."""
    if not isinstance(body, dict):
        raise ValueError("page %d: response is not a JSON object" % page)
    if body.get("errors"):
        raise ValueError("page %d: GraphQL errors: %r" % (page, body["errors"]))
    data = body.get("data")
    pagination = data.get("animePagination") if isinstance(data, dict) else None
    if not isinstance(pagination, dict):
        raise ValueError("page %d: data.animePagination is missing" % page)
    info = pagination.get("paginatorInfo")
    if not isinstance(info, dict):
        raise ValueError("page %d: paginatorInfo is missing" % page)
    if info.get("currentPage") != page:
        raise ValueError("page %d: response has currentPage %r" % (page, info.get("currentPage")))
    if info.get("perPage") != PER_PAGE:
        raise ValueError("page %d: response has perPage %r, expected %d" % (page, info.get("perPage"), PER_PAGE))
    if not isinstance(pagination.get("data"), list):
        raise ValueError("page %d: animePagination.data is not a list" % page)
    return pagination


def _fetch(client: PoliteClient, cache: FileCache, page: int, refresh: bool) -> tuple[dict, bool]:
    """Return (animePagination, cache hit)."""
    key = _page_key(page)
    if not refresh:
        data = cache.get(key)
        if data is not None:
            try:
                return validate_page(json.loads(data), page), True
            except ValueError:
                # Corrupt or stale-format entry: drop it and fetch again.
                cache.delete(key)
    query = _template().replace("{page}", str(page))
    reply = client.request(
        "POST",
        ANIMETHEMES_URL,
        headers={"Content-Type": "application/json"},
        body=json.dumps({"query": query}).encode("utf-8"),
    )
    try:
        body = json.loads(reply.body)
    except ValueError as error:
        raise ValueError("page %d: response is not valid JSON: %s" % (page, error)) from error
    pagination = validate_page(body, page)
    cache.put(key, reply.body)
    return pagination, False


def fetch_page(client: PoliteClient, cache: FileCache, page: int, *, refresh: bool = False) -> dict:
    return _fetch(client, cache, page, refresh)[0]


def _ids(pagination: dict) -> list:
    return [item.get("id") for item in pagination["data"]]


def _id_range(ids: list) -> str:
    if not ids:
        return "none"
    return "%s..%s" % (ids[0], ids[-1])


class _Shifted(Exception):
    pass


def _crawl_once(client: PoliteClient, cache: FileCache, log: Callable[[str], None]) -> CrawlResult:
    items: list = []
    seen: dict = {}  # anime id -> page number
    previous_last = None
    total = None
    page = 1
    while True:
        pagination, hit = _fetch(client, cache, page, False)
        has_more = bool(pagination["paginatorInfo"].get("hasMorePages"))
        if hit and has_more and cache.get(_page_key(page + 1)) is None:
            # Resume point: make sure the source did not shift under the cache.
            fresh, _ = _fetch(client, cache, page, True)
            if _ids(fresh) != _ids(pagination):
                cleared = cache.clear(_key_prefix())
                log("crawl: page %d shifted since it was cached (ids %s -> %s); cleared %d entries, restarting"
                    % (page, _id_range(_ids(pagination)), _id_range(_ids(fresh)), cleared))
                raise _Shifted()
            pagination = fresh
            has_more = bool(pagination["paginatorInfo"].get("hasMorePages"))

        info = pagination["paginatorInfo"]
        ids = _ids(pagination)
        for anime_id in ids:
            if anime_id in seen:
                cache.delete(_page_key(page))
                raise ValueError("duplicate anime id %r in page %d and page %d; page %d's cache entry was deleted"
                                 % (anime_id, seen[anime_id], page, page))
        if ids and previous_last is not None and not ids[0] > previous_last:
            log("warning: anime ids do not ascend: page %d ends with id %r, page %d starts with id %r"
                % (page - 1, previous_last, page, ids[0]))
        for anime_id in ids:
            seen[anime_id] = page
        if ids:
            previous_last = ids[-1]
        items.extend(pagination["data"])
        total = info.get("total")
        log("page %d currentPage=%r anime=%d ids=%s%s"
            % (page, info.get("currentPage"), len(ids), _id_range(ids), " (cached)" if hit else ""))
        if not has_more:
            break
        page += 1

    if total != len(items):
        log("warning: crawled %d anime but the last page reports total %r" % (len(items), total))
    log("crawl pages=%d anime=%d total=%s" % (page, len(items), total))
    return CrawlResult(items, page, total)


def crawl(client: PoliteClient, cache: FileCache, *, log: Callable[[str], None] = print) -> CrawlResult:
    while True:
        try:
            return _crawl_once(client, cache, log)
        except _Shifted:
            # The cache was cleared, so the restart has no hits and cannot shift again.
            continue


def _choose_audio(theme: dict):
    entries = list(enumerate(theme.get("animethemeentries") or []))
    entries.sort(key=lambda pair: (pair[1].get("version") is not None, pair[1].get("version") or 0, pair[0]))
    for _, entry in entries:
        videos = list((entry.get("videos") or {}).get("nodes") or [])
        videos.sort(key=lambda video: video.get("basename") or "")
        for video in videos:
            audio = video.get("audio")
            if audio is not None:
                return {
                    "id": audio.get("id"),
                    "basename": audio.get("basename"),
                    "url": audio.get("link"),
                    "size": audio.get("size"),
                }
    return None


def _song_fields(theme: dict) -> tuple:
    song = theme.get("song")
    if song is None:
        return None, []
    title = (song.get("title") or {}).get("romaji")
    artists = []
    for performance in song.get("performances") or []:
        name = ((performance.get("artist") or {}).get("name") or {}).get("main")
        if name is not None:
            artists.append(name)
    return title, artists


def select_themes(item: dict) -> dict:
    resources = (item.get("resources") or {}).get("nodes") or []
    anilist_id = None
    for node in resources:
        if node.get("site") == "ANILIST":
            value = node.get("externalId")
            anilist_id = int(value) if value is not None else None
            break
    anidb_ids: list = []
    for node in resources:
        value = node.get("externalId")
        if node.get("site") == "ANIDB" and value is not None and int(value) not in anidb_ids:
            anidb_ids.append(int(value))

    themes = []
    for theme in item.get("animethemes") or []:
        if theme.get("type") not in ("OP", "ED"):
            continue
        audio = _choose_audio(theme)
        if audio is None:
            continue
        song, artists = _song_fields(theme)
        themes.append({
            "id": theme.get("id"),
            "type": theme.get("type"),
            "slug": theme.get("slug"),
            "song": song,
            "artists": artists,
            "audio": audio,
        })

    return {
        "id": item.get("id"),
        "slug": item.get("slug"),
        "name": (item.get("title") or {}).get("romaji"),
        "year": item.get("year"),
        "season": item.get("season"),
        "anilistId": anilist_id,
        "anidbIds": anidb_ids,
        "themes": themes,
    }
