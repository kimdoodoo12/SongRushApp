"""AnimeThemes GraphQL crawl and theme selection (cycle 017).

fetch_page posts the saved page query with a literal page number, validates
the response and caches it. crawl walks every page with resume, order,
duplicate and total checks and a last-page freshness check (cycle 023),
and continues without clearing when the source only grew (cycle 025).
select_themes turns one raw anime item into a plain record with one chosen
audio file per OP/ED theme.
docs/cycles/017-animethemes-crawl.md and docs/cycles/023-build-cli.md are
the specification, with docs/cycles/025-incremental-crawl.md.
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


MAX_RESTARTS = 2  # resume-shift, total-change and freshness restarts per crawl call


class _Restart(Exception):
    pass


def _restart(cache: FileCache, reason: str, can_restart: bool, log: Callable[[str], None]) -> None:
    """Clear the page prefix and restart the crawl, or raise ValueError when the cap is reached."""
    if not can_restart:
        raise ValueError("%s; giving up after %d restarts" % (reason, MAX_RESTARTS))
    cleared = cache.clear(_key_prefix())
    log("%s; cleared %d entries, restarting" % (reason, cleared))
    raise _Restart()


class _Pass:
    """The items of one crawl pass, with the per-page checks."""

    def __init__(self, cache: FileCache, log: Callable[[str], None]):
        self.cache = cache
        self.log = log
        self.items: list = []
        self.seen: dict = {}  # anime id -> page number
        self.previous_last = None
        self.total = None
        self.total_known = False
        self.grew = False  # the total grew on a fetched page during this pass
        self.last_start = 0  # index in items where the latest page begins

    def check_new_ids(self, page: int, ids: list) -> None:
        for anime_id in ids:
            if anime_id in self.seen:
                self.cache.delete(_page_key(page))
                raise ValueError("duplicate anime id %r in page %d and page %d; page %d's cache entry was deleted"
                                 % (anime_id, self.seen[anime_id], page, page))

    def add(self, page: int, pagination: dict, ids: list) -> None:
        if ids and self.previous_last is not None and not ids[0] > self.previous_last:
            self.log("warning: anime ids do not ascend: page %d ends with id %r, page %d starts with id %r"
                     % (page - 1, self.previous_last, page, ids[0]))
        for anime_id in ids:
            self.seen[anime_id] = page
        if ids:
            self.previous_last = ids[-1]
        self.last_start = len(self.items)
        self.items.extend(pagination["data"])

    def replace_last(self, page: int, fresh: dict, new_ids: list) -> None:
        """Swap the latest page's items for the fresh page, whose extra ids are new_ids."""
        self.check_new_ids(page, new_ids)
        for anime_id in new_ids:
            self.seen[anime_id] = page
        if new_ids:
            self.previous_last = new_ids[-1]
        del self.items[self.last_start:]
        self.items.extend(fresh["data"])


    def latest_ids(self) -> list:
        """The ids of the latest page added."""
        return [item.get("id") for item in self.items[self.last_start:]]


def _has_more(pagination: dict) -> bool:
    return bool(pagination["paginatorInfo"].get("hasMorePages"))


def _grew(old_total, new_total) -> bool:
    return isinstance(old_total, int) and isinstance(new_total, int) and new_total > old_total


def _appended_only(old: dict, fresh: dict) -> list | None:
    """Return the new ids when fresh only appends to old (cycle 025 rule 2), else None.

    (a) the new ids fit on the page and total grew by exactly their number, or
    (b) the fresh page is full, now has more pages, and total grew strictly and
    by at least the number of new ids (which may be zero).
    """
    old_ids, new_ids = _ids(old), _ids(fresh)
    if len(new_ids) < len(old_ids) or new_ids[:len(old_ids)] != old_ids:
        return None
    added = new_ids[len(old_ids):]
    old_total = old["paginatorInfo"].get("total")
    new_total = fresh["paginatorInfo"].get("total")
    if not _grew(old_total, new_total):
        return None
    if added and new_total - old_total == len(added):
        return added
    if len(new_ids) == PER_PAGE and _has_more(fresh) and new_total - old_total >= len(added):
        return added
    return None


