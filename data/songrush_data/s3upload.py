"""S3 audio upload: the cached audio into the private bucket (cycle 074, C-017).

run uploads every file listed in cache_dir/index.json under a random object key,
resumably: the work map s3-keys.work.json keeps each basename's key between
runs, objects already listed with the index size are skipped, and the served
map s3-keys.json, which the server reads, is written last and holds only the
objects a final listing confirms. main is the command line:
python -m songrush_data.s3upload --bucket NAME [--profile NAME] [--cache DIR].
docs/cycles/074-s3-audio.md is the specification; its "Revisions after spec
review round 1" section governs. The log never carries an object key, a
basename or a credential: those would identify the songs or open the bucket.
"""

import argparse
import json
import os
import secrets
import sys
from pathlib import Path
from typing import Callable

from songrush_data.audio import _BASENAME, DEFAULT_CACHE_DIR, INDEX_KEY, _replace

REGION = "ap-southeast-2"
KEY_PREFIX = "a/"
CONTENT_TYPE = "audio/ogg"
WORK_MAP_NAME = "s3-keys.work.json"
SERVED_MAP_NAME = "s3-keys.json"
PROGRESS_EVERY = 25
MAX_KEY_ATTEMPTS = 10


def _default_new_key() -> str:
    return KEY_PREFIX + secrets.token_urlsafe(16)


# Files.

def _write_json_atomic(path: Path, value) -> None:
    """Write value as JSON to path through a temp file and a replace, so a reader never sees half a file."""
    part = path.with_name(path.name + ".part")
    data = json.dumps(value, ensure_ascii=True, indent=1, sort_keys=True).encode("ascii")
    try:
        with open(part, "wb") as handle:
            handle.write(data)
            handle.flush()
            os.fsync(handle.fileno())
        _replace(part, path)
    except OSError:
        part.unlink(missing_ok=True)
        raise


def load_index(cache_dir) -> dict:
    """{basename: size} from cache_dir/index.json; FileNotFoundError when absent, ValueError when malformed."""
    path = Path(cache_dir) / INDEX_KEY
    try:
        index = json.loads(path.read_bytes().decode("utf-8"))
    except FileNotFoundError:
        raise FileNotFoundError("%s is missing: run the audio prefetch first" % path) from None
    except ValueError as error:
        raise ValueError("%s is not valid UTF-8 JSON" % path) from error
    if not isinstance(index, dict):
        raise ValueError("%s is not a JSON object" % path)
    sizes = {}
    for position, entry in enumerate(index.values(), start=1):
        basename = entry.get("basename") if isinstance(entry, dict) else None
        size = entry.get("size") if isinstance(entry, dict) else None
        # The messages name the entry's position only: a basename identifies the song.
        if not isinstance(basename, str) or not _BASENAME.fullmatch(basename):
            raise ValueError("%s entry %d has a missing or unsafe basename" % (path, position))
        if not isinstance(size, int) or isinstance(size, bool) or size <= 0:
            raise ValueError("%s entry %d has an invalid size" % (path, position))
        if sizes.get(basename, size) != size:
            raise ValueError("%s entry %d repeats a basename with another size" % (path, position))
        sizes[basename] = size
    return sizes


def load_work_map(cache_dir) -> dict:
    """{basename: key} from the work map; {} when absent, ValueError naming the file when malformed."""
    path = Path(cache_dir) / WORK_MAP_NAME
    try:
        work = json.loads(path.read_bytes().decode("utf-8"))
    except FileNotFoundError:
        return {}
    except OSError as error:
        raise ValueError("%s is unreadable: %s" % (path, type(error).__name__)) from error
    except ValueError as error:
        raise ValueError("%s is not valid UTF-8 JSON" % path) from error
    if not isinstance(work, dict):
        raise ValueError("%s is not a JSON object" % path)
    for basename, key in work.items():
        if not isinstance(key, str) or not key.startswith(KEY_PREFIX) or len(key) <= len(KEY_PREFIX):
            raise ValueError("%s has an entry whose key is not a string starting with %s" % (path, KEY_PREFIX))
    if len(set(work.values())) != len(work):
        raise ValueError("%s gives one key to two basenames" % path)
    return work


# S3.

def _listing(s3, bucket: str) -> dict:
    """{key: size} of every object under the key prefix, across all pages."""
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


def _error_code(error: BaseException) -> str:
    """The S3 error code of a service error, or "" when there is none.

    Transport errors (botocore's HTTPClientError subclasses) carry response None,
    and others have no response attribute at all.
    """
    response = getattr(error, "response", None)
    details = response.get("Error") if isinstance(response, dict) else None
    code = details.get("Code") if isinstance(details, dict) else None
    return code if isinstance(code, str) else ""


