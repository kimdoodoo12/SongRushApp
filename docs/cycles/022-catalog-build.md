# 022 catalog-build (C-007, part 1 of 2)

## Goal
Assemble the catalog from the source outputs that already exist (selected AnimeThemes anime, AniList media, Wikidata labels, Korean strings, and `groups.build_titles`), compute the draw set, the Korean-gap list and the build report, and write `data/catalog.json` deterministically. Pure assembly plus file writing; no network. Part 2 (cycle 023) adds the crawl total check and freshness, the CLI with its lock file, and the first real build.

## Interface: `data/songrush_data/catalog.py`

### Constants
- `CATALOG_VERSION = 1`, `DRAW_SET_SIZE = 1500` (D-019, D-027, D-034), `SIZE_CAP_BYTES = 12_000_000`.

### `build_catalog(anime, media, wikidata, korean, *, draw_size=DRAW_SET_SIZE) -> tuple[dict, dict]`
- `anime`: list of `animethemes.select_themes` outputs (`id`, `slug`, `name`, `year`, `season`, `anilistId`, `anidbIds`, `themes[]`). `media`, `wikidata`, `korean` as in cycle 021, keyed by AniList id.
- It calls `groups.build_titles(catalog_ids, media, wikidata, korean)` with the distinct non-null `anilistId`s.
- **Catalog anime** (one per AnimeThemes anime, sorted by `id`):
  `{"id", "anilistId", "slug", "year", "season", "group", "titles", "reveal"}`
  - Popularity is used for the draw-set order and the report but is **not stored** in the catalog: it changes on every rebuild and would turn most anime lines into diff noise (spec review M4); design 014 decision 7 does not list it. The **popularity** of an anime is AniList `popularity` of `anilistId`, or 0 when there is no AniList id, no media, or a null popularity (design 014 decision 6).
  - `group`, `titles` (the four slots), `reveal`: from `build_titles` for `anilistId`; when `anilistId` is null, `group` is null, every slot is empty and `reveal` is null.
  - `name` (the AnimeThemes display name) is not stored as a title (design 014 decision 3).
- **Themes** (sorted by theme `id`): `{"id", "animeId", "type", "slug", "song", "artists", "audio": {"basename", "url", "size"}}`. The audio `id` from the crawl is dropped.
- **Groups** (sorted by `id`): `{"id": group id, "accepted": [...]}` from `build_titles`; each group appears once.
- **Playable anime:** an anime whose group exists and whose group's `accepted` list is non-empty. Themes of other anime are kept in `themes` but never enter the draw set (cycle 021 review M1).
- **Draw set** (D-034, design 014 decision 6): the themes of playable anime, sorted by anime `popularity` descending, then `animeId` ascending, then theme `id` ascending; keep the first theme for each `audio.basename`; take the first `draw_size` theme ids, in that order.
- **Catalog object:** `{"version": CATALOG_VERSION, "anime": [...], "themes": [...], "groups": [...], "drawSet": [...]}`. No timestamps, so equal inputs give equal bytes.
- **Report** (a dict, for logging and the gap file):
  - `counts`: `anime`, `themes`, `groups`, `playableAnime`, `drawSet`, `drawSetAnime` (distinct anime in the draw set).
  - `koreanGaps`: draw-set anime whose own `ko` slot is empty, one entry per anime, sorted by popularity descending then anime id: `{"animeId", "anilistId", "popularity", "romaji", "english", "groupHasKorean"}`. `romaji`/`english` are the first strings of those slots or null. `groupHasKorean` is true when another catalog anime in the same group has a non-empty `ko` slot.
  - `unplayable`: anime ids (sorted) that have themes but are not playable.
  - `englishFromSynonyms`: `{"catalog": n, "drawSet": m}`, the number of catalog anime (and of draw-set anime) whose `en` slot is non-empty while their AniList `titles.english` is null and they have no Wikidata `en` label (the D-035 fallback).
  - `largestGroups`: the 20 largest groups by number of catalog anime (ties by group id), each `{"id", "size", "titles": the reveal titles of its first 10 member anime by AniList id}`, so an over-join is visible.

### `write_catalog(path, catalog, *, size_cap=SIZE_CAP_BYTES) -> int`
- Serializes as UTF-8 with LF line ends, no BOM:
  ```
  {"version": 1,
  "anime": [
  <one anime record per line>,
  ...
  ],
  "drawSet": [
  <one theme id per line>,
  ...
  ],
  "groups": [ ...one per line... ],
  "themes": [ ...one per line... ]
  }
  ```
  Keys after `version` are in sorted order. Each record is `json.dumps(record, ensure_ascii=False, sort_keys=True, separators=(",", ":"))`. An empty array is written as `"name": [\n]`. The file ends with `}\n`.
