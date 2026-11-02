# Title DB audit (2026-11-02)

Built by the lead from live data, to check the answer-matching rules (D-015, D-020) on the full catalog before design.
- AnimeThemes GraphQL: all 51 `animePagination` pages (5,021 anime; 5,018 with OP/ED; 5,012 with an AniList id), ~10 s per page, 3.2 s gaps, no errors.
- AniList: titles, synonyms, popularity and v2 relations for those 5,012 ids (101 requests, 2.2 s gaps, no 429).
- anime-offline-database release 2026-27 (4,979 of the ids matched), Wikidata SPARQL ko labels/altLabels by P8729 (2,066 AniList ids), AniDB titles dump `ko` via aod's AniDB links.
- Normalization per D-015: NFKC, lowercase, strip combining marks, strip whitespace/punctuation/symbols. Scripts: session scratchpad `audit/` (not in repo).

## Grouping (D-020: AnimeThemes series ∩ AniList SEQUEL/PREQUEL/SUMMARY)
- 3,589 groups: 2,856 singletons, 432 pairs, largest 17 (Yami Shibai 1-17).
- Big groups: Mobile Suit Gundam UC line 14 (0083, Char's Counterattack, Unicorn, The Origin), Monogatari 13, Pokemon 12, Shingeki no Kyojin 12 (incl. recap movies), Gintama 11.

## Findings
| # | Case | Count | Examples | Risk |
|---|---|---|---|---|
| 1 | Same official title on different works (remakes) | 60 strings | Hunter x Hunter 1999/2011, Fruits Basket 2001/2019, Pokemon, Dororo, Rurouni Kenshin 1996/2023 | none: either title is a real name of the round's anime; reveal shows the year |
| 2 | Alias of one work = official title of another | 187 | "One Punch Man" alias on *One Punch Man Specials*; "Fullmetal Alchemist" alias on *Brotherhood*; "Demon Slayer" alias on *Onigiri* | mostly related works (fine under D-020's wide range); a few are wrong data (*Onigiri*) |
| 3 | Alias shared by several works | 316 | "snk", "aot", "hxh" | low |
| 4 | Normalized length ≤ 2 | 526 strings (40 official) | "op" One Piece, "dn" Death Note, "tg", "sg", "cg", "r2", 死神 | **high**: short chat text would score |
| 5 | Common chat words that are accepted | 3 | "gg" (*Kanojo mo Kanojo*), "next" (*Slayers*), "1" (*Trillion Game*) | **high** |
| 6 | Digit-only strings | 17 | "86" (official), "15"/"16" (*Kokoro Connect* aliases), "0" | high for aliases |
| 7 | Hangul aliases with abbreviations or season markers | 154 | "나히아 1기", "SxF, 스파이 패밀리", "【최애의 아이】 2기" | harmless under the wide range |
| 8 | Strings that normalize to empty | 0 | | |

## Korean coverage (groups with any Hangul string; AniList english shown for comparison)
| Groups by popularity | Korean (AniList+aod+Wikidata+AniDB) | Korean without Wikidata/AniDB | English |
|---|---|---|---|
| top 100 | 92 | 86 | 99 |
| top 500 | 382 (76%) | 341 | 497 |
| top 1500 | 964 (64%) | 763 | 1,464 |
| all 3,589 | 1,861 (52%) | 1,296 | 3,055 |

Popular groups still without Korean (54 in the top 300), e.g. Black Clover, Enen no Shouboutai, Ao no Exorcist, Tokyo Revengers, Blue Lock, Kuroko no Basket, Mashle, Kanojo Okarishimasu, Yofukashi no Uta, Summer Time Render.

## Proposed exception rules (for S-008 design)
1. Remakes sharing a title: accept (case 1).
2. Drop an alias when, after normalization, it is ≤ 2 characters, digit-only, or in a chat stop-list (gg, next, ok, lol, skip, 패스, 스킵, ㅋㅋ, ...). Official titles are kept even if short ("86", "K").
3. Drop an alias that equals an official title of a group in a *different* AnimeThemes series (wrong-data case like *Onigiri*); keep it when both are in the same series (related works).
4. Korean gaps: a hand-curated Korean title file for popular groups without Korean (about 118 in the top 500, 536 in the top 1500), reviewed by a human.
