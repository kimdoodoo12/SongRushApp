"""S3 cover upload: the cached covers into the private bucket (cycle 092, C-020).

run uploads every cover named in covers_dir/index.json under the key c/<id>,
resumably: objects already listed with the index size are skipped, and the
served map s3-covers.json, which the server reads, is written last and holds
only the objects a final listing confirms. The cover ids are already random and
stable in the index, so no work map is needed. main is the command line:
python -m songrush_data.s3covers --bucket NAME [--profile NAME] [--covers DIR].
docs/cycles/092-cover-s3.md is the specification. The log never carries an
anime id, a cover id, an object key or a credential, and never an error's
text: errors are reported by type name and S3 error code only. Covers are
<id>.jpg (image/jpeg) or <id>.png (image/png) since cycle 095
(docs/cycles/095-png-covers.md).
"""

import argparse
import json
import sys
from pathlib import Path
from typing import Callable

from songrush_data.covers import _ID, DEFAULT_COVERS_DIR, INDEX_NAME, INDEX_VERSION, SUFFIXES
from songrush_data.s3upload import REGION, _error_code, _utf8_console, _write_json_atomic

KEY_PREFIX = "c/"
CONTENT_TYPE = "image/jpeg"
PNG_CONTENT_TYPE = "image/png"
_CONTENT_TYPES = {".jpg": CONTENT_TYPE, ".png": PNG_CONTENT_TYPE}
SERVED_MAP_NAME = "s3-covers.json"
PROGRESS_EVERY = 100


# Files.

def load_index(covers_dir) -> dict:
    """{animeId: (cover id, file, size)} from covers_dir/index.json; file is <id>.jpg or <id>.png.

    FileNotFoundError when absent, ValueError when malformed. The messages name
    an entry's position only: an anime id or a cover id identifies the song.
    """
    path = Path(covers_dir) / INDEX_NAME
    try:
        index = json.loads(path.read_bytes().decode("utf-8"))
    except FileNotFoundError:
        raise FileNotFoundError("%s is missing: run the cover fetch first" % path) from None
    except OSError as error:
        raise ValueError("%s is unreadable: %s" % (path, type(error).__name__)) from error
    except ValueError as error:
        raise ValueError("%s is not valid UTF-8 JSON" % path) from error
    if not isinstance(index, dict):
        raise ValueError("%s is not a JSON object" % path)
    version = index.get("version")
    if not isinstance(version, int) or isinstance(version, bool) or version != INDEX_VERSION:
        raise ValueError("%s has an unsupported version" % path)
    covers = index.get("covers")
    if not isinstance(covers, dict):
        raise ValueError("%s has no covers object" % path)
    entries = {}
    seen = set()
    for position, (anime_id, entry) in enumerate(covers.items(), start=1):
        if not isinstance(entry, dict):
            raise ValueError("%s entry %d is not an object" % (path, position))
        cover_id, file, size = entry.get("id"), entry.get("file"), entry.get("size")
        if not isinstance(cover_id, str) or not _ID.fullmatch(cover_id):
            raise ValueError("%s entry %d has a missing or unsafe id" % (path, position))
        if not isinstance(file, str) or file not in {cover_id + suffix for suffix in SUFFIXES}:
            raise ValueError("%s entry %d has a file that is not <id>.jpg or <id>.png" % (path, position))
        if not isinstance(size, int) or isinstance(size, bool) or size <= 0:
            raise ValueError("%s entry %d has an invalid size" % (path, position))
        folded = cover_id.lower()  # the id alphabet is ASCII, so lower() is a full case fold
        if folded in seen:
            raise ValueError("%s entry %d repeats an earlier id" % (path, position))
        seen.add(folded)
        entries[anime_id] = (cover_id, file, size)
    return entries


# S3.

def _listing(s3, bucket: str) -> dict:
    """{key: size} of every object under the cover prefix, across all pages."""
    objects = {}
    params = {"Bucket": bucket, "Prefix": KEY_PREFIX}
    while True:
        page = s3.list_objects_v2(**params)
        for item in page.get("Contents") or []:
            objects[item["Key"]] = item["Size"]
        if not page.get("IsTruncated"):
            return objects
        token = page.get("NextContinuationToken")
        if not token:
            raise RuntimeError("S3 listing is truncated but has no continuation token")
        params = {"Bucket": bucket, "Prefix": KEY_PREFIX, "ContinuationToken": token}


