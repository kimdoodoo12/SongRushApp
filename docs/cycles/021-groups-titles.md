# 021 groups-titles (C-006)

## Goal
A pure function that turns per-anime source data (AniList media, Wikidata labels, Korean strings) into series groups, per-language title slots, the accepted-answer set and the reveal title for every catalog anime, following design 014 decisions 3 and 5 and the user's decisions D-015, D-020, D-035, D-037, D-038, D-039, D-041, D-042 and D-045.

## Interface: `data/songrush_data/groups.py` (pure, no I/O)

### Input
`build_titles(catalog_ids, media, wikidata, korean) -> dict[int, dict]`
- `catalog_ids`: iterable of AniList ids (ints) of the anime in the catalog. Duplicates are ignored.
- `media`: `{id: normalized media or None}`, the cycle 018/020 `fetch_media` shape: `{"popularity", "titles": {"romaji", "english", "native"}, "synonyms": [...], "relations": [{"type", "node": {"id", "type", "format", "titles": {"romaji", "english"}}}]}`. A missing key or `None` means no AniList data.
- `wikidata`: `{id: {"ko": [...], "en": [...], "ja": [...]}}` (cycle 019). Missing ids mean no labels.
- `korean`: `{id: [Korean strings]}` from aod and, later, AniDB and the manual supplement, already merged by the caller. Missing ids mean none.

### Output: one record per catalog id
```
{"group": int, "slots": {"ko": [...], "en": [...], "ja": [...], "romaji": [...]}, "accepted": [...], "reveal": str | None}
```

