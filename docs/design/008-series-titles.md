# 008 Series grouping and titles for chat answers (S-008)

Stack updated for D-029 (2026-11-02); decisions unchanged.

Researched 2026-11-02:
- Two `explorer` runs: series grouping and titles, then Korean title sources.
- One lead audit of the full catalog (`docs/research/title-db-audit-2026-11-02.md`).

All live requests followed the CLAUDE.md External source rules (SongRushBot UA, gaps of at least 2 s, no 429).

User decisions that bind this design:
- **D-007/D-008.** Full official titles in Korean, English or Japanese count; romaji counts too. Season suffixes are not required. The reveal shows the exact entry's title.
- **D-015.** Answers come from chat. The server compares every message, ignoring spaces, punctuation, case and width.
- **D-020.** Use the automatic series rule as-is. The accepted range is wide.
- **D-022.** Keep every alias for now. Filtering comes later.

## Questions (BACKLOG S-008)
1. How to group seasons and sequels into one series.
2. How to get each series' titles per language.
3. Where official Korean titles come from, and how much of the catalog they cover.

## Findings

### F1. AnimeThemes `series` is a franchise bucket
Source: explorer, `seriesPagination` (751 series, 8 requests).
- It never splits the main seasons. Shingeki (15 entries), Kimetsu (7), Jujutsu (4), Haikyuu (6) and SAO each sit in one series.
- It **over-merges** different works:
  - Fate (series 42, 28 entries: stay night, Zero, Prisma Illya, Apocrypha, FGO, ...);
  - Toaru (Index and Railgun);
  - Re:Zero with the Isekai Quartet crossovers;
  - Shingeki with the *Chuugakkou* parody.
- 41 of the 100 entries on `gql_page1.json` have no series.

### F2. AniList relations
Source: `data/tests/fixtures/anilist_relations_franchises.json`, 124 entries in 12 franchises.

| Edges used | Components | Effect |
|---|---|---|
| SEQUEL/PREQUEL | 46 | Index and Railgun split; Shingeki under-merged into 6 pieces (the recap movies are loose) |
| + SUMMARY | 43 | recap movies join; Index/Railgun stay split; Fate/Zero and stay night stay together (AniList links them as prequel/sequel) |
| + PARENT | 25 | Toaru and Fate re-merge, as in AnimeThemes |

