# 073 Audio on S3 with presigned URLs (S-006)

Owner decision D-092 (2026-11-06): the 1,500 cached songs move from the server disk to a private S3 bucket. Players get short-lived S3 presigned URLs, signed with the EC2 instance role, so there is no stored key and no CloudFront for now. The upload uses an upload-only IAM user key on the owner's PC with a resumable tool, and the key is deleted after the upload.

## Facts (checked 2026-11-06)
- **Bucket:** `songrush-audio-7k2m-498952560408-ap-southeast-2-an`.
  - General purpose, account-regional namespace, `ap-southeast-2`.
  - Block Public Access is on, ACLs are disabled, encryption is SSE-S3.
  - CORS allows `GET`/`HEAD` from `https://animeopguessr.com` (owner, console).
- **Upload user:** IAM user `songrush-audio-upload`. Inline policy `songrush-audio-upload-only` allows `s3:ListBucket` on the bucket and `s3:PutObject` on `bucket/*`. It has no access key yet.
- **Server role:** `songrush-ec2-ssm` is attached to `i-029cc43ab5ef75df1`. The owner adds `s3:GetObject` on `bucket/*` (policy `songrush-audio-read`).
  - A container on the server reads the role through IMDSv2: a `curlimages/curl` container got a 56-character token and the role name `songrush-ec2-ssm`. The metadata hop limit therefore allows containers.
- **Client:** it loads a song with `fetchFn(url)`, then `decodeAudioData` (`frontend/src/audio/playback.js:24-36`). A cross-origin source therefore needs CORS. A same-origin `/audio/{token}` that answers with a redirect to S3 keeps the client unchanged: `fetch` follows the redirect in CORS mode, and the Origin stays `https://animeopguessr.com` because the redirect starts same-origin.
- **Server today:**
  - `AudioController` streams `audio-cache/<basename>` for a valid token (`backend/.../audio/AudioController.java`).
  - `CatalogLoader` counts a theme as a draw candidate only when `audio-cache/index.json` has an entry and the local file exists with the indexed size (`CatalogLoader.java:81-115`).
  - Tokens come from `Room` with `TOKEN_TTL` (`Room.java:439`).
- **Index:** `data/audio-cache/index.json` maps theme id to `{basename, durationMs, size}`. It is gitignored and not on the server today.

## Decisions
1. **Object names.**
   - Each audio basename gets one random object key: `a/` followed by 22 URL-safe characters (`secrets.token_urlsafe(16)`), with no extension.
   - The upload sets `Content-Type: audio/ogg` and `Cache-Control: private, max-age=86400`.
   - The key carries nothing about the song.
   - The same audio always has the same key. Fingerprinting by key is no worse than fingerprinting by file content, which is already accepted (D-025, D-067).
2. **Key map.** `data/audio-cache/s3-keys.json` maps `{basename: key}`.
   - It is written atomically before any upload starts, so a crash never leaves an uploaded object without its map entry.
   - It reveals answers, so it stays out of git (`audio-cache/` is ignored) and out of the image (`.dockerignore` allows only `backend` and `frontend`).
   - It goes to the server's `data/audio-cache/` with `index.json`.
   - Neither the map nor any key is ever logged.
3. **Upload tool.** `python -m songrush_data.s3upload --bucket <name> [--profile <aws profile>]` uses boto3, pinned in `requirements.txt`.
   - It reads credentials from the standard AWS chain: a profile, or `AWS_*` variables in the owner's own shell. It never takes a key as an argument and never prints one.
   - **Order of work:**
     1. Load `index.json`.
     2. Extend the key map for new basenames and save it.
     3. List the bucket under `a/` with `ListObjectsV2`, paged.
     4. Upload, one at a time, every mapped object that is missing or whose listed size differs from the local file.
   - **Resume.** A rerun after a break uploads only the rest.
   - **Progress.** It prints a count every 25 files and a final summary of uploaded, skipped, missing local files and failures, with exit status 1 on any failure.
   - Its tests use a fake client object: no network, no boto3 call.
4. **Server, S3 mode.** It is on when `songrush.audio.s3-bucket` is set (env `SONGRUSH_AUDIO_S3_BUCKET`). Otherwise the server works exactly as today.
   - **Settings:**
     - `songrush.audio.s3-region`, default `ap-southeast-2`;
     - `songrush.audio.url-ttl`, default 10 minutes.
   - **Startup.** The key map is loaded from `audio-cache/s3-keys.json`. A missing or invalid map fails startup, like a bad catalog.
   - **Candidates.** A candidate is a theme with a valid index entry whose basename has a key. No local file is needed.
   - **`GET /audio/{token}`.** It resolves the token and the candidate exactly as today, then answers `302` with `Location` set to a presigned `GetObject` URL for that key, valid for `url-ttl`, and `Cache-Control: no-store`. Unknown or expired tokens stay 404.
   - **Signing.** The AWS SDK v2 `S3Presigner` signs locally with the default credentials chain, which is the instance role on EC2. Presigning makes no network call.
   - Logs keep only the token prefix.
5. **Client.** No change. The redirect is followed by `fetch`, and `decodeAudioData` is the same.
6. **Operations (owner, with the lead's help).**
   - Add `s3:GetObject` to the server role.
   - Copy `index.json` and `s3-keys.json` to `/opt/songrush/data/audio-cache/`.
   - Set `SONGRUSH_AUDIO_S3_BUCKET` in the server compose.
   - Deploy.
   - Delete the upload key after the upload.

## Options considered
- **CloudFront signed URLs (D-077).** They need a CloudFront key pair kept on the server, and the simplified account may block key groups. They can be added later without moving any object.
- **The browser fetching S3 directly from a URL in `prepare`.** The client would need a new field, and URLs would sit in messages longer. The redirect keeps today's opaque `/audio/{token}` contract.

## Resulting items
1. **C-012 data: resumable S3 upload with random object names** (from S-006).
   - Tests, with a fake client, cover:
     - a new map is written before the first upload;
     - existing map entries are kept;
     - objects already listed with the right size are skipped;
     - a size mismatch is re-uploaded;
     - missing local files are reported;
     - a failure gives exit status 1, and the map is still saved;
     - no key or credential appears in the output;
     - the `--bucket` flag is required and a bare positional argument is rejected.
2. **B-018 backend: S3 presigned audio mode** (from S-006).
   - Tests cover:
     - with no bucket, behaviour is unchanged (existing tests);
     - with a bucket and a key map, candidates no longer need local files;
     - `/audio/{token}` gives a 302 to `https://<bucket>.s3.ap-southeast-2.amazonaws.com/a/<key>` with `X-Amz-Expires=600` and `Cache-Control: no-store`;
     - an expired token gives 404;
     - a missing or invalid key map fails startup;
     - the `Location` holds no basename or title.
   - The tests use static fake credentials and make no network call.
