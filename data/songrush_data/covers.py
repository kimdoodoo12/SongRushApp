"""Cover download: the draw set's AnimeThemes LARGE_COVER images into a local cache (cycle 090).

run reads the cover links from a lean paged AnimeThemes GraphQL query (pages
cached in the shared FileCache under ci- keys), downloads the LARGE_COVER of
every draw-set anime, politely and resumably, checks each body (non-empty, at
most MAX_BYTES, JPEG or PNG signature) and records it in covers_dir/index.json
under a random id that the server later uses as the cover's public name, as
<id>.jpg or <id>.png by the body's format (cycle 095,
docs/cycles/095-png-covers.md). parse_page
is the pure page parser. main is the command line:
python -m songrush_data.covers [--cache DIR] [--covers DIR] [--catalog PATH] [--refresh-pages].
docs/cycles/090-cover-tool.md is the specification. The page loop is bounded
(cycle 091, docs/cycles/091-cover-page-bound.md): page 1 must carry a positive
integer lastPage, a run never requests more than that + MAX_PAGE_SLACK pages,
and a page that claims more pages but lists no anime stops the run.
"""

import argparse
import hashlib
import json
import os
import re
import secrets
import sys
import time
import traceback
from pathlib import Path
from typing import Callable

from songrush_data.http import FileCache, HttpError, PoliteClient, SystemClock, TransportError, UrllibTransport

DATA_DIR = Path(__file__).resolve().parent.parent
DEFAULT_CACHE_DIR = DATA_DIR / "cache"
DEFAULT_COVERS_DIR = DATA_DIR / "cover-cache"
DEFAULT_CATALOG_PATH = DATA_DIR / "catalog.json"
ANIMETHEMES_URL = "https://graphql.animethemes.moe/"
QUERY_TEMPLATE = ("query{ animePagination(first:100,page:{page}){ "
                  "paginatorInfo{ currentPage hasMorePages lastPage total } "
                  "data{ id images{ nodes{ facet link } } } } }")
PAGE_KEY_PREFIX = "ci-"
LOCK_NAME = ".covers.lock"
INDEX_NAME = "index.json"
INDEX_VERSION = 1
MAX_BYTES = 2_000_000
MAX_CONSECUTIVE_FAILURES = 5
MAX_PAGE_SLACK = 5  # pages read past page 1's lastPage, for a source that grows during the run
JPEG_SIGNATURE = b"\xff\xd8\xff"
PNG_SIGNATURE = b"\x89PNG\r\n\x1a\n"
SUFFIXES = (".jpg", ".png")  # the only index file names are <id>.jpg and <id>.png

_ID = re.compile(r"^[A-Za-z0-9_-]{1,64}$")
_REPLACE_RETRY_SLEEPS = (0.1, 0.3, 1.0)  # same schedule as FileCache
_QUERY_DIGEST = hashlib.sha256(QUERY_TEMPLATE.encode("utf-8")).hexdigest()[:16]


class CoversLocked(RuntimeError):
    """Another run holds covers_dir/.covers.lock (or a killed one left it)."""


class CoversStopped(Exception):
    """The run stopped early; the index holds every cover finished before the stop."""


# Pages.

def page_key(page: int) -> str:
    """FileCache key of a query page; the query digest keeps pages of another query apart."""
    return "%s%s-p%d.json" % (PAGE_KEY_PREFIX, _QUERY_DIGEST, page)


def _anime_id(value) -> int:
    if isinstance(value, bool):
        raise ValueError("anime id %r is not an integer" % (value,))
    if isinstance(value, int):
        return value
    if isinstance(value, str) and value.isascii() and value.isdigit():
        return int(value)
    raise ValueError("anime id %r is not an integer" % (value,))


def _large_cover(anime: dict):
    """The https LARGE_COVER link of one anime, or None."""
    images = anime.get("images") or {}
    nodes = images.get("nodes") if isinstance(images, dict) else None
    for node in nodes or []:
        if isinstance(node, dict) and node.get("facet") == "LARGE_COVER":
            link = node.get("link")
            if isinstance(link, str) and link.startswith("https://"):
                return link
            return None
    return None


