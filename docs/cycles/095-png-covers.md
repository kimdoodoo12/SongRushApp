# 095: accept PNG covers (C-022)

The first real cover run (I-012, 2026-11-08) stopped after 34 covers: 15 downloaded, 19 failed with `body is not a JPEG (starts with 89504e47)`. AnimeThemes serves many `LARGE_COVER` links ending in `.jpg` whose body is PNG. Design 088 assumed JPEG from three samples; this cycle accepts both formats end to end.

## Goal

Covers in JPEG or PNG are downloaded, stored, uploaded and served with their real format.

## Behaviour

- **covers.py.** A body is accepted when it starts with the JPEG signature `FF D8 FF` or the PNG signature `89 50 4E 47 0D 0A 1A 0A` (other checks unchanged: non-empty, at most `MAX_BYTES`). The file is `<id>.jpg` for JPEG and `<id>.png` for PNG, written through `<file>.part`. The index entry's `file` is `<id>.jpg` or `<id>.png`. On a rerun, an entry is cached when its recorded file exists with the recorded size; when the body's format changes on a refetch, the new file replaces the entry's `file` and the old file is removed. The index rule becomes `file in {<id>.jpg, <id>.png}`.
- **s3covers.py.** The entry rule becomes `file in {<id>.jpg, <id>.png}`; the local path is `<covers>/<file>`; `ContentType` is `image/jpeg` for `.jpg` and `image/png` for `.png`. Object key and served map are unchanged (`c/<id>`, `{id, key, size}`).
- **Backend.** `CoverMap.loadDisk` accepts `file` = `<id>.jpg` or `<id>.png` and checks that file; `fileFor(id)` returns it. `GET /cover/{id}` in disk mode sends `image/jpeg` or `image/png` by the file's suffix. S3 mode is unchanged (S3 returns the content type stored at upload).

## Acceptance criteria

1. covers: a PNG body is saved as `<id>.png` with `file: "<id>.png"`; a JPEG body as `<id>.jpg`; any other body (e.g. GIF `47 49 46 38`, HTML, empty) still fails and leaves no file.
2. covers: a rerun skips a PNG entry whose file has the recorded size; an entry refetched in the other format ends with only the new file on disk and the index updated, keeping its id.
3. covers and s3covers: an index entry whose `file` is neither `<id>.jpg` nor `<id>.png` is rejected as before.
4. s3covers: `.jpg` uploads with `image/jpeg` and `.png` with `image/png`, from `<covers>/<file>`.
5. backend: a disk index with a `.png` entry loads and `/cover/<id>` returns 200 `image/png` with the exact bytes; `.jpg` still returns `image/jpeg`; an entry whose `file` suffix is anything else is skipped.
6. `.venv\Scripts\python.exe -I -m pytest -q` from `data/` and `.\gradlew.bat test` from `backend/` exit 0.

## Test list

- `data/tests/test_covers.py`: update the JPEG-only cases (bad bodies, index rule) and add PNG cases (criteria 1-3).
- `data/tests/test_s3covers.py`: update the entry rule and add the PNG content type (criteria 3-4).
- `backend/src/test/java/com/songrush/cover/CoverMapTest.java`, `CoverEndpointTest.java`: add PNG cases and the suffix rule (criterion 5).

## File ownership

- Test writer: the four test files above.
- Implementer: `data/songrush_data/covers.py`, `data/songrush_data/s3covers.py`, `backend/src/main/java/com/songrush/cover/CoverMap.java`, `CoverController.java`.

## Out of scope

Running the cover tool again (I-012), converting images, the frontend (F-022).