### Rules
1. **Slots** (each list deduplicated, first occurrence order; empty strings and `None` dropped):
   - `romaji`: AniList `titles.romaji`.
   - `en`: AniList `titles.english`, then Wikidata `en` labels.
   - `ja`: AniList `titles.native`, then Wikidata `ja` labels.
   - `ko`: Wikidata `ko` labels, then `korean` strings, keeping only strings with a Hangul syllable (`titles.has_hangul`) (D-039).
   - **Alias fallback (D-035), per slot:** if `en` or `romaji` is empty after the above, it takes the AniList synonyms that are **Latin-script** (they contain at least one letter, and every letter's Unicode name starts with `LATIN`). If `ja` is empty, it takes the synonyms that contain kana or CJK ideographs and no Hangul. Synonyms never enter a slot that already has an official title, so "OP" is not accepted for One Piece.
2. **Join rule** (D-020, D-041, D-042, D-045). Members are catalog anime plus relation nodes (a node that is itself a catalog id is that catalog anime). Only edges whose node `type` is `ANIME` count.
   - **Step 1, relation components.** Connect members along edges of type `SEQUEL`, `PREQUEL`, `SIDE_STORY`, `PARENT`, `ALTERNATIVE`, `SUMMARY`, `SPIN_OFF`, `COMPILATION` and `CONTAINS`. Every other type (`CHARACTER`, `SOURCE`, `OTHER`, `ADAPTATION`, ...) is ignored. Relation nodes outside the catalog are reached only through edges listed by catalog anime (one hop; their own relations are unknown, D-036).
   - **Step 2, groups.** Within each relation component, two members are joined when they **share a title**, compared for **every pair of members in the component**, not only across a direct edge. In addition, the two ends of a `COMPILATION` or `CONTAINS` edge are always joined (D-045). Groups are the connected components of these joins (union-find).
   - **Share a title:** compare each official romaji or English title of one member with each of the other (for a relation node, its node `titles`; for a catalog anime, its AniList `titles.romaji` and `titles.english`). The **base** of a title is `normalize(strip_qualifier(t))`, used only if it has at least 4 characters; the **head** is `titles.head(t)`. Two titles share when one base is a prefix of the other, when both heads exist and are equal, or when one title's head is a prefix of the other title's base.
   - Why pairs within a component (spec review H1): per-edge comparison split series whose entries are linked only through a differently named entry or through numbered heads (Sword Art Online and Alicization, Kizumonogatari II/III/Koyomi Vamp, Owarimonogatari and its second season, Railgun and Railgun T). This refines design 014 decision 3 without changing what the user decided (D-041: entries that share a title group, entries whose real title differs stay separate).
3. **Group id:** the smallest catalog AniList id in the group.
4. **Accepted** (D-015, D-038): over every source string of every member of the group, catalog anime and relation nodes alike, add `normalize(s)` and `normalize(strip_qualifier(s))`, dropping empty results. Source strings are a catalog anime's four slots, and a relation node's romaji and English titles. The list is sorted and deduplicated, and every catalog anime in a group gets the same list.
5. **Reveal** (design 014 decision 5), from the anime's own data only:
   1. the first Wikidata `ko` label that has a Hangul syllable and does not end in `<digits>화`, with a trailing ` (…애니메이션)` parenthetical removed;
   2. else the first `korean` string with a Hangul syllable;
   3. else AniList `titles.english`;
   4. else AniList `titles.romaji`;
   5. else the first string of any slot, else `None`.
6. **Determinism:** the output depends only on the input values, not on dict or set iteration order.

## Acceptance criteria (offline, pure)
The fixture `anilist_relations_franchises.json` (124 raw records with `title`, `relations` and no `synonyms`) is turned into `media` with `anilist._normalize`, and all 124 ids form the catalog unless a criterion says otherwise.
1. Fate/Zero (10087) and Fate/stay night (356) share a group (head "fate", D-042).
2. Bakemonogatari (5081) and Nisemonogatari (11597) are in different groups; Koyomimonogatari (21520) is not in 5081's group.
3. The Attack on Titan movies 20691 and 20692 are in the group of 16498; the Re:Zero OVAs 100049 are in the group of 21355.
4. With 101922's media taken from `anilist_id_in.json` (its synonyms include "Demon Slayer: Kimetsu no Yaiba: Bonds of Siblings" and "KnY") and the relations fixture for the rest, 101922's accepted list contains `normalize("Kimetsu no Yaiba")` and `normalize("Demon Slayer: Kimetsu no Yaiba")`, and does not contain `normalize("Demon Slayer")` or `normalize("KnY")`.
5. 99147 (Attack on Titan Season 3) accepts `normalize("Attack on Titan")`.
6. **Aliases:** an inline anime 21 with english "One Piece", romaji "ONE PIECE", native "ONE PIECE" and synonyms ["OP", "ワンピ"] does not accept "op"; an inline anime with english `None` and synonyms ["Latin Alias", "日本語別名", "한글별명"] gets `en == ["Latin Alias"]` and accepts `normalize("Latin Alias")`.
7. **Provenance:** for every catalog anime in the fixture run, every accepted string equals `normalize(s)` or `normalize(strip_qualifier(s))` for some `s` in the upper-bound set: the slots of every catalog anime with the same group id, plus the romaji and English titles of every `ANIME` relation node those anime list (computed in the test from the inputs).
8. **Compilations (D-045):** inline anime 1 "Alpha Story" with a `COMPILATION` edge to anime 2 "Totally Different" puts both in one group; the same pair with a `SEQUEL` edge stays separate; with a `CHARACTER` edge between "Fate/A" and "Fate/B" they stay separate.
9. **Out-of-catalog node:** inline catalog anime 10 "Gamma Saga" and 11 "Gamma Saga Final" have no edge to each other, but each has a `SEQUEL`/`PREQUEL` edge to node 12 "Gamma Saga 2" which is not in the catalog. 10 and 11 share a group, and the accepted list contains `normalize("Gamma Saga 2")`.
10. **Korean slot:** wikidata `ko` ["나루토 (애니메이션)", "Naruto"] and korean ["나루토", "나루토 질풍전"] give `ko == ["나루토 (애니메이션)", "나루토", "나루토 질풍전"]`, and the accepted list contains `normalize("나루토")` and `normalize("나루토 질풍전")`.
11. **Reveal:**
    - wikidata `ko` ["진격의 거인 1화", "진격의 거인 (애니메이션)"] gives "진격의 거인";
    - no ko labels and korean ["귀멸의 칼날"] gives "귀멸의 칼날";
    - no Korean at all gives the AniList English title, else romaji;
    - an anime with no AniList data and only wikidata `en` ["Only English"] gives "Only English";
    - no data at all gives `None`.
12. **Determinism:** running on the fixture twice, and once with the catalog ids, `media` and relation lists in reversed order, gives identical output; every group id is the smallest catalog id of its group.
13. `.venv\Scripts\python.exe -m pytest` exits 0 from `data/`.
14. **Series that must join** (fixture run): Owarimonogatari 21262 and Owarimonogatari (Ge) 21745; Kizumonogatari II 21399, III 21400 and Koyomi Vamp 181970; Sword Art Online 11757 with Ordinal Scale 21403 and Alicization 100182; Railgun 6213 and Railgun T 104462; Isekai Quartet 104454 and Isekai Quartet 3 194447; Haikyuu!! 20464 and TO THE TOP 106625. Kizumonogatari III 21400 accepts `normalize("Kizumonogatari I: Tekketsu-hen")` (a title of its group), and Alicization 100182 accepts `normalize("Sword Art Online")`.
15. **Series that must stay separate** (fixture run): Toaru Majutsu no Index 4654 and Toaru Kagaku no Railgun 6213; Isekai Quartet 104454 and Re:Zero 21355; Bakemonogatari 5081 and Monogatari Series: Second Season 17074; plus criterion 2.

## Test list
- `data/tests/test_groups.py`: criteria 1-12, 14, 15.

## File ownership
- Test writer: `data/tests/test_groups.py`.
- Implementer: `data/songrush_data/groups.py`.

## Review changes (spec round 1)
The critic simulated the rule on the 124-record fixture outside the repo.
- **H1.** Comparing titles only across a direct edge split seven same-title series in the fixture (criterion 14 lists them), so Alicization rejected "Sword Art Online" and Kizumonogatari III rejected "Kizumonogatari". Titles are now compared for every pair inside a relation component, and a head that prefixes the other's base also counts. The critic's simulation of the new rule kept criteria 1-5 and every separation in criterion 15, and showed no over-join in the fixture.
- **H2.** No criterion caught that split; criteria 14 and 15 now pin it.
- **M3.** Criterion 7 named the group's sources, which the output does not expose; it now uses an upper-bound set the test can compute.
- **Noted, not changed.** 3-nen Z-gumi Ginpachi-sensei joins Gintama (its English title starts with "GINTAMA"), which fits the shared-title rule. The head-prefix condition could merge a large franchise with a generic head (Gundam); design 014 already marks Gundam unverified, and C-007's first real build should list the largest groups for review.

## Out of scope
Popularity and the draw set, the catalog file and the Korean-gap report (C-007). AniDB (C-008) and the manual supplement (C-009) feed `korean` later without changing this function.

## Test correction (attempt 1)
Criterion 14 first said Kizumonogatari III accepts `normalize("Kizumonogatari")`. No anime in the group has that title: the only such string is the manga (`SOURCE` node 143701, type `MANGA`), and "Kizumonogatari" is the front part of "Kizumonogatari I: Tekketsu-hen", which D-038 does not accept. The implementer reported the contradiction with criterion 7 instead of editing the locked test. The lead corrected the criterion, unlocked only `test_groups.py`, and the test writer changed only that assertion.

## Result
- Tests `293a9d8`, corrected `8c7e5b0` (one assertion, see "Test correction"); implementation `a684bf5` (attempt 1, unchanged by the correction).
- Red phase (verifier): `33 errors`, all `ImportError` for the missing `songrush_data.groups`.
- Verifier: full suite exit 0, `453 passed in 12.92s`; `git diff 8c7e5b0 HEAD -- data/tests/` empty; criteria 1-15 confirmed. `build_titles` on the 124-record fixture takes about 11 ms.

## Code review (critic)
No Blocker or High. The critic compared the implementer's prefix-index join against a naive pairwise implementation of the spec's three share conditions: identical groups on the full fixture, on 200 random catalog subsets and on 3,000 random adversarial inputs. Largest fixture groups: Fate 21, Attack on Titan 15, Gintama 13, Sword Art Online 10; no anime has an empty accepted list.
- **M1 (queued in C-007).** A catalog anime whose AniList media is `None` (a deleted or merged id) gets no accepted titles, and the titles another anime lists for it as a relation node are not used. Its songs would be unanswerable, so C-007 leaves such anime out of the draw set and reports them.
- **N1 (Follow-up).** Reveal step 5 ("first string of any slot") can return a `ko` episode label that step 1 skipped, when an anime has no AniList data.
- **Noted.** When an anime has no official English title, every Latin-script synonym is accepted (D-035); C-007's first real build should count how often that happens in the top 1,500.
