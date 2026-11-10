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
   - `String rewrite(String presignedUrl, String cdnHost)`: returns `"https://" + cdnHost + <raw path> + "?" + <raw query>` of the presigned URL. The raw (still-encoded) path and query are kept exactly; nothing is decoded or re-encoded.
   - If `cdnHost` is null or blank, it returns the input unchanged.
   - `static boolean validHost(String)`: true only for a lowercase DNS hostname, labels `[a-z0-9-]` separated by dots, with at least one dot and no scheme, port, path or whitespace.
2. **Property.** A new `@ConfigurationProperties("songrush.audio")` record holds `cdnHost`. Existing `AudioProperties(String bucket)` constructors used by tests must keep compiling, so it is either a separate record or an added component with the old constructor kept.
   - An invalid non-blank value makes the application fail to start, with a message that does not echo the value beyond the host itself.
3. **Use.** `S3AudioLinks.presign` result (audio) and `CoverController.redirect` (covers) pass through `CdnUrls.rewrite` with the configured host. Disk mode is unchanged.
4. **Logs never contain the presigned URL or its query** (as today).

## Acceptance (tests, offline)
1. **`CdnUrlsTest`:**
   - The host is replaced and the scheme is https.
   - The path and the query are identical (including `%2F`, `+` and `=` in `X-Amz-Credential` and `X-Amz-Signature`).
   - Blank or null host means no change.
   - `validHost` accepts `d1234abcd.cloudfront.net` and `media.example.com`. It rejects `https://x.cloudfront.net`, `x.cloudfront.net/`, `x.cloudfront.net:443`, `UPPER.cloudfront.net`, `localhost`, `a b.com` and the empty string.
2. **Controller tests (S3 mode, fake credentials, like the existing S3 controller tests).**
   - With `songrush.audio.cdn-host=d1234abcd.cloudfront.net`, the `/audio/{token}` and `/cover/{id}` 302 `Location` host is that host, and its query still carries `X-Amz-Signature` and `X-Amz-Expires=60`.
   - Without the property, `Location` is the S3 host as before.
3. **Startup with an invalid host fails.**
4. **Commands.** `.\gradlew.bat test` passes. The existing tests are unchanged.

## Owner steps (console)
These are given to the owner in Korean in the conversation:
- Create a distribution with the S3 origin and **origin access Public (no OAC; the bucket policy is not changed)**.
- Settings: viewer protocol redirect-to-HTTPS, GET/HEAD, cache policy `CachingDisabled`, origin request policy `AllViewerExceptHostHeader`, no WAF.
- Send the lead the distribution domain.

## Verification before deploy (lead)
- Once the distribution is deployed, take a real `Location` from the live site's `/audio/{token}` during a game, swap its host with `CdnUrls`' rule, and fetch it. Expect 200, the audio content type, and the CORS header for the site origin.
- A tampered signature through the CDN must give 403. This proves the bucket is not opened.

## Measurement after deploy
- `perf/run.py` three times with the switch on, plus `perf/devtools_shot.py`, recorded as step 1 in `docs/performance.md`.
- The improvement must be measured with the switch on and off in the same session, alternating.

## File ownership
- **Test writer:** new tests under `backend/src/test/java/com/songrush/audio/` and `.../cover/`.
- **Implementer:** `backend/src/main/java/com/songrush/audio/**`, `.../cover/CoverController.java`, `.../AudioProperties.java` or a new properties record, and `backend/src/main/resources/application.properties` if needed.
- **Lead:** docs.

## Out of scope
- CloudFront signed URLs.
- Caching.
- Changing the bucket policy.
- Frontend changes.