- If the encoded size exceeds `size_cap`, it raises `ValueError` and writes nothing.
- Writes atomically through `FileCache(path.parent).put(path.name, data)`. Returns the byte count.

### `write_gaps(path, report) -> None`
Writes `report["koreanGaps"]` as `data/korean_gaps.json` in the same style: `[` on its own line, one entry per line, `]` and a final newline, written through `FileCache.put`.

## Acceptance criteria (offline, inline inputs unless stated)
1. **Catalog anime:** an AnimeThemes anime with `anilistId` 7 becomes `{"id","anilistId":7,"slug","year","season","group":7,"titles":{...},"reveal":...}` with exactly those keys (no `popularity`); one with `anilistId` null gets `group` null, empty slots and `reveal` null. Null popularity counts as 0 in the draw-set order.
2. **Themes:** the catalog theme has exactly `id, animeId, type, slug, song, artists, audio` and `audio` has exactly `basename, url, size`.
3. **Draw set order and dedupe:** themes of anime with popularities 10, 30, 30 (anime ids 5 and 3 tie at 30) come out as anime 3's themes by theme id, then anime 5's, then the popularity-10 anime's; a theme whose `audio.basename` equals an earlier one is skipped; `draw_size=2` keeps the first two.
4. **Playable only:** themes of an anime with `anilistId` null, and of an anime whose media is `None` and that has no Wikidata or Korean data (empty accepted), are not in the draw set and are listed in `report["unplayable"]`.
5. **Groups:** two catalog anime of one series produce one `groups` entry; both anime carry its id.
6. **Korean gaps:** in a draw set with anime A (ko slot non-empty), B (no ko, same group as A) and C (no ko, alone), `koreanGaps` lists C then B or B then C by popularity, with `groupHasKorean` false for C and true for B; A is absent. An anime not in the draw set is never listed.
7. **Report counts:** `counts` matches the inline input; `englishFromSynonyms` is `{"catalog": 1, "drawSet": 1}` for one draw-set anime with english null, no Wikidata `en` and Latin synonym "Alias", `{"catalog": 1, "drawSet": 0}` when that anime is outside the draw set, and zeros otherwise; `largestGroups` is ordered by size then id, has at most 20 entries, and lists at most 10 titles per group in AniList id order.
8. **Determinism:** `build_catalog` on the same input with `anime` list order, each `themes` list order and dict insertion orders reversed gives an equal catalog and report; `write_catalog` of equal catalogs gives identical bytes.
9. **File format:** `write_catalog` output decodes as UTF-8 without BOM, has no `\r`, ends with `}\n`, parses with `json.loads` back to the catalog, and every element of `anime`, `themes`, `groups` and `drawSet` occupies exactly one line (the line count equals the number of elements plus a fixed overhead computed in the test from the stated layout). Korean text is written as-is, not `\u` escapes.
10. **Size cap:** with `size_cap` smaller than the encoded size, `ValueError` is raised and no file (and no temp file) exists afterwards.
11. **Gap file:** `write_gaps` output parses to the `koreanGaps` list and has one entry per line.
12. **Fixture smoke:** using `gql_page1.json` (its `data.animePagination.data` items, each through `select_themes`) as `anime` with empty `media`, `wikidata` and `korean`, `build_catalog` runs, `report["unplayable"]` equals the sorted ids of all anime with themes (all 100 in this fixture), and the draw set is empty.
13. `.venv\Scripts\python.exe -m pytest` exits 0 from `data/`.

## Test list
- `data/tests/test_catalog.py`: criteria 1-12.

## File ownership
- Test writer: `data/tests/test_catalog.py`.
- Implementer: `data/songrush_data/catalog.py`.

## Review changes (spec round 1)
No Blocker or High. The critic estimated the real catalog at 5.5-7 MB from fixture record sizes, so the 12 MB cap leaves about 2x headroom (to be measured in the real build).
- **M1.** `englishFromSynonyms` also counts the draw set, which is what the backlog asks.
- **M2.** `largestGroups` lists up to 10 member titles, so an over-joined franchise is visible.
- **M3.** The second half of C-007 is now its own backlog item, C-011, with every remaining criterion, including the end-to-end byte-identical build.
- **M4.** Popularity is not stored in the catalog, to keep rebuild diffs readable.
- **N1.** Criterion 12 names the unwrap path and asserts the exact unplayable list.
- **For S-003.** Theme records (`audio.basename`/`url` contain the anime slug, `song`, `artists`) and anime titles reveal the answer. The backend must proxy audio under opaque ids and send none of these fields before the round ends; recorded on S-003 in BACKLOG.

## Out of scope
The crawl total check, freshness and `refresh_all`, the CLI, the lock file, logging the report, and the real build (cycle 023). The Java catalog loader (from S-003).
