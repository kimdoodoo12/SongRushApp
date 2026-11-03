"""Korean titles per AniList id from the AniDB titles dump (cycle 043).

load_korean uses the cached anime-titles.xml.gz, or downloads it with one
request at most once per 24 h and never before NOT_BEFORE. The attempt marker
is written before the request and never removed, so a failed request is not
retried within 24 h. parse_titles decompresses the dump with a size cap and
parses it with expat, rejecting any doctype or entity declaration.
korean_by_anilist maps the titles to AniList ids through the ANIDB resources
AnimeThemes lists. A failing source is skipped with a warning.
docs/cycles/043-anidb-korean.md is the specification.
"""

import json
import unicodedata
import zlib
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Callable
from xml.parsers import expat

from songrush_data.http import FileCache, HttpError, PoliteClient, TransportError
from songrush_data.titles import has_hangul

ANIDB_URL = "https://anidb.net/api/anime-titles.xml.gz"
MIN_INTERVAL = timedelta(hours=24)
# Read at call time, so tests can lower it.
MAX_XML_BYTES = 100 * 1024 * 1024
# The previous request was 2026-11-02 18:34 KST (CLAUDE.md).
NOT_BEFORE = datetime(2026, 11, 3, 18, 34, tzinfo=timezone(timedelta(hours=9)))

DUMP_NAME = "anime-titles.xml.gz"
MARKER_NAME = "attempt.json"

# expat reports namespaced names as "<uri> <local>" with this separator.
_XML_LANG = "http://www.w3.org/XML/1998/namespace lang"
_PRIMARY_TYPES = ("main", "official")
_MAX_AID_DIGITS = 18


class _Rejected(Exception):
    """Raised inside expat handlers; parse_titles turns it into ValueError."""


def _decompress(gz_bytes: bytes) -> bytes:
    cap = MAX_XML_BYTES
    decompressor = zlib.decompressobj(wbits=31)  # gzip header and trailer
    try:
        # max_length bounds the output, so a gzip bomb never fully expands.
        data = decompressor.decompress(gz_bytes, cap + 1)
    except zlib.error as error:
        raise ValueError("anidb: dump is not valid gzip: %s" % error) from error
    if len(data) > cap:
        raise ValueError("anidb: decompressed dump exceeds %d bytes" % cap)
    if not decompressor.eof:
        raise ValueError("anidb: gzip data is truncated")
    return data


def _aid(value) -> int | None:
    if not isinstance(value, str) or not (value.isascii() and value.isdigit()) or len(value) > _MAX_AID_DIGITS:
        return None
    aid = int(value)
    return aid if aid > 0 else None


def parse_titles(gz_bytes: bytes) -> dict:
    """Return {aid: [Korean titles]}: main/official first, then the rest, each in document order."""
    xml = _decompress(gz_bytes)
    groups: dict[int, tuple[list[str], list[str]]] = {}
    state = {"depth": 0, "aid": None, "title": None, "text": []}

    def reject_doctype(*_args):
        raise _Rejected("anidb: the dump has a doctype")

    def reject_entity(*_args):
        raise _Rejected("anidb: the dump has an entity declaration")

    def start(name, attrs):
        state["depth"] += 1
        depth = state["depth"]
        if depth == 1:
            if name != "animetitles":
                raise _Rejected("anidb: root is %r, not animetitles" % name)
        elif depth == 2:
            state["aid"] = _aid(attrs.get("aid")) if name == "anime" else None
        elif depth == 3 and name == "title" and state["aid"] is not None:
            state["title"] = (attrs.get("type"), attrs.get(_XML_LANG))
            state["text"] = []

    def end(_name):
        depth = state["depth"]
        state["depth"] -= 1
        if depth == 3 and state["title"] is not None:
            kind, lang = state["title"]
            state["title"] = None
            text = unicodedata.normalize("NFC", "".join(state["text"]))
            if lang == "ko" and has_hangul(text):
                primary, others = groups.setdefault(state["aid"], ([], []))
                (primary if kind in _PRIMARY_TYPES else others).append(text)
        elif depth == 2:
            state["aid"] = None

    def characters(data):
        if state["title"] is not None:
            state["text"].append(data)

    parser = expat.ParserCreate(namespace_separator=" ")
    parser.SetParamEntityParsing(expat.XML_PARAM_ENTITY_PARSING_NEVER)
    parser.StartDoctypeDeclHandler = reject_doctype
    parser.EntityDeclHandler = reject_entity
    parser.UnparsedEntityDeclHandler = reject_entity
    parser.ExternalEntityRefHandler = reject_entity
    parser.StartElementHandler = start
    parser.EndElementHandler = end
    parser.CharacterDataHandler = characters
    try:
        parser.Parse(xml, True)
    except _Rejected as error:
        raise ValueError(str(error)) from error
    except expat.ExpatError as error:
        raise ValueError("anidb: malformed XML: %s" % error) from error

    result: dict[int, list[str]] = {}
    for aid, (primary, others) in groups.items():
        titles: list[str] = []
        for text in primary + others:
            if text not in titles:
                titles.append(text)
        result[aid] = titles
    return result