### F3. Rule chosen in D-020: AnimeThemes series ∩ AniList SEQUEL/PREQUEL/SUMMARY component
Source: lead audit over the full catalog. That is 5,018 anime with OP/ED, of which 5,012 have an AniList id.
- **3,589 groups.** 2,856 are singletons and 432 are pairs. The largest are:
  - Yami Shibai, 17;
  - the Gundam UC line, 14 (0083, Char's Counterattack, Unicorn);
  - Monogatari, 13; Pokemon, 12; Shingeki, 12; Gintama, 11.
- Index and Railgun are separate, and Fate/Zero and stay night are one group. That matches D-020: "the accepted range is wide".
- Chains break where the linking entry is not in the catalog. Those groups under-merge, but every entry's own titles are still accepted.

### F4. Titles per language (AniList)
Source: `anilist_titles_sample.json`.

| Sample | english | native | romaji |
|---|---|---|---|
| top 50 | 50 | 50 | 50 |
| random 50 | 41 | 50 | 50 |

- Where `english` is missing, the romaji title stands in.
- Suffix stripping by regex is **not** used. Explorer counted 29-37% of later-season titles that are not "base + suffix" (Kimetsu "Yuukaku-hen", Haikyuu "烏野高校 VS 白鳥沢学園高校"). Instead the group accepts every member's titles, so the base title arrives from the first season's entry.

### F5. Korean titles
Sources: Korean explorer, `korean_title_sources_sample.json`, `wikidata_ko_labels_sample.json`, and the lead audit.

| Source | Access | Notes |
|---|---|---|
| AniList `synonyms` with Hangul | GraphQL, with the titles query | few, mostly official |
| **anime-offline-database (aod)** | weekly GitHub release `anime-offline-database.jsonl.zst` (ODbL 1.0 + DbCL) | the largest source; unlabeled mix of official titles and abbreviations |
| Wikidata `ko` label/altLabel | one SPARQL query by P8729 (AniList id), 2,066 ids | disambiguators such as "(애니메이션)" |
| AniDB titles dump `ko` | `anime-titles.xml.gz`, at most once a day (secondhand rule), mapped through aod's AniDB links | typed official/syn, but the typing is unreliable (귀멸의 칼날 is "syn") |
| Kitsu, Laftel, TMDB | — | not used: no `ko` field / no ids / API key and no AniList mapping |

Coverage over **groups**, by AniList popularity, with all four sources pooled across group members:

| Groups | Any Korean | English |
|---|---|---|
| top 100 | 92 | 99 |
| top 500 | 382 | 497 |
| top 1500 | 964 | 1,464 |
| all 3,589 | 1,861 | 3,055 |

Gaps remain even among popular titles: 54 of the top 300 groups have no Korean (Black Clover, Blue Lock, Kuroko no Basket, ...).

### F6. Matching hazards
Source: the audit.
- 60 identical official titles on different works (remakes, such as Hunter x Hunter 1999/2011). These are harmless.
- 526 accepted strings of 2 or fewer characters (for example "op" for One Piece).
- Chat words that are accepted: "gg", "next" and "1".
- D-022 keeps all of these for now. The audit doc lists the proposed filters.

## Options
- **Grouping.** A: AnimeThemes series alone (over-merges Fate and Toaru). **B: intersection (D-020, chosen).** C: AniList components alone (under-merges more, and ignores the free series data).
- **Korean.** A: AniList only (top 100: about 20). B: AniList + Wikidata (top 50: 28). **C: all four sources (chosen; top 100: 92).**
- **Where matching runs.** A: on the client, with an index sent to it (design 002 D3). **B: on the server (chosen).** Chat answers must be judged by the authority (CLAUDE.md Multiplayer rules), and the client no longer needs any title list. Design 002's `GET /api/anime-index` is dropped.

## Decision
1. **Groups.**
   - Graph G has AniList ids as its nodes. Its edges are every SEQUEL, PREQUEL and SUMMARY edge to an ANIME node listed in any catalog anime's AniList `relations`, including edges to anime outside the catalog.
   - Two catalog anime are united (union-find) when they share at least one AnimeThemes series id **and** lie in the same connected component of G. This is what the audit computed.
     - A chain A-B-C, where B is not in the catalog, still unites A and C, because both A and C list their edge to B.
     - An anime with several series ids can bridge two series. That is accepted, since the range is wide (D-020).
   - An anime with no series, or with no AniList id, is its own group.
   - `groupId` is the smallest AnimeThemes anime id in the group, which keeps it stable across rebuilds.
2. **Accepted strings for a group.** The union over its members of:
   - AnimeThemes `title.romaji` and `synonyms`;
   - AniList `title.romaji`, `title.english`, `title.native` and `synonyms`;
   - aod `title` and `synonyms`, **only those containing Hangul**. aod's role is the Korean source, and its other strings are mostly duplicates of AniList or titles in other languages. Measured on the audit data, the accepted strings take 0.83 MB with Hangul-only aod versus 1.92 MB with all aod strings, against C-004's 5 MB cap. The audit's hazard counts (F6) were computed with all aod strings, so they overstate the hazards slightly;
   - Wikidata `ko` labels and altLabels;
   - AniDB `ko` titles.

   Each string is also added with a trailing `(YYYY)`, `(YYYY년 애니메이션)` or `(... 애니메이션)` removed. All strings are stored after **`normalize`**, the same rule the matcher (B-006) uses, and duplicates are removed. The rule has one Java implementation in the backend (B-006) and one Python implementation in the data tools (C-006); both must pass the shared vectors in `contracts/normalize-vectors.json`, and the game server re-normalizes `accepted` when it loads the catalog (design 011 decision 3). No other filtering is applied (D-022).
3. **Normalization** (used for both the stored strings and chat messages; Java in the backend, Python in the data tools, design 011 decision 3), applied in this order:
   1. NFKC
   2. lowercase
   3. NFD
   4. drop U+0300-U+036F
   5. NFC
   6. drop every character whose Unicode category starts with P, S or Z, every format character (Cf), the Hangul fillers U+115F, U+1160 and U+3164, the variation selectors U+FE00-U+FE0F, and all whitespace (hardening listed in design 011 F2)

   This is design 002 D3's function, moved to the server.
4. **Match:** `normalize(message) ∈ accepted(groupOf(currentTheme.animeId))`, with exact set membership. No fuzzy matching (D-015: "spelling must match").
5. **Reveal title.** Use the entry's own titles, in this order of preference:
   1. AniDB `ko` typed `official`
   2. Wikidata `ko` label
   3. AniList Hangul synonym
   4. AniList english
   5. romaji

   Apply the same parenthetical stripping as in decision 2 (so "나루토 (애니메이션)" becomes "나루토"), then add the year (superseded by design 009 D7: the year travels as a separate `year` field, not in the title). Example: 16498 gives "진격의 거인", from AniDB official.
6. **Data stays on the server.** `catalog.json` holds groups and accepted strings. No endpoint sends titles or groups to clients.
7. **Korean sources: failures and caching.** A Korean source is optional for the build.
   - **Failure.** If aod, Wikidata or AniDB fails after 3 attempts (429 `Retry-After` honored; a 5xx or timeout backs off), the build continues without that source. It logs `korean source <name> unavailable` and the per-source counts.
   - **Cache.**
     - aod: the cached release is reused for 7 days.
     - Wikidata: the result is reused for 7 days.
     - AniDB: the dump is reused for 24 h.
   - **Writes are atomic** (write to a temp file, then rename), so an interrupted download is never reused.
   - **Decompression.** aod's `.jsonl.zst` is read with the PyPI package `zstandard` (0.25.0), pinned in `data/requirements.txt`; the Python 3.12 standard library has no zstd (design 011 F4).
   - **AniDB ids** come from the AnimeThemes `resources` entry with `site == "ANIDB"`, which C-001 already fetches (gql_bakemonogatari: [6327]; an anime can have several, so C-001 keeps `anidbIds`). This replaces mapping through aod.
8. **Attribution.** aod's ODbL needs a credit line on the site, such as "데이터 출처: AnimeThemes, AniList, anime-offline-database, AniDB, Wikidata". **D-023 decided (user, 2026-11-02): yes.**

## Resulting items
- **C-003 rewritten** (AniList enrichment). The query also selects `title{romaji english native}`, `synonyms`, and `relations{edges{relationType(version:2) node{id type}}}`.
- **C-005** (new) data: Korean title sources, with fetch, cache and parse each injected:
  - aod: read the release file once per build. A zstd decoder is needed, or the release's uncompressed JSON asset if one exists; check the release assets.
  - Wikidata: one SPARQL query.
  - AniDB: the dump, re-downloaded at most once per 24 h, using the cached copy's mtime.
- **C-006** (new) data: groups and accepted strings, pure (Python).
- **B-006** (new) backend: chat answer matcher, pure (Java).
- **F-002 dropped.** D-015 removes autocomplete, and normalization lives in B-006.
- **C-001 extended:** keep `anidbIds` from the AnimeThemes resources.
- **C-004 note.** Accepted strings measured 0.83 MB on the audit data. The real build records the full byte count against the 5 MB cap.
- The audit scripts are committed under `docs/research/title-db-audit/` so the numbers can be reproduced.
- **D-023** goes to Needs decision (attribution line).

## Review changes (round 1)
Critic: 0 Blocker, 2 High, 5 Medium, 4 Nit. All of them are applied.

| Finding | Change |
|---|---|
| H1: the aod string scope differs between decision 2 and C-005 | aod contributes Hangul strings only. Sizes measured: 0.83 MB vs 1.92 MB with all aod strings |
| H2: normalize could be implemented twice, and the order was C-006 before B-006 | B-006 moved before C-006; a criterion pins `normalize`. Under D-029 the Java backend and the Python data tools each implement it, held together by shared vectors (design 011 decision 3) |
| M1: reveal title wrong for Shingeki and Naruto | AniDB official ko comes first, and parenthetical stripping applies to reveal titles; criteria added |
| M2: AniDB mapping through aod is unnecessary | `anidbId` taken from the AnimeThemes resources (C-001 criterion: 6327) |
| M3: no failure or cache policy for the new sources | Decision 7: sources are optional, use 7-day / 24 h caches and atomic writes; criteria added |
| M4: component scope unclear | Graph G defined: edges from catalog relations, including non-catalog nodes; chain criterion added |
| M5: numbers not reproducible | Audit scripts committed in `docs/research/title-db-audit/` |
| Nits | zstd decoder (now PyPI `zstandard`, design 011); timing criterion removed from B-006; 002 D3 marked superseded; M-1 text updated; Wikidata altLabel criterion uses a synthetic response |
