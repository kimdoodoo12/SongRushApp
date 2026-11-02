# 020 raw-cache-nfc (C-010)

## Goal
Make the AniList cache hold the raw AniList media, normalized on read, so later fixes to the normalizer apply to records already cached (cycle 018 review M1); and make the aod Korean filter see decomposed (NFD) Hangul (cycle 019 review M1).

## Interface changes

### `data/songrush_data/anilist.py`
- **Stored record:** `{"media": <raw media object as returned by AniList, or null>}`, under the same key as before (`al-<first 16 hex of sha256(MEDIA_FIELDS)>-<id>.json`). The raw object is the element of `data.Page.media` for that id, unchanged.
- **Read path:** a hit returns `_normalize(raw)` (or `None` for a stored null). The result of `fetch_media` keeps exactly the cycle 018 shape.
- **Damaged entries:** as before, an entry that is not JSON, not an object, lacks `media`, or whose `media` is neither null nor an object is deleted and counts as a miss.
- No real AniList cache exists yet (the first real build is C-007), so no migration of normalized records is needed.

### `data/songrush_data/aod.py`
- In `korean_by_anilist`, each `title`/`synonyms` string is NFC-normalized (`unicodedata.normalize("NFC", text)`) before the Hangul check, and the NFC form is what is kept and deduplicated.
- So a decomposed-jamo string counts as Korean, and an NFD copy and an NFC copy of the same title collapse into one entry.

## Acceptance criteria (offline)
1. **Raw storage:** after `fetch_media` for 101922 against the `anilist_id_in.json` fixture (served for its ids, as in cycle 018), the cache entry is `{"media": M}` where `M` equals the fixture's raw media object for 101922 (it has `title`, not `titles`).
2. **Normalize on read:** a cache entry pre-seeded as `{"media": {"id": 7, "popularity": 5, "title": {"romaji": "R", "english": null, "native": "N"}, "synonyms": ["S"], "relations": {"edges": [{"relationType": "SEQUEL", "node": {"id": 8, "type": "ANIME", "format": "TV", "title": {"romaji": "R2"}}}]}}}` is returned by `fetch_media(..., [7])` with 0 requests as `{"popularity": 5, "titles": {"romaji": "R", "english": None, "native": "N"}, "synonyms": ["S"], "relations": [{"type": "SEQUEL", "node": {"id": 8, "type": "ANIME", "format": "TV", "titles": {"romaji": "R2", "english": None}}}]}`.
3. **Normalizer changes apply to cached records:** with the entry from criterion 2 cached, monkeypatching `anilist._normalize` to a function returning `{"marker": raw["id"]}` makes `fetch_media(..., [7])` return `{7: {"marker": 7}}` with 0 requests.
4. **Damaged entries:** pre-seeded entries for id 7 holding `{"media": 3}`, `{"other": 1}` or `not json` (each case separately) lead to exactly 1 request and a replaced entry whose `media` is the raw object.
5. **aod NFC** (strings built in the test with `unicodedata.normalize`):
   - (a) **NFD only**, the real case: a record with source `https://anilist.co/anime/157295`, title `"Jijou wo Shiranai Tenkousei ga Guigui Kuru."` and the single synonym `NFD("사정을 모르는 전학생이 엄청나게 들이댄다")` gives `{157295: [NFC string]}`. The current code gives `{}`, so this is red before the change.
   - (b) **NFD and NFC copies** of `"괴짜가족"` (the real AniList 2716 case) under one id collapse into one NFC entry.
6. **No regressions:** every existing test passes unchanged: `.venv\Scripts\python.exe -m pytest` exits 0 from `data/`.

## Test list
- `data/tests/test_raw_cache_nfc.py`: criteria 1-5.

## File ownership
- Test writer: `data/tests/test_raw_cache_nfc.py`.
- Implementer: `data/songrush_data/anilist.py`, `data/songrush_data/aod.py`.

## Review changes (spec round 1)
- **H1.** Criterion 5 as first written already passed on the old code (the NFD copy was dropped and the NFC copy kept). It now has an NFD-only case built on the real AniList 157295 record, which is red before the change. On the real release the change adds exactly that id (2661 to 2662 ids); 2716 is unchanged.
- The critic confirmed criteria 1-4 are red on the current code and that no locked cycle 018/019 test asserts the stored normalized form.
- **Nit.** The implementer also updates the `anilist.py` module docstring ("stores one normalized record").

## Out of scope
Cache expiry and the refresh option (C-007). Grouping and title rules (C-006).