def korean_by_anilist(titles_by_aid: dict, anime: list) -> dict:
    """Return {AniList id: [Korean titles of its anidbIds]} for ids with any Korean title."""
    result: dict[int, list[str]] = {}
    for record in anime:
        anilist_id = record.get("anilistId")
        if anilist_id is None:
            continue
        korean: list[str] = []
        for aid in record.get("anidbIds") or []:
            for text in titles_by_aid.get(aid, []) if isinstance(aid, int) else []:
                if text not in korean:
                    korean.append(text)
        if not korean:
            continue
        merged = result.setdefault(anilist_id, [])
        merged.extend(text for text in korean if text not in merged)
    return result


def _read_marker(path: Path, now: datetime, log: Callable[[str], None]) -> datetime | None:
    """Return the last attempt time, None without a marker, or now when the marker is unusable."""
    try:
        data = path.read_bytes()
    except FileNotFoundError:
        return None
    except OSError as error:
        log("warning: anidb marker %s unreadable (%s), counting it as an attempt now" % (path, error))
        return now
    try:
        document = json.loads(data.decode("utf-8"))
        if not isinstance(document, dict) or not isinstance(document.get("at"), str):
            raise ValueError("no \"at\" string")
        at = datetime.fromisoformat(document["at"])
        if at.tzinfo is None or at.utcoffset() is None:
            raise ValueError("time %r has no offset" % document["at"])
        if at > now:
            raise ValueError("time %s is in the future" % at.isoformat())
    except ValueError as error:
        # Fail closed: an unusable marker blocks the request (delete it by hand to reset).
        log("warning: anidb marker %s invalid (%s), counting it as an attempt now" % (path, error))
        return now
    return at


def _write_marker(path: Path, now: datetime) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps({"at": now.isoformat()}), encoding="utf-8")


def _fetch(client: PoliteClient, cache: FileCache, cache_dir: Path, now: datetime,
           log: Callable[[str], None]) -> dict | None:
    """Download and parse the dump within the throttle; None when skipped."""
    if now < NOT_BEFORE:
        log("anidb skipped: not before %s" % NOT_BEFORE.isoformat())
        return None
    marker = cache_dir / MARKER_NAME
    last = _read_marker(marker, now, log)
    if last is not None and now - last < MIN_INTERVAL:
        log("anidb skipped: last attempt %s, next allowed %s"
            % (last.isoformat(), (last + MIN_INTERVAL).isoformat()))
        return None
    # The marker is written before the request, so even a crash spends the attempt.
    _write_marker(marker, now)
    body = client.request("GET", ANIDB_URL).body
    titles = parse_titles(body)
    try:
        cache.put(DUMP_NAME, body)
    except OSError as error:
        log("warning: anidb dump not stored (%s: %s), using it for this run only" % (type(error).__name__, error))
    log("anidb downloaded %d bytes" % len(body))
    return titles


def load_korean(client: PoliteClient, cache_dir, anime: list, *, now: datetime,
                log: Callable[[str], None] = print) -> dict:
    """Return korean_by_anilist of the AniDB dump, or {} when it is unavailable."""
    if now.tzinfo is None or now.utcoffset() is None:
        raise ValueError("anidb: now must be an aware datetime, got %r" % (now,))
    cache_dir = Path(cache_dir)
    cache = FileCache(cache_dir)
    try:
        titles = None
        data = cache.get(DUMP_NAME)
        if data is not None:
            try:
                titles = parse_titles(data)
            except ValueError as error:
                log("warning: anidb cached dump invalid (%s), deleting it" % error)
                cache.delete(DUMP_NAME)
        if titles is None:
            titles = _fetch(client, cache, cache_dir, now, log)
            if titles is None:
                return {}
        result = korean_by_anilist(titles, anime)
    except (HttpError, TransportError, ValueError, OSError) as error:
        # The build continues without AniDB's Korean strings; the marker stays.
        log("warning: anidb skipped: %s: %s" % (type(error).__name__, error))
        return {}
    log("anidb aids_with_korean=%d anilist_ids_with_korean=%d" % (len(titles), len(result)))
    return result
