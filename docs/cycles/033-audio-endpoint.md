# 033 audio-endpoint (B-003)

## Goal
Serve cached audio only through short-lived random tokens, with no response detail that names the song, and stop catalog records from printing answers in logs or messages (design 029; cycle 032 review M2; CLAUDE.md multiplayer rules).

## Interface: package `com.songrush.audio`
- **`AudioTokens`** (a Spring `@Component`; constructor `AudioTokens(java.time.Clock clock)`):
  - `String issue(int themeId, Duration ttl)`: 16 bytes from `SecureRandom`, encoded base64url without padding (22 characters). `ttl` must be positive, otherwise `IllegalArgumentException`. Every call first removes expired entries.
  - `Optional<Integer> resolve(String token)`: the theme id while the token is valid (`now < issuedAt + ttl`); an expired token is removed and gives empty; an unknown token gives empty.
  - `int size()`: the number of stored entries (for tests and monitoring).
  - Thread-safe (`ConcurrentHashMap`).
- **Clock bean:** an `@Configuration` in the package provides `Clock.systemUTC()` unless another `Clock` bean exists (`@ConditionalOnMissingBean`).
- **`AudioController`**: `GET /audio/{token}`.
  - Resolves the token, looks up the theme in `Catalog`, reads `<songrush.data-dir>/audio-cache/<audioBasename>` as bytes, and returns `200` with exactly these content headers: `Content-Type: audio/ogg`, `Content-Length`, `Cache-Control: no-store`. No `Last-Modified`, `ETag`, `Content-Disposition`, `Content-Range` or `Accept-Ranges`; a `Range` request header is ignored (the whole file, `200`). The body is a `byte[]`, not a `Resource`, so Spring's resource handling (which adds `Last-Modified` and Range support) is not involved.
  - An unknown or expired token, a theme no longer in the catalog, or a file that is missing or unreadable returns `404` with the body `{"error":"not found"}`; nothing in the response names the theme or the file. Server logs for these cases carry the token prefix (first 6 characters) only.
- **Answer-safe `toString`:** `Anime`, `Theme` and `Candidate` (package `com.songrush.catalog`) override `toString` to print only their numeric ids, e.g. `Theme[id=10, animeId=1]`, `Anime[id=1]`, `Candidate[themeId=10]`.

## Acceptance criteria (JUnit 5, from `backend/`)
1. **Tokens** (`AudioTokens` with a mutable test `Clock`): a token is 22 characters from `[A-Za-z0-9_-]`; two `issue` calls for the same theme give different tokens; `resolve` returns the theme id before expiry; after the clock passes the TTL, `resolve` is empty and `size()` drops by one; an `issue` call removes other expired entries; a zero or negative TTL throws `IllegalArgumentException`.
2. **Serving** (`@SpringBootTest(webEnvironment = RANDOM_PORT)` with `songrush.data-dir` set by `@DynamicPropertySource` to a `@TempDir` holding a copy of the cycle 032 fixture catalog, an `audio-cache/index.json` and one audio file of distinctive bytes; plain `java.net.http.HttpClient` as in `HealthTest`): a token issued through the `AudioTokens` bean gives `200`, the exact file bytes, `Content-Type: audio/ogg`, a `Content-Length` equal to the file size and `Cache-Control: no-store`.
3. **No revealing headers:** in that response, `Last-Modified`, `ETag`, `Content-Disposition`, `Content-Range` and `Accept-Ranges` are absent, and no header value contains the basename, the theme id or the anime id; a request with `Range: bytes=0-9` also gets `200` and the whole file.
4. **404s:** an unknown token, an expired token (issued with a 1 ms TTL, then waited past it), and a token for a theme whose file was deleted after startup each give `404` with exactly `{"error":"not found"}` as the body, and no header contains the basename.
5. **`toString`:** for the fixture's records, `toString()` of an `Anime`, a `Theme` and a `Candidate` contains the id and none of the record's titles, reveal, song, artists or basename.
6. `.\gradlew.bat test` and `.\gradlew.bat build` exit 0; every cycle 012 and 032 test still passes.

## Test list
- `backend/src/test/java/com/songrush/audio/AudioTokensTest.java`: criterion 1.
- `backend/src/test/java/com/songrush/audio/AudioEndpointTest.java`: criteria 2-4.
- `backend/src/test/java/com/songrush/catalog/RecordToStringTest.java`: criterion 5.

## File ownership
- Test writer: the three test classes (and any new fixture under `backend/src/test/resources/audio/`).
- Implementer: `backend/src/main/java/com/songrush/audio/*`, and the `toString` overrides in `backend/src/main/java/com/songrush/catalog/{Anime,Theme,Candidate}.java`.

## Out of scope
Who issues tokens and with which TTL (rounds, S-003); WebSocket; rate limiting.
