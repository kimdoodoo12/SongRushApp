# 074 s3-audio (C-017, B-018)

## Goal
Build the code that serves the song audio from the private S3 bucket. This follows design 073; its "Review changes (round 1)" section governs.
- A resumable data tool uploads the cached audio under random object keys and writes the map the server reads.
- The server, in S3 mode, redirects `/audio/{token}` to a 60-second presigned URL.

The client does not change.

## Part A: data tool `data/songrush_data/s3upload.py` (C-017)

### Files in the cache directory (default `data/audio-cache`)
| File | Role |
|---|---|
| `index.json` | Input, as today: `{themeId: {basename, durationMs, size, ...}}` |
| `s3-keys.work.json` | Work map `{basename: key}`. It is kept between runs, and existing entries never change |
| `s3-keys.json` | Served map `{basename: {"key": key, "size": size}}`. It is written last, atomically (temp file and replace), and holds only verified objects |

### Interface
`run(s3, cache_dir, bucket, *, log=print, new_key=None) -> dict`
- `s3`: a boto3 S3 client (or anything with the same methods).
- `new_key`: a zero-argument callable. It defaults to `lambda: "a/" + secrets.token_urlsafe(16)`.
- Returns the summary `{"uploaded", "skipped", "missing", "mismatched", "failed", "served"}` (all ints).

`main(argv=None) -> int`
- `--bucket` is required. `--profile` is optional. `--cache-dir` is optional.
- A positional argument is rejected with argparse exit 2.
- `boto3` is imported inside `main` (lazily). It builds `boto3.Session(profile_name=...).client("s3", region_name="ap-southeast-2")` and calls `run`.
- Returns 0 when `failed == 0`, else 1.
- **Corrupt maps.** An unreadable or malformed work map or served map returns 1 before any S3 call or file write, and the message names the file.
- Output is UTF-8 (`sys.stdout.reconfigure`, as in `audio.py`).

### Steps in `run`
1. **Load.** Load `index.json`. A missing index raises an error.
2. **Assign keys.** Load the work map (absent means empty; corrupt raises `ValueError`). Add a key from `new_key()` for every index basename without one. Save the work map atomically **before** any S3 call. Keys are unique.
3. **List.** List the bucket under `Prefix="a/"` with `list_objects_v2`, following `IsTruncated` and `NextContinuationToken`. An empty bucket has no `Contents` key. The result is `{key: size}`.
4. **Upload.** For each index entry, in sorted basename order:
   - The local file is missing → count `missing`.
   - The local size differs from the index `size` → count `mismatched`.
   - The listed size equals the index size → count `skipped`.
   - Otherwise call `put_object(Bucket=bucket, Key=key, Body=<bytes>, ContentType="audio/ogg")`. Success counts `uploaded`. An exception counts `failed`, and the run continues.
   - Log progress every 25 entries.
5. **Verify.** List again, as in step 3.
6. **Write the served map.** Write it with every basename whose key is listed with the index size, then log the summary.

### Never
- No credential, access key, object key or basename appears in the log output. Counts and file names of the maps are fine.
- No network in tests. The conftest guard already blocks sockets.

### Dependencies
`boto3` and its transitive packages are pinned to exact versions in `data/requirements.txt`, installed into `data/.venv`.

## Part B: backend S3 mode (B-018)

### Setting
`songrush.audio.s3-bucket` (env `SONGRUSH_AUDIO_S3_BUCKET`), in a new `@ConfigurationProperties(prefix = "songrush.audio")` record. Its default is empty, which means disk mode, unchanged. `SongrushProperties` stays as is.

### Constants
- Region `ap-southeast-2`.
- Presigned URL lifetime 60 seconds.

### Served map
`audio-cache/s3-keys.json`, the format of Part A. In S3 mode a missing or invalid map (bad JSON, a non-object entry, a key not starting with `a/`, a size that is not a positive integer) fails startup with a `CatalogException`, like a bad catalog.

### Candidates in S3 mode
A draw-set theme is a candidate when:
- it has a valid index entry (as today: basename, positive duration, size);
- the served map has an entry for that basename;
- that entry's `size` equals the index `size`.

No local file is needed. Startup fails when `index.json` exists and no candidate remains.

The existing `CatalogLoader.load(catalogJson, indexJson)` and the `Candidate` record keep their signatures and behaviour. S3 mode is a new overload or a new class.

### Drop counts
Both modes log how many themes were dropped per reason, at INFO: no index entry, a bad entry, no file or no key, a size mismatch.

