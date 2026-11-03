# 054 proxy-and-static (B-009)

## Goal
The backend works behind a TLS-terminating proxy, so the WebSocket same-origin check sees `https`, and it serves the built frontend from a configured folder. One origin then carries the page, `/ws` and `/audio` (design 052, review B1, M1, M3).

## Behaviour
1. **Forwarded headers.**
   - `application.properties` gets `server.forward-headers-strategy=native`.
   - Tomcat then trusts `X-Forwarded-Proto`/`X-Forwarded-For` from internal-network peers (loopback included) and corrects the scheme, port and remote address.
   - No custom header parsing.
2. **Static frontend.**
   - The folder is set only through Boot's `spring.web.resources.static-locations` (for example `file:/opt/songrush/web/`). Boot's resource handling serves `/` as the welcome page and `/assets/*` with content types.
   - One controller mapping forwards `GET /r/{code}` and `/r/{code}/` to `/index.html`. It applies only when a static location is configured; otherwise those paths stay 404 JSON.
   - `/ws`, `/audio/{token}`, `/health` and the JSON 404 for unknown paths keep their behaviour.
3. **Startup guard.** When a configured `file:` static location resolves to `songrush.data-dir` or a folder inside it, startup fails. The exception message contains both paths and the word `static`. A folder that merely shares a prefix, such as `data-web` next to `data`, is fine.

## Acceptance criteria (JUnit 5, from `backend/`)
1. **`ProxyHandshakeTest`** (`@SpringBootTest(webEnvironment = RANDOM_PORT)` with the test catalog, as the existing socket tests):
   - A WebSocket handshake to `ws://127.0.0.1:<port>/ws` with `Host: play.test`, `Origin: https://play.test` and `X-Forwarded-Proto: https` succeeds. Sending `create{nickname:"A"}` then gets `joined`.
   - The same headers with `Origin: https://evil.test` are refused (HTTP 403 on the upgrade).
   - Without `X-Forwarded-Proto`, `Origin: https://play.test` with `Host: play.test` is refused (403). This pins that the proxy header is what makes it pass.
   - An HTTP `GET /health` with `X-Forwarded-For: 203.0.113.7` from loopback reaches a test-only endpoint or filter that records `request.getRemoteAddr()`. The recorded address is `203.0.113.7`. A test-scoped `@TestConfiguration` controller is allowed.
2. **`StaticFrontendTest`** (`RANDOM_PORT`; a `@TempDir` web folder with `index.html` containing `SONGRUSH-INDEX` and `assets/app.js`; `spring.web.resources.static-locations=file:<dir>/` through `@DynamicPropertySource`):
   - `GET /` and `GET /r/ABCDE` and `GET /r/ABCDE/` return 200 with a body containing `SONGRUSH-INDEX`.
   - `GET /assets/app.js` returns 200 with a content type containing `javascript`.
   - `GET /../catalog.json`, `/%2e%2e/catalog.json` and `/assets/..%2f..%2fcatalog.json` never return 200 with the catalog body.
   - `GET /health` still returns `{"ok":true}`.
   - `GET /nope` returns 404.
3. **`NoStaticTest`** (no static location set): `GET /` and `GET /r/ABCDE` return 404 with a JSON body, as today.
4. **`StaticGuardTest`**: starting the application with `songrush.data-dir=<tmp>/data` and a static location `file:<tmp>/data/web/`, or `file:<tmp>/data/`, throws, and the cause chain's message contains `static`. With `file:<tmp>/data-web/` it starts. Use `SpringApplicationBuilder` or `ApplicationContextRunner` with the real configuration.
5. All earlier backend tests pass unchanged, and `.\gradlew.bat test` exits 0.

## Test list
- `backend/src/test/java/com/songrush/web/ProxyHandshakeTest.java`
- `backend/src/test/java/com/songrush/web/StaticFrontendTest.java`
- `backend/src/test/java/com/songrush/web/NoStaticTest.java`
- `backend/src/test/java/com/songrush/web/StaticGuardTest.java`

## File ownership
- **Test writer:** the test list. Test helpers are allowed under `backend/src/test/java/com/songrush/web/`.
- **Implementer:** `backend/src/main/resources/application.properties` and new files under `backend/src/main/java/com/songrush/web/`. Existing main files are changed only if strictly needed, with the reason reported.

## Out of scope
- Deploy files (I-004).
- Per-IP limits (follow-up).
- Changes to the frontend build.