def _describe(error: BaseException) -> str:
    """Type name and S3 error code; never the error's text, which can hold a key or the endpoint URL."""
    code = _error_code(error)
    return type(error).__name__ + (" " + code if code else "")


def run(s3, covers_dir, bucket: str, *, log: Callable[[str], None] = print) -> dict:
    """Upload the indexed covers to bucket and write the served map; return the run's counts."""
    covers_dir = Path(covers_dir)
    entries = load_index(covers_dir)
    log("s3 covers: %d indexed covers" % len(entries))

    result = {"uploaded": 0, "skipped": 0, "missing": 0, "mismatched": 0, "failed": 0, "served": 0}
    listed = _listing(s3, bucket)
    total = len(entries)
    for position, (cover_id, file, size) in enumerate(entries.values(), start=1):
        key = KEY_PREFIX + cover_id
        path = covers_dir / file  # load_index allowed only <id>.jpg or <id>.png
        content_type = _CONTENT_TYPES[Path(file).suffix]
        if listed.get(key) == size:
            result["skipped"] += 1
        elif not path.is_file():
            result["missing"] += 1
        else:
            try:
                body = path.read_bytes()
            except OSError as error:
                result["failed"] += 1
                log("s3 covers: cover %d/%d unreadable (%s)" % (position, total, type(error).__name__))
                body = None
            if body is not None and len(body) != size:
                result["mismatched"] += 1
            elif body is not None:
                try:
                    s3.put_object(Bucket=bucket, Key=key, Body=body, ContentType=content_type)
                except Exception as error:  # any client or transport error: count it and go on
                    result["failed"] += 1
                    log("s3 covers: cover %d/%d failed (%s)" % (position, total, _describe(error)))
                else:
                    result["uploaded"] += 1
        if position % PROGRESS_EVERY == 0:
            log("s3 covers: %d/%d uploaded=%d skipped=%d failed=%d"
                % (position, total, result["uploaded"], result["skipped"], result["failed"]))

    verified = _listing(s3, bucket)
    served = {anime_id: {"id": cover_id, "key": KEY_PREFIX + cover_id, "size": size}
              for anime_id, (cover_id, _file, size) in entries.items()
              if verified.get(KEY_PREFIX + cover_id) == size}
    _write_json_atomic(covers_dir / SERVED_MAP_NAME, served)
    result["served"] = len(served)
    log("s3 covers done: uploaded=%d skipped=%d missing=%d mismatched=%d failed=%d served=%d (%s)"
        % (result["uploaded"], result["skipped"], result["missing"], result["mismatched"],
           result["failed"], result["served"], SERVED_MAP_NAME))
    return result


# Command line.

def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="python -m songrush_data.s3covers",
                                     description="Upload the cached covers to the private S3 bucket.",
                                     allow_abbrev=False)
    parser.add_argument("--bucket", required=True, help="S3 bucket name")
    parser.add_argument("--profile", default=None,
                        help="AWS profile name (default: the standard credential chain)")
    parser.add_argument("--covers", default=str(DEFAULT_COVERS_DIR),
                        help="cover cache folder (default: data/cover-cache)")
    return parser


def main(argv=None) -> int:
    _utf8_console()
    try:
        args = _parser().parse_args(argv)
    except SystemExit as error:
        # argparse exits on --help (0) and on usage errors (2); return instead of raising.
        return 0 if error.code == 0 else 2
    covers_dir = Path(args.covers)
    # Check the index before any client exists, so a bad file costs no S3 call and no write.
    try:
        load_index(covers_dir)
    except (OSError, ValueError) as error:
        # These messages are written above and name a path and an entry position only.
        print("s3 covers stopped: %s" % error, file=sys.stderr)
        return 1
    try:
        import boto3

        s3 = boto3.Session(profile_name=args.profile).client("s3", region_name=REGION)
        result = run(s3, covers_dir, args.bucket, log=print)
    except Exception as error:
        # No traceback and no text: frames and messages could show ids, keys or the endpoint URL.
        print("s3 covers failed: %s" % _describe(error), file=sys.stderr)
        return 1
    return 0 if result["failed"] == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
