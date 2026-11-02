# Reference game mechanics (2026-11-02)

Fetched by the lead with WebFetch on 2026-11-02. Only what the pages state; gaps are marked.

## songspot.co
Source: https://songspot.co/ and https://songspot.co/room
- Solo "Clip Challenge": clips grow 0.1 s -> 0.5 s -> 2 s -> 8 s -> 15 s; "A wrong answer or Skip moves songspot to a longer clip." Answer by searching the title ("Search the title to answer"). "Song Guesser" mode: "Choose from four answers."
- Filters: difficulty (Easy..Impossible), genre, decade, artist.
- Rooms: hosting needs Premium ($7.99/mo); players "join free with a link" or nickname + room code, no account; up to 8 players; formats "Quick Pick or the 0.1s Challenge"; 10 songs per game; "Everyone hears the same clip at the same time"; "the fastest right answer scores the most".
- Not stated: point formula, time limit, audio source, between-round screens.

## StarCraft use-map song quiz (노래맞추기 유즈맵)
Source: https://namu.wiki/w/음악%20퀴즈
- A sound plays; the first player to type the answer in chat takes the point. Fixed question count; highest score wins; commonly "선 20점" (first to 20).
- Most maps require answers without spaces; the original map accepts one answer only (multi-answer needs modified sources).
- Skip: if enough players press K, the question is skipped (threshold not stated).
- Host sets options at start. Typing speed matters; near-simultaneous answers can look different per player due to ping.

## usemap.kr (web successor of the use-map style)
Source: https://www.usemap.kr/about
- Short clip, answer in chat, fastest correct answer wins; chosung (초성) hints revealed in stages.
- Not stated: audio source, player count, scoring, skip.

## Differences from our current design (D-003, D-004, design 002)
| Mechanic | songspot | Use-map / usemap.kr | Ours now |
|---|---|---|---|
| Answer input | title search | free chat | autocomplete |
| Who scores | fastest scores most | first correct only | every correct player, by speed |
| Clip | growing (solo) | one clip | fixed clip + time limit |
| Skip | per player (solo) | vote (K) | none |
| Hints | none | chosung (usemap.kr) | none |
| Game end | 10 songs | N questions or first to 20 | fixed rounds |

## How the audio is delivered (follow-up, 2026-11-02)

### songspot.co: read from its public JS bundle `/assets/songspot-game-V7t7FOQE.js`
Only the bundle was read; songspot's API was not called.
- The client asks its own server for a round: `/api/songspot/round?...` (also `prepare=1` to fetch the next round ahead). The response carries `roundKey`, `roundToken`, an optional `audioUrl`, and `expiresAt`.
- Audio bytes come from songspot's own endpoint `PI=\`/api/songspot/audio\``, sent with header `"x-songspot-round-token"`, `cache:"no-store"`. If the response has an absolute `audioUrl`, the client tries it with a 4 s timeout (`FI=4e3`) and falls back to `/api/songspot/audio`.
- The whole response is read with `r.arrayBuffer()`, decoded with `AudioContext.decodeAudioData`, and played from memory through an `AudioBufferSourceNode` with a gain node. Up to 8 raw buffers (`NI=8`) and 2 decoded buffers are cached.
- So the answer never appears in a URL (opaque token), there is no seeking over the network, and the next round is downloaded and decoded during the current one.
- Not visible from the bundle: whether the server returns a pre-cut clip or a whole song, the codec, and the upstream source.