def parse_page(body: bytes) -> tuple[dict[int, str | None], bool, int]:
    """(links, has_more, current_page) of a page body; ValueError when it is not a usable page."""
    try:
        document = json.loads(body)
    except (ValueError, TypeError) as error:
        raise ValueError("page is not JSON (%s)" % error) from error
    if not isinstance(document, dict):
        raise ValueError("page is not a JSON object")
    if document.get("errors"):
        raise ValueError("GraphQL errors: %s" % json.dumps(document["errors"], ensure_ascii=True)[:300])
    data = document.get("data")
    pagination = data.get("animePagination") if isinstance(data, dict) else None
    if not isinstance(pagination, dict):
        raise ValueError("page has no data.animePagination")
    info = pagination.get("paginatorInfo")
    if not isinstance(info, dict):
        raise ValueError("page has no paginatorInfo")
    current, has_more = info.get("currentPage"), info.get("hasMorePages")
    if not isinstance(current, int) or isinstance(current, bool):
        raise ValueError("currentPage %r is not an integer" % (current,))
    if not isinstance(has_more, bool):
        raise ValueError("hasMorePages %r is not a boolean" % (has_more,))
    items = pagination.get("data")
    if not isinstance(items, list):
        raise ValueError("page data is not a list")
    links: dict[int, str | None] = {}
    for anime in items:
        if not isinstance(anime, dict):
            raise ValueError("page entry %r is not an object" % (anime,))
        links[_anime_id(anime.get("id"))] = _large_cover(anime)
    return links, has_more, current


def _last_page(body: bytes) -> int:
    """paginatorInfo.lastPage of a body parse_page accepted; ValueError unless a positive integer."""
    last = json.loads(body)["data"]["animePagination"]["paginatorInfo"].get("lastPage")
    if not isinstance(last, int) or isinstance(last, bool) or last <= 0:
        raise ValueError("lastPage %r is not a positive integer" % (last,))
    return last


def _read_page(client, cache: FileCache, page: int):
    """(links, has_more, last_page) of page, from the cache or fetched, checked and cached.

    last_page is read only for page 1 (None otherwise). ValueError on a bad page,
    which is then not cached."""
    key = page_key(page)
    body = cache.get(key)
    source = "cached"
    if body is None:
        query = QUERY_TEMPLATE.replace("{page}", str(page))
        reply = client.request("POST", ANIMETHEMES_URL,
                               headers={"Content-Type": "application/json", "Accept": "application/json"},
                               body=json.dumps({"query": query}).encode("utf-8"))
        body = reply.body
        source = "fetched"
    try:
        links, has_more, current = parse_page(body)
    except ValueError as error:
        raise ValueError("%s page %d (%s): %s" % (source, page, key, error)) from error
    if current != page:
        raise ValueError("%s page %d (%s): response has currentPage %d" % (source, page, key, current))
    if has_more and not links:
        raise ValueError("%s page %d (%s): hasMorePages is true but the page lists no anime" % (source, page, key))
    last_page = None
    if page == 1:
        try:
            last_page = _last_page(body)
        except ValueError as error:
            raise ValueError("%s page %d (%s): %s" % (source, page, key, error)) from error
    if source == "fetched":
        cache.put(key, body)
    return links, has_more, last_page


def _all_links(client, cache: FileCache, log) -> dict[int, str | None]:
    links: dict[int, str | None] = {}
    page = 1
    bound = None
    while True:
        page_links, has_more, last_page = _read_page(client, cache, page)
        if page == 1:
            bound = last_page + MAX_PAGE_SLACK
        links.update(page_links)
        if not has_more:
            break
        if page >= bound:
            raise ValueError("page %d still has more pages, but page 1 said lastPage %d (bound %d with slack %d); "
                             "not requesting page %d" % (page, bound - MAX_PAGE_SLACK, bound, MAX_PAGE_SLACK,
                                                         page + 1))
        page += 1
    log("covers pages=%d listedAnime=%d" % (page, len(links)))
    return links


# Catalog and index.

def _draw_anime(catalog_path: Path) -> list[int]:
    """Anime ids of the draw set, in order of first appearance; ValueError on a broken catalog."""
    catalog = json.loads(Path(catalog_path).read_text(encoding="utf-8"))
    if not isinstance(catalog, dict):
        raise ValueError("%s is not a JSON object" % catalog_path)
    anime_of = {theme.get("id"): theme.get("animeId") for theme in catalog.get("themes") or []
                if isinstance(theme, dict)}
    ordered: list[int] = []
    seen = set()
    for theme_id in catalog.get("drawSet") or []:
        if theme_id not in anime_of:
            raise ValueError("%s: draw-set theme %r is not in themes" % (catalog_path, theme_id))
        anime_id = _anime_id(anime_of[theme_id])
        if anime_id not in seen:
            seen.add(anime_id)
            ordered.append(anime_id)
    return ordered


