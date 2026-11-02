# 001 Audio source and catalog (S-001)
Stack updated for D-029 (2026-11-02); decisions unchanged.

Probed 2026-11-01/02 by the `explorer` agent with UA `SongRushBot/0.1 (+https://github.com/kimdoodoo12/SongRushApp)`, about 58 requests, at least 1 s apart per host. Audio was fetched only as small Range slices and then deleted.

## Questions
1. Which AnimeThemes API endpoints give anime -> themes -> audio file URL? Rate limit?
2. Does the file URL play in a browser `<audio>` cross-origin, with HTTP Range for offset starts? Time to first byte?
3. How do `anime-op-ed-dataset` entries map onto AnimeThemes? Or is AnimeThemes alone simpler?
4. Which catalog fields exist, and where does each come from (incl. Korean titles)?

## Findings

### F1. One request returns the full chain for one anime
- `GET https://api.animethemes.moe/anime/bakemonogatari?include=animethemes.song.artists,animethemes.animethemeentries.videos.audio,animesynonyms,resources,images,series` returns 200 and 11,428 bytes. Saved as `data/tests/fixtures/animethemes_anime_bakemonogatari.json`.
- The chain is `anime` -> `animethemes[]` (`type` OP/ED, `slug` "OP1", `sequence`) -> `song {title, artists[]}` -> `animethemeentries[]` (`version`, `nsfw`, `spoiler`, `episodes`) -> `videos[]` -> `audio {basename, size, link}`.
- Example audio link: `https://a.animethemes.moe/Bakemonogatari-OP1.ogg`.
- `audio` hangs under each **video**, so the same audio repeats across videos of one entry. Deduplicate by `audio.id`.
- `sequence` is inconsistent. `.hack//roots` OP1 has null, while its only ED has `sequence: 1`, and Bakemonogatari ED1 has null. **Use `slug`.** See `data/tests/fixtures/animethemes_anime_page1.json`.

