# 106 cloudfront-front (D-118)

## Decision (owner, 2026-11-10)
"그러면 이제 나는 CloudFront를 먼저 개선단계로서 써보고싶어. 내가 해야하는 단계를 알려주고 넌 그동안 필요한 작업을 해"

- Improvement step 1 of the performance report (`docs/performance.md`, section 5) is CloudFront in front of the audio and cover bucket.
- This reverses D-110, which dropped CloudFront. It does so without that decision's two problems:
  - **No CloudFront key pair.** The server keeps signing **S3 presigned URLs** (60 s) exactly as today and only swaps the URL's scheme and host for the CloudFront domain.
  - **No unsigned, never-expiring URLs.** The distribution has no origin access control: it forwards every query string, and the S3 origin's host header, to the private bucket. S3 still checks the signature and expiry, so a request without a valid signature is refused just as now. CloudFront only moves the TCP/TLS endpoint near the player and carries the bytes over the AWS network.
- **On/off switch.** The host swap happens only when `songrush.audio.cdn-host` (env `SONGRUSH_AUDIO_CDNHOST`) is set. Blank means today's behaviour. This is what lets the report compare before and after.

## Goal
In S3 mode, when `songrush.audio.cdn-host` is set, the `Location` of `/audio/{token}` and `/cover/{id}` redirects is the same presigned URL with scheme `https` and host = the CDN host. The path and the raw query (all `X-Amz-*` parameters) are byte-for-byte unchanged.

## Backend
1. **New pure helper `com.songrush.audio.CdnUrls`.**
   - `static String rewrite(String presignedUrl, String cdnHost)`: returns `"https://" + cdnHost + <raw path> + "?" + <raw query>` of the presigned URL. The raw (still-encoded) path and query are kept exactly; nothing is decoded or re-encoded.
   - If `cdnHost` is null or blank, it returns the input unchanged.
   - `static boolean validHost(String)`: true only for a lowercase DNS hostname, labels `[a-z0-9-]` separated by dots, with at least one dot and no scheme, port, path or whitespace.
2. **Property.** A new `@ConfigurationProperties("songrush.audio")` record holds `cdnHost`. Existing `AudioProperties(String bucket)` constructors used by tests must keep compiling, so it is either a separate record or an added component with the old constructor kept.
   - An invalid non-blank value makes the application fail to start, with a message that does not echo the value beyond the host itself.
3. **Use.** `S3AudioLinks.presign` result (audio) and `CoverController.redirect` (covers) pass through `CdnUrls.rewrite` with the configured host. Disk mode is unchanged.
4. **Logs never contain the presigned URL or its query** (as today).

## Acceptance (tests, offline)
1. **`CdnUrlsTest`:**
   - The host is replaced and the scheme is https.
   - The path and the query are identical (including `%2F` in `X-Amz-Credential` and `%2B`/`%2F`/`%3D` in `X-Amz-Security-Token`).
   - Blank or null host means no change.
   - `validHost` accepts `d1234abcd.cloudfront.net` and `media.example.com`. It rejects `https://x.cloudfront.net`, `x.cloudfront.net/`, `x.cloudfront.net:443`, `UPPER.cloudfront.net`, `localhost`, `a b.com` and the empty string.
2. **Controller tests (S3 mode, fake credentials, like the existing S3 controller tests).**
   - With `songrush.audio.cdn-host=d1234abcd.cloudfront.net`, the `/audio/{token}` and `/cover/{id}` 302 `Location` host is that host, and its query still carries `X-Amz-Signature` and `X-Amz-Expires=60`.
   - Without the property, `Location` is the S3 host as before.
3. **Startup with an invalid host fails.**
4. **Commands.** `.\gradlew.bat test` passes. The existing tests are unchanged.

