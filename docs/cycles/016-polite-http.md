# 016 polite-http (C-002)

## Goal
One HTTP client and file cache that every data tool uses, so the CLAUDE.md external source rules hold everywhere (design 014 decision 1):
- the User-Agent;
- one request at a time;
- the gap between requests;
- timeouts;
- `Retry-After`;
- no retry on other 4xx;
- the cache.

## Interface: `data/songrush_data/http.py`
- `class Response`: `status: int`, `headers: dict[str, str]` (lower-case keys), `body: bytes`.
- **`Transport`** is a protocol: `send(method, url, headers, body, timeout) -> Response`. Tests inject a fake. The real one is **`UrllibTransport`**, built on `urllib.request` from the standard library:
  - It sets the User-Agent through `Request(headers=...)`, which redirects copy, never `add_unredirected_header`.
  - It catches `urllib.error.HTTPError` (any status) **first** and returns it as a `Response` with its status, headers and body. It never raises for a status.
  - It converts `URLError`, `OSError` (timeouts included) and `http.client.HTTPException` (for example `IncompleteRead`) into `TransportError`.
  - It does not send `Accept-Encoding`, so bodies arrive uncompressed. A caller that adds it gets compressed bytes.
  - Bodies are read whole into memory. The largest expected is about 62 MB.
- **`Clock`** is a protocol with three methods: `now() -> float` (monotonic seconds), `wall() -> float` (epoch seconds, used to read HTTP-date headers) and `sleep(seconds)`. The real one uses `time.monotonic`, `time.time` and `time.sleep`; tests inject a fake whose `now` and `wall` advance when it sleeps.
- **`PoliteClient(transport, clock, *, gap_s, timeout_s=60.0, max_attempts=3, backoff_s=(10.0, 20.0), max_retry_after_s=300.0, log=None)`**. `max_attempts` must be at least 1.
  - `request(method, url, *, headers=None, body=None) -> Response`.
  - **Every request** adds `User-Agent: songrush_data.common.USER_AGENT`, which overrides any caller value.
  - **One at a time:** a lock keeps calls on one client sequential.
  - **Waiting:** the next attempt starts at `previous attempt end + max(gap_s, wait)`, where `wait` is the `Retry-After` or backoff time that applies (0 otherwise).
    - Waits never add up.
    - Every attempt counts as "previous", whether it succeeded, failed or raised `TransportError`.
    - A stored not-before time from an earlier 429 also delays the next request.
    - The first request does not wait.
  - **Timeout:** each attempt is passed `timeout_s`, which must be at least 60; anything less raises `ValueError`.
  - **Retries** (at most `max_attempts`):
    - **2xx** returns.
    - **429** reads `Retry-After` in seconds or as an HTTP-date. An HTTP-date is converted with `clock.wall()` and clamped at 0 if it is in the past. A missing or invalid value means 30 s.
      - **Above `max_retry_after_s`:** the client does **not** retry; it raises `HttpError` at once.
      - **Otherwise** it retries after that wait.
      - **The next request** waits too, after a final failure or a refusal: the client stores the full not-before time, even when the value exceeded the cap. That honours the source's request instead of shortening it.
    - **5xx** and `TransportError` back off `backoff_s[i]` (the last value repeats), then retry.
    - **Any other 4xx** raises `HttpError` at once, with no retry.
    - After the last attempt it raises `HttpError`, or re-raises the `TransportError`. `HttpError` has the fields `status`, `url` and `attempts`.
    - **Naming:** `HttpError` is this module's class. It never wraps urllib's `HTTPError`, so the two cannot be confused in `except` clauses.
  - **`max_attempts=1`** (the AniDB rule): exactly one `send`, then return or raise. It never sleeps for that request.
  - `log(message)` is called once per retry with the reason.
