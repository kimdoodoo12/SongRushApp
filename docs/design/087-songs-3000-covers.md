# 087 design: 3,000 songs and anime covers (S-007)

Backlog item S-007, from D-102 (3,000 songs), D-103 (cover on the reveal card only), D-104 (covers served by our server) and D-105 (difficulty for the larger set). Investigated by an explorer subagent on branch `cycle/087-songs-3000-covers`, spot-checked by the lead.

## Questions

1. What has to change to grow the draw set from 1,500 to 3,000, and what does it cost in disk, S3 and time?
2. How do we get anime covers, which facet, and how big are they?
3. How do we serve a cover without leaking the answer before the reveal?
4. What tier ranges work for 3,000 songs, and how does "no anime repeated in a game" fit game lengths up to 30?

## Findings

### Draw set size
- 1,500 is fixed in `data/songrush_data/catalog.py:16` (`DRAW_SET_SIZE = 1500`), pinned by `data/tests/test_catalog.py:272`. The backend tiers are fixed in `backend/src/main/java/com/songrush/game/DrawFilter.java:29-33` (`EASY_LAST = 300`, `HARD_FIRST = 501`, `HARD_LAST = 1500`), pinned by `RoomTierTest.java` and `DrawFilterTierTest.java`. The frontend has no numbers (`frontend/src/game/filters.js:18-54`).
- The catalog already holds every theme (5,021 anime, 14,591 themes, 5,735,977 bytes against the 12 MB cap at `catalog.py:17`), so only the draw set grows.
- Rebuilt offline from the cached sources with `draw_size=3000`: ranks 1-1,500 equal today's `drawSet` exactly; ranks 1,501-3,000 are 1,500 themes (919 ED, 581 OP) of 5,275,893,666 bytes (mean 3.52 MB, p95 5.09 MB, max 18.1 MB). Total about 10.7 GB.
- Prefetch time: the gap is 3 s (`audio.py:328`), but the real rate of the first run was 236-248 files per hour (audio-cache mtimes), so about 6 hours. The tool stops after 5 consecutive failures (`audio.py:292`) and resumes by skipping valid files (`docs/cycles/030-audio-prefetch.md:64-66`).
- Production serves audio from S3 (PROGRESS, 11월 6일: no audio on the server disk). `/audio/{token}` answers 302 to a 60 s presigned URL (`AudioController.java:99-117`, `S3AudioLinks.java:39-48`), with random object keys `a/<token_urlsafe(16)>` (`s3upload.py:33-34`). The server disk holds only `catalog.json`, `audio-cache/index.json` and `audio-cache/s3-keys.json` (`S3AudioConfiguration.java:36-38`). The 16 GiB EBS needs no change. S3 grows to about 10.7 GB (about 0.27 USD a month at an assumed 0.025 USD/GB-month).
- A partial prefetch is safe: `CatalogLoader.candidates()` (`CatalogLoader.java:98-137`) skips themes with no index entry or no stored file, and `Catalog.rank` uses the drawSet position whether cached or not (`Catalog.java:32-38`).

### Covers
- AnimeThemes GraphQL `anime { images { nodes { facet link } } }` returns `LARGE_COVER` (jpg) and `SMALL_COVER` (avif), on `pub-92474f7785774e91a790e086dfa6b2ef.r2.dev` (live POST to `https://graphql.animethemes.moe/`: 200, 1.54 s, `X-RateLimit-Limit: 90`).
- Five live cover GETs (200 each, 3.5 s apart): LARGE_COVER 86,151 / 89,946 / 98,904 bytes at 460x617-654; SMALL_COVER 3,156-3,158 bytes at 100x141. No `Cache-Control`.
- The REST id-batch filter `api.animethemes.moe/anime?filter[id]=...&include=images` returned 503 twice, so it is unverified.
- Adding `images` to `animethemes.graphql` changes the page cache key (`animethemes.py:37-43`) and re-crawls all 51 heavy pages. A separate lean query (`id images{...}` only, own cache prefix) is 51 small pages, about 5 minutes at the 3 s gap.
- The top 3,000 has 846 anime, so about 76 MB of large covers.

### Answer safety
- The reveal is built only in `Room.endSong` (`Room.java:467-477`); `prepare` sends only an opaque `/audio/<token>` (`Room.java:451-453`, `AudioTokens.java:19,33-49`), and the reconnect snapshot `Room.currentSong()` (`Room.java:170-182`) carries no anime data.

### Difficulty
- The draw sorts by anime popularity, then anime id (`catalog.py:104-107`), so each anime's themes are contiguous. Big franchises take many ranks: One Piece 73, Naruto 25.
- Distinct anime / series groups in the top 3,000 draw order:

