"""Cycle 056 criteria 1-9: the smoke check deploy/smoke.py.

docs/cycles/056-smoke-check.md is the specification. deploy/smoke.py is
imported inside each test (never at module import time), so a missing file
fails its tests rather than the collection. The import runs with
sys.dont_write_bytecode set, so no deploy/__pycache__ appears and the cycle
055 ASCII/LF check over deploy/ keeps passing.

The stub server is a socketserver.ThreadingTCPServer on 127.0.0.1 with a free
port, running in a background thread. Its responder is hand-written: plain
HTTP/1.1 with Content-Length and Connection: close for the pages, and an
RFC 6455 WebSocket on /ws that checks Host, Origin, the key and the masking
of client frames, answers create with joined, roster and token frames (or a
variant), and records the leave frame on a threading.Event. Anything the stub
finds wrong in the client is appended to stub.errors.

The credit line is built from integer code points, as the encoding rule
requires; this file stays ASCII-only.
"""

import ast
import base64
import hashlib
import importlib.util
import json
import os
import socket
import socketserver
import sys
import threading
from pathlib import Path

import pytest

REPO = Path(__file__).resolve().parents[2]
DEPLOY = REPO / "deploy"
SMOKE = DEPLOY / "smoke.py"

CREDIT = ("".join(chr(c) for c in (0xB370, 0xC774, 0xD130, 0x20, 0xCD9C, 0xCC98))
          + ": AnimeThemes, AniList, anime-offline-database, AniDB, Wikidata")

CHECKS = ["health", "credit", "room-link", "websocket"]
USER_AGENT = "SongRushSmoke/1"
WS_GUID = b"258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
LEAVE_WAIT = 5
STUB_TIMEOUT = 15

INDEX_HTML = (b'<!doctype html>\n<html lang="ko">\n<head>\n'
              b'<meta charset="UTF-8" />\n<title>SongRush</title>\n'
              b'<script crossorigin src="/assets/app.js" type="module"></script>\n'
              b'<link rel="stylesheet" crossorigin href="/assets/app.css">\n'
              b'</head>\n<body><div id="root"></div></body>\n</html>\n')
APP_CSS = b"body{margin:0}\n"

REASONS = {101: "Switching Protocols", 200: "OK", 400: "Bad Request",
           403: "Forbidden", 404: "Not Found"}


def _script_body(with_credit):
    line = CREDIT if with_credit else "no credit here"
    return ('const credit="' + line + '";console.log(credit);\n').encode("utf-8")


def _compact(obj):
    return json.dumps(obj, separators=(",", ":")).encode("utf-8")


def _accept_for(key):
    return base64.b64encode(hashlib.sha1(key.encode("ascii") + WS_GUID).digest()).decode("ascii")


def _frame(opcode, payload, mask=None):
    """One FIN frame; masked with the 4-byte key when mask is given."""
    head = bytearray([0x80 | opcode])
    mask_bit = 0x80 if mask is not None else 0
    n = len(payload)
    if n < 126:
        head.append(mask_bit | n)
    elif n < 65536:
        head.append(mask_bit | 126)
        head += n.to_bytes(2, "big")
    else:
        head.append(mask_bit | 127)
        head += n.to_bytes(8, "big")
    if mask is not None:
        head += mask
        payload = bytes(b ^ mask[i % 4] for i, b in enumerate(payload))
    return bytes(head) + payload


# ---------------------------------------------------------------- the stub

