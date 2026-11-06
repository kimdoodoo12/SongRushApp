"""AniList media fetch by id, batched and cached per id (cycle 018).

fetch_media posts id_in queries with literal ids, validates each batch
response before caching anything from it, and stores the raw AniList media
object per id, including a null record for ids AniList did not return.
Records are normalized on read, so normalizer changes apply to cached
records (cycle 020). docs/cycles/018-anilist-enrich.md and
docs/cycles/020-raw-cache-nfc.md are the specification.
"""

import hashlib
import json
from typing import Callable

from songrush_data.http import FileCache, PoliteClient

ANILIST_URL = "https://graphql.anilist.co"
MAX_BATCH = 50
MEDIA_FIELDS = (
    "id popularity title{romaji english native} synonyms "
    "relations{edges{relationType(version:2) node{id type format title{romaji english}}}}"
)


def build_query(ids) -> str:
    id_list = ",".join(str(i) for i in ids)
    return "query{Page(perPage:%d){media(id_in:[%s],type:ANIME){%s}}}" % (MAX_BATCH, id_list, MEDIA_FIELDS)


def _key(media_id: int) -> str:
    digest = hashlib.sha256(MEDIA_FIELDS.encode("utf-8")).hexdigest()[:16]
    return "al-%s-%d.json" % (digest, media_id)


def _dict(value) -> dict:
    return value if isinstance(value, dict) else {}


def _list(value) -> list:
    return value if isinstance(value, list) else []


def _normalize(raw: dict) -> dict:
    title = _dict(raw.get("title"))
    relations = []
    for edge in _list(_dict(raw.get("relations")).get("edges")):
        edge = _dict(edge)
        node = _dict(edge.get("node"))
        node_title = _dict(node.get("title"))
        relations.append({
            "type": edge.get("relationType"),
            "node": {
                "id": node.get("id"),
                "type": node.get("type"),
                "format": node.get("format"),
                "titles": {"romaji": node_title.get("romaji"), "english": node_title.get("english")},
            },
        })
    return {
        "popularity": raw.get("popularity"),
        "titles": {
            "romaji": title.get("romaji"),
            "english": title.get("english"),
            "native": title.get("native"),
        },
        "synonyms": list(_list(raw.get("synonyms"))),
        "relations": relations,
    }


def _read_cached(cache: FileCache, media_id: int) -> tuple[bool, dict | None]:
    """Return (hit, raw media). A corrupt entry is deleted and counts as a miss."""
    key = _key(media_id)
    data = cache.get(key)
    if data is None:
        return False, None
    try:
        record = json.loads(data)
    except ValueError:
        record = None
    if isinstance(record, dict) and "media" in record and (record["media"] is None or isinstance(record["media"], dict)):
        return True, record["media"]
    cache.delete(key)
    return False, None


def _check_ids(ids) -> list:
    """Drop None, deduplicate keeping the first occurrence, and require plain ints."""
    seen = set()
    unique = []
    for media_id in ids:
        if media_id is None:
            continue
        if not isinstance(media_id, int) or isinstance(media_id, bool):
            raise ValueError("AniList ids must be int, got %r" % (media_id,))
        if media_id not in seen:
            seen.add(media_id)
            unique.append(media_id)
    return unique


def _validate_batch(body, batch: list) -> dict:
    """Return {id: raw media} for one batch response, or raise ValueError."""
    if not isinstance(body, dict):
        raise ValueError("anilist batch: response is not a JSON object")
    if body.get("errors"):
        raise ValueError("anilist batch: GraphQL errors: %r" % (body["errors"],))
    data = body.get("data")
    page = data.get("Page") if isinstance(data, dict) else None
    media = page.get("media") if isinstance(page, dict) else None
    if not isinstance(media, list):
        raise ValueError("anilist batch: data.Page.media is missing")
    requested = set(batch)
    found = {}
    for item in media:
        if not isinstance(item, dict):
            raise ValueError("anilist batch: media entry is not an object: %r" % (item,))
        media_id = item.get("id")
        if isinstance(media_id, bool) or not isinstance(media_id, int) or media_id not in requested:
            raise ValueError("anilist batch: response has unrequested media id %r" % (media_id,))
        # A repeated id keeps its first record.
        found.setdefault(media_id, item)
    return found


def _fetch_batch(client: PoliteClient, batch: list) -> dict:
    reply = client.request(
        "POST",
        ANILIST_URL,
        headers={"Content-Type": "application/json"},
        body=json.dumps({"query": build_query(batch)}).encode("utf-8"),
    )
    try:
        body = json.loads(reply.body)
    except ValueError as error:
        raise ValueError("anilist batch: response is not valid JSON: %s" % error) from error
    return _validate_batch(body, batch)


def fetch_media(
    client: PoliteClient,
    cache: FileCache,
    ids,
    *,
    batch_size: int = MAX_BATCH,
    log: Callable[[str], None] = print,
) -> dict:
    """Return {id: normalized media or None} for every requested id."""
    if isinstance(batch_size, bool) or not isinstance(batch_size, int) or not 1 <= batch_size <= MAX_BATCH:
        raise ValueError("batch_size must be between 1 and %d, got %r" % (MAX_BATCH, batch_size))
    unique = _check_ids(ids)

    result = {}
    uncached = []
    for media_id in unique:
        hit, raw = _read_cached(cache, media_id)
        if hit:
            result[media_id] = _normalize(raw) if raw is not None else None
        else:
            uncached.append(media_id)
    hits = len(unique) - len(uncached)

    batches = [uncached[i:i + batch_size] for i in range(0, len(uncached), batch_size)]
    for number, batch in enumerate(batches, start=1):
        log("anilist batch %d/%d ids=%d" % (number, len(batches), len(batch)))
        found = _fetch_batch(client, batch)
        # Validation passed for the whole batch; only now is anything stored.
        for media_id in batch:
            raw = found.get(media_id)
            cache.put(_key(media_id), json.dumps({"media": raw}, ensure_ascii=False).encode("utf-8"))
            result[media_id] = _normalize(raw) if raw is not None else None

    ordered = {media_id: result[media_id] for media_id in unique}
    missing = [media_id for media_id, media in ordered.items() if media is None]
    log("anilist media=%d missing=%d cached=%d" % (len(ordered) - len(missing), len(missing), hits))
    if missing:
        log("anilist missing ids: %s" % ",".join(str(i) for i in missing))
    return ordered