### StarCraft use-map song quiz
Source: forum posts (https://staredit.net/279011/, https://staredit.net/topic/18451/); the rest is general knowledge of StarCraft, not verified this cycle.
- The song excerpts are WAV files stored inside the map file and played with the trigger action "Play WAV". Forum advice: at most 44100 Hz 16-bit, mono is enough; no known map size cap (an 80 MB map is reported to play).
- (General knowledge) Every player downloads the map before the game starts, so all audio is local before round 1; triggers run on the same game tick on every client, so all players hear the clip at the same time without streaming.

### What this means for D-009
Both references download the clip to the player **before** it is played and play it from memory. Neither seeks inside a remote file. That matches option C (server cache, opaque URL) and suggests a variant not yet measured: **serve a pre-cut clip** (only the round's excerpt, e.g. ~20 s) instead of the whole 3.6 MB file, which would cut download size and wait time. Queued for S-009 comparison.

## Where the audio itself comes from (follow-up 2, 2026-11-02)

### songspot.co: third-party 30-second-style previews
- https://songspot.co/about-us: "songspot uses short third-party music previews and catalog metadata where available." and "A publicly accessible preview is not, by itself, proof of permission for every possible reuse."
- https://songspot.co/privacy-policy: "Music catalog and audio-preview providers supply track metadata and preview audio." Named services: Cloudflare (hosting, delivery, database), Stripe, Google AdSense/Analytics, and "outbound links may open Apple Music".
- The provider is not named. **Inference (unverified):** Apple's public previews (iTunes Search API `previewUrl`), given the Apple Music links. The bundle's absolute `audioUrl` with a 4 s timeout and fallback to its own `/api/songspot/audio` fits "try the provider's preview URL, else our proxy".

### usemap.kr: YouTube embed
Read from its public Next.js chunks (`/_next/static/chunks/*.js`):
- Each question carries `videoId` and `startTime`; `audioUrl:""` is empty.
- It loads `https://www.youtube.com/iframe_api` and creates `new window.YT.Player(t,{height:"100%",width:"100%",playerVars:{autoplay:0,controls:0,modestbranding:1,rel:0,...,disablekb:1}})`.
- On the server's play action it calls `seekTo(E,!0)`, `unMute()`, `setVolume(a)`, `playVideo()`; on tab return it re-seeks by elapsed wall time.
- Whether the player box is visible or covered was not determined from the code.
- Its own UI sounds (`/sound/Glue/*.wav`) are fetched and played with Web Audio.

### StarCraft use-map: clips cut by the map maker
- The maker cuts excerpts from their own audio files (e.g. in Audacity, "Export ... as a .wav") and imports them; the map stores the WAV and the trigger references it by string id (https://staredit.net/371582/).
- No central audio source; each map ships its own clips.

### Summary
| Game | Audio source | Delivery |
|---|---|---|
| songspot.co | third-party previews (provider unnamed; Apple likely) | own server endpoint with opaque token, Web Audio |
| usemap.kr | YouTube videos | YouTube IFrame player, seek to `startTime` |
| StarCraft use-map | maker's own files, pre-cut WAV | inside the map file, Play WAV trigger |
| Ours (D-009) | AnimeThemes files | own server, pre-cut highlight clip, opaque URL |

## Apple (iTunes) previews for anime songs (follow-up 3, 2026-11-02)

Measured by the lead with the public iTunes Search API (`itunes.apple.com/search`, `country=JP`, `entity=song`, `limit=5`), one request every 3.2 s, UA `SongRushBot/0.1 research`. Query text = song title + first artist as AnimeThemes stores them. Media was downloaded to the session scratchpad only, never to the repo.

### Coverage
- **15 famous OP/ED songs** (queried in Japanese): 15/15 found with a `previewUrl`. Two top hits were a different recording (紅蓮の弓矢 "TVサイズver.", シルエット a live version).
- **25 random themes from `gql_page1.json`** (queried with AnimeThemes' romaji titles, seed 7), judged by hand from the top hit:
  - about 8 clearly the right song (e.g. Taiyou Runner -> 太陽ランナー, LIAR MASK -> Liar Mask 真山りか, Mahi -> 麻痺 yama);
  - 3 the right song in a character/short variant (Aikatsu "ver." tracks);
  - 2 unclear;
  - about 12 wrong song or no result (e.g. Dance With Me Tonight -> Olly Murs; Unite -> an Uma Musume track; 5 had no result).
- So: popular songs are there; for a romaji-only naive search over the whole catalog, roughly a third to a half match. Matching would need Japanese titles (not in our GraphQL query) and a match check.

### Which part of the song is the preview?
All previews were 30.0 s, cut from the full-length release (full tracks 239-275 s). Each preview was cross-correlated against the AnimeThemes TV-size OP (about 90 s, starts at the song's intro), whole and in three 5 s windows (consistent windows step by +10 s):

| Song | Preview lands at TV-size t= | 5 s windows (2/12/22 s) | Reading |
|---|---|---|---|
| 紅蓮華 | +30.1 s | +32.1 / +42.1 / +52.1 | middle of the TV cut (verse end -> chorus) |
| staple stable | +40.3 s | +42.3 / +52.3 / +62.3 | middle |
| unravel | +30.0 s | +32.0 / +42.0 / (44.9, weak) | middle; the TV edit diverges later |
| only my railgun | -7.4 s | +0.5 / +4.6 / +14.6 (inconsistent) | inconclusive |

In 3 of 4 the preview is **not the first 30 s**; it starts 30-40 s into the song, around the pre-chorus/chorus. The sources found say the start point is set by the label or distributor, not by Apple (https://help.emastered.com/hc/en-us/articles/40047077703821).

### StarCraft use-map WAVs
Not measured. Each map holds a few dozen excerpts chosen by one maker, usually down-sampled to keep the map small, with no metadata (song id, anime) and no central list; they would have to be extracted map by map and matched by hand.
