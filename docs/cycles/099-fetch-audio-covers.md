# 099: prefetch audio ranks 1,501-3,000 and fetch covers (I-012)

From S-007 (design 088), D-102 to D-104. A run on the owner's PC with the existing data tools; no code change. The S3 uploads and copying files to the server are the owner's (AWS account, server access).

## Runs

1. Covers, first run (2026-11-08 19:58, `python -u -m songrush_data.covers` from `data/`): 51 lean pages fetched (`covers pages=51 listedAnime=5022`), then 15 covers downloaded and 19 failed with `body is not a JPEG (starts with 89504e47)`; it stopped on 5 consecutive failures (`covers drawSetAnime=846 cached=0 downloaded=15 noCover=0 notListed=0 failed=19`). Cause: many AnimeThemes LARGE_COVER links ending in .jpg serve PNG. Fixed by C-022 (cycle 095).
2. Audio (2026-11-08 20:04 to 2026-11-09, `python -u -m songrush_data.audio`): `audio done=1499 skipped=1499 failed=2 bytes=5272799064`; failed ids 7565 and 7514 (`not Ogg Opus`; 7565 already failed in the first prefetch, D-056 leaves both out of the draw). One 502 was retried. The cache now holds 2,998 themes in about 10 GB.
3. Covers, second run (2026-11-09, after cycle 095): `covers drawSetAnime=846 cached=15 downloaded=831 noCover=0 notListed=0 failed=0`. 575 JPEG and 271 PNG, 188 MB, in gitignored `data/cover-cache/`.

Logs are in the lead's scratchpad (`i012-covers.log`, `i012-audio.log`, `i012-covers2.log`), not in git.

## Owner steps (not run by agents)

1. Audio to S3: from `data/`, `.venv\Scripts\python.exe -m songrush_data.s3upload --bucket <bucket> --profile <profile>` (resumable; uploads only new files; writes `audio-cache/s3-keys.json`).
2. Covers to S3: `.venv\Scripts\python.exe -m songrush_data.s3covers --bucket <bucket> --profile <profile>` (writes `cover-cache/s3-covers.json`).
3. Copy to the server's data folder: `catalog.json`, `audio-cache/index.json`, `audio-cache/s3-keys.json`, `cover-cache/s3-covers.json`; then restart the app.
4. Merge dev into main only after steps 1-3, so the public site gets the new tiers with the new songs and covers together.

## Not verified

A real-browser check of the reveal card across three languages and six moods with real covers needs the backend serving the covers; it is left for after the owner's upload.

## Owner run (2026-11-09)

- Audio to S3: `s3 upload done: uploaded=1233 skipped=1765 missing=0 mismatched=0 failed=0 served=2998 (s3-keys.json)`. The 266 skipped beyond the first 1,499 were already in S3 from an interrupted earlier attempt. Local check: `s3-keys.json` holds 2,998 entries, all keys under `a/`.
- Covers to S3: local check of `cover-cache/s3-covers.json`: 846 entries, every key `c/<id>`, ids equal to `cover-cache/index.json`.
- Server copy: the owner's `ls -la` on `/opt/songrush/data` shows `catalog.json` 5,746,355, `audio-cache/index.json` 294,546, `audio-cache/s3-keys.json` 269,550, `cover-cache/s3-covers.json` 86,673 bytes, equal to the local files; the previous three are kept as `.bak`.
- Release: the owner merged PR #15 (dev to main); the `Docker image` workflow on main `5d977e4` succeeded and `GET https://animeopguessr.com/health` returned `{"ok":true}`.
- Live check (lead, built-in browser, Korean, default mood, a hard game of 5): the reveal card showed `.reveal-cover mx-auto mb-3 h-32 w-24` with `img` `/cover/<id>` loaded (`naturalWidth` 460, 1.7 s); no `/cover/` request before the reveal. Audio loaded through `/audio/<token>` (200) but slowly: 47.6 s for the first song and 10.3 s for the second; `curl -L` of one audio link from the owner's PC: `size=3593948 total=9.944020s redirect=0.862031s speed=361418B/s`. The owner considered CloudFront for this and dropped it (D-110).
- Not checked: the reveal card in en/ja and the other five moods with a real cover.