| Draw ranks | Anime | Groups |
|---|---|---|
| 1-300 (today's easy) | 32 | 25 |
| 1-612 (top 100 anime) | 100 | 77 |
| 1-986 (top 200 anime) | 200 | 140 |
| 1-3,000 | 846 | 582 |

- Today's easy tier cannot fill a 30-song game without repeats (25 groups).
- `Room.java:188-222` draws by a partial Fisher-Yates over themes and does not avoid repeats; a default game can already draw several Naruto themes.

## Options

### A. Tier cut: theme rank vs anime rank
1. Keep cutting by theme rank, with bigger numbers. Simple, but the cut lands inside franchises and moves when a franchise gains themes.
2. **Cut by anime popularity rank** (order of the anime's first appearance in `drawSet`). Stable, and it matches "famous anime". The backend derives it from `drawSet` at load; no catalog change.

### B. No repeats: anime vs series group
1. Per anime. AoT S1 and AoT S2 could both appear, with the same accepted answer (answers are matched per series group, D-020).
2. **Per series group.** Each song in a game has a different answer. Needs at least N groups in the pool.

### C. Which tiers avoid repeats
1. **Easy only** (owner, D-106).
2. All tiers. Rejected by the owner.

### D. How to draw without repeats (easy)
1. Pick a group uniformly, then a theme in it. Changes today's odds: One Piece would drop from 73 chances to 1.
2. **Shuffle themes, keep the first theme of each unseen group, stop at N.** Same odds as today for the first pick; only repeats are removed. For easy, `available` becomes the number of distinct groups.

### E. Cover fetch
1. Extend the main crawl query. Re-crawls about 17 MB and all 51 heavy pages.
2. **A separate lean images query**, own cache prefix, then download the LARGE_COVER of draw-set anime only.

### F. Cover serving
1. Put the cover URL in `catalog.json` and the frontend. Leaks the answer before the reveal: rejected.
2. Per-reveal token like audio. Needs a token store and a TTL, and if it shared `AudioTokens`, an audio token from `prepare` could fetch the cover before the reveal (review H1, H2).
3. **A fixed random id per anime** (`token_urlsafe(16)`, made once by the data tool). The id appears only in the reveal message as `coverUrl: "/cover/<id>"`. Since it never leaves the server before the reveal and is unrelated to the audio tokens, there is nothing to leak and nothing to expire. Disk mode serves the file; S3 mode 302s to a presigned URL of object key `c/<id>`.

## Decision

- Draw set: 3,000 (D-102). The expected total is 10.69 GB (9.96 GiB), which is the "about 10 GB" the owner accepted when choosing 3,000 songs (D-102 raised D-044's stop to it), so an agent may start the prefetch of ranks 1,501-3,000 on this PC. Upload to S3 and copying the small data files to the server are the owner's (AWS account, server access).
- Tiers (D-107, owner): cut by **anime popularity rank**, and every tier starts at rank 1 and widens. `easy` = anime ranks 1-100 (about 612 themes, 77 groups). `normal` = anime ranks 1-300 (C-018 prints the theme count). `hard` = all 3,000. The default (no `tier` sent) becomes `normal` = 1-300, so `hard` is the only tier that draws from all songs. The 300 is the design's proposal; the owner fixed only the nested shape.
- No repeated series group in **easy only** (D-106), drawn by option D2. An easy pool with fewer groups than N answers `not-enough-songs` with `available` = distinct groups and `unit: "series"`; the frontend says "작품" instead of "곡" for it.
- Covers: option E2 to fetch, F3 to serve, LARGE_COVER jpg. A missing cover for one anime sends `coverUrl: null` and the card shows no image. A missing or broken cover map logs a WARN and sends null for every reveal; it never stops startup (unlike the audio map).
- A player who reconnects during the 5 s reveal gets no reveal card and no cover (current behaviour, `Room.java:173`); unchanged on purpose.

## Resulting items

- **C-018** data: draw set 3,000. `DRAW_SET_SIZE = 3000`; test pins 3,000 and that ranks 1-1,500 equal the old order. Rebuild `catalog.json` from the cache (no live request) and print the theme count of anime ranks 1-100 and 1-300.
- **C-019** data: cover tool. Lean paged images query with its own cache prefix; download LARGE_COVER for draw-set anime into `data/cover-cache/` (added to `.gitignore`, checked with `git check-ignore`) with `index.json` (`animeId -> {id, file, size}`, `id` a `token_urlsafe(16)` kept across runs); gentle rules (UA, 3 s gap, 60 s timeout, 429 Retry-After, resume, stop after 5 failures). Tests on saved fixtures only.
- **C-020** data: S3 upload of covers. A new module beside `s3upload.py` (that one hard-codes `.ogg`, `a/` and `audio/ogg`), reusing its client and fake: uploads under `c/<id>` with `image/jpeg`, resumable, writes `cover-cache/s3-keys.json`.
- **I-012** run: prefetch audio ranks 1,501-3,000 and fetch covers on this PC; record counts and failures. Long background job.
- **B-020** backend: `/cover/{id}`. Disk mode serves `cover-cache` files, S3 mode 302s to a presigned URL; unknown id 404; an audio token sent to `/cover/` is 404 and a cover id sent to `/audio/` is 404; the reveal carries `coverUrl` (null when none); no cover id in any message before the reveal or in the reconnect snapshot; a missing or broken cover map logs WARN, startup succeeds and every `coverUrl` is null.
- **B-021** backend: tiers by anime rank and no repeated series group in easy. `easy` anime ranks 1-100, `normal` (also the default) 1-300, `hard` all; easy draws without a repeated group and reports `available` as distinct groups with `unit: "series"`; normal and hard draw as today.
- **F-022** frontend: reveal card cover and wording. Shows the image from `coverUrl` when present, nothing when null; `not-enough-songs` with `unit: "series"` says 작품 (and en/ja equivalents); layout stable in the three languages and six moods.
- Owner (not an agent item): run the S3 uploads and copy `catalog.json`, the audio and cover maps to the server.

## Requests and fixtures

- Live requests in this cycle: 1 GraphQL POST (200), 2 REST GETs (503), 5 cover GETs (200), plus 1 GraphQL probe by the lead before the cycle (200). No fixture saved yet; C-019 saves a lean-query page as a fixture.