- **`FileCache(root: Path)`**
  - `get(key) -> bytes | None`, `put(key, data)`, `delete(key)`, `clear(prefix)`.
  - **Keys** match `^[a-z0-9][a-z0-9._-]{0,119}$`, do not end with `.`, and are not a Windows reserved name (`con`, `prn`, `aux`, `nul`, `com1`-`com9`, `lpt1`-`lpt9`, also with an extension). Otherwise `ValueError`. Keys are lowercase because NTFS is case-insensitive.
  - `put` writes atomically: a temp file named `~tmp-<uuid>` (which never matches the key pattern) in the same directory, then `os.replace`.
    - On `PermissionError` (Windows Defender) it retries `os.replace` up to 3 times, sleeping 0.1, 0.3 and 1.0 s through an injectable sleep.
    - On final failure it deletes the temp file and re-raises.
  - `clear("")` raises `ValueError`.
  - `clear(prefix)` removes only key files that start with the prefix, never temp files, and returns the count.
- **`cached_json(cache, key, fetch) -> object`**
  - It returns the cached JSON when present and valid.
  - If the entry is missing, it calls `fetch()`, stores the bytes and returns the parsed JSON.
  - If the cached bytes do not parse, it deletes the entry and fetches once. If that also fails to parse, it raises `ValueError` naming the key.

## Test network guard change (Follow-up from cycle 013)
`data/tests/conftest.py`'s guard lets loopback through, so tests can use `socket.socketpair()` and asyncio on Windows:
- `getaddrinfo` for `localhost`, `127.0.0.1` and `::1` is allowed;
- `connect` and `connect_ex` to `127.0.0.0/8` and `::1` are allowed;
- everything else still raises `RuntimeError("network disabled in tests")`.

The existing guard tests in `data/tests/test_bootstrap.py` that target `127.0.0.1:9` would now reach loopback. They are changed in this cycle to target `10.0.0.1:9`, which is non-loopback and must still raise `RuntimeError`. New tests prove that `socket.socketpair()` works.

## Acceptance criteria (each is a test, offline, with a fake transport and clock)
1. Every request sends the User-Agent, and a caller-supplied `User-Agent` is replaced. `max_attempts=0` raises `ValueError`.
2. **Gap:** asserted on the fake clock's start times, not on sleep calls.
   - With `gap_s=3` and responses that take 5 s, the second request starts 3 s after the first one ended.
   - The first request does not wait.
   - With `gap_s=3` and `429 Retry-After: 1`, the retry starts 3 s after the 429 (max, not sum).
3. `timeout_s` reaches the transport as at least 60, and `timeout_s=30` raises `ValueError`.
4. **429:**
   - `429 Retry-After: 7`, then 200, sleeps 7 s and succeeds.
   - Three 429s with `Retry-After: 120` raise `HttpError`, and the next request starts at least 120 s after the last 429.
   - A missing `Retry-After` waits 30 s.
   - `Retry-After: 99999` raises `HttpError` after 1 attempt without retrying, and the next request starts at least 99999 s later.
   - An HTTP-date `Retry-After` 40 s after `clock.wall()` waits 40 s. A past date waits 0, which is still subject to the gap.
5. **5xx and errors:**
   - 503, 503, 200 sleeps 10 s, then 20 s, and succeeds.
   - Three `TransportError`s re-raise after 3 attempts.
6. 403 and 404 raise `HttpError` after exactly 1 attempt.
6b. **`max_attempts=1`:** 429, 503 and `TransportError` each make exactly one `send` and no sleep before raising.
6c. **`UrllibTransport` against a loopback `http.server`** (allowed by the new guard):
   - a 404 comes back as `Response(404)` without raising;
   - a 429 with `Retry-After: 5` comes back with the header;
   - a 302 to another loopback path keeps the User-Agent;
   - a body cut short by the server raises `TransportError`;
   - a refused port (closed loopback port) raises `TransportError`.
7. **FileCache:**
   - Values round-trip.
   - Invalid keys raise: uppercase, `.`, `..`, `a.`, `nul`, `com1.json`, and 121 characters.
   - `clear("")` raises.
   - `clear("a-")` keeps `b-1`.
   - `put` leaves no temp files behind.
   - A `PermissionError` on the first `os.replace` is retried after a 0.1 s sleep and then succeeds.
   - A `PermissionError` on every attempt leaves no temp file and re-raises.
