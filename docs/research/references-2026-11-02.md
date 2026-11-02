# References (checked 2026-11-02 via WebFetch)

## nonlooped/anime-op-ed-dataset
- URL: https://github.com/nonlooped/anime-op-ed-dataset
- One file, `songs.json` (JSON array). Fields: `query`, `video_id`, `title`, `duration` (s), `view_count`, `channel`, `url`, `thumbnails`, `anime`, `type` (opening/ending), `type_number`, `theme_number`, `original_theme_text`.
- About 1,000 anime, all their themes. No exact count stated.
- **No audio/video hosted**; each entry is a YouTube link.
- No dedicated artist field; the artist appears inside `query` / `original_theme_text`.
- Sources: AnimeThemes.moe, public MyAnimeList data, scraped YouTube metadata.
- README: research and non-commercial purposes; view counts are as of scraping; rarely a video is a different song with the same title.
- LICENSE file exists; type not confirmed yet (S-001 should read it).

## songspot.co (reference product)
- Clip Challenge: clip grows 0.1 s -> 0.5 s -> 2 s -> 8 s -> 15 s after wrong guesses or skips; solving earlier scores higher.
- Multiple Choice mode (4 options). Typing mode with title autocomplete.
- Filters (genre, era, artist), five difficulty levels.
- Room-based "play with friends" is a paid feature. Lobby details, scoring formula, and audio source are not described on the page.

## Implication
- The dataset alone cannot deliver precise short clips (YouTube embed latency and ads). AnimeThemes, the dataset's upstream, serves media files directly and is the leading audio candidate. Verified in S-001.
