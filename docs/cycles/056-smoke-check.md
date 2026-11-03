# 056 smoke-check (I-005)

## Goal
`deploy/smoke.py <base-url>` checks a deployed site in four steps and exits 0 only if all of them pass. It uses only the Python standard library, with a small hand-written WebSocket client, so the owner's PC needs nothing extra (design 052).

## Behaviour
- **Usage.** `python deploy/smoke.py <base-url>`.
  - `<base-url>` is `http://` or `https://` followed by `host[:port]`, with either no path or only a trailing `/`.
  - Anything else exits 2 with a usage line.
  - Every request sends `User-Agent: SongRushSmoke/1`, has a 15 s timeout, and never sends `Accept-Encoding`. Caddy's gzip therefore never applies.
- **Output.** Each check prints one line, `OK <name>` or `FAIL <name>: <reason>`, in the order below. All four checks run even if one fails.
  1. **`health`:** `GET /health` returns 200 and `json.loads(body) == {"ok": True}`. The real server sends `{"ok":true}` with no spaces (review H2).
  2. **`credit`:**
     - `GET /` returns 200 HTML.
     - Every `<script type="module" ... src="...">` is fetched, matched with its attributes in any order and with the src resolved against the base URL.
     - At least one script contains the D-023 credit line as UTF-8 bytes. The Vite bundle keeps that line as a literal UTF-8 string; this was checked in `frontend/dist`.
  3. **`room-link`:** `GET /r/ABCDE` returns 200 with a body byte-identical to that of `GET /`.
  4. **`websocket`:**
     - Connect to `ws://` or `wss://` (matching the base URL) at `host[:port]/ws`, using `ssl.create_default_context()` for wss.
     - Send a handshake with:
       - `Host` set to the netloc of the base URL, including the port when one is given;
       - `Origin` set to `<scheme>://<netloc>` (Spring's same-origin check compares the two; review M2);
       - a random 16-byte `Sec-WebSocket-Key`.
     - Require a `101` response and a `Sec-WebSocket-Accept` that matches RFC 6455.
     - Send the masked text frame `{"type":"create","nickname":"smoke"}`.
     - Read the unmasked server text frames, handling 7-bit, 16-bit and 64-bit lengths and skipping ping and other frames:
       - a frame with `"type":"joined"` passes;
       - a frame with `"type":"error"` fails, with its code in the reason;
       - 10 s with neither fails.
     - Then send `{"type":"leave"}` and a close frame.
     - Wait up to 2 s for the server's close frame or EOF, then close the socket. Closing with unread data makes Windows send RST and drop the `leave` (review M1).
- **Exit code.** 0 if all four pass, otherwise 1.
- **Layout.**
  - The functions are importable for tests: `main(argv) -> int`.
  - The script runs `sys.exit(main(sys.argv[1:]))` under `if __name__ == "__main__"`.

## Encoding rule (review H1, B2)
- **ASCII and LF only.** `deploy/smoke.py` contains only ASCII with LF line endings. The cycle 055 deploy-kit test checks every file under `deploy/`, and `.gitattributes` now keeps `deploy/**` LF.
- **Building the credit line.** It is built from integer code points. Example:
  - `''.join(chr(c) for c in (0xB370, 0xC774, 0xD130, 0x20, 0xCD9C, 0xCC98))`
  - followed by the ASCII tail `': AnimeThemes, AniList, anime-offline-database, AniDB, Wikidata'`.
- **Forbidden in the source.** String escape notation and Korean literals. File-writing tools turn escape notation into the real characters (CLAUDE.md pitfall).
- **Check after writing.** The author confirms the file has 0 non-ASCII bytes.
- **The test** builds its expected string the same way.
- **No bytecode.** `test_smoke.py` imports `deploy/smoke.py` with `sys.dont_write_bytecode = True`, monkeypatched for the import. This keeps `deploy/__pycache__/` from appearing. Otherwise the deploy-kit ASCII test would fail from the second run on.

## Acceptance criteria (pytest, from `data/`; loopback only, as the conftest guard allows)
`data/tests/test_smoke.py` runs a stub server on `127.0.0.1` with a free port in a background thread. The stub is a `socketserver.ThreadingTCPServer` with a hand-written HTTP/WebSocket responder, inside the test file. The test imports `deploy/smoke.py` by path with `importlib.util`.

1. **All good.**
   - The stub serves:
     - `/health` as `{"ok":true}`;
     - an `index.html` whose module script `/assets/app.js` contains the credit line;
     - `/r/ABCDE` as the same `index.html`;
     - `/ws`, which:
       - checks that `Host` equals `127.0.0.1:<port>` and `Origin` equals `http://127.0.0.1:<port>`;
       - completes the handshake;
       - checks that the client frame is masked;
       - answers `joined`, then `roster` and `token` frames, as the real server does.
   - `main([base])` returns 0 and the output has four `OK` lines in order.
   - The stub records the `leave` frame. The test waits for it on a `threading.Event` for up to 5 s.
2. **Missing credit line.** The script lacks the credit line, so the result is 1 with `FAIL credit`. The other three are `OK`.
3. **WebSocket error.** The stub answers `{"type":"error","code":"server-full"}`, so the result is 1 with `FAIL websocket` and `server-full` in the reason.
4. **Bad handshake.** The stub answers the upgrade with 403, so the result is 1 with `FAIL websocket`.
5. **Room link differs.** `/r/ABCDE` returns 404, so the result is 1 with `FAIL room-link`.
6. **Health wrong.** `/health` returns `{"ok":false}`, so the result is 1 with `FAIL health`.
7. **Bad arguments.** `main([])`, `main(["ftp://x"])` and `main(["http://h/path"])` all return 2.
8. **Large frame.** The stub pads the `joined` message past 126 bytes, so the client has to read a 16-bit length. The result is still 0.
9. **Encoding.**
   - `deploy/smoke.py` is ASCII with LF line endings.
   - It imports only standard-library modules, checked against `sys.stdlib_module_names`.
   - After the test run, no `deploy/__pycache__` exists.
10. The full `.venv\Scripts\python.exe -m pytest` passes, including the cycle 055 deploy-kit tests.

## Review changes (spec round 1)
- **Blocker.**
  - After checkout, `deploy/` files were CRLF because of `core.autocrlf=true`. This already failed the deploy-kit ASCII/LF test on `dev`.
  - Fixed in this cycle by the `.gitattributes` rule `deploy/** text eol=lf` (commit `45b9813`), and a CLAUDE.md pitfall was added.
  - Importing `smoke.py` must not write bytecode under `deploy/`.
- **High.**
  - There is now a single encoding rule: ASCII, with the credit line built from code points.
  - `/health` is compared as JSON.
- **Medium.**
  - The client waits for the server's close before closing.
  - The Host/Origin rule is stated.
- **Nit.** No `Accept-Encoding` header.

## Test list
- `data/tests/test_smoke.py`

## File ownership
- **Test writer:** `data/tests/test_smoke.py`.
- **Implementer:** `deploy/smoke.py`.

## Out of scope
- Running against the real server (I-007).
- Checking audio.

## Result
- **Commits.**
  - `45b9813` fixed the spec-review Blocker: CRLF `deploy/` files on checkout. `deploy/**` is now LF, and a CLAUDE.md pitfall records it.
  - `288e698` holds the tests (locked).
  - `fbcdd47` holds the implementation (attempt 1).
- **Red phase (verifier).** 13 of 19 failed on the missing `deploy/smoke.py`. The 721 others passed, including the 20 deploy-kit tests, and no `deploy/__pycache__` was written.
- **Verifier.**
  - `pytest tests/test_smoke.py`: 19 passed.
  - Full suite: 734 passed, twice, with no `deploy/__pycache__` afterwards.
  - `smoke.py` is ASCII with LF.
  - **Real backend.** The jar was rebuilt and served a copy of `frontend/dist` on port 18081. `deploy/smoke.py http://127.0.0.1:18081` printed `OK health`, `OK credit`, `OK room-link` and `OK websocket`, and exited 0. Against a closed port it printed four `FAIL ... ConnectionRefusedError` lines and exited 1.

## Code review (critic)
No Blocker, High or Medium. Confirmed:
- the RFC 6455 send and receive framing;
- TLS with SNI and certificate checks;
- the handshake passing Spring's same-origin check behind Caddy, through forwarded headers;
- every blocking read bounded by a deadline;
- a smoke lobby freed within about 2.5 minutes;
- no other file needs an LF rule (`backend/gradlew` already has one).

Nits (follow-up):
- The wss and over-125-byte send branches only run against the real server (I-007).
- The stub does not check the close handshake.
