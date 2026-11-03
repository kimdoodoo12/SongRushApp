# 033 audio-endpoint (B-003)

## Goal
Serve cached audio only through short-lived random tokens, with no response detail that names the song, and stop catalog records from printing answers in logs or messages (design 029; cycle 032 review M2; CLAUDE.md multiplayer rules).

## Interface: package `com.songrush.audio`
- **`AudioTokens`** (a Spring `@Component`; constructor `AudioTokens(java.time.Clock clock)`):
  - `String issue(int themeId, Duration ttl)`: 16 bytes from `SecureRandom`, encoded base64url without padding (22 characters). `ttl` must be positive, otherwise `IllegalArgumentException`. Every call first removes expired entries.
  - `Optional<Integer> resolve(String token)`: the theme id while the token is valid (`now < issuedAt + ttl`); an expired token is removed and gives empty; an unknown token gives empty.
  - `int size()`: the number of stored entries (for tests and monitoring).
  - Thread-safe (`ConcurrentHashMap`).
- **Clock bean:** an `@Configuration` in the package provides a plain `@Bean Clock` returning `Clock.systemUTC()` (spec review N1: `@ConditionalOnMissingBean` is meant for auto-configuration and nothing overrides the clock).
- **`AudioController`**: `GET /audio/{token}`.
  - Resolves the token, looks up the theme among the catalog's **candidates** (a new `Catalog.candidate(int themeId) -> Optional<Candidate>`, so only verified basenames from the draw candidates are served; spec review N2), and streams `<songrush.data-dir>/audio-cache/<audioBasename>` to the response: the controller sets `Content-Type: audio/ogg`, `Content-Length` (the file size) and `Cache-Control: no-store` and copies the file with `Files.copy(path, outputStream)` (or a `StreamingResponseBody`), so memory stays constant per request (spec review M1). It does not use a Spring `Resource`, so Spring's resource handling (which adds `Last-Modified` and Range support) is not involved. No `Last-Modified`, `ETag`, `Content-Disposition`, `Content-Range` or `Accept-Ranges`; a `Range` request header is ignored (the whole file, `200`).
  - An unknown or expired token, a theme that is not a candidate, or a file that is missing or unreadable returns `404` with the body `{"error":"not found"}` (by throwing `ResponseStatusException(NOT_FOUND)` so `JsonErrorController` writes it, before any byte of the body is sent); nothing in the response names the theme or the file. Server logs for these cases carry the token prefix (first 6 characters) only.
- **Answer-safe `toString`:** `Anime`, `Theme` and `Candidate` (package `com.songrush.catalog`) override `toString` to print only their numeric ids, e.g. `Theme[id=10, animeId=1]`, `Anime[id=1]`, `Candidate[themeId=10]`.

## Acceptance criteria (JUnit 5, from `backend/`)
1. **Tokens** (`AudioTokens` with a mutable test `Clock`): a token is 22 characters from `[A-Za-z0-9_-]`; two `issue` calls for the same theme give different tokens; `resolve` returns the theme id before expiry; after the clock passes the TTL, `resolve` is empty and `size()` drops by one; an `issue` call removes other expired entries; a zero or negative TTL throws `IllegalArgumentException`.
2. **Serving** (`@SpringBootTest(webEnvironment = RANDOM_PORT)` with `songrush.data-dir` set by `@DynamicPropertySource` to a static `@TempDir`; the fixture files are written before the context loads, inside the `@DynamicPropertySource` method or a static `@BeforeAll`: a copy of the cycle 032 fixture catalog, an `audio-cache/index.json`, and **two** audio files with distinctive bytes and the right sizes: theme 10's for serving and theme 11's for the deletion case, so test order does not matter (spec review H1); plain `java.net.http.HttpClient` as in `HealthTest`): a token issued through the `AudioTokens` bean gives `200`, the exact file bytes, `Content-Type: audio/ogg`, a `Content-Length` equal to the file size and `Cache-Control: no-store`.
3. **No revealing headers:** the set of response header names (lower-cased) is a subset of `{content-type, content-length, cache-control, date, keep-alive, connection}` (an allow-list, so a header added later by Spring or Tomcat is caught); no header value contains the basename or the anime slug (case-insensitive). Numeric ids are not searched for, because the `Date` header contains digits (spec review B1). A request with `Range: bytes=0-9` also gets `200` and the whole file.
4. **404s:** an unknown token, an expired token (issued with a 1 ms TTL, then waited past it), a token for theme 11 after its file was deleted, and a token for a theme that is not a candidate (theme 30) each give `404` with exactly `{"error":"not found"}` as the body, and no header value contains a basename or slug.
5. **`toString`:** for the fixture's records, `toString()` of an `Anime`, a `Theme` and a `Candidate` contains the id and none of the record's titles, reveal, song, artists or basename.
6. `.\gradlew.bat test` and `.\gradlew.bat build` exit 0; every cycle 012 and 032 test still passes.

## Test list
- `backend/src/test/java/com/songrush/audio/AudioTokensTest.java`: criterion 1.
- `backend/src/test/java/com/songrush/audio/AudioEndpointTest.java`: criteria 2-4.
- `backend/src/test/java/com/songrush/catalog/RecordToStringTest.java`: criterion 5.

## File ownership
- Test writer: the three test classes (and any new fixture under `backend/src/test/resources/audio/`).
- Implementer: `backend/src/main/java/com/songrush/audio/*`, the `toString` overrides in `backend/src/main/java/com/songrush/catalog/{Anime,Theme,Candidate}.java`, and `Catalog.candidate(int)` in `Catalog.java`.

## Review changes (spec round 1)
- **B1.** Searching header values for one- or two-digit ids would fail at random on the `Date` header; criterion 3 now uses a header-name allow-list and searches only for the basename and slug.
- **H1.** Two audio files, so the deletion case cannot break the serving tests whatever the order; fixture files are written before the context loads.
- **M1.** The file is streamed instead of read into a `byte[]`, so memory per request stays constant.
- **N1.** A plain `Clock` bean. **N2.** Only draw candidates are served, through `Catalog.candidate(int)`.
- The critic ran the built jar and confirmed Tomcat adds `Date` (and `Keep-Alive`/`Connection` on keep-alive), no `Vary` or CORS headers without CORS configuration, and that `JsonErrorController` turns a 404 into exactly `{"error":"not found"}`. `Content-Length` equal to the public size is accepted under D-057.

## Out of scope
Who issues tokens and with which TTL (rounds, S-003); WebSocket; rate limiting.
