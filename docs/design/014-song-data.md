# 014 Song data and catalog (S-001)

## Questions
1. Where do the songs, anime, popularity, titles and series relations come from? What are each source's limits and terms?
2. Which titles are accepted as answers, and how is chat compared with them? (D-015, D-020, D-034, D-035)
3. Which songs are drawn (D-019, D-027, D-033)?
4. What file do the Python tools produce for the Spring Boot backend?

## Findings
This cycle's explorer made every live request below one at a time, with the SongRushBot UA. The saved responses are in the session scratchpad `s001/`. There was no AniDB request and no media download.

- **F1. AnimeThemes API.**
  - Both JSON:API and GraphQL are documented with no deprecation notice (https://api-docs.animethemes.moe/, 200). GraphQL is labeled "experimental, may change without notice".
  - The rate limit is 90 requests/min, with depth 13 and complexity 10000 (`/graphql/intro/ratelimiting/`, 200). Live responses carry `x-ratelimit-limit: 90`.
  - No terms page was found: `animethemes.moe/terms-of-service` returned 404. The API code is MIT.
  - Totals from a live count query (200, 1.97 s): 5,021 anime, 14,771 themes, 14,574 distinct audio files, 751 series.
  - **Crawl.** Paging is `animePagination(first:100, page:N)`, with 51 pages; `data/tests/fixtures/gql_page_request.json` holds a working query. A full page took 8.5 s and 326 KB, so a full crawl takes about 7-25 min.
  - **Audio.** Each audio link is `https://a.animethemes.moe/<basename>.ogg`, Ogg. The median size is about 3.56 MB, the maximum about 14 MB.
    - Several videos of one theme point to the same audio file, so audio must be deduplicated by basename.
    - In the samples, 308/309 and 358/358 themes had audio.
  - **Theme fields.**
    - `song.title.romaji` is the song title.
    - `performances` (the artists) is empty for about 50% of themes.
    - `sequence` is null for single themes, so `slug` (for example `OP1`) is the reliable label.
  - **Links to other sources.**
    - `resources` carries an ANILIST external id for every one of the 200 sampled anime.
    - `series` is filled for only about 60% of anime.
- **F2. AniList GraphQL** (`https://graphql.anilist.co`).
  - **Limit.** Live headers show `X-RateLimit-Limit: 30`. The docs say 90/min normally and 30/min in a "degraded state"; that is from a search snippet, because docs.anilist.co returned 403 to the fetch tool.
  - **Batches.** `Page(perPage:50){ media(type:ANIME, id_in:[...]) }` works.
  - **Fields** (verified live):
    - `popularity` (users with it on a list);
    - `title{romaji english native}` and `synonyms`;
    - `relations{edges{relationType node{id type format}}}`.
  - **Terms.** The summary says non-commercial use is free, and "mass collection/hoarding" and "backup storage" are barred.
- **F3. Korean titles.** Coverage of the live AniList top 100 by popularity:

  | Source | Access | Coverage (top 50 / top 100) | Notes |
  |---|---|---|---|
  | anime-offline-database (aod) | GitHub release `2026-27`, 62 MB JSON (6 MB `.zst`) | 45/50, 79/100 | ODbL 1.0 + DbCL 1.0. **The repository is archived** (pushed 2026-07-04), so pin this release; new anime get nothing from it. Hangul appears only among untagged `title`/`synonyms` strings. |
  | Wikidata SPARQL | `query.wikidata.org/sparql`, P8729 (AniList id) with the `ko` `rdfs:label` | 22/50, 44/100 | CC0 (not re-verified). One batched query covered 100 ids in 2.7 s. |
  | AniDB titles dump | not requested | 37/50 (old fixture sample) | Its license and ban risk are unverified. Not before 2026-11-03 18:34 KST. |

  - aod and Wikidata together cover 85/100.
  - Every Korean miss in the top 50 is a later season: Shingeki no Kyojin S3 Part 2, Black Clover, Haikyuu!!, Boku no Hero Academia 4. Series-level acceptance (D-007/D-008) covers most of them.
- **F4. Aliases.**
  - 36 of the top 100 anime have an alias of 3 or fewer normalized characters (SnK, AoT, JJK, HxH, OPM). "OP" is a synonym of One Piece.
  - aod alone has 1,523 strings of at most 2 characters.
  - The same strings appear on many entries, mostly within one series ("snk3" and "aot3" on two Attack on Titan entries).
- **F6. Title coverage per language.** The live AniList top 100 (`s001/al_top1.json` and `al_top2.json`, queried with `title{romaji english native}`) has romaji for 100/100, native for 100/100 and English for 99/100; only *Charlotte* lacks one. So English, Japanese and romaji official titles are effectively always there for popular anime, and **Korean is the slot with real gaps** (15/100 have none in aod or Wikidata, F3). A first count over the old fixtures showed 350/429 English titles missing. That was wrong: those fixtures were saved with queries that did not select `english` or `native`.
- **F5. Popularity.** AnimeThemes has no popularity field. The join to AniList `popularity` goes through the ANILIST resource id. With about 2.9 themes per anime, the top 1,500 themes reach roughly the top 500-600 anime (an estimate).

## Options
1. **AnimeThemes API.**
   - (A) GraphQL: 51 page requests with a working query fixture, but labeled experimental.
   - (B) JSON:API: stable, but several includes per page.

   Choose A. The crawl runs offline into a cache, so if the schema changes only a rebuild breaks; switch to B then.
2. **Series grouping (D-020, D-041).**
   - (A) Connected components over AniList SEQUEL and PREQUEL edges.
   - (B) A, plus AnimeThemes `series`.
   - (C) Include SIDE_STORY, PARENT, ALTERNATIVE, SUMMARY and SPIN_OFF edges.

   - A leaves side entries alone: Koyomimonogatari, the Re:Zero OVAs, the Attack on Titan movies.
   - B depends on a field that is 40% empty and groups whole franchises (`gundam` holds After War Gundam X).
   - C over-merges franchises whose titles differ.

   The user chose **C, but entries whose real title differs stay separate** (D-041). Decision 3 turns that into a title rule.
3. **Korean sources.**
   - (A) aod (the pinned release) plus Wikidata.
   - (B) A plus the AniDB dump.

   Start with A. AniDB is a later item after its license check (Follow-ups).
4. **Draw set (D-034).**
   - (A) Pure popularity.
   - (B) A per-series cap.

   The user chose A.

## Decision
1. **Sources.**
   - **AnimeThemes GraphQL** for anime, themes, audio links and the AniList id.
   - **AniList** only for the AniList ids that AnimeThemes lists (D-036). Requests are batches of 50, one at a time, at least 2 s apart, at most 30/min.
   - **Wikidata SPARQL** for `ko`, `en` and `ja` labels.
   - **aod release `2026-27`** (pinned; the repository is archived) for Korean strings.
   - **AniDB titles dump** for Korean titles (D-040). It is used only after its license is checked, at most once per 24 h, and never before 2026-11-03 18:34 KST. It is its own item.
   - A **manual Korean supplement** `data/korean_titles_manual.json` (D-040), which the user fills in for popular anime that still have no Korean title. The build writes the list of such anime so the user knows what to fill.
   - Every response is cached under `data/cache/` with the CLAUDE.md external source rules. The UA is `songrush_data.common.USER_AGENT`.
2. **Themes in the catalog.** Keep themes of type OP or ED that have at least one audio file. Each theme keeps:
   - its AnimeThemes theme id, `animeId`, type, slug and song title;
   - the artists (possibly empty);
   - one audio basename, url and size. The audio is the first non-null audio, with entries ordered by `version` (null first) and then id, and videos ordered by id.
3. **Accepted titles: multilingual by design** (D-015, D-035, D-037, D-038, D-039). Players type the title they know in **Korean, English, Japanese or romaji**.
   - **English, Japanese and romaji: official titles only, typed in full** (D-035, D-038).

     | Slot | Official sources |
     |---|---|
     | English | AniList `title.english`, Wikidata `en` label |
     | Japanese | AniList `title.native`, Wikidata `ja` label |
     | Romaji | AniList `title.romaji` |

     - The AnimeThemes display name is not a title source, because it carries disambiguation tags.
     - A front part is not accepted on its own: "Demon Slayer" alone is wrong for "Demon Slayer: Kimetsu no Yaiba", but the romaji "Kimetsu no Yaiba" is right.
     - English aliases (synonyms) are not accepted when an official English title exists. F6 shows English, Japanese and romaji official titles exist for 99-100 of the top 100.
   - **Korean: every collected Korean title** (D-039). Korean has no single official title, so the slot takes every string with a Hangul syllable (U+AC00-U+D7A3) from Wikidata `ko`, aod, AniDB and the manual supplement.
   - **Trailing tags need not be typed** (D-038). Each accepted title is also accepted with its trailing qualifier removed. C-001 fixes the exact list and pins it with vectors. The list covers:
     - `Season N`, `, season N`, `Nth Season` (2nd, 3rd, ...), `Part N`, `Final Season`, `The Final Season`;
     - `第N期`, `N기`, `시즌 N`, `N화`;
     - a trailing year `(YYYY)` and parenthetical qualifiers such as `(TV)` or `(애니메이션)`.

     Both forms are accepted. A strip that would leave fewer than 4 characters is not applied.
   - **Series pooling** (D-007/D-008, D-020, D-041, D-042). A series group is the connected components of catalog anime over the AniList edges SEQUEL, PREQUEL, SIDE_STORY, PARENT, ALTERNATIVE, SUMMARY and SPIN_OFF. **Every edge type joins only when the two entries share a title**, which the user's examples define:
     - **Base title.** An official romaji or English title, with its trailing qualifier removed, normalized. If the base has fewer than 4 characters, the title is not used for joining.
     - **Head.** The part of the official title before the first `/` or `:`, if there is one, normalized and at least 4 characters.
     - **Two entries share a title** when, for some pair of their official romaji or English titles:
       - the base of one is a prefix of the base of the other, **or**
       - both have a head and the heads are equal.
     - **Examples:**
       - Fate/Zero and Fate/stay night: head "fate" on both, so they join (D-042).
       - Attack on Titan Season 2: its base equals "attackontitan", so it joins the first season.
       - The Re:Zero OVAs: the base "rezerokarahajimeruisekaiseikatsu" is a prefix of the OVA titles, so they join.
       - Bakemonogatari and Nisemonogatari: neither prefix nor head matches, so they stay separate (D-042).
       - Koyomimonogatari stays separate from Bakemonogatari.
     - **Anime outside the catalog** (reached through relations) contribute their titles from the relation node, which C-004 selects as `node{id type format title{romaji english}}`. They join by the same rule, but only one hop: their own relations are not fetched (D-036).
     - Every anime in a group accepts the union of its group's accepted titles.
     - C-006 pins these examples, plus Gundam once relation data for it is saved as a fixture (its expected grouping is unverified until then).
   - **Alias fallback (D-035) applies per slot.** If an anime has no official English title (for example *Charlotte*, or *Carnival Phantasm* in the fixture), its Latin-script synonyms are accepted for English.
     - These may include other European languages; this case is rare (F6).
     - Japanese and romaji official titles were present for every anime sampled, so their fallback is defined the same way but not expected to trigger.
   - The catalog keeps each anime's titles per slot, so the reveal and later modes can show the title in the player's language.
4. **Normalization (D-015):** spaces, punctuation, case and width are ignored, and spelling must match. C-001 specifies the exact Unicode steps and pins them in `contracts/normalize-vectors.json`, hand-written from D-015 and this design. The Python tools and the Java backend both run it.
5. **Reveal title.** The reveal shows the entry's own title, season included:
   1. the Wikidata `ko` label, else the first Korean title from aod, AniDB or the manual supplement;
   2. else the AniList `title.english`;
   3. else the AniList `title.romaji`.

   The reveal strips a Wikidata parenthetical disambiguation such as `(애니메이션)`, and episode-item labels such as `… 1화` are not used.
6. **Popularity and draw set (D-034).**
   - Each theme's popularity is its anime's AniList `popularity`; an anime with no AniList data counts as 0.
   - Sort every catalog theme by popularity (descending), then AnimeThemes anime id, then theme id.
   - Keep the first theme for each audio basename.
   - Take the first 1,500. That is the draw set.
7. **Catalog file** `data/catalog.json` (committed): UTF-8, LF, sorted keys, one record per line, with a size cap fixed in C-007. It holds the whole catalog, so later modes (M-5) can widen the range. Its contents:
   - `version`;
   - `anime[]`, with the titles per slot and the reveal title;
   - `themes[]`;
   - `groups[]`, each with its accepted normalized strings;
   - the ordered `drawSet[]` of theme ids.

   It contains no audio files.

## Resulting items (each one cycle; acceptance criteria in BACKLOG)
- **C-001** normalize, the trailing-qualifier rule and Hangul detection, plus `contracts/normalize-vectors.json`.
- **C-002** polite HTTP client and cache: UA, gap, timeout of at least 60 s, `Retry-After`, cache. 4xx is not retried, **except 429**, which waits `Retry-After`.
- **C-003** AnimeThemes crawl and theme selection.
- **C-004** AniList enrichment: popularity, titles, and relations with `node{id type format title{romaji english}}`.
- **C-005** Wikidata labels (`ko`, `en`, `ja`) and the pinned aod Korean strings.
- **C-006** groups, accepted titles and reveal titles (pure), with the D-041/D-042 title-sharing rule, pinned on the saved relation fixtures.
- **C-007** catalog build CLI, draw set, and the list of popular anime with no Korean title. **The lead then runs the first real build.**
- **C-008** AniDB Korean titles, after a license check (Needs decision if unclear), not before 2026-11-03 18:34 KST.
- **C-009** manual Korean supplement: file format, merge, and the report of anime still missing a Korean title.
- **Later, from S-003:** the Java normalize and catalog loader, which run the same vectors.
- **CLAUDE.md:** the lead records the confirmed limits (AnimeThemes 90/min, AniList 30/min, Wikidata, aod pinned) in the external source rules.
- **Needs decision:** the D-023 credit line, once the final source list is known.
