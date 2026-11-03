"""Audio prefetch: the draw set's OP/ED audio into a local cache (cycle 030).

prefetch downloads every draw-set theme's AnimeThemes audio into cache_dir,
politely and resumably, verifies each body (exact size, Ogg Opus, positive
duration) and records basename, size and duration in cache_dir/index.json.
ogg_info is the pure Ogg page parser behind the verification. main is the
command line: python -m songrush_data.audio [--cache DIR].
docs/cycles/030-audio-prefetch.md is the specification.
"""

import argparse
import json
import os
import re
import struct
import sys
import time
import traceback
from pathlib import Path
from typing import Callable

from songrush_data.http import FileCache, HttpError, PoliteClient, SystemClock, TransportError, UrllibTransport

DATA_DIR = Path(__file__).resolve().parent.parent
CATALOG_PATH = DATA_DIR / "catalog.json"
DEFAULT_CACHE_DIR = DATA_DIR / "audio-cache"
LOCK_NAME = ".prefetch.lock"
INDEX_KEY = "index.json"
MAX_CONSECUTIVE_FAILURES = 5
OPUS_RATE = 48000

_BASENAME = re.compile(r"^[A-Za-z0-9._-]+\.ogg$")
_NO_GRANULE = 0xFFFFFFFFFFFFFFFF
_PAGE_HEADER = struct.Struct("<4sBBQIIIB")  # capture, version, type, granule, serial, sequence, crc, segments
_REPLACE_RETRY_SLEEPS = (0.1, 0.3, 1.0)  # same schedule as FileCache


class PrefetchLocked(RuntimeError):
    """Another prefetch holds cache_dir/.prefetch.lock (or a killed one left it)."""


class PrefetchStopped(Exception):
    """The run stopped early; the index holds every file finished before the stop."""


# Ogg parsing.

def _pages(data: bytes):
    """Yield (granule, serial, body, lacing) for each Ogg page; ValueError when data is not Ogg pages."""
    if not data:
        raise ValueError("no data")
    offset = 0
    while offset < len(data):
        if len(data) - offset < _PAGE_HEADER.size:
            raise ValueError("truncated Ogg page header at offset %d" % offset)
        capture, version, _type, granule, serial, _sequence, _crc, count = _PAGE_HEADER.unpack_from(data, offset)
        if capture != b"OggS":
            raise ValueError("no Ogg capture pattern at offset %d" % offset)
        if version != 0:
            raise ValueError("unsupported Ogg version %d at offset %d" % (version, offset))
        table_start = offset + _PAGE_HEADER.size
        lacing = data[table_start:table_start + count]
        if len(lacing) != count:
            raise ValueError("truncated Ogg segment table at offset %d" % offset)
        body_start = table_start + count
        body_end = body_start + sum(lacing)
        if body_end > len(data):
            raise ValueError("truncated Ogg page body at offset %d" % offset)
        yield granule, serial, data[body_start:body_end], lacing
        offset = body_end


def _first_packet(body: bytes, lacing: bytes) -> bytes:
    """The first packet on a page (it may continue on the next page; the prefix is enough here)."""
    length = 0
    for value in lacing:
        length += value
        if value < 255:
            break
    return body[:length]


def ogg_info(data: bytes) -> dict:
    """Parse Ogg pages: Opus or not, pre-skip, last granule and duration in ms."""
    opus = False
    pre_skip = 0
    serial_of_head = None
    last_granule = 0
    for index, (granule, serial, body, lacing) in enumerate(_pages(data)):
        if index == 0:
            head = _first_packet(body, lacing)
            if head.startswith(b"OpusHead") and len(head) >= 12:
                opus = True
                pre_skip = struct.unpack_from("<H", head, 10)[0]
                serial_of_head = serial
            continue
        if not opus or serial != serial_of_head or granule == _NO_GRANULE:
            continue
        last_granule = granule
    duration_ms = max(0, last_granule - pre_skip) * 1000 // OPUS_RATE if opus else 0
    return {"opus": opus, "preSkip": pre_skip, "lastGranule": last_granule, "durationMs": duration_ms}


# File writes.

def _write_part(path, data: bytes) -> None:
    """Write data to path (the .part file); module level so tests can make it fail."""
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


def _acquire_lock(path: Path) -> None:
    try:
        fd = os.open(path, os.O_CREAT | os.O_EXCL | os.O_WRONLY)
    except FileExistsError as error:
        raise PrefetchLocked("%s exists: another prefetch may be running; delete it only if no prefetch is running"
                             % path) from error
    try:
        os.write(fd, str(os.getpid()).encode("ascii"))
    finally:
        os.close(fd)


# Prefetch.

def _load_index(cache: FileCache, log) -> dict:
    data = cache.get(INDEX_KEY)
    if data is None:
        return {}
    try:
        index = json.loads(data)
    except (ValueError, TypeError):
        index = None
    if not isinstance(index, dict):
        log("audio index unreadable, rebuilding it from the cached files")
        return {}
    return index


def _write_index(cache: FileCache, index: dict) -> None:
    cache.put(INDEX_KEY, json.dumps(index, ensure_ascii=True, indent=1, sort_keys=True).encode("ascii"))


def _local_entry(path: Path, basename: str, size: int):
    """The index entry for a complete local file, or None when the file is missing or not valid."""
    try:
        if path.stat().st_size != size:
            return None
        data = path.read_bytes()
    except FileNotFoundError:
        return None
    try:
        info = ogg_info(data)
    except ValueError:
        return None
    if not info["opus"] or info["durationMs"] <= 0:
        return None
    return {"basename": basename, "size": size, "durationMs": info["durationMs"]}


