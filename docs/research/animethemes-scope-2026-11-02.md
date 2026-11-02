# What AnimeThemes offers (2026-11-02)

Live requests by the lead to `https://graphql.animethemes.moe/` (6 POSTs, at least 4 s apart, UA `SongRushBot/0.1 (+repo)`, timeout 120 s): schema introspection, counts, samples.

## Theme types and counts
- `ThemeType` enum: **`OP`, `ED`, `IN`** (insert song). There is no BGM/OST type.
- `animethemePagination(type:X){paginatorInfo{total}}`: **OP 5,664 / ED 8,954 / IN 153** (14,771 themes). `songPagination` total: 14,642 songs.
- IN examples: Kuzu no Honkai "Seishun Kyousoukyoku", Yagate Kimi ni Naru "rise", Precure idol songs. Inserts are sparse compared with OP/ED.
- The project describes itself as "a simple and consistent repository of anime opening and ending themes" (https://github.com/AnimeThemes).

## Fields relevant to us (introspection)
- Query fields include `animethemeShuffle(type, format, year_lte, year_gte, spoiler, first, page)` (server-side random themes with filters), `findAnimeByExternalSite` (lookup by AniList/MAL id), `series`, `studio`, `artist`, `playlist`, `search`.
- `Anime`: `title synonyms year season format synopsis images resources series studios animethemes`.
  - `ImageFacet`: `SMALL_COVER, LARGE_COVER, GRILL, ...` (covers usable on the reveal screen).
- `Video`: `nc` (creditless), `overlap` (`NONE/TRANS/OVER`: credits over the video), `lyrics`, `subbed`, `uncen`, `source` (`WEB/RAW/BD/DVD/VHS/LD`), `resolution`, `audio`.
- `Song`: `title performances resources`. `ResourceSite` includes `SPOTIFY, APPLE_MUSIC, YOUTUBE_MUSIC, AMAZON_MUSIC`, but **0 of 200 sampled songs** (songPagination pages 5 and 40) had any song resource, so song-level music links cannot be relied on.

## Not offered
- Background music (BGM/OST tracks), character songs that are not OP/ED/IN, and highlight timestamps.