def _load_index(path: Path) -> dict:
    """The covers map of index.json ({} when absent); CoversStopped when it is malformed."""
    try:
        data = path.read_bytes()
    except FileNotFoundError:
        return {}
    try:
        index = json.loads(data)
    except (ValueError, TypeError):
        index = None
    if not isinstance(index, dict) or index.get("version") != INDEX_VERSION \
            or not isinstance(index.get("covers"), dict):
        raise CoversStopped("%s is malformed (need a JSON object with version %d and a covers object); "
                            "it is not rebuilt because its ids cannot be recovered" % (path, INDEX_VERSION))
    covers = index["covers"]
    for key, entry in covers.items():
        size = entry.get("size") if isinstance(entry, dict) else None
        cover_id = entry.get("id") if isinstance(entry, dict) else None
        if not isinstance(cover_id, str) or not _ID.fullmatch(cover_id) \
                or not isinstance(entry.get("file"), str) \
                or entry["file"] not in {cover_id + suffix for suffix in SUFFIXES} \
                or not isinstance(size, int) or isinstance(size, bool) or size <= 0:
            raise CoversStopped("%s is malformed: entry %r is not {id, file: <id>.jpg or <id>.png, size}"
                                % (path, key))
    return covers


def _write_index(store: FileCache, covers: dict) -> None:
    body = {"version": INDEX_VERSION, "covers": covers}
    store.put(INDEX_NAME, json.dumps(body, ensure_ascii=True, indent=1, sort_keys=True).encode("ascii"))


def _fresh_id(covers: dict, new_id: Callable[[], str]) -> str:
    # Case-insensitive: Windows file names ignore case, and the id is the file name.
    taken = {entry["id"].casefold() for entry in covers.values()}
    while True:
        cover_id = new_id()
        if not isinstance(cover_id, str) or not _ID.fullmatch(cover_id):
            raise ValueError("new id %r is not a url-safe token" % (cover_id,))
        if cover_id.casefold() not in taken:
            return cover_id


# Files.

def _check_body(body: bytes) -> str:
    """The file suffix of a usable body (".jpg" or ".png"); ValueError otherwise."""
    if not body:
        raise ValueError("empty body")
    if len(body) > MAX_BYTES:
        raise ValueError("body of %d bytes is over %d" % (len(body), MAX_BYTES))
    if body.startswith(JPEG_SIGNATURE):
        return ".jpg"
    if body.startswith(PNG_SIGNATURE):
        return ".png"
    raise ValueError("body is not a JPEG or PNG (starts with %s)" % body[:8].hex())


def _write_part(path: Path, data: bytes) -> None:
    with open(path, "wb") as handle:
        handle.write(data)
        handle.flush()
        os.fsync(handle.fileno())


def _replace(source: Path, target: Path) -> None:
    # os.replace overwrites on Windows; antivirus scanning can hold the file briefly.
    for delay in _REPLACE_RETRY_SLEEPS:
        try:
            os.replace(source, target)
            return
        except PermissionError:
            time.sleep(delay)
    os.replace(source, target)


def _is_cached(path: Path, size: int) -> bool:
    try:
        return path.stat().st_size == size
    except FileNotFoundError:
        return False


def _acquire_lock(path: Path) -> None:
    try:
        fd = os.open(path, os.O_CREAT | os.O_EXCL | os.O_WRONLY)
    except FileExistsError as error:
        raise CoversLocked("%s exists: another covers run may be running; delete it only if none is running"
                           % path) from error
    try:
        os.write(fd, str(os.getpid()).encode("ascii"))
    finally:
        os.close(fd)


# Run.

def run(*, client, cache: FileCache, catalog_path, covers_dir, refresh_pages: bool = False,
        log: Callable[[str], None] = print, new_id: Callable[[], str] | None = None) -> dict:
    """Download the draw set's covers into covers_dir; return the run's counts."""
    covers_dir = Path(covers_dir)
    covers_dir.mkdir(parents=True, exist_ok=True)
    lock = covers_dir / LOCK_NAME
    _acquire_lock(lock)
    try:
        return _run(client, cache, Path(catalog_path), covers_dir, refresh_pages, log,
                    new_id if new_id is not None else (lambda: secrets.token_urlsafe(16)))
    finally:
        # Only reached after this run created the lock.
        lock.unlink(missing_ok=True)