### F2. Rate limit and pagination
- Every API response carries `x-ratelimit-limit: 90`, with `x-ratelimit-remaining` decrementing. The unit is not stated. We assume per minute.
- The docs home (https://api-docs.animethemes.moe/, 200) states no rate limit, no max page size, and no hotlink rule.
- Pagination uses `page[size]` and `page[number]` with `links.next`, and there is no total.
  - `page[size]=100` works with a shallow include. `page[size]=1000` returns **422**. The deep include at size 100 is checked in F6.
  - curl needs `-g` because of the brackets.
- Catalog size: pages 1-50 at size 100 were full, page 51 had 21 rows, and page 52 was empty. That makes **about 5,021 anime**, so a full listing is about 51 pages.
- The theme total was not measured.

### F3. Audio host behavior (`a.animethemes.moe`)

| Check | Result |
|---|---|
| HEAD | **403** on all 3 files |
| `Range: bytes=500000-600000` | **206**, `Accept-Ranges: bytes`, `Content-Range: .../3610594` (matches `audio.size`) |
| Content-Type | `audio/ogg`. The codec is Ogg **Opus**, 48 kHz (`OpusHead` in the header bytes) |
| CORS | **No `Access-Control-Allow-Origin` header**, even with `Origin: http://localhost:5173` |
| Redirects | None |
| Cookies | Sets `XSRF-TOKEN` and `animethemes_session` |
| Cache | `Cache-Control: no-cache, private`, with ETag and Last-Modified |
| **Time to first byte** | **7.4-13.3 s** for a 100 KB range. A 4 KB range took 12.4 s. A repeat was still about 10 s, so it is not cached. DNS, connect and TLS took under 1 s in total; the wait is on the server side. Measured from one network only. |
| Duration | Not in the API and not in the headers. Computed from the last Ogg granule of OP1: 89.98 s (a TV-size theme). |

### F4. Dataset `nonlooped/anime-op-ed-dataset`
- The branch is `master`. `songs.json` is 3.27 MB and holds **3,275 entries** (1,241 OP and 2,034 ED) over **949 anime**.
- The LICENSE says CC BY 4.0 with attribution "Anime OP/ED Dataset by @nonlooped". A disclaimer also says "research, educational, and non-commercial purposes".
- There are no ids, no audio URL, and no year. Its only unique fields are YouTube `view_count`, `duration` and `channel`.
- `view_count` is noisy. For example, Chihayafuru ED1 counts a karaoke re-upload with 140k views.
- Mapping sample: 15 random rows, looked up by `GET /anime?q=<name>`, then an exact name match, then a slug match.
  - **7 of 15 matched cleanly.**
  - 2 found the anime but not the theme.
  - 6 had name mismatches, for example "Meitantei Conan" vs "Detective Conan".

### F5. Catalog fields

| Field | Source |
|---|---|
| Anime name (romaji or English) | `anime.name` |
| Synonyms | include `animesynonyms` -> `[{text, language}]`. `language` was null in the sample. No Korean was seen, but Korean was not surveyed. |
| Year, season, format | `anime.year`, `anime.season`, `anime.media_format` |
| Song title and artists | `song.title`, `song.artists[].name` |
| Type and number | `animetheme.type`, `animetheme.slug` |
| Audio URL and size | `videos[].audio.link`, `audio.size` |
| Duration | Not in the API. Bakemonogatari's 6 audio files are 3.50-3.66 MB, and OP1 is 3,610,594 B over 89.98 s, about **40 KB/s**. `audio.size / 40000` gives a rough duration without reading the file. This is an estimate from one file. |
| External ids | `resources[]`: MyAnimeList, AniList, Kitsu, aniDB, ANN, and others. The Bakemonogatari AniList id is 5081. |
| Popularity | **None on AnimeThemes.** `entry.tracks_count` exists but is undocumented. |

**AniList** (`POST https://graphql.anilist.co`, header `X-RateLimit-Limit: 30`):
- `synonyms` sometimes include Hangul. In the **top 50 by popularity**, 9 of 50 had a Hangul synonym, e.g. 귀멸의 칼날 and 체인소 맨. Saved as `data/tests/fixtures/anilist_top50.json`, which has only `id`, `title` and `synonyms`. The share across all ~5,021 anime is surely lower, because this sample is biased to popular titles.
- `popularity` and `id_in` batch lookup are checked in F6.

### F6. Follow-up probe after critic review (4 requests, all 200)
- **Kimetsu no Yaiba, full include.** `GET /anime/kimetsu_no_yaiba?include=...` returned 200, 10,088 B, in 15.4 s. Saved as `data/tests/fixtures/animethemes_anime_kimetsu.json`.
  - 3 themes: OP1 with audio 8188, ED1 with 8186, ED2 with 8187. Every video has audio.
  - The AniList id from `resources` is **101922**.
  - The AnimeThemes synonyms have no Hangul.
- **AniList batch.** `POST graphql.anilist.co` with `media(id_in:[5081,101922])` returned 200 in 0.32 s, with `X-RateLimit-Limit: 30` and no `Retry-After`. Saved as `data/tests/fixtures/anilist_id_in.json`.
  - 5081: `popularity` 347014, no Hangul.
  - 101922: `popularity` 1010700, synonyms include **귀멸의 칼날**, `idMal` 38000.
  - So `popularity` and Hangul synonyms are both confirmed through the planned `id_in` path.
- **Deep-include listing.** `GET /anime?page[size]=100&page[number]=1&include=<deep>` returned **200**, 597,963 B, in 15.0 s. Saved as `data/tests/fixtures/animethemes_deep_page1.json`.
  - It holds 100 anime and **358 themes**, with 0 themes lacking audio.
  - **1 theme has two different audio files across versions** (`akuma_no_riddle` OP1), which pins the "lowest version only" rule.
  - Extrapolated: about 5,021 anime × 3.58 gives **about 18,000 themes**, and a full build takes about 51 such requests at about 15 s each, so about 13 min.
- **Duration estimate caveat.** In this page, 120 of 501 videos give `audio.size/40000 < 60 s` (min 17 s, median 89 s). The 40 KB/s rate came from a single file. Some of those are genuinely short (ONA or short-form themes) and some may use a different bitrate; this is unverified per file. C-001 uses the estimate as specified. S-003 checks a few short estimates by reading the Ogg tail (one small Range request each).
- **AnimeThemes is uniformly slow** from this network: 14-15 s for API calls of any size. AniList is fast.

## Options

### Catalog source
- **A. AnimeThemes only, enriched by AniList (chosen).**
  - AnimeThemes gives anime -> theme -> audio with no name matching.
  - AniList, keyed by the `resources` id, adds `popularity` and Hangul synonyms.
  - Cost: about 51 AnimeThemes pages plus about 100 AniList batches of 50.
- **B. Dataset first, joined to AnimeThemes by name.** It keeps the user-provided curated list of about 1,000 popular anime. But about half the rows fail to match by name (F4), and it adds YouTube view counts that are noisy.
- **C. Dataset only, with YouTube embeds.** This is ruled out by the user's decision for AnimeThemes playback, and by clip precision.

### Clip latency (F3 is the main risk)
- **A. Client prefetch one round ahead (recommended for the MVP).**
  - The server announces the next clip's audio URL and offset while the current round is still running.
  - The client loads it into a hidden `<audio preload="auto">`, seeks to the offset, and reports `ready`.
  - Round 1 is prefetched during the lobby countdown after the host presses start.
  - **Unverified assumption:** a seek costs one Range request. Ogg has no seek index, so a browser may bisect with several Range requests at 7-13 s each. Seek-to-offset could then take tens of seconds and exceed a 20+ s round. S-003 must measure the request count and the time to `canplay` at the offset.
- **B. Server-side clip cache/proxy.**
  - The server Range-fetches the excerpt once, caches it in memory or a temp dir (never git), and serves it with CORS and an opaque id.
  - This gives fast, uniform loading, hides the answer, and enables Web Audio.
  - It costs server bandwidth and an Ogg slicing step.
  - **It reverses the user's decision D-002 ("AnimeThemes direct playback")**, so only the user can adopt it.
- **C. Rely on browser streaming at round start.** Ruled out: it adds 7-13 s of dead air per round.

**Failure path (host slow or down mid-game).**
1. Clients do not reach `ready`.
2. The ready timeout expires.
3. Every player would hear silence.
4. The server never touches audio, so it learns about this only from missing `ready` acks.

Server rule:
- If fewer than half of the players report `ready` by the timeout, the server **replaces the song** with another catalog entry and re-prepares.
- After 3 consecutive replacements, it ends the game with an "audio source unavailable" notice.
- Timeout values come from design 002.

### Answer leak via URL
The audio filename includes the anime name (`Bakemonogatari-OP1.ogg`). With prefetch (option A), the **next** round's answer is visible in devtools while the current round is being played. That violates the CLAUDE.md rule "never send the answer ... before the round ends".
- **A. Accept it for the friends-only MVP** (recommended, pending D-005).
- **B. Proxy with opaque ids.** This is the same machinery as latency option B, and also needs the user's decision.

> **Updated 2026-11-02:** decisions 3, 6, 7, 8 and the latency/leak options are superseded by D-009/D-018 (designs 009, 010); D-005 is resolved. Decisions 1, 2 and 4 stand. Decision 5's field list is extended by designs 007 and 008, and `estDurationSec` is not used for play: duration comes from the audio manifest (design 010).

## Decision
1. **Catalog source: AnimeThemes + AniList (option A).**
   - The dataset is not used in the build. AniList `popularity` gives the same "popular anime" signal, with ids.
   - Whether to keep the dataset anyway, for example as a "top ~1000" filter, is **D-006** (needs the user's decision, non-blocking).
2. **Catalog entry = one AnimeThemes theme that has audio.** The rules below are pinned on the Bakemonogatari fixture.

   | Input case | Rule | Bakemonogatari |
   |---|---|---|
   | Theme with several entries (versions) | Take audio from the lowest `version` entry (null counts as 1) that has a video with `audio` | OP4 v1/v2 -> one item, audio 2566. ED1 v1-v3 -> one item, audio 2562 |
   | Versions with different audio | Lowest version only | (deep page fixture: `akuma_no_riddle` OP1) |
   | `nsfw` / `spoiler` flags | **Ignored.** They describe the video picture, and we play audio only | OP5 (all nsfw) -> kept, audio 2567 |
   | Theme with no audio on any entry | **Excluded** | (not in fixture) |
   | `sequence` | Ignored; use `slug` | ED1 sequence null -> slug "ED1" |
   | Duration | `estDurationSec = round(audio.size / 40000)`, a **lower bound** (files encoded at about 25 KB/s are underestimated, which is the safe side for offsets). Exclude only when `audio.size < 1,500,000` B (under 60 s even at 25 KB/s) | OP1 3,610,594 B -> 90 s. Deep page: 9 of 358 excluded, e.g. `akage_no_anne` ED1 (1,415,172 B). `ajin` ED1 (2,254,676 B, est 56) is kept |

   Expected output for the fixture: **6 items** with audio ids {2562, 2563, 2564, 2565, 2566, 2567}.
3. **The clip offset policy belongs to design 002.** The constraint from here is `offset + clipLength <= estDurationSec - 5`. Every kept theme has `estDurationSec >= 37`.
   - Round 2 review found that an earlier "est < 60 s" rule would have dropped 108 of 358 themes (30%). Most of those were normal 90 s TV themes encoded at about 25 KB/s (e.g. `Ajin-ED1.ogg`, 2.25 MB). The rule was replaced by the size floor above.
4. **Layout: the catalog builder is the Python package in `data/`,** and pytest tests it (design 011 decision 1).
   - Code: `data/songrush_data/`
   - Tests and fixtures: `data/tests/`, `data/tests/fixtures/`
   - API cache: `data/cache/` (gitignored)
   - Output: `data/catalog.json`
5. **The output is committed** (metadata only) with deterministic, sorted output and **a size cap of 5 MB**.
   - About 18,000 themes are expected (F6). To fit the cap, the file is normalized: an `anime` list (id, names, synonyms, year, season, popularity) and a `themes` list (id, animeId, slug, song, artists, audioUrl, estDurationSec). Names are not repeated per theme.
   - C-004 logs the counts and size. If the cap is exceeded, the build fails, and that is logged as a follow-up rather than silently committing a big file.
6. **Playback: client prefetch (latency option A) for M-1/M-2.** M-3 depends on S-003's measurement.
7. **D-005 scope.** Until the user decides, option (a) applies to M-1 and M-2 as an interim exception to the CLAUDE.md answer rule, recorded there. D-005 **blocks M-3**: the M-3 planning cycle must not start until it is answered.
8. **Proxy (B) is not adopted by agents.** S-003 reports measurements and a recommendation only. Adoption is the user's decision, together with **D-005** (the answer leak).
9. **Korean titles:** use AniList Hangul synonyms where present (9 of the top 50; fewer overall). Everything else is romaji or English. Manual Korean aliases are **D-007** (needs the user's decision, non-blocking).

## Resulting items
Ids and acceptance criteria are written in BACKLOG.md.
- **C-001** catalog: AnimeThemes anime -> catalog items transform (pure).
- **C-002** catalog: AnimeThemes paginated fetcher. Injected fetch and clock, cache, rate limit, handling of 429 with Retry-After.
- **C-003** catalog: AniList enrichment by `id_in` batches (popularity, Hangul synonyms). Injected fetch and clock, 429 handling.
- **C-004** catalog: build CLI that writes `data/catalog.json` (deterministic, 5 MB cap).
- **S-003** design: real-browser measurement of prefetch, seek and play latency against AnimeThemes (request count, time to `canplay` at the offset), with a recommendation on proxy option B for the user.

All C- items depend on I-002 (the data bootstrap; design 011).