## Owner steps (console, corrected after the spec review)
1. **Pricing:** Pay as you go. The flat-rate plans bundle WAF, whose rules can block the long signed query.
2. **Origin:**
   - Amazon S3, the audio bucket.
   - **Uncheck "Allow private S3 bucket access to CloudFront".** That checkbox creates an OAC and changes the bucket policy, which would make unsigned requests work.
   - The origin domain is exactly `<bucket>.s3.ap-southeast-2.amazonaws.com`, and the origin path is empty.
   - Afterwards, check that the bucket policy is unchanged and Block Public Access is all On.
3. **Cache settings:**
   - Customize: `CachingDisabled` + `AllViewerExceptHostHeader`.
   - No response headers policy.
   - Redirect HTTP to HTTPS, GET/HEAD.
4. **Security:** do not enable protections.
5. **After creation, Error pages:** a custom error response for 400, 403, 404, 500 and 503 with **error caching minimum TTL 0**.
   - The cache key is the path only, so a cached 403 from one bad request could block another player's valid request for 10 s. That is a fairness issue.
6. Send the lead the distribution domain.

## Verification before deploy (lead, `cdn_check.py` run within the URL's 60 s)
1. **Valid signature through the CDN:**
   - Send it with `Origin: https://animeopguessr.com`. Expect 200, the audio content type, and `Access-Control-Allow-Origin` = the site.
   - Fetch one cover the same way.
2. **Tampered signature:** expect 403.
3. **No query at all:** expect 403. This catches an OAC-opened bucket.
4. **Error caching:**
   - Right after the 403 above, send a fresh valid URL for the same path. Expect 200, and `X-Cache` must not be `Error from cloudfront`.
   - Wait 60 s and fetch the expired URL. Expect 403.

## Alternative noted
S3 Transfer Acceleration uses the same edge network without the host swap. It is a candidate for a later step if CloudFront shows little gain.

## Measurement after deploy
- `perf/run.py` three times with the switch on, plus `perf/devtools_shot.py`, recorded as step 1 in `docs/performance.md`.
- **Switching** means changing `SONGRUSH_AUDIO_CDNHOST` and restarting the backend container. The owner does this, and it ends any open rooms.
- Each run's results record the final media host (`*.cloudfront.net` or S3) as proof of on or off.
- The improvement is measured on and off, alternating, in the same session.

## File ownership
- **Test writer:** new tests under `backend/src/test/java/com/songrush/audio/` and `.../cover/`.
- **Implementer:** `backend/src/main/java/com/songrush/audio/**`, `.../cover/CoverController.java`, `.../AudioProperties.java` or a new properties record, and `backend/src/main/resources/application.properties` if needed.
- **Lead:** docs.

## Out of scope
- CloudFront signed URLs.
- Caching.
- Changing the bucket policy.
- Frontend changes.

## Pre-deploy check (lead, 2026-11-10, distribution `dsxxd4m1cz9m6.cloudfront.net`)
`cdn_check.py`, kept in the job tmp: a live choice game, with the `/audio` and `/cover` `Location` hosts swapped by hand. Output:
```
audio origin was S3: True | signed: True
1 audio via CDN, valid: 200 | audio/ogg | CORS=https://animeopguessr.com | X-Cache=Miss from cloudfront
1b cover via CDN, valid: 200 | image/jpeg | CORS=None | X-Cache=Miss from cloudfront
2 tampered signature: 403 | X-Cache=Error from cloudfront
3 no query: 403 | X-Cache=Error from cloudfront
4 valid right after the 403s: 200 | audio/ogg | CORS=https://animeopguessr.com | X-Cache=Miss from cloudfront
5 expired: 403 | X-Cache=Error from cloudfront
RESULT: PASS
```
Same-file speed. Each song was downloaded both ways, alternating which went first:
```
song 1 S3 direct  2.99 MB in 9.61 s = 0.31 MB/s
song 1 CloudFront 2.99 MB in 2.10 s = 1.42 MB/s
song 2 CloudFront 3.54 MB in 2.14 s = 1.65 MB/s
song 2 S3 direct  3.54 MB in 13.91 s = 0.25 MB/s
song 3 S3 direct  3.65 MB in 7.10 s = 0.51 MB/s
song 3 CloudFront 3.65 MB in 2.74 s = 1.33 MB/s
```
