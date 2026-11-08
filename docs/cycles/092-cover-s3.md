# 092: S3 upload of covers (C-020)

From S-007, design `docs/design/088-songs-3000-covers.md` (option F3), decision D-104, and the cycle 090 review N1 (upload only what `index.json` names).

## Goal

`python -m songrush_data.s3covers --bucket NAME [--profile NAME] [--covers DIR]` uploads every cover named in `data/cover-cache/index.json` to the private bucket under `c/<id>` and writes the served map `data/cover-cache/s3-keys.json` that the server will read (B-020).

## Behaviour

- Input: `<covers>/index.json` as written by `songrush_data.covers` (`{"version": 1, "covers": {"<animeId>": {"id", "file", "size"}}}`). A missing or malformed index stops the run before any S3 call (exit 1). Files in the folder that the index does not name are never uploaded.
- Object key `c/<id>`; no separate work map is needed, because the ids are already random and stable in the index. Content type `image/jpeg`.
- Resume: one listing of prefix `c/` (all pages) first; an object already listed with the index size is skipped. A missing local file counts `missing`; a local file whose size differs from the index counts `mismatched`; neither is uploaded. A `put_object` error counts `failed` and the run goes on.
- Served map, written last through a temp file and a replace, from a second listing: `{"<animeId>": {"id": "<id>", "size": <bytes>}}` holding only covers whose object is listed with the index size.
- Logs never carry an anime id, a cover id, an object key or a credential, and an error's text is never printed (as `s3upload.py`). The final line: `s3 covers done: uploaded=<n> skipped=<n> missing=<n> mismatched=<n> failed=<n> served=<n>`.
- Exit 0 when `failed == 0`, else 1. Usage errors (including a bare positional argument) exit 2. The S3 client is made the same way as `s3upload.main` (boto3 session with optional profile, region `ap-southeast-2`).
- Reuse from `s3upload.py` by import where it fits (`REGION`, `_error_code`, `_write_json_atomic`, `_utf8_console`); `s3upload.py` itself does not change.

## Acceptance criteria

1. Every index entry with a present, right-size file is uploaded once to `c/<id>` with `ContentType="image/jpeg"`; a file in the folder that is not in the index is not uploaded.
2. A rerun against the same fake bucket uploads nothing and reports them as `skipped`.
3. Missing and wrong-size local files are counted and not uploaded; a `put_object` exception is counted as `failed`, the run continues, and the exit code is 1.
4. The served map holds exactly the covers listed in the final listing with the index size, maps anime id to `{id, size}`, and is written after all uploads (a failure mid-run leaves no new served map content for failed covers).
5. A missing or malformed index (not an object, wrong version, `covers` not an object) exits 1 before any S3 call.
6. No log line contains an anime id, a cover id or an object key from the run; the final log line has the counts.
7. The CLI rejects a bare positional argument (exit 2) and requires `--bucket`.
8. `.venv\Scripts\python.exe -I -m pytest -q` from `data/` exits 0; tests use a fake S3 client (as `test_s3upload.py`), no network and no AWS environment.

## Test list

- `data/tests/test_s3covers.py`: criteria 1-7 with a fake bucket in the style of `data/tests/test_s3upload.py` (copy the helpers it needs).

## File ownership

- Test writer: `data/tests/test_s3covers.py`.
- Implementer: `data/songrush_data/s3covers.py` (new).

## Out of scope

Running the upload for real (the owner runs it with their AWS profile), serving covers (B-020).
