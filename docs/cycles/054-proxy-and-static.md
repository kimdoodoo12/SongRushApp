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
   - One controller mapping forwards `GET /r/{code}` and `/r/{code}/` to `/index.html`, unconditionally. With no static location the forward ends in the resource handler's 404 and so in the JSON 404 (review N1).
   - Boot decides the welcome page once at startup: if `index.html` is missing when the server starts, `/` stays 404 until a restart. The runbook uploads the web files before starting the service (review M4).
   - `/ws`, `/audio/{token}`, `/health` and the JSON 404 for unknown paths keep their behaviour.
3. **Startup guard (review H1).** Each configured static location is resolved with Spring's `ResourceLoader.getResource(loc).getFile()`, then `toAbsolutePath().normalize()`. The same is done to `songrush.data-dir`. If either path equals or contains the other, startup fails, whichever is the outer one. The exception message contains both paths and the word `static`. A folder that merely shares a prefix, such as `data-web` next to `data`, is fine. Non-`file:` locations (`classpath:`) are ignored.

## Acceptance criteria (JUnit 5, from `backend/`)
1. **`ProxyHandshakeTest`** (`@SpringBootTest(webEnvironment = RANDOM_PORT)` with the test catalog, as the existing socket tests):
   - **Client (review M1).** The JDK `HttpClient` forbids `Host`. The success case uses Spring's `StandardWebSocketClient` with `WebSocketHttpHeaders` whose key is exactly `"Host"`. The refusal cases send a raw HTTP/1.1 upgrade request over `java.net.Socket` and read the status line.
   - A WebSocket handshake to `ws://127.0.0.1:<port>/ws` with `Host: play.test`, `Origin: https://play.test` and `X-Forwarded-Proto: https` succeeds. Sending `create{nickname:"A"}` then gets `joined`.
   - The same headers with `Origin: https://evil.test` are refused (HTTP 403 on the upgrade).
   - Without `X-Forwarded-Proto`, `Origin: https://play.test` with `Host: play.test` is refused (403). This pins that the proxy header is what makes it pass.
   - An HTTP `GET /health` with `X-Forwarded-For: 203.0.113.7` from loopback reaches a test-only endpoint or filter that records `request.getRemoteAddr()`. The recorded address is `203.0.113.7`. A test-scoped `@TestConfiguration` controller is allowed.
2. **`StaticFrontendTest`** (`RANDOM_PORT`, run with the test catalog as the other web tests are). The web folder is `<tmp>/web`, holding `index.html` (containing `SONGRUSH-INDEX`) and `assets/app.js`. A decoy `<tmp>/catalog.json` contains `DECOY-SECRET`. The location is `spring.web.resources.static-locations=<tmp>/web as dir.toUri().toString()`, set through `@DynamicPropertySource`.
   - `GET /` and `GET /r/ABCDE` and `GET /r/ABCDE/` return 200 with a body containing `SONGRUSH-INDEX`.
   - `GET /assets/app.js` returns 200 with a content type containing `javascript`.
   - `GET /../catalog.json`, `/%2e%2e/catalog.json` and `/assets/..%2f..%2fcatalog.json`: the status is not 200 and the body does not contain `DECOY-SECRET` (review M2). Send them raw if the client normalizes paths.
   - `GET /health` still returns `{"ok":true}`.
   - `GET /nope` returns 404.
3. **`NoStaticTest`** (no static location set): `GET /` and `GET /r/ABCDE` return 404 with a JSON body, as today.
4. **`StaticGuardTest`** (review M3):
   - Setup:
     - `new SpringApplicationBuilder(BackendApplication.class)` as a SERVLET application, run with `--server.port=0`.
     - `songrush.data-dir=<tmp>/data`. The test writes a minimal valid catalog (and an empty audio index) there, copying the test catalog resources; a web-package fixture is allowed.
     - All folders are created first, and locations are passed as `dir.toUri().toString()`.
   - These static locations make startup throw with `static` in the cause chain's message:
     - `<tmp>/data/web/` (inside the data dir);
     - `<tmp>/data/` (equal);
     - `<tmp>/` (contains it).
   - `<tmp>/data-web/` starts. Its context is closed.
5. All earlier backend tests pass unchanged, and `.\gradlew.bat test` exits 0.

## Review changes (spec round 1)
- **High.**
  - The guard now applies in both directions.
  - A static location that contains the data dir would have served `catalog.json` and the audio file names.
- **Medium.**
  - The test clients are specified, since the JDK `HttpClient` forbids `Host`.
  - The traversal test uses a decoy file.
  - The guard test's startup method is specified.
  - The welcome page is fixed at startup.
- **Nit.** The `/r/{code}` mapping is unconditional.

## Test-writing note
- spring-websocket 7.0.10's `AbstractWebSocketClient.execute` drops user headers named in its special set, which includes `host`. The success case therefore uses a test-only subclass, `HostHeaderWebSocketClient`, that calls the protected `executeInternal`. The lead accepted this; it changes no behaviour under test.

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