def _verify_previous(client: PoliteClient, cache: FileCache, state: _Pass, page: int,
                     can_restart: bool, log: Callable[[str], None]) -> None:
    """Refetch page - 1 and restart unless its ids are unchanged (cycle 025 rule 1).

    Page's cache entry carries a larger total that is not verified yet, so it
    is deleted when the verification does not succeed by any path.
    """
    verified = False
    try:
        if page > 1:
            previous, _ = _fetch(client, cache, page - 1, True)
            old_ids, new_ids = state.latest_ids(), _ids(previous)
            if new_ids != old_ids:
                _restart(cache, "crawl: page %d no longer holds the same ids after page %d reported a larger total"
                         " (ids %s -> %s)" % (page - 1, page, _id_range(old_ids), _id_range(new_ids)),
                         can_restart, log)
        verified = True
    finally:
        if not verified:
            cache.delete(_page_key(page))


def _crawl_once(client: PoliteClient, cache: FileCache, log: Callable[[str], None],
                refresh_all: bool, can_restart: bool) -> CrawlResult:
    state = _Pass(cache, log)
    page = 1
    while True:
        pagination, hit = _fetch(client, cache, page, refresh_all)
        fetched = not hit
        resumed = False
        if hit and _has_more(pagination) and cache.get(_page_key(page + 1)) is None:
            # Resume point: make sure the source did not shift under the cache.
            fresh, _ = _fetch(client, cache, page, True)
            if _ids(fresh) != _ids(pagination):
                _restart(cache, "crawl: page %d shifted since it was cached (ids %s -> %s)"
                         % (page, _id_range(_ids(pagination)), _id_range(_ids(fresh))), can_restart, log)
            pagination = fresh
            fetched = True
            resumed = True

        info = pagination["paginatorInfo"]
        new_total = info.get("total")
        if not state.total_known:
            state.total, state.total_known = new_total, True
        elif new_total != state.total:
            if not _grew(state.total, new_total):
                _restart(cache, "crawl: total changed on page %d (%s -> %s)" % (page, state.total, new_total),
                         can_restart, log)
            if fetched:
                # A cached page with a larger total was verified before it was cached,
                # and the resume-check page was just compared with its cached copy.
                if not resumed:
                    _verify_previous(client, cache, state, page, can_restart, log)
                log("crawl: total grew on page %d (%s -> %s), continuing" % (page, state.total, new_total))
                state.grew = True
            state.total = new_total
        ids = _ids(pagination)
        state.check_new_ids(page, ids)
        state.add(page, pagination, ids)
        log("page %d currentPage=%r anime=%d ids=%s%s"
            % (page, info.get("currentPage"), len(ids), _id_range(ids), " (cached)" if hit else ""))
        if _has_more(pagination):
            page += 1
            continue
        if fetched:
            break

        # Freshness: the last page came from the cache, so check it once against the source.
        fresh, _ = _fetch(client, cache, page, True)
        fresh_info = fresh["paginatorInfo"]
        if (_ids(fresh) == ids and _has_more(fresh) == _has_more(pagination)
                and fresh_info.get("total") == info.get("total")):
            state.replace_last(page, fresh, [])
            break
        added = _appended_only(pagination, fresh)
        if added is None:
            _restart(cache, "crawl: last page %d changed since it was cached" % page, can_restart, log)
        state.replace_last(page, fresh, added)
        state.total = fresh_info.get("total")
        state.grew = True
        log("crawl: last page %d gained %d anime" % (page, len(added)))
        if not _has_more(fresh):
            break
        page += 1

    items, total = state.items, state.total
    if state.grew and total != len(items):
        _restart(cache, "crawl: item count %d does not match total %s after the source grew" % (len(items), total),
                 can_restart, log)
    if total != len(items):
        log("warning: crawled %d anime but the last page reports total %r" % (len(items), total))
    log("crawl pages=%d anime=%d total=%s" % (page, len(items), total))
    return CrawlResult(items, page, total)


def crawl(client: PoliteClient, cache: FileCache, *, refresh_all: bool = False,
          log: Callable[[str], None] = print) -> CrawlResult:
    """Walk every page; restart at most MAX_RESTARTS times when the source changed under the cache."""
    restarts = 0
    while True:
        try:
            return _crawl_once(client, cache, log, refresh_all, restarts < MAX_RESTARTS)
        except _Restart:
            restarts += 1


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
