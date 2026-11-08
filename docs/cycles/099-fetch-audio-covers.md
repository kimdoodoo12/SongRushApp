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
