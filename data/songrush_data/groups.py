"""Series groups, title slots, accepted answers and reveal titles (cycle 021).

docs/cycles/021-groups-titles.md is the specification. build_titles is pure:
no I/O, and the output depends only on the input values, not on dict, set or
list ordering of ids and relations. The source stays ASCII, so non-ASCII
literals are built with chr().
"""

import re
import unicodedata

from songrush_data.titles import _has_cjk, has_hangul, head, normalize, strip_qualifier

SLOTS = ("ko", "en", "ja", "romaji")

# Step 1: relation types that connect members into relation components.
_COMPONENT_TYPES = frozenset({
    "SEQUEL", "PREQUEL", "SIDE_STORY", "PARENT", "ALTERNATIVE", "SUMMARY",
    "SPIN_OFF", "COMPILATION", "CONTAINS",
})
# Step 2: the ends of these edges are always joined (D-045).
_ALWAYS_JOIN_TYPES = frozenset({"COMPILATION", "CONTAINS"})

_MIN_BASE = 4

# Reveal: a ko label ending in "<digits>hwa" is an episode, and a trailing
# " (...aenimeisyeon)" parenthetical is removed.
_KO_ANIMATION = "".join(chr(c) for c in (0xC560, 0xB2C8, 0xBA54, 0xC774, 0xC158))
_KO_HWA = chr(0xD654)
_DIGIT = "[0-9" + chr(0xFF10) + "-" + chr(0xFF19) + "]"
_EPISODE_RE = re.compile(_DIGIT + "+" + _KO_HWA + r"\Z")
_DIGITS = frozenset("0123456789" + "".join(chr(c) for c in range(0xFF10, 0xFF1A)))
_FULL_WIDTH_COLON = chr(0xFF1A)
_ANIMATION_SUFFIX_RE = re.compile(r" \([^()]*" + _KO_ANIMATION + r"\)\Z")
# Front-part separators (D-049, D-051). Multi-character ones match as strings;
# single characters match anywhere. "/", the full-width slash and the wave
# dashes (~, full-width tilde, wave dash) are skipped between two digits.
_STRING_SEPARATORS = (": ", _FULL_WIDTH_COLON, " -")
_WAVE_DASHES = "~" + chr(0xFF5E) + chr(0x301C)
_SLASHES = "/" + chr(0xFF0F)
_DIGIT_EXCEPTION = frozenset(_WAVE_DASHES + _SLASHES)
_CHAR_SEPARATORS = frozenset(
    _WAVE_DASHES + _SLASHES + "".join(chr(c) for c in (0x2013, 0x2014, 0x2015, 0xFF0D))
)


def _text(value) -> str | None:
    return value if isinstance(value, str) and value else None


def _dedupe(strings) -> list[str]:
    out = []
    for s in strings:
        s = _text(s)
        if s is not None and s not in out:
            out.append(s)
    return out


def _is_latin(s: str) -> bool:
    """At least one letter, and every letter is a LATIN letter."""
    letters = [ch for ch in s if unicodedata.category(ch).startswith("L")]
    return bool(letters) and all(unicodedata.name(ch, "").startswith("LATIN") for ch in letters)


def _is_japanese(s: str) -> bool:
    """Kana or CJK ideographs, and no Hangul syllable."""
    return _has_cjk(s) and not has_hangul(s)


def _slots(media: dict | None, labels: dict, korean: list) -> dict:
    titles = (media or {}).get("titles") or {}
    synonyms = [s for s in (media or {}).get("synonyms") or [] if _text(s)]
    slots = {
        "ko": [s for s in _dedupe(list(labels.get("ko") or []) + list(korean)) if has_hangul(s)],
        "en": _dedupe([titles.get("english")] + list(labels.get("en") or [])),
        "ja": _dedupe([titles.get("native")] + list(labels.get("ja") or [])),
        "romaji": _dedupe([titles.get("romaji")]),
    }
    # Alias fallback (D-035): synonyms fill only a slot with no official title.
    for key in ("en", "romaji"):
        if not slots[key]:
            slots[key] = _dedupe(s for s in synonyms if _is_latin(s))
    if not slots["ja"]:
        slots["ja"] = _dedupe(s for s in synonyms if _is_japanese(s))
    return slots