class Stub:
    """Configuration and observations of one stub server."""

    def __init__(self, credit=True, ws_mode="ok", room_status=200,
                 health_body=b'{"ok":true}'):
        self.credit = credit
        self.ws_mode = ws_mode          # ok, large, error, forbidden
        self.room_status = room_status
        self.health_body = health_body
        self.port = None
        self.left = threading.Event()
        self.created = []
        self.errors = []
        self.requests = []
        self._lock = threading.Lock()

    @property
    def base(self):
        return f"http://127.0.0.1:{self.port}"

    def error(self, text):
        with self._lock:
            self.errors.append(text)

    def record(self, method, target, headers):
        with self._lock:
            self.requests.append((method, target, headers))

    def route(self, target):
        index = (200, "text/html; charset=utf-8", INDEX_HTML)
        if target == "/health":
            return 200, "application/json", self.health_body
        if target == "/":
            return index
        if target == "/r/ABCDE":
            if self.room_status == 200:
                return index
            return self.room_status, "text/plain", b"not found"
        if target == "/assets/app.js":
            return 200, "text/javascript", _script_body(self.credit)
        if target == "/assets/app.css":
            return 200, "text/css", APP_CSS
        return 404, "text/plain", b"not found"

    def handshake_problems(self, headers):
        netloc = f"127.0.0.1:{self.port}"
        problems = []
        if headers.get("host") != netloc:
            problems.append(f"Host is {headers.get('host')!r}, expected {netloc!r}")
        if headers.get("origin") != "http://" + netloc:
            problems.append(f"Origin is {headers.get('origin')!r}, expected 'http://{netloc}'")
        if headers.get("upgrade", "").lower() != "websocket":
            problems.append(f"Upgrade is {headers.get('upgrade')!r}")
        if "upgrade" not in [t.strip().lower() for t in headers.get("connection", "").split(",")]:
            problems.append(f"Connection is {headers.get('connection')!r}")
        if headers.get("sec-websocket-version") != "13":
            problems.append(f"Sec-WebSocket-Version is {headers.get('sec-websocket-version')!r}")
        key = headers.get("sec-websocket-key", "")
        try:
            raw = base64.b64decode(key, validate=True)
        except ValueError:
            raw = b""
        if len(raw) != 16:
            problems.append(f"Sec-WebSocket-Key {key!r} is not 16 base64 bytes")
        return problems

    def replies(self):
        """The frames the stub answers create with, per ws_mode."""
        joined = {"type": "joined", "code": "ABCDE", "playerId": "p1", "host": True}
        if self.ws_mode == "error":
            return [_compact({"type": "error", "code": "server-full"})]
        if self.ws_mode == "large":
            joined["pad"] = "x" * 300
        first = _compact(joined)
        if self.ws_mode == "large":
            assert 125 < len(first) < 65536
        roster = {"type": "roster", "players": [
            {"id": "p1", "nickname": "smoke", "score": 0, "host": True}]}
        return [first, _compact(roster), _compact({"type": "token", "token": "t0k3n"})]


class _Handler(socketserver.StreamRequestHandler):
    timeout = STUB_TIMEOUT

    def handle(self):
        stub = self.server.stub
        try:
            self._handle(stub)
        except OSError as exc:  # includes socket timeouts and resets
            stub.error(f"{type(exc).__name__}: {exc}")

    def _handle(self, stub):
        request_line = self.rfile.readline(65537)
        if not request_line:
            return
        parts = request_line.decode("latin-1").split()
        if len(parts) != 3:
            stub.error(f"bad request line {request_line!r}")
            return
        method, target, _version = parts
        headers = {}
        while True:
            line = self.rfile.readline(65537)
            if line in (b"\r\n", b"\n", b""):
                break
            name, sep, value = line.decode("latin-1").partition(":")
            if not sep:
                stub.error(f"bad header line {line!r}")
                return
            headers[name.strip().lower()] = value.strip()
        stub.record(method, target, headers)
        if target == "/ws":
            self._websocket(stub, headers)
        else:
            self._http(stub, method, target)

    def _respond(self, status, ctype, body, extra=""):
        head = (f"HTTP/1.1 {status} {REASONS[status]}\r\n"
                f"Content-Type: {ctype}\r\nContent-Length: {len(body)}\r\n"
                f"{extra}Connection: close\r\n\r\n")
        self.wfile.write(head.encode("ascii") + body)
        self.wfile.flush()

    def _http(self, stub, method, target):
        if method != "GET":
            stub.error(f"unexpected method {method} {target}")
            self._respond(400, "text/plain", b"bad method")
            return
        self._respond(*stub.route(target))

    def _read_exact(self, n):
        data = self.rfile.read(n)
        if len(data) != n:
            return None
        return data

    def _read_frame(self, stub):
        """(opcode, payload) of the next client frame, or None at EOF."""
        head = self._read_exact(2)
        if head is None:
            return None
        opcode = head[0] & 0x0F
        masked = head[1] & 0x80
        n = head[1] & 0x7F
        if n == 126:
            ext = self._read_exact(2)
            if ext is None:
                return None
            n = int.from_bytes(ext, "big")
        elif n == 127:
            ext = self._read_exact(8)
            if ext is None:
                return None
            n = int.from_bytes(ext, "big")
        if not masked:
            stub.error(f"client frame (opcode {opcode}) is not masked")
            return None
        mask = self._read_exact(4)
        payload = self._read_exact(n) if n else b""
        if mask is None or payload is None:
            return None
        return opcode, bytes(b ^ mask[i % 4] for i, b in enumerate(payload))

    def _send(self, opcode, payload):
        self.wfile.write(_frame(opcode, payload))
        self.wfile.flush()

    def _websocket(self, stub, headers):
        if stub.ws_mode == "forbidden":
            self._respond(403, "text/plain", b"forbidden")
            return
        problems = stub.handshake_problems(headers)
        if problems:
            for problem in problems:
                stub.error(problem)
            self._respond(400, "text/plain", b"bad handshake")
            return
        accept = _accept_for(headers["sec-websocket-key"])
        self.wfile.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\n"
                          "Connection: Upgrade\r\n"
                          f"Sec-WebSocket-Accept: {accept}\r\n\r\n").encode("ascii"))
        self.wfile.flush()

        replied = False
        while True:
            frame = self._read_frame(stub)
            if frame is None:
                return
            opcode, payload = frame
            if opcode == 0x8:
                self._send(0x8, payload[:2])
                return
            if opcode == 0x9:
                self._send(0xA, payload)
                continue
            if opcode != 0x1:
                continue
            try:
                message = json.loads(payload.decode("utf-8"))
            except ValueError:
                stub.error(f"text frame is not JSON: {payload!r}")
                continue
            kind = message.get("type") if isinstance(message, dict) else None
            if not replied:
                if kind != "create":
                    stub.error(f"first message is not create: {payload!r}")
                stub.created.append(message)
                for reply in stub.replies():
                    self._send(0x1, reply)
                replied = True
            elif kind == "leave":
                stub.left.set()
            else:
                stub.error(f"unexpected message after create: {payload!r}")


