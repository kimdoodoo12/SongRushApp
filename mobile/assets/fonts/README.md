# Bundled fonts

The four families of the web's room stylesheet (`frontend/src/room.css` `:root`):
display Jua, body Gowun Dodum, fallback IBM Plex Sans KR, mono JetBrains Mono.
Each folder holds a subset of the family's font files (cycle 153) and the
family's licence (`OFL.txt`, SIL Open Font License 1.1), copied unchanged.

Source: the official Google Fonts repository, https://github.com/google/fonts,
branch `main` at commit `7af0d90b4d532ffa4b041937b560c6df4e7b1e95`
(downloaded 2026-11-17 from raw.githubusercontent.com, cycle 148). The full
files are kept, unchanged, in `tool/fonts-src/<folder>/` with their licence;
they are committed but not bundled (pubspec.yaml lists only `assets/fonts/`).

| Folder | Repository path | Files kept | Weights |
|---|---|---|---|
| `jua/` | `ofl/jua/` | `Jua-Regular.ttf`, `OFL.txt` | 400 |
| `gowundodum/` | `ofl/gowundodum/` | `GowunDodum-Regular.ttf`, `OFL.txt` | 400 |
| `ibmplexsanskr/` | `ofl/ibmplexsanskr/` | `IBMPlexSansKR-Regular.ttf`, `-Medium.ttf`, `-Bold.ttf`, `OFL.txt` | 400, 500, 700 |
| `jetbrainsmono/` | `ofl/jetbrainsmono/` | `JetBrainsMono[wght].ttf` saved as `JetBrainsMono-wght.ttf`, `OFL.txt` | variable (100-800); the app uses 400 and 600 |

Only the weights the web uses are kept. The JetBrains Mono file is renamed
because brackets in an asset path are URL-escaped on the web build. The app
picks its weight with `FontVariation('wght', ...)` (`lib/ui/palette.dart`).

## Subsets

The full files came to 18 MB, which a phone downloaded on the first load of
the web build. `tool/subset_fonts.py` (fontTools) writes the subsets here
under the same file names, so `pubspec.yaml` is unchanged. From `mobile/`,
with a Python that has `fonttools` installed:

```
python -I tool/subset_fonts.py           # write the subsets, print the sizes
python -I tool/subset_fonts.py --check   # only verify the existing subsets
```

Rerun it after adding text with new characters to the ARB files or the dart
sources; `--check` exits 1 when a subset lacks a wanted character that its
source font has.

Kept in Jua and Gowun Dodum: ASCII, Latin-1, Latin Extended-A (romaji
macrons such as ō), general punctuation, a list of symbols (✔ ✘ ✕ ▲ ★ ♪ → 「」
and so on, `SYMBOLS` in the script), Hangul compatibility jamo, the 2,350
KS X 1001 Hangul syllables, and every character of `lib/**/*.dart` and the
ARB files (ko, en, ja). Every OpenType feature of the kept glyphs is kept;
TrueType hinting is dropped (it was a third of Jua's size).

IBM Plex Sans KR is only the fallback of Jua and Gowun Dodum
(`Fonts.fallbacks` in `lib/ui/palette.dart`): Flutter uses it only for a
character the primary family lacks. Its subsets therefore keep the same set
minus the Hangul syllables and jamo that both Jua and Gowun Dodum have. If it
ever becomes a primary family, change its kind to `"text"` in the script.

JetBrains Mono (room codes, counters) keeps ASCII, Latin-1, general
punctuation and the symbols; its `wght` axis is kept.

A character a subset lacks is not drawn from the browser's own fonts: on the
web build, Flutter (CanvasKit) fetches a fallback font for it from Google
Fonts (`fonts.gstatic.com`) at runtime. This covers Hangul syllables outside
KS X 1001 (for example 똠, 햏) and Japanese kana and kanji, which these fonts
mostly never had (of the ja ARB's kana and kanji, Gowun Dodum and IBM Plex
Sans KR have 2 and Jua 1).

| File | Full | Subset |
|---|---|---|
| `jua/Jua-Regular.ttf` | 2,119,352 | 1,330,104 |
| `gowundodum/GowunDodum-Regular.ttf` | 7,229,088 | 1,243,392 |
| `ibmplexsanskr/IBMPlexSansKR-Regular.ttf` | 2,797,100 | 72,392 |
| `ibmplexsanskr/IBMPlexSansKR-Medium.ttf` | 2,866,736 | 73,688 |
| `ibmplexsanskr/IBMPlexSansKR-Bold.ttf` | 2,875,584 | 71,316 |
| `jetbrainsmono/JetBrainsMono-wght.ttf` | 187,208 | 94,724 |
| total | 18,075,068 | 2,885,616 |

Bytes, cycle 153. With the site's gzip (`deploy/Caddyfile`) the subsets
travel as about 0.85 MB (853,392 bytes at gzip level 6).

SHA-256 of the full files in `tool/fonts-src/`:

```
769677aef240bfc3b9965f2b50748075bff885e6c6992fc591a3fb268279f898  jua/Jua-Regular.ttf
a6e457933227483a11758fd0947bc74422a106d46f0bf057fdaa5af94a30067d  gowundodum/GowunDodum-Regular.ttf
53750379270312368cf7641901f43a98dd892e3d9d5798cf25cdc245c85c71c0  ibmplexsanskr/IBMPlexSansKR-Regular.ttf
4a7130f56ce50bf10f9d383f624e50bad2c80bebc63e51f4269ece2c7d919166  ibmplexsanskr/IBMPlexSansKR-Medium.ttf
9d82a8be5330f6d7b53121262867b402baca672eb69b852928f06d185d357f7d  ibmplexsanskr/IBMPlexSansKR-Bold.ttf
48715a42ec242c21e9f02692891e147d022299a52e48d5e413e1a942193ffeda  jetbrainsmono/JetBrainsMono-wght.ttf
```
