# Subsets the bundled fonts (cycle 153). The full Google Fonts files live in
# tool/fonts-src/<family>/ (committed, not bundled); this writes the subsets
# under the same names to assets/fonts/<family>/, so pubspec.yaml is
# unchanged. Kept: ASCII, Latin-1, Latin Extended-A (romaji macrons), general
# punctuation, the symbols below, every non-ASCII character of lib/**/*.dart
# and of the ARB files, Hangul compatibility jamo and the 2,350 KS X 1001
# Hangul syllables (the 2-byte EUC-KR Hangul codes). A character the
# source font lacks stays missing; Flutter web falls back to the browser's
# fonts for it (kana and most kanji, rare Hangul syllables).
# IBM Plex Sans KR is only the fallback of Jua and Gowun Dodum
# (lib/ui/palette.dart), so it drops the Hangul both of them have: Flutter
# never reaches it for those. Give it back the full set (kind "text") if it
# becomes a primary family. JetBrains Mono (room codes, counters) keeps only
# ASCII, Latin-1 and the symbols. Needs fonttools (pip install fonttools). From mobile/:
#   python -I tool/subset_fonts.py           write the subsets, print sizes
#   python -I tool/subset_fonts.py --check   only verify the existing subsets
# --check exits 1 when a subset lacks a character of the ARB files, the
# dart sources or the kept ranges that its source font has (for the fallback,
# one that Jua or Gowun Dodum lacks).
import sys
from pathlib import Path

from fontTools import subset
from fontTools.ttLib import TTFont

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "tool" / "fonts-src"
OUT = ROOT / "assets" / "fonts"
FONTS = {
    "jua/Jua-Regular.ttf": "text",
    "gowundodum/GowunDodum-Regular.ttf": "text",
    "ibmplexsanskr/IBMPlexSansKR-Regular.ttf": "fallback",
    "ibmplexsanskr/IBMPlexSansKR-Medium.ttf": "fallback",
    "ibmplexsanskr/IBMPlexSansKR-Bold.ttf": "fallback",
    "jetbrainsmono/JetBrainsMono-wght.ttf": "mono",
}
PRIMARY = ("jua/Jua-Regular.ttf", "gowundodum/GowunDodum-Regular.ttf")
# Symbols the UI uses or a song title is likely to hold.
SYMBOLS = "✔✘✕✓✗▲▼►◄▶◀●○◆◇■□★☆♪♫♬♩♡♥♦♣♠∞×÷±−≠≤≥→←↑↓↔※〜～・「」『』【】〈〉《》、。！？（）：；，．"


def hangul_ks_x_1001():
    # Decode the 2-byte codes of the Hangul block (rows 0xB0..0xC8). Encoding
    # U+AC00..U+D7A3 would not do: Python's euc_kr codec writes the other
    # syllables as 8-byte jamo sequences instead of failing.
    out = {bytes([a, b]).decode("euc-kr") for a in range(0xB0, 0xC9) for b in range(0xA1, 0xFF)}
    assert len(out) == 2350
    return [ord(c) for c in out]


def source_chars():
    """Every printable character of the ARB files and the dart sources."""
    chars = set()
    for p in sorted((ROOT / "lib").rglob("*.dart")) + sorted((ROOT / "lib" / "l10n").glob("*.arb")):
        chars |= set(p.read_text(encoding="utf-8"))
    return {ord(c) for c in chars if c.isprintable()}


def wanted(kind, used):
    base = set(range(0x20, 0x7F)) | set(range(0xA0, 0x100)) | {ord(c) for c in SYMBOLS}
    base |= set(range(0x2010, 0x2028)) | set(range(0x2030, 0x205F))
    if kind == "mono":
        return base
    base |= set(range(0x100, 0x180)) | set(range(0x3130, 0x3190))
    base |= set(hangul_ks_x_1001()) | used
    if kind == "fallback":
        hangul = set(hangul_ks_x_1001()) | set(range(0x3130, 0x3190)) | {c for c in used if 0xAC00 <= c <= 0xD7A3}
        base -= hangul & cmap(SRC / PRIMARY[0]) & cmap(SRC / PRIMARY[1])
    return base


def cmap(path):
    with TTFont(path, lazy=True) as f:
        return set(f.getBestCmap())


def build(src, dst, codepoints):
    opts = subset.Options()
    opts.layout_features = ["*"]
    opts.name_IDs = ["*"]
    opts.name_languages = ["*"]
    opts.notdef_outline = True
    opts.glyph_names = False
    opts.hinting = False
    font = subset.load_font(str(src), opts)
    sub = subset.Subsetter(opts)
    sub.populate(unicodes=sorted(codepoints))
    sub.subset(font)
    dst.parent.mkdir(parents=True, exist_ok=True)
    subset.save_font(font, str(dst), opts)
    font.close()


def main(argv):
    if argv not in ([], ["--check"]):
        sys.exit("usage: subset_fonts.py [--check]")
    check_only = argv == ["--check"]
    used = source_chars()
    failed = False
    total_src = total_out = 0
    for rel, kind in FONTS.items():
        src, dst = SRC / rel, OUT / rel
        want = wanted(kind, used)
        if not check_only:
            build(src, dst, want)
        have = cmap(src)
        got = cmap(dst)
        missing = sorted((want & have) - got)
        lacks = sorted(c for c in used - have if c > 0x7F) if kind != "mono" else []
        total_src += src.stat().st_size
        total_out += dst.stat().st_size
        print(f"{rel}: {src.stat().st_size:,} -> {dst.stat().st_size:,} bytes, "
              f"{len(got):,} chars; source lacks {len(lacks)} of the app's non-ASCII chars")
        if missing:
            failed = True
            print("  MISSING from subset: " + "".join(chr(c) for c in missing))
    print(f"total: {total_src:,} -> {total_out:,} bytes")
    if failed:
        sys.exit(1)
    print("check: every wanted character the source fonts have is in the subsets")


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    main(sys.argv[1:])
