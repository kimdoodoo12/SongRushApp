# 056 smoke-check (I-005)

## Goal
`deploy/smoke.py <base-url>` checks a deployed site in four steps and exits 0 only if all pass. It uses the Python standard library only, with a minimal hand-written WebSocket client, so the owner's PC needs nothing extra (design 052).

## Behaviour
- **Usage.** `python deploy/smoke.py <base-url>`, where `<base-url>` is `http://` or `https://` host[:port] with no path, or a trailing `/`. Anything else exits 2 with a usage line. It sets `User-Agent: SongRushSmoke/1` and a 15 s timeout per request.
- **The four checks.** Each prints one line, `OK <name>` or `FAIL <name>: <reason>`, in this order. It runs all four even if one fails.
  1. **`health`:** `GET /health` gives 200 with JSON exactly `{"ok": true}`.
  2. **`credit`:**
     - `GET /` gives 200 HTML.
     - It finds every `<script type="module" ... src="...">` and fetches each src relative to the base URL.
     - At least one fetched script contains the D-023 credit line, encoded in UTF-8: `데이터 출처: AnimeThemes, AniList, anime-offline-database, AniDB, Wikidata`. The string is kept as written in the source; the file is UTF-8.
  3. **`room-link`:** `GET /r/ABCDE` gives 200 with a body byte-identical to the body of `GET /`.
  4. **`websocket`:**
     - Connect to `ws://` or `wss://` (matching the base URL) `host[:port]/ws`, using `ssl.create_default_context()` for wss. Send a handshake with `Origin: <base-url without trailing slash>` and a random 16-byte `Sec-WebSocket-Key`.
     - Require `101` and a `Sec-WebSocket-Accept` that matches RFC 6455.
     - Send a masked text frame `{"type":"create","nickname":"smoke"}`. Read unmasked server text frames, handling 7-bit, 16-bit and 64-bit lengths and skipping ping or other frames, until one with `"type":"joined"` arrives (pass), `"type":"error"` arrives (fail, with its code), or 10 s pass (fail).
     - Then send `{"type":"leave"}` and a close frame.
- **Exit code.** 0 if all four pass, 1 otherwise.
- **Code layout.** The functions are importable for tests (`main(argv) -> int`); the script calls `sys.exit(main(sys.argv[1:]))` under `if __name__ == "__main__"`.

## Acceptance criteria (pytest, from `data/`; loopback only, as the conftest guard allows)
`data/tests/test_smoke.py` starts a stub server on `127.0.0.1` with a free port in a background thread. The stub is built on `socketserver.ThreadingTCPServer` with a hand-written HTTP/WebSocket responder; it is part of the test file. The test imports `deploy/smoke.py` by path with `importlib.util`.

1. **All good.**
   - The stub serves `/health` and an `index.html` whose module script `/assets/app.js` contains the credit line. `/r/ABCDE` serves the same `index.html`.
   - `/ws` completes the handshake, checks that the client frame is masked, and answers `joined`.
   - Result: `main([base])` returns 0, the output has four `OK` lines in order, and the stub saw the `leave` frame.
2. **Missing credit line.** The script lacks the credit line, so the result is 1 with `FAIL credit`. The other three are `OK`.
3. **WebSocket error.** The stub answers `{"type":"error","code":"server-full"}`, so the result is 1 with `FAIL websocket` and `server-full` in the reason.
4. **Bad handshake.** The stub answers the upgrade with 403, so the result is 1 with `FAIL websocket`.
5. **Room link differs.** `/r/ABCDE` returns 404, so the result is 1 with `FAIL room-link`.
6. **Health wrong.** `/health` returns `{"ok": false}`, so the result is 1 with `FAIL health`.
7. **Bad arguments.** `main([])`, `main(["ftp://x"])` and `main(["http://h/path"])` return 2.
8. **Large frame.** The stub sends the `joined` message padded past 126 bytes, so the client has to read a 16-bit length. The result is still 0.
9. **Encoding.** `deploy/smoke.py` is UTF-8 with LF line endings and only uses the standard library: its imports are checked against `sys.stdlib_module_names`.
10. The full `.venv\Scripts\python.exe -m pytest` passes, including the cycle 055 deploy-kit tests.

The cycle 055 deploy-kit test requires every file in `deploy/` to be ASCII. A Korean literal in `smoke.py` would fail it, so the credit line must be built from code points, as in the CLAUDE.md pitfall (for example `"데..."`-style escapes written as ASCII text, or `chr()`). Criterion 9 then means ASCII-only text.

## Test list
- `data/tests/test_smoke.py`

## File ownership
- **Test writer:** `data/tests/test_smoke.py`.
- **Implementer:** `deploy/smoke.py`.

## Out of scope
- Running against the real server (I-007).
- Checking audio.
