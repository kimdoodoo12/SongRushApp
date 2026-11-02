"""Wikidata labels per AniList id, batched and cached raw per id (cycle 019).

fetch_labels posts SPARQL queries for the AniList ids (P8729), validates each
batch response before caching anything from it, and stores the raw bindings
of every id, so a later change to the parsing applies to cached ids too.
A failing batch is skipped with a warning and the ids resolved so far are
returned. docs/cycles/019-korean-titles.md is the specification.
"""

import hashlib
import json
import urllib.parse
from typing import Callable

from songrush_data.http import FileCache, HttpError, PoliteClient, TransportError

WDQS_URL = "https://query.wikidata.org/sparql"
MAX_BATCH = 200
LANGS = ("ko", "en", "ja")
QUERY_TEMPLATE = (
    "SELECT ?id ?item ?ko ?en ?ja WHERE {\n"
    "  VALUES ?id { <values> }\n"
    "  ?item wdt:P8729 ?id .\n"
    '  OPTIONAL { ?item rdfs:label ?ko . FILTER(LANG(?ko) = "ko") }\n'
    '  OPTIONAL { ?item rdfs:label ?en . FILTER(LANG(?en) = "en") }\n'
    '  OPTIONAL { ?item rdfs:label ?ja . FILTER(LANG(?ja) = "ja") }\n'
    "}"
)


def build_query(ids) -> str:
    return QUERY_TEMPLATE.replace("<values>", " ".join('"%d"' % i for i in ids))


def _key(anilist_id: int) -> str:
    digest = hashlib.sha256(QUERY_TEMPLATE.encode("utf-8")).hexdigest()[:16]
    return "wd-%s-%d.json" % (digest, anilist_id)


def _labels(bindings: list) -> dict:
    """{"ko": [...], "en": [...], "ja": [...]} in binding order, duplicates removed."""
    labels = {lang: [] for lang in LANGS}
    for item in bindings:
        for lang in LANGS:
            cell = item.get(lang)
            value = cell.get("value") if isinstance(cell, dict) else None
            if isinstance(value, str) and value not in labels[lang]:
                labels[lang].append(value)
    return labels


def _is_bindings(value) -> bool:
    return isinstance(value, list) and all(isinstance(item, dict) for item in value)


def _read_cached(cache: FileCache, anilist_id: int) -> tuple[bool, list | None]:
    """Return (hit, bindings). A damaged entry is deleted and counts as a miss."""
    key = _key(anilist_id)
    data = cache.get(key)
    if data is None:
        return False, None
    try:
        record = json.loads(data)
    except ValueError:
        record = None
    if isinstance(record, dict) and _is_bindings(record.get("bindings")):
        return True, record["bindings"]
    cache.delete(key)
    return False, None


def _check_ids(ids) -> list:
    """Drop None, deduplicate keeping the first occurrence, and require plain ints."""
    seen = set()
    unique = []
    for anilist_id in ids:
        if anilist_id is None:
            continue
        if not isinstance(anilist_id, int) or isinstance(anilist_id, bool):
            raise ValueError("AniList ids must be int, got %r" % (anilist_id,))
        if anilist_id not in seen:
            seen.add(anilist_id)
            unique.append(anilist_id)
    return unique


def _validate_batch(body, batch: list) -> dict:
    """Return {id: [raw bindings]} for every id of one batch, or raise ValueError."""
    if not isinstance(body, dict):
        raise ValueError("wikidata batch: response is not a JSON object")
    results = body.get("results")
    bindings = results.get("bindings") if isinstance(results, dict) else None
    if not isinstance(bindings, list):
        raise ValueError("wikidata batch: results.bindings is missing")
    by_value = {str(anilist_id): anilist_id for anilist_id in batch}
    found = {anilist_id: [] for anilist_id in batch}
    for item in bindings:
        if not isinstance(item, dict):
            raise ValueError("wikidata batch: binding is not an object: %r" % (item,))
        cell = item.get("id")
        value = cell.get("value") if isinstance(cell, dict) else None
        if not isinstance(value, str) or value not in by_value:
            raise ValueError("wikidata batch: binding has unrequested id %r" % (value,))
        found[by_value[value]].append(item)
    return found


def _fetch_batch(client: PoliteClient, batch: list) -> dict:
    reply = client.request(
        "POST",
        WDQS_URL,
        headers={
            "Content-Type": "application/x-www-form-urlencoded",
            "Accept": "application/sparql-results+json",
        },
        body=urllib.parse.urlencode({"query": build_query(batch), "format": "json"}).encode("utf-8"),
    )
    try:
        body = json.loads(reply.body)
    except ValueError as error:
        raise ValueError("wikidata batch: response is not valid JSON: %s" % error) from error
    return _validate_batch(body, batch)


def fetch_labels(
    client: PoliteClient,
    cache: FileCache,
    ids,
    *,
    batch_size: int = MAX_BATCH,
    log: Callable[[str], None] = print,
) -> dict:
    """Return {id: {"ko": [...], "en": [...], "ja": [...]}} for every resolved id."""
    if isinstance(batch_size, bool) or not isinstance(batch_size, int) or not 1 <= batch_size <= MAX_BATCH:
        raise ValueError("batch_size must be between 1 and %d, got %r" % (MAX_BATCH, batch_size))
    unique = _check_ids(ids)

    raw = {}
    uncached = []
    for anilist_id in unique:
        hit, bindings = _read_cached(cache, anilist_id)
        if hit:
            raw[anilist_id] = bindings
        else:
            uncached.append(anilist_id)
    hits = len(unique) - len(uncached)

    batches = [uncached[i:i + batch_size] for i in range(0, len(uncached), batch_size)]
    for number, batch in enumerate(batches, start=1):
        log("wikidata batch %d/%d ids=%d" % (number, len(batches), len(batch)))
        try:
            found = _fetch_batch(client, batch)
        except (HttpError, TransportError, ValueError) as error:
            # The caller continues without Wikidata for the remaining ids.
            log("warning: wikidata skipped: %s: %s" % (type(error).__name__, error))
            break
        # Validation passed for the whole batch; only now is anything stored.
        for anilist_id in batch:
            bindings = found[anilist_id]
            cache.put(_key(anilist_id), json.dumps({"bindings": bindings}, ensure_ascii=False).encode("utf-8"))
            raw[anilist_id] = bindings

    result = {anilist_id: _labels(raw[anilist_id]) for anilist_id in unique if anilist_id in raw}
    with_ko = sum(1 for labels in result.values() if labels["ko"])
    log("wikidata ids=%d with_ko=%d cached=%d" % (len(result), with_ko, hits))
    return result
