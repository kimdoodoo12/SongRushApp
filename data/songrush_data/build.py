"""The build command: every source into catalog.json and korean_gaps.json (cycle 023).

run crawls AnimeThemes, fetches AniList, Wikidata and aod through the cached
source modules, assembles the catalog and writes both files, holding a lock
file in the output folder for the whole run. main is the command line:
python -m songrush_data.build [--refresh-all] [--cache DIR] [--out DIR]
[--anilist-batch N]. docs/cycles/023-build-cli.md is the specification; cycle 028
adds the hand-edited <out>/korean_titles_manual.json, merged before aod.
"""

import argparse
import json
import os
import sys
import traceback
from pathlib import Path
from typing import Callable

from songrush_data import anilist, animethemes, aod, catalog, wikidata
from songrush_data.http import FileCache, PoliteClient, SystemClock, UrllibTransport
from songrush_data.manual import load_manual, merge_korean

DATA_DIR = Path(__file__).resolve().parent.parent
LOCK_NAME = ".build.lock"

# (gap_s, timeout_s) per source. AnimeThemes pages take 8-30 s (cycle 017).
_CLIENTS = {
    "animethemes": (3.0, 120.0),
    "anilist": (2.0, 60.0),
    "wikidata": (3.0, 60.0),
    "aod": (3.0, 60.0),
}


class BuildLocked(RuntimeError):
    pass


def _acquire_lock(path: Path) -> None:
    try:
        fd = os.open(path, os.O_CREAT | os.O_EXCL | os.O_WRONLY)
    except FileExistsError as error:
        raise BuildLocked("%s exists: another build may be running; delete it only if no build is running"
                          % path) from error
    try:
        os.write(fd, str(os.getpid()).encode("ascii"))
    finally:
        os.close(fd)


def run(*, transport, clock, cache_root, out_dir, refresh_all: bool = False, anilist_batch: int = anilist.MAX_BATCH,
        aod_size: int = aod.AOD_SIZE, aod_sha256: str = aod.AOD_SHA256, manual_path=None,
        log: Callable[[str], None] = print) -> dict:
    """Build and write the catalog; return the report with catalogBytes added."""
    cache_root = Path(cache_root)
    out_dir = Path(out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    lock = out_dir / LOCK_NAME
    manual_path = out_dir / "korean_titles_manual.json" if manual_path is None else Path(manual_path)
    _acquire_lock(lock)
    try:
        # Validate the hand-edited file before any request, so a typo fails at once (cycle 028).
        manual = load_manual(manual_path, log=log)
        clients = {
            name: PoliteClient(transport, clock, gap_s=gap_s, timeout_s=timeout_s, log=log)
            for name, (gap_s, timeout_s) in _CLIENTS.items()
        }
        cache = FileCache(cache_root)
        if refresh_all:
            cleared_al = cache.clear("al-")
            cleared_wd = cache.clear("wd-")
            log("build refresh_all: cleared %d AniList and %d Wikidata entries" % (cleared_al, cleared_wd))

        crawled = animethemes.crawl(clients["animethemes"], cache, refresh_all=refresh_all, log=log)
        anime = [animethemes.select_themes(item) for item in crawled.items]
        ids = sorted({a["anilistId"] for a in anime if a["anilistId"] is not None})
        media = anilist.fetch_media(clients["anilist"], cache, ids, batch_size=anilist_batch, log=log)
        labels = wikidata.fetch_labels(clients["wikidata"], cache, ids, log=log)
        korean = aod.load_korean(
            clients["aod"],
            cache_root / "aod" / "anime-offline-database-minified.json",
            expected_size=aod_size,
            expected_sha256=aod_sha256,
            log=log,
        )
        outside = set(manual) - set(ids)
        if outside:
            log("manual korean: %d ids not in the catalog %s" % (len(outside), sorted(outside)))

        built, report = catalog.build_catalog(anime, media, labels, merge_korean(manual, korean))
        size = catalog.write_catalog(out_dir / "catalog.json", built)
        catalog.write_gaps(out_dir / "korean_gaps.json", report)
        report["catalogBytes"] = size

        log("build counts %s" % json.dumps(report["counts"], sort_keys=True))
        log("build catalog bytes=%d" % size)
        log("build englishFromSynonyms catalog=%d drawSet=%d"
            % (report["englishFromSynonyms"]["catalog"], report["englishFromSynonyms"]["drawSet"]))
        log("build drawSetNoReveal=%d" % report["drawSetNoReveal"])
        log("build unplayable=%d" % len(report["unplayable"]))
        log("build koreanGaps=%d" % len(report["koreanGaps"]))
        for group in report["largestGroups"]:
            log("build group %s size=%d titles=%s"
                % (group["id"], group["size"], json.dumps(group["titles"], ensure_ascii=False)))
        return report
    finally:
        lock.unlink(missing_ok=True)


def _batch_size(text: str) -> int:
    value = int(text)
    if not 1 <= value <= anilist.MAX_BATCH:
        raise argparse.ArgumentTypeError("must be between 1 and %d" % anilist.MAX_BATCH)
    return value


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="python -m songrush_data.build",
                                     description="Build data/catalog.json and data/korean_gaps.json.")
    parser.add_argument("--refresh-all", action="store_true",
                        help="refetch every AnimeThemes page and clear the AniList and Wikidata caches")
    parser.add_argument("--cache", default=str(DATA_DIR / "cache"), help="cache folder (default: data/cache)")
    parser.add_argument("--out", default=str(DATA_DIR), help="output folder (default: data)")
    parser.add_argument("--anilist-batch", type=_batch_size, default=anilist.MAX_BATCH,
                        help="AniList ids per request (default: %d)" % anilist.MAX_BATCH)
    return parser


def _utf8_console() -> None:
    # The Windows console and pipes are cp949, which cannot encode romaji accents.
    for stream in (sys.stdout, sys.stderr):
        reconfigure = getattr(stream, "reconfigure", None)
        if reconfigure is not None:
            reconfigure(encoding="utf-8", errors="backslashreplace")


def main(argv=None) -> int:
    _utf8_console()
    try:
        args = _parser().parse_args(argv)
    except SystemExit as error:
        # argparse exits on --help (0) and on usage errors (2); return instead of raising.
        return 0 if error.code == 0 else 2
    try:
        run(
            transport=UrllibTransport(),
            clock=SystemClock(),
            cache_root=Path(args.cache),
            out_dir=Path(args.out),
            refresh_all=args.refresh_all,
            anilist_batch=args.anilist_batch,
        )
    except BuildLocked as error:
        print("build locked: %s" % error, file=sys.stderr)
        return 3
    except Exception as error:
        traceback.print_exc(file=sys.stderr)
        print("build failed: %s: %s" % (type(error).__name__, error), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