class _Server(socketserver.ThreadingTCPServer):
    daemon_threads = True
    allow_reuse_address = False


@pytest.fixture
def start_stub():
    servers = []

    def start(**config):
        stub = Stub(**config)
        server = _Server(("127.0.0.1", 0), _Handler)
        server.stub = stub
        stub.port = server.server_address[1]
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        servers.append((server, thread))
        return stub

    yield start
    for server, thread in servers:
        server.shutdown()
        server.server_close()
        thread.join(5)


# ---------------------------------------------------------------- helpers

def _load_smoke(monkeypatch):
    assert SMOKE.is_file(), "missing deploy/smoke.py"
    monkeypatch.setattr(sys, "dont_write_bytecode", True)
    spec = importlib.util.spec_from_file_location("songrush_deploy_smoke", SMOKE)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def _run(smoke, base, capsys):
    code = smoke.main([base])
    out = capsys.readouterr().out
    lines = [line for line in out.splitlines() if line.startswith(("OK ", "FAIL "))]
    return code, lines, out


def _name(line):
    if line.startswith("OK "):
        return line[3:].strip()
    return line[5:].split(":", 1)[0].strip()


def _assert_lines(lines, out, failing=()):
    assert [_name(line) for line in lines] == CHECKS, out
    for line in lines:
        name = _name(line)
        if name in failing:
            assert line.startswith(f"FAIL {name}:"), out
        else:
            assert line == f"OK {name}", out


def _line(lines, name):
    return next(line for line in lines if _name(line) == name)


# ---------------------------------------------------------------- the stub itself

def _client_handshake(port):
    sock = socket.create_connection(("127.0.0.1", port), timeout=10)
    key = base64.b64encode(os.urandom(16)).decode("ascii")
    sock.sendall((f"GET /ws HTTP/1.1\r\nHost: 127.0.0.1:{port}\r\n"
                  f"Origin: http://127.0.0.1:{port}\r\nUpgrade: websocket\r\n"
                  "Connection: Upgrade\r\nSec-WebSocket-Version: 13\r\n"
                  f"Sec-WebSocket-Key: {key}\r\n\r\n").encode("ascii"))
    reader = sock.makefile("rb")
    status = reader.readline()
    headers = {}
    while True:
        line = reader.readline()
        if line in (b"\r\n", b""):
            break
        name, _, value = line.decode("latin-1").partition(":")
        headers[name.strip().lower()] = value.strip()
    return sock, reader, status, headers, key