def _run(client, cache: FileCache, catalog_path: Path, covers_dir: Path, refresh_pages: bool, log,
         new_id: Callable[[], str]) -> dict:
    covers = _load_index(covers_dir / INDEX_NAME)
    draw = _draw_anime(catalog_path)
    store = FileCache(covers_dir)
    result = {"drawSetAnime": len(draw), "cached": 0, "downloaded": 0, "noCover": 0, "notListed": 0,
              "failed": 0, "failedIds": []}
    log("covers: %d draw-set anime into %s" % (len(draw), covers_dir))

    if refresh_pages:
        log("covers: refreshing pages, removed %d cached" % cache.clear(PAGE_KEY_PREFIX))
    links = _all_links(client, cache, log)

    def summary():
        if result["failedIds"]:
            log("covers failed anime: %s" % ", ".join(str(i) for i in result["failedIds"]))
        if result["notListed"]:
            log("covers: %d draw-set anime are on no cached page; rerun with --refresh-pages to fetch new pages"
                % result["notListed"])
        log("covers drawSetAnime=%d cached=%d downloaded=%d noCover=%d notListed=%d failed=%d"
            % tuple(result[k] for k in ("drawSetAnime", "cached", "downloaded", "noCover", "notListed", "failed")))

    def stop(message: str, cause=None):
        log(message)
        summary()
        raise CoversStopped(message) from cause

    streak = 0
    for position, anime_id in enumerate(draw, start=1):
        key = str(anime_id)
        entry = covers.get(key)
        if entry is not None and _is_cached(covers_dir / entry["file"], entry["size"]):
            result["cached"] += 1
            continue
        if anime_id not in links:
            result["notListed"] += 1
            continue
        url = links[anime_id]
        if url is None:
            result["noCover"] += 1
            continue
        try:
            reply = client.request("GET", url)
            suffix = _check_body(reply.body)
        except (HttpError, TransportError, ValueError) as error:
            result["failed"] += 1
            result["failedIds"].append(anime_id)
            streak += 1
            log("covers failed %s: %s" % (key, error))
            if streak >= MAX_CONSECUTIVE_FAILURES:
                stop("covers stopped after %d consecutive failures" % MAX_CONSECUTIVE_FAILURES)
            continue
        cover_id = entry["id"] if entry is not None else _fresh_id(covers, new_id)
        # A refetch in the other format renames the entry's file and keeps its id; the old file is
        # left alone, since nothing reads a file the index does not name.
        final = covers_dir / (cover_id + suffix)
        part = covers_dir / (cover_id + suffix + ".part")
        try:
            _write_part(part, reply.body)
            _replace(part, final)
            covers[key] = {"id": cover_id, "file": final.name, "size": len(reply.body)}
            _write_index(store, covers)
        except OSError as error:
            try:
                part.unlink(missing_ok=True)
            except OSError as unlink_error:
                log("covers could not remove %s: %s" % (part, unlink_error))
            stop("covers stopped: cannot write %s: %s" % (final, error), error)
        result["downloaded"] += 1
        streak = 0
        log("covers got %s %s bytes=%d (%d/%d)" % (key, final.name, len(reply.body), position, len(draw)))

    summary()
    return result


# Command line.

def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="python -m songrush_data.covers",
                                     description="Download the draw set's AnimeThemes covers into a local cache.")
    parser.add_argument("--cache", default=str(DEFAULT_CACHE_DIR),
                        help="response cache folder for the cover-link pages (default: data/cache)")
    parser.add_argument("--covers", default=str(DEFAULT_COVERS_DIR),
                        help="cover cache folder (default: data/cover-cache)")
    parser.add_argument("--catalog", default=str(DEFAULT_CATALOG_PATH),
                        help="catalog with the draw set (default: data/catalog.json)")
    parser.add_argument("--refresh-pages", action="store_true",
                        help="delete the cached cover-link pages and fetch them again")
    return parser


def _utf8_console() -> None:
    # The Windows console and pipes are cp949; line buffering keeps a background log current.
    for stream in (sys.stdout, sys.stderr):
        reconfigure = getattr(stream, "reconfigure", None)
        if reconfigure is not None:
            reconfigure(encoding="utf-8", errors="backslashreplace", line_buffering=True)


def main(argv=None) -> int:
    _utf8_console()
    try:
        args = _parser().parse_args(argv)
    except SystemExit as error:
        # argparse exits on --help (0) and on usage errors (2); return instead of raising.
        return 0 if error.code == 0 else 2
    try:
        client = PoliteClient(UrllibTransport(), SystemClock(), gap_s=3.0, timeout_s=60.0, log=print)
        run(client=client, cache=FileCache(Path(args.cache)), catalog_path=Path(args.catalog),
            covers_dir=Path(args.covers), refresh_pages=args.refresh_pages, log=print)
    except CoversLocked as error:
        print("covers locked: %s" % error, file=sys.stderr)
        return 3
    except CoversStopped as error:
        print("covers stopped: %s" % error, file=sys.stderr)
        return 1
    except Exception as error:
        traceback.print_exc(file=sys.stderr)
        print("covers failed: %s: %s" % (type(error).__name__, error), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
