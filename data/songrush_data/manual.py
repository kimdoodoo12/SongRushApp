"""The hand-edited Korean title file, data/korean_titles_manual.json (cycle 028).

load_manual reads and validates the file the owner fills in: AniList ids (as
decimal digit strings) to lists of Korean titles; keys starting with "_" are
comments. A malformed file raises ValueError so the build stops loudly.
merge_korean concatenates Korean title maps in priority order.
docs/cycles/028-manual-korean.md is the specification.
"""

import json
import re
import unicodedata
from pathlib import Path
from typing import Callable

from songrush_data.titles import has_hangul

_DIGITS = re.compile(r"[0-9]+")

# Korean hints for the owner, who edits this file by hand.
_JSON_HINT = "\ub9c8\uc9c0\ub9c9 \ud56d\ubaa9 \ub4a4\uc5d0 \uc27c\ud45c\uac00 \uc788\ub294\uc9c0, \ud0a4\ub97c \ud070\ub530\uc634\ud45c\ub85c \uac10\uc30c\ub294\uc9c0 \ud655\uc778\ud558\uc138\uc694"
_UTF8_HINT = "\ud30c\uc77c\uc744 UTF-8\ub85c \uc800\uc7a5\ud558\uc138\uc694"


class _DuplicateKey(Exception):
    def __init__(self, key: str):
        super().__init__(key)
        self.key = key


def _reject_duplicates(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise _DuplicateKey(key)
        result[key] = value
    return result


def _parse(path: Path, data: bytes) -> dict:
    try:
        text = data.decode("utf-8-sig")
    except UnicodeDecodeError as error:
        raise ValueError("%s: not UTF-8 (byte %d): %s" % (path, error.start, _UTF8_HINT)) from error
    try:
        document = json.loads(text, object_pairs_hook=_reject_duplicates)
    except _DuplicateKey as error:
        raise ValueError("%s: key %r appears more than once" % (path, error.key)) from None
    except json.JSONDecodeError as error:
        raise ValueError("%s: invalid JSON: %s at line %d column %d: %s"
                         % (path, error.msg, error.lineno, error.colno, _JSON_HINT)) from error
    if not isinstance(document, dict):
        raise ValueError("%s: the top level must be a JSON object" % path)
    return document


def load_manual(path, *, log: Callable[[str], None] = print) -> dict:
    """Return {anilist_id: [korean titles]} from the manual file, or {} when it is missing."""
    path = Path(path)
    try:
        data = path.read_bytes()
    except FileNotFoundError:
        log("manual korean: %s not found, skipped" % path)
        return {}
    document = _parse(path, data)

    result = {}
    for key, value in document.items():
        if key.startswith("_"):
            continue
        if not _DIGITS.fullmatch(key):
            raise ValueError("%s: key %r is not an AniList id (decimal digits)" % (path, key))
        if not isinstance(value, list):
            raise ValueError("%s: key %r: the value must be a list of titles" % (path, key))
        anilist_id = int(key)
        if anilist_id in result:
            raise ValueError("%s: key %r repeats AniList id %d" % (path, key, anilist_id))
        titles = []
        for item in value:
            if not isinstance(item, str):
                raise ValueError("%s: key %r: %r is not a string" % (path, key, item))
            title = unicodedata.normalize("NFC", item.strip())
            if not title:
                continue
            if not has_hangul(title):
                raise ValueError("%s: key %r: %r has no Hangul syllable" % (path, key, title))
            if title not in titles:
                titles.append(title)
        if titles:
            result[anilist_id] = titles

    log("manual korean: %d ids, %d titles" % (len(result), sum(len(t) for t in result.values())))
    return result


def merge_korean(*sources) -> dict:
    """Concatenate each id's lists across sources in order, dropping duplicates."""
    merged = {}
    for source in sources:
        for anilist_id, titles in source.items():
            target = merged.setdefault(anilist_id, [])
            for title in titles:
                if title not in target:
                    target.append(title)
    return merged