def _client_read_frame(reader):
    head = reader.read(2)
    n = head[1] & 0x7F
    if n == 126:
        n = int.from_bytes(reader.read(2), "big")
    elif n == 127:
        n = int.from_bytes(reader.read(8), "big")
    return head[0] & 0x0F, reader.read(n)


@pytest.mark.parametrize("mode", ["ok", "large"])
def test_stub_websocket_answers_create_and_records_leave(start_stub, mode):
    stub = start_stub(ws_mode=mode)
    sock, reader, status, headers, key = _client_handshake(stub.port)
    try:
        assert status.startswith(b"HTTP/1.1 101"), status
        assert headers["sec-websocket-accept"] == _accept_for(key)
        mask = os.urandom(4)
        sock.sendall(_frame(0x1, b'{"type":"create","nickname":"smoke"}', mask))
        kinds = []
        for _ in range(3):
            opcode, payload = _client_read_frame(reader)
            assert opcode == 0x1
            kinds.append(json.loads(payload)["type"])
        assert kinds == ["joined", "roster", "token"]
        sock.sendall(_frame(0x1, b'{"type":"leave"}', mask))
        sock.sendall(_frame(0x8, (1000).to_bytes(2, "big"), mask))
        assert _client_read_frame(reader)[0] == 0x8
    finally:
        reader.close()
        sock.close()
    assert stub.left.wait(LEAVE_WAIT)
    assert stub.errors == []


def test_stub_rejects_wrong_origin(start_stub):
    stub = start_stub()
    sock = socket.create_connection(("127.0.0.1", stub.port), timeout=10)
    try:
        sock.sendall((f"GET /ws HTTP/1.1\r\nHost: 127.0.0.1:{stub.port}\r\n"
                      "Origin: http://evil.example\r\nUpgrade: websocket\r\n"
                      "Connection: Upgrade\r\nSec-WebSocket-Version: 13\r\n"
                      "Sec-WebSocket-Key: AAAAAAAAAAAAAAAAAAAAAA==\r\n\r\n").encode("ascii"))
        assert sock.recv(64).startswith(b"HTTP/1.1 400")
    finally:
        sock.close()
    assert any("Origin" in e for e in stub.errors)


def test_stub_rejects_unmasked_frame(start_stub):
    stub = start_stub()
    sock, reader, status, _headers, _key = _client_handshake(stub.port)
    try:
        assert status.startswith(b"HTTP/1.1 101")
        sock.sendall(_frame(0x1, b'{"type":"create","nickname":"smoke"}'))
        assert reader.read(1) == b""
    finally:
        reader.close()
        sock.close()
    assert any("not masked" in e for e in stub.errors)


def test_credit_line_is_built_from_code_points():
    assert CREDIT.startswith("".join(map(chr, (0xB370, 0xC774, 0xD130))))
    assert CREDIT.endswith(": AnimeThemes, AniList, anime-offline-database, AniDB, Wikidata")
    assert len(CREDIT.encode("utf-8")) == len(CREDIT) + 2 * 5


# ---------------------------------------------------------------- criterion 1

def test_all_good_passes_and_sends_leave(start_stub, monkeypatch, capsys):
    smoke = _load_smoke(monkeypatch)
    stub = start_stub()
    code, lines, out = _run(smoke, stub.base, capsys)
    assert code == 0, out + f"\nstub errors: {stub.errors}"
    _assert_lines(lines, out)
    assert stub.left.wait(LEAVE_WAIT), f"stub got no leave frame; stub errors: {stub.errors}"
    assert stub.created == [{"type": "create", "nickname": "smoke"}]
    assert stub.errors == []


def test_requests_send_user_agent_and_no_compression(start_stub, monkeypatch, capsys):
    smoke = _load_smoke(monkeypatch)
    stub = start_stub()
    _run(smoke, stub.base, capsys)
    targets = [target for _method, target, _headers in stub.requests]
    for needed in ("/health", "/", "/assets/app.js", "/r/ABCDE", "/ws"):
        assert needed in targets, f"no request for {needed}: {targets}"
    for _method, target, headers in stub.requests:
        assert headers.get("user-agent") == USER_AGENT, (target, headers)
        # http.client adds "identity" unless told to skip; identity never compresses.
        assert headers.get("accept-encoding", "identity") == "identity", (target, headers)