def _audio_of(themes_by_id: dict, theme_id):
    """(basename, url, size) of a draw-set theme, or raise ValueError naming what is wrong."""
    theme = themes_by_id.get(theme_id)
    if theme is None:
        raise ValueError("not in the catalog's themes")
    audio = theme.get("audio") or {}
    basename, url, size = audio.get("basename"), audio.get("url"), audio.get("size")
    if not isinstance(basename, str) or not _BASENAME.fullmatch(basename):
        raise ValueError("unsafe or missing basename %r" % (basename,))
    if not isinstance(url, str) or not url:
        raise ValueError("missing audio url")
    if not isinstance(size, int) or isinstance(size, bool) or size <= 0:
        raise ValueError("invalid audio size %r" % (size,))
    return basename, url, size


def _verify(body: bytes, size: int) -> dict:
    """ogg_info of a downloaded body, or raise ValueError with the reason it fails."""
    if len(body) != size:
        raise ValueError("size %d, expected %d" % (len(body), size))
    try:
        info = ogg_info(body)
    except ValueError as error:
        raise ValueError("not an Ogg file (%s)" % error) from error
    if not info["opus"]:
        raise ValueError("not Ogg Opus")
    if info["durationMs"] <= 0:
        raise ValueError("duration %d ms" % info["durationMs"])
    return info


def prefetch(client, catalog: dict, cache_dir, *, log: Callable[[str], None] = print) -> dict:
    """Download and verify the draw set's audio into cache_dir; return the run's counts."""
    cache_dir = Path(cache_dir)
    cache_dir.mkdir(parents=True, exist_ok=True)
    lock = cache_dir / LOCK_NAME
    _acquire_lock(lock)
    try:
        return _run(client, catalog, cache_dir, log)
    finally:
        # Only reached after this run created the lock.
        lock.unlink(missing_ok=True)


def _run(client, catalog: dict, cache_dir: Path, log) -> dict:
    themes_by_id = {theme.get("id"): theme for theme in catalog.get("themes") or []}
    draw = list(catalog.get("drawSet") or [])
    cache = FileCache(cache_dir)
    index = _load_index(cache, log)
    result = {"done": 0, "skipped": 0, "failed": 0, "failedIds": [], "bytes": 0}
    streak = 0
    log("audio prefetch: %d draw-set themes into %s" % (len(draw), cache_dir))

    def summary():
        log("audio done=%d skipped=%d failed=%d bytes=%d"
            % (result["done"], result["skipped"], result["failed"], result["bytes"]))
        if result["failedIds"]:
            log("audio failed ids: %s" % ", ".join(str(i) for i in result["failedIds"]))

    def stop(message: str, cause=None):
        log(message)
        summary()
        raise PrefetchStopped(message) from cause

    def save_index():
        try:
            _write_index(cache, index)
        except OSError as error:
            stop("audio stopped: cannot write %s: %s" % (cache_dir / INDEX_KEY, error), error)

    for position, theme_id in enumerate(draw, start=1):
        key = str(theme_id)
        try:
            basename, url, size = _audio_of(themes_by_id, theme_id)
        except ValueError as error:
            reason = str(error)
        else:
            final = cache_dir / basename
            entry = _local_entry(final, basename, size)
            if entry is not None:
                result["skipped"] += 1
                if index.get(key) != entry:
                    # Index lost, or killed between the move and the index write: adopt the file.
                    index[key] = entry
                    save_index()
                continue
            if index.pop(key, None) is not None:
                save_index()  # the file behind this entry is gone or invalid
            reason = None
            try:
                reply = client.request("GET", url)
                info = _verify(reply.body, size)
            except (HttpError, TransportError, ValueError) as error:
                reason = str(error)
            else:
                part = cache_dir / (basename + ".part")
                try:
                    _write_part(part, reply.body)
                    _replace(part, final)
                except OSError as error:
                    try:
                        part.unlink(missing_ok=True)
                    except OSError as unlink_error:
                        log("audio could not remove %s: %s" % (part, unlink_error))
                    save_index()
                    stop("audio stopped: cannot write %s: %s" % (final, error), error)
                index[key] = {"basename": basename, "size": size, "durationMs": info["durationMs"]}
                save_index()
                result["done"] += 1
                result["bytes"] += size
                streak = 0
                log("audio got %s %s bytes=%d durationMs=%d (%d/%d)"
                    % (key, basename, size, info["durationMs"], position, len(draw)))
                continue
        result["failed"] += 1
        result["failedIds"].append(theme_id)
        streak += 1
        log("audio failed %s: %s" % (key, reason))
        if streak >= MAX_CONSECUTIVE_FAILURES:
            save_index()
            stop("audio stopped after %d consecutive failures" % MAX_CONSECUTIVE_FAILURES)

    save_index()
    summary()
    return result


# Command line.

def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="python -m songrush_data.audio",
                                     description="Download and verify the draw set's audio into a local cache.")
    parser.add_argument("--cache", default=str(DEFAULT_CACHE_DIR),
                        help="audio cache folder (default: data/audio-cache)")
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
        catalog = json.loads(CATALOG_PATH.read_text(encoding="utf-8"))
        client = PoliteClient(UrllibTransport(), SystemClock(), gap_s=3.0, timeout_s=120.0, log=print)
        prefetch(client, catalog, Path(args.cache), log=print)
    except PrefetchLocked as error:
        print("audio locked: %s" % error, file=sys.stderr)
        return 3
    except PrefetchStopped as error:
        print("audio prefetch stopped: %s" % error, file=sys.stderr)
        return 1
    except Exception as error:
        traceback.print_exc(file=sys.stderr)
        print("audio prefetch failed: %s: %s" % (type(error).__name__, error), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