def _reveal(media: dict | None, labels: dict, korean: list, slots: dict) -> str | None:
    for label in labels.get("ko") or []:
        label = _text(label)
        if label is None or not has_hangul(label) or _EPISODE_RE.search(label):
            continue
        return _ANIMATION_SUFFIX_RE.sub("", label)
    for s in korean:
        if _text(s) and has_hangul(s):
            return s
    titles = (media or {}).get("titles") or {}
    for key in ("english", "romaji"):
        if _text(titles.get(key)):
            return titles[key]
    for key in SLOTS:
        if slots[key]:
            return slots[key][0]
    return None


def front_parts(s: str) -> list[str]:
    """The text before each separator, in position order (D-049, D-051, D-052).

    Separators are ": ", a full-width colon, " -", the dashes and wave dashes
    in _CHAR_SEPARATORS, and "/" or a full-width slash. "/", the full-width
    slash and the wave dashes are not separators when both neighbours are
    digits ("Ranma 1/2", an episode range). Each part is right-stripped
    (including U+3000); a separator at index 0 or one that leaves nothing
    before it gives no part. Duplicates are dropped.
    """
    cuts = set()
    for sep in _STRING_SEPARATORS:
        i = s.find(sep)
        while i >= 0:
            cuts.add(i)
            i = s.find(sep, i + 1)
    for i, ch in enumerate(s):
        if ch not in _CHAR_SEPARATORS:
            continue
        if (ch in _DIGIT_EXCEPTION and 0 < i < len(s) - 1
                and s[i - 1] in _DIGITS and s[i + 1] in _DIGITS):
            continue
        cuts.add(i)
    out = []
    for cut in sorted(cuts):
        if cut <= 0:
            continue
        part = s[:cut].rstrip()
        if part and part not in out:
            out.append(part)
    return out


def front_part(s: str) -> str | None:
    """The first of front_parts(s), or None when there is none."""
    parts = front_parts(s)
    return parts[0] if parts else None


def _front_accepted(s: str) -> set:
    """normalize() of every front part and its unqualified form (D-050 minimum)."""
    out = set()
    for f in front_parts(s):
        minimum = 2 if _has_cjk(f) else _MIN_BASE
        for candidate in (normalize(f), normalize(strip_qualifier(f))):
            if len(candidate) >= minimum:
                out.add(candidate)
    return out


class _UnionFind:
    def __init__(self):
        self.parent = {}

    def add(self, x):
        self.parent.setdefault(x, x)

    def find(self, x):
        root = x
        while self.parent[root] != root:
            root = self.parent[root]
        while self.parent[x] != root:
            self.parent[x], x = root, self.parent[x]
        return root

    def union(self, a, b):
        ra, rb = self.find(a), self.find(b)
        if ra != rb:
            # Keep the smaller root so the structure does not depend on order.
            if rb < ra:
                ra, rb = rb, ra
            self.parent[rb] = ra


def _keys(title_strings) -> tuple[set, set]:
    """The bases (4+ characters) and heads of a member's titles."""
    bases, heads = set(), set()
    for t in title_strings:
        base = normalize(strip_qualifier(t))
        if len(base) >= _MIN_BASE:
            bases.add(base)
        h = head(t)
        if h is not None:
            heads.add(h)
    return bases, heads


