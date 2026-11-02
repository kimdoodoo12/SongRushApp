"""Korean strings per AniList id from the pinned anime-offline-database (cycle 019).

load_korean reads the pinned release file, checks its size and sha256, and
downloads it once when it is missing or damaged. korean_by_anilist maps each
AniList id to the record's Hangul title and synonyms. A failing source is
skipped with a warning. docs/cycles/019-korean-titles.md is the specification.
"""

import hashlib
import json
import re
import unicodedata
from pathlib import Path
from typing import Callable

from songrush_data.http import FileCache, HttpError, PoliteClient, TransportError
from songrush_data.titles import has_hangul

AOD_RELEASE = "2026-27"
AOD_URL = (
    "https://github.com/manami-project/anime-offline-database/releases/download/"
    "%s/anime-offline-database-minified.json" % AOD_RELEASE
)
AOD_SIZE = 62331136
AOD_SHA256 = "395be786fb2f98f1fca9e963911b9332110098a080e7e2569902bbc5641d1a41"

_ANILIST_SOURCE = re.compile(r"^https://anilist\.co/anime/(\d+)$")


def _check_document(document) -> list:
    if not isinstance(document, dict) or not isinstance(document.get("data"), list):
        raise ValueError("aod: document is not an object with a data list")
    return document["data"]


def korean_by_anilist(document) -> dict:
    """Return {AniList id: [Hangul title and synonyms]} for ids with any Hangul string."""
    result: dict[int, list[str]] = {}
    for record in _check_document(document):
        if not isinstance(record, dict):
            continue
        sources, title, synonyms = record.get("sources"), record.get("title"), record.get("synonyms")
        if not isinstance(sources, list) or not isinstance(synonyms, list) or not isinstance(title, str):
            continue
        ids = []
        for source in sources:
            match = _ANILIST_SOURCE.fullmatch(source) if isinstance(source, str) else None
            if match and int(match.group(1)) not in ids:
                ids.append(int(match.group(1)))
        korean = []
        for text in [title] + synonyms:
            if not isinstance(text, str):
                continue
            # Decomposed (NFD) Hangul is jamo, which has_hangul does not see.
            text = unicodedata.normalize("NFC", text)
            if has_hangul(text) and text not in korean:
                korean.append(text)
        if not korean:
            continue
        for anilist_id in ids:
            merged = result.setdefault(anilist_id, [])
            merged.extend(text for text in korean if text not in merged)
    return result


def _parse(data: bytes, expected_size: int, expected_sha256: str) -> dict:
    """Return the parsed document when the bytes are valid, otherwise raise ValueError."""
    if len(data) != expected_size:
        raise ValueError("aod: size %d does not match expected %d" % (len(data), expected_size))
    digest = hashlib.sha256(data).hexdigest()
    if digest != expected_sha256:
        raise ValueError("aod: sha256 %s does not match expected %s" % (digest, expected_sha256))
    try:
        document = json.loads(data)
    except ValueError as error:
        raise ValueError("aod: file is not valid JSON: %s" % error) from error
    _check_document(document)
    return document


def load_korean(
    client: PoliteClient,
    path: Path,
    *,
    expected_size: int = AOD_SIZE,
    expected_sha256: str = AOD_SHA256,
    log: Callable[[str], None] = print,
) -> dict:
    """Return korean_by_anilist of the pinned release, or {} when aod is unavailable."""
    path = Path(path)
    try:
        document = None
        if path.exists():
            try:
                document = _parse(path.read_bytes(), expected_size, expected_sha256)
            except ValueError:
                path.unlink()
                log("aod cached file invalid, refetching")
        if document is None:
            body = client.request("GET", AOD_URL).body
            document = _parse(body, expected_size, expected_sha256)
            FileCache(path.parent).put(path.name, body)
            log("aod downloaded %d bytes" % len(body))
        result = korean_by_anilist(document)
    except (HttpError, TransportError, ValueError, OSError) as error:
        # The build continues without aod's Korean strings.
        log("warning: aod skipped: %s: %s" % (type(error).__name__, error))
        return {}
    log("aod release=%s records=%d anilist_ids_with_korean=%d" % (AOD_RELEASE, len(document["data"]), len(result)))
    return result