def run(s3, cache_dir, bucket: str, *, log: Callable[[str], None] = print,
        new_key: Callable[[], str] = None) -> dict:
    """Upload the cached audio to bucket and write the served map; return the run's counts."""
    cache_dir = Path(cache_dir)
    new_key = _default_new_key if new_key is None else new_key
    sizes = load_index(cache_dir)
    work = load_work_map(cache_dir)

    used = set(work.values())
    added = 0
    for basename in sorted(sizes):
        if basename in work:
            continue
        for _attempt in range(MAX_KEY_ATTEMPTS):
            key = new_key()
            if key not in used:
                break
        else:
            raise RuntimeError("new_key returned only keys already in use")
        work[basename] = key
        used.add(key)
        added += 1
    work_path = cache_dir / WORK_MAP_NAME
    if added or not work_path.exists():
        # Saved before any S3 call, so a key is never used for an upload it was not recorded for.
        _write_json_atomic(work_path, work)
    log("s3 upload: %d indexed files, %d new keys in %s" % (len(sizes), added, WORK_MAP_NAME))

    result = {"uploaded": 0, "skipped": 0, "missing": 0, "mismatched": 0, "failed": 0, "served": 0}
    listed = _listing(s3, bucket)
    names = sorted(sizes)
    for position, basename in enumerate(names, start=1):
        key, size = work[basename], sizes[basename]
        path = cache_dir / basename
        if listed.get(key) == size:
            result["skipped"] += 1
        elif not path.is_file():
            result["missing"] += 1
        else:
            try:
                body = path.read_bytes()
            except OSError as error:
                result["failed"] += 1
                log("s3 upload: file %d/%d unreadable (%s)" % (position, len(names), type(error).__name__))
                body = None
            if body is not None and len(body) != size:
                result["mismatched"] += 1
            elif body is not None:
                try:
                    s3.put_object(Bucket=bucket, Key=key, Body=body, ContentType=CONTENT_TYPE)
                except Exception as error:  # any client or transport error: count it and go on
                    result["failed"] += 1
                    # Never str(error): a transport error's text holds the endpoint URL, and so the key.
                    code = _error_code(error)
                    log("s3 upload: file %d/%d failed (%s%s)"
                        % (position, len(names), type(error).__name__, " " + code if code else ""))
                else:
                    result["uploaded"] += 1
        if position % PROGRESS_EVERY == 0:
            log("s3 upload: %d/%d uploaded=%d skipped=%d failed=%d"
                % (position, len(names), result["uploaded"], result["skipped"], result["failed"]))

    verified = _listing(s3, bucket)
    served = {basename: {"key": work[basename], "size": size}
              for basename, size in sizes.items() if verified.get(work[basename]) == size}
    _write_json_atomic(cache_dir / SERVED_MAP_NAME, served)
    result["served"] = len(served)
    log("s3 upload done: uploaded=%d skipped=%d missing=%d mismatched=%d failed=%d served=%d (%s)"
        % (result["uploaded"], result["skipped"], result["missing"], result["mismatched"],
           result["failed"], result["served"], SERVED_MAP_NAME))
    return result


# Command line.

def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="python -m songrush_data.s3upload",
                                     description="Upload the cached audio to the private S3 bucket.",
                                     allow_abbrev=False)
    parser.add_argument("--bucket", required=True, help="S3 bucket name")
    parser.add_argument("--profile", default=None,
                        help="AWS profile name (default: the standard credential chain)")
    parser.add_argument("--cache", default=str(DEFAULT_CACHE_DIR),
                        help="audio cache folder (default: data/audio-cache)")
    return parser


def _utf8_console() -> None:
    # The Windows console and pipes are cp949; line buffering keeps a long run's log current.
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
    cache_dir = Path(args.cache)
    # Check the inputs before any client exists, so a bad file costs no S3 call and no write.
    try:
        load_index(cache_dir)
        load_work_map(cache_dir)
    except (OSError, ValueError) as error:
        print("s3 upload stopped: %s" % error, file=sys.stderr)
        return 1
    try:
        import boto3

        s3 = boto3.Session(profile_name=args.profile).client("s3", region_name=REGION)
        result = run(s3, cache_dir, args.bucket, log=print)
    except Exception as error:
        # No traceback: its frames could show keys or basenames held in local variables.
        print("s3 upload failed: %s: %s" % (type(error).__name__, error), file=sys.stderr)
        return 1
    return 0 if result["failed"] == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
