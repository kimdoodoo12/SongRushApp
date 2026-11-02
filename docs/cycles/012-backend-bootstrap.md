# 012 backend-bootstrap (B-001)

## Goal
Stand up `backend/` as a Spring Boot 4.1.x application on Java 17 with the Gradle wrapper, so later backend items have a tested base. It serves `/health`, answers unknown paths with 404 JSON, and reads `songrush.data-dir`. There is no database (D-029, D-030).

## Setup (before tests)
- Generate the skeleton from start.spring.io: Gradle Groovy DSL, Java 17, group `com.songrush`, artifact `backend`, package `com.songrush`, dependencies `web` and `websocket`. There is no database starter.
  - Check that the generated `build.gradle` uses Spring Boot **4.1.2**; it is Initializr's default, according to the critic's metadata check.
  - The generated wrapper uses Gradle 9.8.2.
- Commit the wrapper (`gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar` and `gradle-wrapper.properties`) together with Initializr's `backend/.gitattributes`. It keeps `gradlew` LF and the jar binary, because `core.autocrlf` is true here.
- Run the wrapper **from `backend/`**: `gradlew.bat test`, or `backend\gradlew.bat -p backend test` from the repo root. A plain `backend\gradlew.bat` from the root has no settings file and fails.
  - The first run downloads Gradle and the dependencies. It must finish **manually, before the tests are locked**, so the Stop hook never pays for the download (the hook is capped at 900 s by settings.json).

## Interface
- `com.songrush.BackendApplication`: the `@SpringBootApplication` entry point.
- `GET /health` returns 200 with `Content-Type: application/json` and the exact body `{"ok":true}`.
- Every error response is JSON, whatever the `Accept` header.
  - One custom `ErrorController` (Boot 4 package `org.springframework.boot.webmvc.error.ErrorController`) is mapped to `/error`. It always returns `ResponseEntity` with `Content-Type: application/json` and a body `{"error":"not found"}` for 404, `{"error":"method not allowed"}` for 405, and `{"error":"error"}` otherwise.
  - `server.error.whitelabel.enabled=false` is set.
  - **Boot 4 facts:**
    - `TestRestTemplate` moved to `org.springframework.boot.resttestclient` and needs `@AutoConfigureTestRestTemplate`.
    - JSON is Jackson 3 (`tools.jackson.*`); do not import `com.fasterxml.jackson.databind`.
- `songrush.data-dir`, bound through `@ConfigurationProperties(prefix = "songrush")`, is a record or class `SongrushProperties(Path dataDir)` with the default `../data`. The default is relative to the working directory, and `backend/` is the working directory when running through Gradle.

## Acceptance criteria (each is a test)
1. `GET /health` returns 200, a `Content-Type` that starts with `application/json`, and the body exactly `{"ok":true}`.
2. `GET /nope` returns 404 with `application/json` and the body `{"error":"not found"}`, both with no `Accept` header and with `Accept: text/html`. This is the browser case, where Boot would otherwise render HTML.
3. `POST /health` returns **405** with `application/json` and the body `{"error":"method not allowed"}`, also with `Accept: text/html`.
4. With no property set, `SongrushProperties.dataDir()` equals `Path.of("../data")`. With `songrush.data-dir=/tmp/x` set through `@SpringBootTest(properties=...)`, it equals `Path.of("/tmp/x")`.
5. The HTTP tests use `@SpringBootTest(webEnvironment = RANDOM_PORT)` with `@LocalServerPort` (`org.springframework.boot.test.web.server`) and **`java.net.http.HttpClient`**. It does not throw on 4xx and needs no Boot 4 test module.
6. From `backend/`, `gradlew.bat test` and `gradlew.bat build` exit 0, and `gradlew.bat test --offline` also exits 0.

## Test list
- `backend/src/test/java/com/songrush/HealthTest.java`: criteria 1-3 and 5.
- `backend/src/test/java/com/songrush/SongrushPropertiesTest.java`: criterion 4.

## File ownership
- **Setup and implementer:** `backend/` except the two test files: `build.gradle`, `settings.gradle`, the wrapper, `src/main/**` and `src/main/resources/application.properties`.
- **Test writer:** the two test files.
- **Lead:** the CLAUDE.md Commands table row, and confirming that the Stop hook runs `backend`.

## Out of scope
WebSocket handlers (B-013), the matcher (B-006) and every game feature.

## Review changes (spec round 1)
The critic generated the Initializr project, opened the Spring Boot 4.1.2 jars, and read the hook and settings.
- **H1.** In Boot 4, `TestRestTemplate` moved and needs an annotation, so tests written from Boot 3 memory would not compile. The client is now pinned to `java.net.http` with `@LocalServerPort`.
- **H2.** With a client that does not send `Accept: text/html`, a Whitelabel test can never fail. The tests now also send `Accept: text/html`, and there is a single JSON `ErrorController`.
- **H3.** `backend\gradlew.bat` from the root fails, because there is no settings file there. Commands now run from `backend/`, and CLAUDE.md is corrected.
- **H4.** settings.json caps the Stop hook at 900 s, which cuts off the hook's 1500 s backend timeout.
  - The first Gradle download now happens manually in setup.
  - The hook's backend timeout drops to 850 s, so it never exceeds the settings cap.
- **M1.** The Boot 4 package moves (`ErrorController`, Jackson 3) are stated.
- **Nits:**
  - Initializr's default is 4.1.2.
  - `backend/.gitattributes` is committed.
  - `POST /health` is pinned to 405.

## Test commit
- `b55230c` test(012).
- Red phase verified: the full suite fails to compile only because `SongrushProperties` is missing. HealthTest alone fails all 5 tests for the right reasons: 404 instead of 200, Boot's default JSON body, Whitelabel HTML, and 404 instead of 405.

## Result
- Implementation: `55ff752`.
- The verifier ran `.\gradlew.bat test --offline --rerun-tasks` and `build --offline`, both exit 0: 8 tests, 0 failures. The locked tests are unchanged, and `gradlew` is LF and tracked.
- The `StringToPathConverter` is needed. Without it, Spring's `PathEditor` resolves `../data` through Tomcat's resource root and the web context fails to start; the critic confirmed this by removing it.

## Code review (critic)
No Blocker or High findings. The critic probed a scratch copy:
- a thrown exception gives 500 JSON with no stack trace or message, also with `Accept: text/html`;
- HEAD and OPTIONS behave as expected;
- 400, 415 and 406 are JSON;
- MVC path parameters are unaffected by the converter;
- the wrapper jar matches the official 9.8.2 jar.

Medium and Nit findings are recorded as Follow-ups in BACKLOG:
- the property test runs without Tomcat, so it cannot catch the binding failure;
- the converter is global;
- `../data` depends on the working directory;
- non-standard status codes become 500;
- a direct `/error` call returns 500;
- the hook runs areas in sequence, so two areas can exceed 900 s in total.