# ---------------------------------------------------------------- criteria 2-6, 8

def test_missing_credit_fails_credit_only(start_stub, monkeypatch, capsys):
    smoke = _load_smoke(monkeypatch)
    stub = start_stub(credit=False)
    code, lines, out = _run(smoke, stub.base, capsys)
    assert code == 1, out
    _assert_lines(lines, out, failing={"credit"})


def test_websocket_error_fails_with_code(start_stub, monkeypatch, capsys):
    smoke = _load_smoke(monkeypatch)
    stub = start_stub(ws_mode="error")
    code, lines, out = _run(smoke, stub.base, capsys)
    assert code == 1, out
    _assert_lines(lines, out, failing={"websocket"})
    assert "server-full" in _line(lines, "websocket"), out


def test_forbidden_handshake_fails_websocket(start_stub, monkeypatch, capsys):
    smoke = _load_smoke(monkeypatch)
    stub = start_stub(ws_mode="forbidden")
    code, lines, out = _run(smoke, stub.base, capsys)
    assert code == 1, out
    _assert_lines(lines, out, failing={"websocket"})


def test_room_link_404_fails_room_link(start_stub, monkeypatch, capsys):
    smoke = _load_smoke(monkeypatch)
    stub = start_stub(room_status=404)
    code, lines, out = _run(smoke, stub.base, capsys)
    assert code == 1, out
    _assert_lines(lines, out, failing={"room-link"})


def test_health_not_ok_fails_health(start_stub, monkeypatch, capsys):
    smoke = _load_smoke(monkeypatch)
    stub = start_stub(health_body=b'{"ok":false}')
    code, lines, out = _run(smoke, stub.base, capsys)
    assert code == 1, out
    _assert_lines(lines, out, failing={"health"})


def test_large_joined_frame_uses_16_bit_length(start_stub, monkeypatch, capsys):
    smoke = _load_smoke(monkeypatch)
    stub = start_stub(ws_mode="large")
    code, lines, out = _run(smoke, stub.base + "/", capsys)
    assert code == 0, out + f"\nstub errors: {stub.errors}"
    _assert_lines(lines, out)
    assert stub.left.wait(LEAVE_WAIT), f"stub got no leave frame; stub errors: {stub.errors}"


# ---------------------------------------------------------------- criterion 7

@pytest.mark.parametrize("argv", [[], ["ftp://x"], ["http://h/path"]],
                         ids=["none", "ftp", "path"])
def test_bad_arguments_exit_2(monkeypatch, capsys, argv):
    smoke = _load_smoke(monkeypatch)
    assert smoke.main(argv) == 2
    captured = capsys.readouterr()
    assert "usage" in (captured.out + captured.err).lower()


# ---------------------------------------------------------------- criterion 9
# Kept last in the file: the __pycache__ check runs after every import above.

def test_smoke_is_ascii_with_lf_only():
    assert SMOKE.is_file(), "missing deploy/smoke.py"
    data = SMOKE.read_bytes()
    assert all(b < 0x80 for b in data), "deploy/smoke.py is not ASCII"
    assert b"\r" not in data, "deploy/smoke.py has CR line endings"


def test_smoke_imports_only_the_standard_library():
    assert SMOKE.is_file(), "missing deploy/smoke.py"
    tree = ast.parse(SMOKE.read_bytes().decode("ascii"))
    names = set()
    for node in ast.walk(tree):
        if isinstance(node, ast.Import):
            names.update(alias.name.split(".")[0] for alias in node.names)
        elif isinstance(node, ast.ImportFrom):
            assert node.level == 0, "deploy/smoke.py uses a relative import"
            names.add(node.module.split(".")[0])
    outside = sorted(n for n in names if n not in sys.stdlib_module_names)
    assert not outside, f"non-stdlib imports: {outside}"


def test_no_bytecode_under_deploy():
    assert not (DEPLOY / "__pycache__").exists(), "deploy/__pycache__ exists"