8. **`cached_json`:**
   - A valid cache entry makes 0 fetches.
   - A missing entry makes 1 fetch and stores the result.
   - A corrupt entry is deleted and fetched again.
   - A double corruption raises `ValueError` with the key.
9. **Network guard:**
   - `socket.socketpair()` works.
   - `example.com`, `10.0.0.1:80` and `10.0.0.1:9` (the changed bootstrap tests) are still blocked with `RuntimeError`.
10. `.venv\Scripts\python.exe -m pytest` exits 0 from `data/`.

## Test list
- `data/tests/test_http.py`: criteria 1-6b.
- `data/tests/test_urllib_transport.py`: criterion 6c.
- `data/tests/test_cache.py`: criteria 7-8.
- `data/tests/test_guard_loopback.py`: criterion 9.
- `data/tests/conftest.py`: the guard change.

## File ownership
- Test writer:
  - `data/tests/test_http.py`, `test_urllib_transport.py`, `test_cache.py` and `test_guard_loopback.py`;
  - `data/tests/conftest.py` and `data/tests/test_bootstrap.py` (only the two guard targets change to `10.0.0.1:9`), which are unlocked for this cycle.
- Implementer: `data/songrush_data/http.py`.

## Out of scope
The source-specific fetchers (C-003, C-004, C-005) and any live request.

## Review changes (spec round 1)
The critic ran three probes on Python 3.12.10.
- **B1.** Allowing loopback makes the existing `127.0.0.1:9` guard tests raise `ConnectionRefusedError` instead of `RuntimeError`. They move to `10.0.0.1:9`, and `test_bootstrap.py` is owned by the test writer in this cycle.
- **H1.** urllib's `HTTPError` is a subclass of `URLError` and `OSError`, so a naive `except OSError` would turn a 404 into a retried `TransportError` and lose `Retry-After`. Also, `IncompleteRead` is not an `OSError`. The conversion rules are now explicit, and criterion 6c tests the real transport against a loopback server, redirects included.
- **H2.** An HTTP-date `Retry-After` needs the wall clock. `Clock.wall()` is added.
- **H3.** Capping `Retry-After` at 300 s and then retrying ignores the source. Over the cap, the client now refuses, and it stores the full not-before time.
- **M1.** Gap and waits combine as `max`, counted from the end of every attempt. Tests assert start times.
- **M2.** The AniDB case `max_attempts=1` is pinned: one send, no sleep.
- **M3.** Keys are lowercase, with a 120-character limit, and Windows reserved names and a trailing `.` are rejected. Temp names never match the key pattern.
- **M4.** The `os.replace` retry sleeps between attempts and cleans up its temp file.
- **Nits:** `HttpError` naming and fields; a note on gzip; redirects keep the User-Agent.

## Code review (critic) and attempt 2
No Blocker. The critic's mutation run caught 12 of 19 mutations, and its probes checked the wait math, the lock scope, the exception order, redirects, proxies, the cache and the guard.

- **H1.** After a refusal over the cap, the next request on the same client slept the whole stored time with no log; the probe showed 86400 s. A build that catches the error and moves on would then hang silently.
- **Attempt 2 rule:** before sending, if the stored not-before is more than `max_retry_after_s` in the future, `request` raises `BackedOffError` (a subclass of `HttpError`, status 429, attempts 0) without sending or sleeping.
  - Any wait the client does take is logged once (`waiting N s`).
  - `Retry-After` values are clamped to 10^7 s before any arithmetic, so a 400-digit value cannot raise `OverflowError`.
- **M1:** the `TransportError` backoff timing is pinned with `gap_s=3`, at +10 and then +20.
- **N2:**
  - `cached_json` validates before it stores;
  - a fetch that raises leaves the cache untouched;
  - `clear` never removes temp files;
  - `delete` validates the key.
- **N3:** the `wait >= cap` boundary and a naive HTTP-date read as UTC are pinned.
- **N4:** the thread test checks completion.
- **M2:** the guard docstring now states what it actually blocks: DNS for non-loopback names, and `connect`/`connect_ex` to non-loopback addresses. asyncio connects to IP literals and UDP `sendto` are not intercepted. The data tools use hostnames over urllib, which stay blocked.
