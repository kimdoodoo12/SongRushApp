# Bundled fonts

The four families of the web's room stylesheet (`frontend/src/room.css` `:root`):
display Jua, body Gowun Dodum, fallback IBM Plex Sans KR, mono JetBrains Mono.
Each folder holds the font files and the family's licence (`OFL.txt`, SIL Open
Font License 1.1), copied unchanged.

Source: the official Google Fonts repository, https://github.com/google/fonts,
branch `main` at commit `7af0d90b4d532ffa4b041937b560c6df4e7b1e95`
(downloaded 2026-11-17 from raw.githubusercontent.com, cycle 148).

| Folder | Repository path | Files kept | Weights |
|---|---|---|---|
| `jua/` | `ofl/jua/` | `Jua-Regular.ttf`, `OFL.txt` | 400 |
| `gowundodum/` | `ofl/gowundodum/` | `GowunDodum-Regular.ttf`, `OFL.txt` | 400 |
| `ibmplexsanskr/` | `ofl/ibmplexsanskr/` | `IBMPlexSansKR-Regular.ttf`, `-Medium.ttf`, `-Bold.ttf`, `OFL.txt` | 400, 500, 700 |
| `jetbrainsmono/` | `ofl/jetbrainsmono/` | `JetBrainsMono[wght].ttf` saved as `JetBrainsMono-wght.ttf`, `OFL.txt` | variable (100-800); the app uses 400 and 600 |

Only the weights the web uses are kept. The JetBrains Mono file is renamed
because brackets in an asset path are URL-escaped on the web build; its
content is unchanged. The app picks its weight with `FontVariation('wght', ...)`
(`lib/ui/palette.dart`).

SHA-256 of the font files:

```
769677aef240bfc3b9965f2b50748075bff885e6c6992fc591a3fb268279f898  jua/Jua-Regular.ttf
a6e457933227483a11758fd0947bc74422a106d46f0bf057fdaa5af94a30067d  gowundodum/GowunDodum-Regular.ttf
53750379270312368cf7641901f43a98dd892e3d9d5798cf25cdc245c85c71c0  ibmplexsanskr/IBMPlexSansKR-Regular.ttf
4a7130f56ce50bf10f9d383f624e50bad2c80bebc63e51f4269ece2c7d919166  ibmplexsanskr/IBMPlexSansKR-Medium.ttf
9d82a8be5330f6d7b53121262867b402baca672eb69b852928f06d185d357f7d  ibmplexsanskr/IBMPlexSansKR-Bold.ttf
48715a42ec242c21e9f02692891e147d022299a52e48d5e413e1a942193ffeda  jetbrainsmono/JetBrainsMono-wght.ttf
```