### `/audio/{token}` in S3 mode
- Resolve the token and the candidate exactly as today. Unknown or expired gives 404.
- Then answer **302** with `Location` set to a presigned `GetObject` URL for the mapped key (60 s), plus `Cache-Control: no-store`. There is no body and no other song-identifying header.
- A presign exception gives **503**, and the log carries only the token prefix.
- **Signing.** The AWS SDK v2 `S3Presigner`, with region `ap-southeast-2`, credentials from an `AwsCredentialsProvider` bean (default `DefaultCredentialsProvider`), and path-style off. The URL host is therefore `<bucket>.s3.ap-southeast-2.amazonaws.com`.
- **Credentials at startup.** In S3 mode the provider is resolved once at startup. A failure fails startup with a clear message. Tests replace the provider bean with static fake credentials.

### Dependencies
- The AWS SDK v2 BOM, exact version, via `platform(...)` in `build.gradle`.
- `software.amazon.awssdk:s3`, with the `apache-client` and `netty-nio-client` modules excluded, because presigning needs no HTTP client.
- If the S3 client classes need an HTTP client at class load, use `url-connection-client` instead.

## Acceptance criteria
### Data (`.venv\Scripts\python.exe -m pytest` from `data/`)
`data/tests/test_s3upload.py` (new) covers the following. It uses `botocore.stub.Stubber` on a real `boto3` client created with fake credentials and region `ap-southeast-2`, plus a deterministic `new_key`.
1. **First run.**
   - An empty bucket (no `Contents`): every valid file is uploaded with `ContentType` `audio/ogg`.
   - The work map is written before the first `put_object`. For example, `new_key` asserts the file does not exist yet; or the stub's first call checks the work map file exists.
   - The served map lists every uploaded basename with key and size.
2. **Second run.** Existing work map entries keep their keys. Objects listed with the right size are skipped (no `put_object`).
3. **Two-page listing.** It is followed through `NextContinuationToken`.
4. **Size mismatch.** A listed size different from the index is uploaded again.
5. **Missing file.** A missing local file counts `missing` and is absent from the served map.
6. **Local size mismatch.** A local size different from the index counts `mismatched`, is not uploaded, and is absent from the served map.
7. **Failed upload.** A `put_object` error counts `failed`, the run continues, and that basename is absent from the served map. `main` returns 1.
8. **Corrupt map.** A corrupt work map or served map makes `main` return 1, with no S3 call and both files unchanged.
9. **Quiet output.** The captured output contains no object key, no basename and no fake credential.
10. **Strict CLI.** `main([])` exits 2 (missing `--bucket`), and a positional argument exits 2.

### Backend (`.\gradlew.bat test` from `backend/`)
New tests under `backend/src/test/java/com/songrush/`:

11. **`catalog/S3CatalogLoaderTest`.** It covers the candidate rules above:
    - map entry present and the size matches → candidate, with no local file;
    - no entry or a size mismatch → dropped;
    - zero candidates with an index → `CatalogException`;
    - an invalid map (each invalid kind) → `CatalogException`.
12. **`audio/S3AudioEndpointTest`.** A Spring Boot test with `songrush.audio.s3-bucket=test-bucket`, a temp data dir with a fixture catalog, index and served map, and static fake credentials. It checks:
    - a valid token gives 302;
    - the `Location` host is `test-bucket.s3.ap-southeast-2.amazonaws.com`;
    - the `Location` path is the mapped key;
    - the query has `X-Amz-Expires=60`;
    - `Cache-Control: no-store`;
    - the `Location` contains no basename and no title;
    - an unknown token gives 404;
    - an expired token gives 404.
13. **Startup failures.** A missing served map in S3 mode fails context startup. A credentials provider that throws fails startup.
14. **Earlier tests.** All earlier backend and data tests pass unchanged, including `AudioEndpointTest` in disk mode.

## Test list
- `data/tests/test_s3upload.py`
- `backend/src/test/java/com/songrush/catalog/S3CatalogLoaderTest.java`
- `backend/src/test/java/com/songrush/audio/S3AudioEndpointTest.java`
- `backend/src/test/java/com/songrush/audio/S3StartupTest.java`
- Any fixtures they need, under the tests' own folders.

## File ownership
- **Data implementer:** `data/songrush_data/s3upload.py`, `data/requirements.txt`.
- **Backend implementer:**
  - `backend/build.gradle`;
  - `backend/src/main/java/com/songrush/audio/**`;
  - `backend/src/main/java/com/songrush/catalog/**`;
  - a new properties record under `com.songrush`.
- **Never touch:** `deploy/`, `frontend/`, the untracked `compose.yml`.

## Out of scope
- CloudFront.
- The client.
- The operations themselves (I-011).
- `deploy/smoke.py` (a proposal to the owner).
