"""Cycle 015 attempt 2: explicit strip_qualifier and head rules.

Pins the rules in docs/cycles/015-title-normalize.md, section "Code review
(critic) and attempt 2", and kills each mutation the critic reported as
surviving the first test set. The source stays ASCII, so non-ASCII text is
built from code points with chr().
"""

import pytest


def _u(*code_points):
    return "".join(chr(c) for c in code_points)


SPACE = " "
NBSP = chr(0x00A0)
IDEOGRAPHIC_SPACE = chr(0x3000)
INFO_SEPARATOR_4 = chr(0x001C)  # Python str.isspace() is True; spec says no
KATAKANA_MIDDLE_DOT = chr(0x30FB)
ARABIC_INDIC_THREE = chr(0x0663)
FULLWIDTH_TWO = chr(0xFF12)
HANGUL_JAMO_KIEUK = chr(0x314B)
CJK_EXT_B_YOSHI = chr(0x20BB7)

KIMETSU_KO = _u(0xADC0, 0xBA78, 0xC758) + SPACE + _u(0xCE7C, 0xB0A0)
NARUTO_KO = _u(0xB098, 0xB8E8, 0xD1A0)
SHINGEKI_JA = _u(0x9032, 0x6483, 0x306E, 0x5DE8, 0x4EBA)
KO_SEASON = _u(0xC2DC, 0xC98C)
KO_HWA = chr(0xD654)
JA_DAI = chr(0x7B2C)
JA_KI = chr(0x671F)


STRIP_CASES = [
    # Boundary rule: a qualifier must follow whitespace, ':' or ','.
    ("no_boundary_year", "Hunter x Hunter(2011)", "Hunter x Hunter(2011)"),
    # Each alternative the critic could remove without a failing test.
    ("tv", "Gintama (TV)", "Gintama"),
    ("part_n", "Title Part 2", "Title"),
    ("ko_season_space", KIMETSU_KO + " " + KO_SEASON + " 2", KIMETSU_KO),
    ("ko_season_no_space", KIMETSU_KO + " " + KO_SEASON + "2", KIMETSU_KO),
    ("ko_hwa", NARUTO_KO + " 12" + KO_HWA, NARUTO_KO),
    # Latin qualifiers need at least one whitespace before the number.
    ("latin_needs_space", "Gintama Season2", "Gintama Season2"),
    # Only one qualifier is removed.
    ("only_one", "Kimetsu Season 2 Part 2", "Kimetsu Season 2"),
    # No match returns s unchanged, untrimmed.
    ("no_match_untrimmed", "  Naruto  ", "  Naruto  "),
    ("case_insensitive", "GINTAMA SEASON 2", "GINTAMA"),
    # Whitespace set: U+0020, U+0009, U+00A0, U+3000.
    (
        "ideographic_space",
        SHINGEKI_JA + IDEOGRAPHIC_SPACE + JA_DAI + "2" + JA_KI,
        SHINGEKI_JA,
    ),
    ("nbsp", "Show" + NBSP + "Season 2", "Show"),
    ("not_whitespace_u001c", "Show" + INFO_SEPARATOR_4 + "Season 2",
     "Show" + INFO_SEPARATOR_4 + "Season 2"),
    # Digits: ASCII 0-9 or full-width only.
    (
        "fullwidth_digit",
        SHINGEKI_JA + " " + JA_DAI + FULLWIDTH_TWO + JA_KI,
        SHINGEKI_JA,
    ),
    ("arabic_indic_digit", "Show Season " + ARABIC_INDIC_THREE,
     "Show Season " + ARABIC_INDIC_THREE),
    # Script check: only Lo/Lm in Hangul syllables, kana and CJK count;
    # the check runs on the remainder, not the whole string.
    ("middle_dot_not_letter", "A" + KATAKANA_MIDDLE_DOT + "B Season 2",
     "A" + KATAKANA_MIDDLE_DOT + "B Season 2"),
    ("jamo_not_counted", HANGUL_JAMO_KIEUK * 2 + " Season 2",
     HANGUL_JAMO_KIEUK * 2 + " Season 2"),
    # Lengths in code points: one supplementary CJK ideograph is 1 < 2.
    (
        "one_code_point_cjk",
        CJK_EXT_B_YOSHI + " " + JA_DAI + "2" + JA_KI,
        CJK_EXT_B_YOSHI + " " + JA_DAI + "2" + JA_KI,
    ),
]


@pytest.mark.parametrize(
    "given,expected",
    [(c[1], c[2]) for c in STRIP_CASES],
    ids=[c[0] for c in STRIP_CASES],
)
def test_strip_qualifier_rules(given, expected):
    from songrush_data.titles import strip_qualifier

    assert strip_qualifier(given) == expected


HEAD_CASES = [
    ("first_separator_slash", "Fate/stay night: Unlimited Blade Works", "fate"),
    ("minimum_is_4", "Re:Zero", None),
    ("first_separator_colon", "Fate:Zero/x", "fate"),
    ("first_not_preferred", "Ab/Cdefgh:Ijkl", None),
]


@pytest.mark.parametrize(
    "given,expected",
    [(c[1], c[2]) for c in HEAD_CASES],
    ids=[c[0] for c in HEAD_CASES],
)
def test_head_rules(given, expected):
    from songrush_data.titles import head

    assert head(given) == expected


def test_this_file_is_ascii():
    from pathlib import Path

    Path(__file__).read_text(encoding="ascii")