def _join_by_titles(members, keys, groups: _UnionFind):
    """Join every pair of members that share a title (spec rule 2).

    Instead of comparing pairs, each base looks up its own prefixes (4+
    characters) among the bases and heads of the component, which finds
    exactly the pairs where one base prefixes another or a head prefixes a
    base. Equal heads are joined through a head index.
    """
    base_index, head_index = {}, {}
    for m in members:
        bases, heads = keys[m]
        for b in bases:
            base_index.setdefault(b, []).append(m)
        for h in heads:
            head_index.setdefault(h, []).append(m)
    for bucket in head_index.values():
        for m in bucket[1:]:
            groups.union(bucket[0], m)
    for m in members:
        for b in keys[m][0]:
            for k in range(_MIN_BASE, len(b) + 1):
                prefix = b[:k]
                for index in (base_index, head_index):
                    for other in index.get(prefix, ()):
                        groups.union(m, other)


def build_titles(catalog_ids, media, wikidata, korean) -> dict[int, dict]:
    """Return {id: {"group", "slots", "accepted", "reveal"}} for every catalog id."""
    catalog = sorted(set(catalog_ids))
    in_catalog = set(catalog)

    slots, reveals, own_titles = {}, {}, {}
    for anime_id in catalog:
        record = media.get(anime_id)
        labels = wikidata.get(anime_id) or {}
        strings = list(korean.get(anime_id) or [])
        slots[anime_id] = _slots(record, labels, strings)
        reveals[anime_id] = _reveal(record, labels, strings, slots[anime_id])
        titles = (record or {}).get("titles") or {}
        own_titles[anime_id] = _dedupe([titles.get("romaji"), titles.get("english")])

    # Relation edges listed by catalog anime; out-of-catalog nodes get the
    # titles of every edge that lists them.
    node_titles = {}
    edges = []
    for anime_id in catalog:
        for edge in (media.get(anime_id) or {}).get("relations") or []:
            node = (edge or {}).get("node") or {}
            node_id = node.get("id")
            if node.get("type") != "ANIME" or not isinstance(node_id, int):
                continue
            relation = edge.get("type")
            if relation not in _COMPONENT_TYPES:
                continue
            if node_id not in in_catalog:
                titles = node.get("titles") or {}
                found = node_titles.setdefault(node_id, set())
                for key in ("romaji", "english"):
                    if _text(titles.get(key)):
                        found.add(titles[key])
            if node_id != anime_id:
                edges.append((anime_id, node_id, relation))

    member_titles = dict(own_titles)
    for node_id, found in node_titles.items():
        member_titles[node_id] = sorted(found)

    # Step 1: relation components.
    components = _UnionFind()
    for m in member_titles:
        components.add(m)
    for a, b, _ in edges:
        components.union(a, b)
    by_component = {}
    for m in sorted(member_titles):
        by_component.setdefault(components.find(m), []).append(m)

    # Step 2: groups.
    keys = {m: _keys(titles) for m, titles in member_titles.items()}
    groups = _UnionFind()
    for m in member_titles:
        groups.add(m)
    for members in by_component.values():
        if len(members) > 1:
            _join_by_titles(members, keys, groups)
    for a, b, relation in edges:
        if relation in _ALWAYS_JOIN_TYPES:
            groups.union(a, b)

    # Rules 3 and 4: group id and accepted list per group.
    sources = {}
    group_id = {}
    for m in sorted(member_titles):
        root = groups.find(m)
        found = sources.setdefault(root, set())
        if m in in_catalog:
            group_id.setdefault(root, m)  # sorted, so the first is the smallest
            for slot in slots[m].values():
                found.update(slot)
        else:
            found.update(member_titles[m])
    accepted = {}
    for root, found in sources.items():
        folded = set()
        for s in found:
            folded.add(normalize(s))
            folded.add(normalize(strip_qualifier(s)))
            folded.update(_front_accepted(s))
        folded.discard("")
        accepted[root] = sorted(folded)

    out = {}
    for anime_id in catalog:
        root = groups.find(anime_id)
        out[anime_id] = {
            "group": group_id[root],
            "slots": slots[anime_id],
            "accepted": list(accepted[root]),
            "reveal": reveals[anime_id],
        }
    return out
