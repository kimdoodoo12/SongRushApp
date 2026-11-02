"""Title rules shared by the data tools and the Java backend (cycle 015).

normalize, strip_qualifier, has_hangul and head follow
docs/cycles/015-title-normalize.md; contracts/normalize-vectors.json pins
normalize. The source stays ASCII, so non-ASCII literals are built with chr().
"""

import re
import unicodedata

# Step 2: explicit mappings after str.lower() (Java toLowerCase(Locale.ROOT)).
_SHARP_S = chr(0x00DF)
_FINAL_SIGMA = chr(0x03C2)
_SIGMA = chr(0x03C3)

# Step 4: the multiplication sign becomes a plain x.
_TIMES = chr(0x00D7)

# Step 5: removed general categories and extra code points.
_REMOVED_CATEGORY_PREFIXES = ("P", "S", "Z")
_REMOVED_CATEGORIES = frozenset({"Cc", "Cf"})
_REMOVED_CODE_POINTS = frozenset(
    {0x115F, 0x1160, 0x3164, 0xFFA0}  # Hangul fillers
    | set(range(0xFE00, 0xFE10))  # variation selectors
    | set(range(0x180B, 0x1810))  # Mongolian variation selectors
    | set(range(0xE0100, 0xE01F0))  # ideographic variation selectors
)


def _is_removed(ch: str) -> bool:
    if ord(ch) in _REMOVED_CODE_POINTS:
        return True
    category = unicodedata.category(ch)
    return category[0] in _REMOVED_CATEGORY_PREFIXES or category in _REMOVED_CATEGORIES


def normalize(s: str) -> str:
    """Fold a title for matching: width, case, diacritics, punctuation, spaces."""
    s = unicodedata.normalize("NFKC", s)
    s = s.lower().replace(_SHARP_S, "ss").replace(_FINAL_SIGMA, _SIGMA)
    s = unicodedata.normalize("NFD", s)
    s = "".join(ch for ch in s if not 0x0300 <= ord(ch) <= 0x036F)
    s = unicodedata.normalize("NFC", s)
    s = s.replace(_TIMES, "x")
    s = "".join(ch for ch in s if not _is_removed(ch))
    return unicodedata.normalize("NFC", s)


def has_hangul(s: str) -> bool:
    """True when s has a Hangul syllable (U+AC00-U+D7A3)."""
    return any(0xAC00 <= ord(ch) <= 0xD7A3 for ch in s)


# Ranges that make a remainder "Hangul, kana or CJK" for the 2-character minimum.
_CJK_RANGES = (
    (0x1100, 0x11FF),  # Hangul Jamo
    (0x3130, 0x318F),  # Hangul Compatibility Jamo
    (0xA960, 0xA97F),  # Hangul Jamo Extended-A
    (0xAC00, 0xD7AF),  # Hangul Syllables
    (0xD7B0, 0xD7FF),  # Hangul Jamo Extended-B
    (0x3040, 0x309F),  # Hiragana
    (0x30A0, 0x30FF),  # Katakana
    (0x31F0, 0x31FF),  # Katakana Phonetic Extensions
    (0xFF66, 0xFF9F),  # halfwidth katakana
    (0x3400, 0x4DBF),  # CJK Extension A
    (0x4E00, 0x9FFF),  # CJK Unified Ideographs
    (0xF900, 0xFAFF),  # CJK Compatibility Ideographs
    (0x20000, 0x3134F),  # CJK Extensions B-G and compatibility supplement
)


def _has_cjk(s: str) -> bool:
    return any(lo <= ord(ch) <= hi for ch in s for lo, hi in _CJK_RANGES)


_KO_ANIMATION = "".join(chr(c) for c in (0xC560, 0xB2C8, 0xBA54, 0xC774, 0xC158))
_KO_GI = chr(0xAE30)
_KO_SEASON = chr(0xC2DC) + chr(0xC98C)
_KO_HWA = chr(0xD654)
_JA_DAI = chr(0x7B2C)
_JA_KI = chr(0x671F)

_QUALIFIERS = (
    r"season\s+\d+",
    r"\d+(?:st|nd|rd|th)\s+season",
    r"part\s+\d+",
    r"the\s+final\s+season",
    r"final\s+season",
    _JA_DAI + r"\d+" + _JA_KI,
    r"\d+" + _KO_GI,
    _KO_SEASON + r"\s*\d+",
    r"\d+" + _KO_HWA,
    r"\(\d{4}\)",
    r"\(tv\)",
    r"\([^()]*" + _KO_ANIMATION + r"\)",
)

# The lookbehind is the boundary (whitespace, ':' or ','), which also keeps a
# qualifier from starting the string. re.search returns the leftmost start,
# and every alternative is anchored at the end, so the longest match wins.
_QUALIFIER_RE = re.compile(
    r"(?<=[\s:,])(?:" + "|".join(_QUALIFIERS) + r")\Z", re.IGNORECASE
)
_TRAILING_SEPARATORS_RE = re.compile(r"[\s:,]+\Z")


def strip_qualifier(s: str) -> str:
    """Remove one trailing season/part/year qualifier, or return s unchanged."""
    trimmed = s.strip()
    match = _QUALIFIER_RE.search(trimmed)
    if match is None:
        return s
    remainder = _TRAILING_SEPARATORS_RE.sub("", trimmed[: match.start()])
    minimum = 2 if _has_cjk(remainder) else 4
    if len(normalize(remainder)) < minimum:
        return s
    return remainder


def head(s: str) -> str | None:
    """normalize() of the text before the first '/' or ':', if 4+ characters."""
    cut = min((i for i in (s.find("/"), s.find(":")) if i >= 0), default=-1)
    if cut < 0:
        return None
    result = normalize(s[:cut])
    return result if len(result) >= 4 else None
