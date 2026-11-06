"""Smoke check of a deployed SongRush site (cycle 056, I-005).

Usage: python deploy/smoke.py <base-url>

<base-url> is http:// or https:// followed by host[:port], with no path or
only a trailing slash. Four checks run in order, each printing one line,
"OK <name>" or "FAIL <name>: <reason>":

  health     GET /health is 200 and its JSON is {"ok": true}
  credit     a module script of GET / contains the D-023 credit line
  room-link  GET /r/ABCDE is 200 and byte-identical to GET /
  websocket  /ws accepts a create and answers joined; then leave and close

Exit code: 0 if all four pass, 1 if any fails, 2 for bad arguments.

Standard library only. This file is ASCII with LF line endings; the Korean
part of the credit line is built from integer code points.
"""

import base64
import hashlib
import html.parser
import http.client
import json
import os
import socket
import ssl
import sys
import time
import urllib.parse

USER_AGENT = "SongRushSmoke/1"
TIMEOUT = 15
WS_REPLY_WAIT = 10
WS_CLOSE_WAIT = 2
WS_GUID = b"258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
ROOM_PATH = "/r/ABCDE"
USAGE = "usage: python deploy/smoke.py <base-url>  (http[s]://host[:port], no path)"

CREDIT = ("".join(chr(c) for c in (0xB370, 0xC774, 0xD130, 0x20, 0xCD9C, 0xCC98))
          + ": AnimeThemes, AniList, anime-offline-database, AniDB, Wikidata")

# The exceptions a check turns into a FAIL line; anything else is a bug.
CHECK_ERRORS = (OSError, http.client.HTTPException, ValueError)


class Base:
    """A validated base URL."""

    def __init__(self, scheme, netloc, hostname, port):
        self.scheme = scheme
        self.netloc = netloc
        self.hostname = hostname
        self.port = port

    @property
    def url(self):
        return f"{self.scheme}://{self.netloc}/"


def parse_base(text):
    """The Base for text, or None when it is not http[s]://host[:port][/]."""
    try:
        parts = urllib.parse.urlsplit(text)
        port = parts.port
    except ValueError:
        return None
    if parts.scheme not in ("http", "https"):
        return None
    if not parts.hostname or "@" in parts.netloc:
        return None
    if parts.path not in ("", "/") or parts.query or parts.fragment:
        return None
    if text.endswith(("?", "#")):
        return None
    if port is None:
        port = 443 if parts.scheme == "https" else 80
    return Base(parts.scheme, parts.netloc, parts.hostname, port)


# ---------------------------------------------------------------- HTTP

def http_get(url):
    """(status, content-type, body) of GET url, with no Accept-Encoding."""
    parts = urllib.parse.urlsplit(url)
    if parts.scheme == "https":
        conn = http.client.HTTPSConnection(parts.hostname, parts.port, timeout=TIMEOUT,
                                           context=ssl.create_default_context())
    elif parts.scheme == "http":
        conn = http.client.HTTPConnection(parts.hostname, parts.port, timeout=TIMEOUT)
    else:
        raise ValueError(f"unsupported URL {url!r}")
    target = parts.path or "/"
    if parts.query:
        target += "?" + parts.query
    try:
        conn.putrequest("GET", target, skip_accept_encoding=True)
        conn.putheader("User-Agent", USER_AGENT)
        conn.endheaders()
        response = conn.getresponse()
        body = response.read()
        return response.status, response.getheader("Content-Type", ""), body
    finally:
        conn.close()


class _ModuleScripts(html.parser.HTMLParser):
    """Collects the src of every <script type="module" src=...>."""

    def __init__(self):
        super().__init__()
        self.sources = []

    def handle_starttag(self, tag, attrs):
        if tag != "script":
            return
        values = {name.lower(): value for name, value in attrs}
        kind = (values.get("type") or "").strip().lower()
        src = values.get("src")
        if kind == "module" and src:
            self.sources.append(src.strip())


def check_health(base):
    status, _ctype, body = http_get(urllib.parse.urljoin(base.url, "/health"))
    if status != 200:
        return f"/health returned {status}"
    try:
        value = json.loads(body.decode("utf-8"))
    except ValueError:
        return f"/health body is not JSON: {body[:80]!r}"
    if value != {"ok": True}:
        return f"/health body is {body[:80]!r}"
    return None


def check_credit(base):
    status, ctype, body = http_get(base.url)
    if status != 200:
        return f"/ returned {status}"
    if "text/html" not in ctype.lower():
        return f"/ is {ctype!r}, not HTML"
    parser = _ModuleScripts()
    parser.feed(body.decode("utf-8", errors="replace"))
    parser.close()
    if not parser.sources:
        return "/ has no module script"
    needle = CREDIT.encode("utf-8")
    found = False
    for src in parser.sources:
        url = urllib.parse.urljoin(base.url, src)
        script_status, _ctype, script = http_get(url)
        if script_status != 200:
            return f"{src} returned {script_status}"
        if needle in script:
            found = True
    if not found:
        return f"credit line not in any module script ({', '.join(parser.sources)})"
    return None


def check_room_link(base):
    status, _ctype, index = http_get(base.url)
    if status != 200:
        return f"/ returned {status}"
    status, _ctype, room = http_get(urllib.parse.urljoin(base.url, ROOM_PATH))
    if status != 200:
        return f"{ROOM_PATH} returned {status}"
    if room != index:
        return f"{ROOM_PATH} body differs from / ({len(room)} vs {len(index)} bytes)"
    return None


# ---------------------------------------------------------------- WebSocket

class _Socket:
    """A socket with a read buffer and deadline-bounded exact reads."""

    def __init__(self, sock):
        self.sock = sock
        self.buffer = bytearray()

    def _fill(self, deadline):
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            raise socket.timeout("timed out")
        self.sock.settimeout(remaining)
        chunk = self.sock.recv(65536)
        if not chunk:
            raise EOFError
        self.buffer += chunk

    def read_exact(self, n, deadline):
        while len(self.buffer) < n:
            self._fill(deadline)
        data = bytes(self.buffer[:n])
        del self.buffer[:n]
        return data

    def read_head(self, deadline):
        """The HTTP response head up to and excluding the blank line."""
        while b"\r\n\r\n" not in self.buffer:
            if len(self.buffer) > 65536:
                raise ValueError("handshake response head too long")
            self._fill(deadline)
        end = self.buffer.index(b"\r\n\r\n")
        head = bytes(self.buffer[:end])
        del self.buffer[:end + 4]
        return head

    def send_frame(self, opcode, payload):
        """One masked FIN frame, as RFC 6455 requires of a client."""
        head = bytearray([0x80 | opcode])
        n = len(payload)
        if n < 126:
            head.append(0x80 | n)
        elif n < 65536:
            head.append(0x80 | 126)
            head += n.to_bytes(2, "big")
        else:
            head.append(0x80 | 127)
            head += n.to_bytes(8, "big")
        mask = os.urandom(4)
        head += mask
        masked = bytes(b ^ mask[i % 4] for i, b in enumerate(payload))
        self.sock.settimeout(TIMEOUT)
        self.sock.sendall(bytes(head) + masked)

    def read_frame(self, deadline):
        """(fin, opcode, payload) of the next server frame."""
        head = self.read_exact(2, deadline)
        fin = bool(head[0] & 0x80)
        opcode = head[0] & 0x0F
        n = head[1] & 0x7F
        if n == 126:
            n = int.from_bytes(self.read_exact(2, deadline), "big")
        elif n == 127:
            n = int.from_bytes(self.read_exact(8, deadline), "big")
        mask = self.read_exact(4, deadline) if head[1] & 0x80 else None
        payload = self.read_exact(n, deadline) if n else b""
        if mask is not None:
            payload = bytes(b ^ mask[i % 4] for i, b in enumerate(payload))
        return fin, opcode, payload


def _accept_for(key):
    return base64.b64encode(hashlib.sha1(key.encode("ascii") + WS_GUID).digest()).decode("ascii")


def _open_websocket(base):
    raw = socket.create_connection((base.hostname, base.port), timeout=TIMEOUT)
    if base.scheme == "https":
        try:
            raw = ssl.create_default_context().wrap_socket(raw, server_hostname=base.hostname)
        except OSError:
            raw.close()
            raise
    return _Socket(raw)


def _handshake(conn, base):
    """None when the upgrade succeeded, else the reason it did not."""
    key = base64.b64encode(os.urandom(16)).decode("ascii")
    request = (f"GET /ws HTTP/1.1\r\n"
               f"Host: {base.netloc}\r\n"
               f"Origin: {base.scheme}://{base.netloc}\r\n"
               f"User-Agent: {USER_AGENT}\r\n"
               "Upgrade: websocket\r\n"
               "Connection: Upgrade\r\n"
               "Sec-WebSocket-Version: 13\r\n"
               f"Sec-WebSocket-Key: {key}\r\n\r\n")
    conn.sock.settimeout(TIMEOUT)
    conn.sock.sendall(request.encode("ascii"))
    try:
        head = conn.read_head(time.monotonic() + TIMEOUT)
    except EOFError:
        return "connection closed during the handshake"
    lines = head.decode("latin-1").split("\r\n")
    status = lines[0].split(None, 2)
    if len(status) < 2 or status[1] != "101":
        return f"handshake answered {lines[0]!r}"
    headers = {}
    for line in lines[1:]:
        name, _sep, value = line.partition(":")
        headers[name.strip().lower()] = value.strip()
    if headers.get("sec-websocket-accept") != _accept_for(key):
        return f"bad Sec-WebSocket-Accept {headers.get('sec-websocket-accept')!r}"
    return None


def _await_joined(conn):
    """None when a joined frame arrives within WS_REPLY_WAIT, else the reason."""
    deadline = time.monotonic() + WS_REPLY_WAIT
    message = bytearray()
    text = False
    while True:
        try:
            fin, opcode, payload = conn.read_frame(deadline)
        except socket.timeout:
            return f"no joined or error message within {WS_REPLY_WAIT} s"
        except EOFError:
            return "connection closed before joined"
        if opcode == 0x8:
            code = int.from_bytes(payload[:2], "big") if len(payload) >= 2 else None
            return f"server closed the connection (code {code})"
        if opcode == 0x1:
            message = bytearray(payload)
            text = True
        elif opcode == 0x0 and text:
            message += payload
        else:
            continue  # ping, pong, binary and anything else are skipped
        if not fin:
            continue
        text = False
        try:
            value = json.loads(bytes(message).decode("utf-8"))
        except ValueError:
            continue
        if not isinstance(value, dict):
            continue
        if value.get("type") == "joined":
            return None
        if value.get("type") == "error":
            return f"server answered error {value.get('code')!r}"


def _leave(conn):
    """Sends leave and close, then waits briefly for the server's close."""
    conn.send_frame(0x1, b'{"type":"leave"}')
    conn.send_frame(0x8, (1000).to_bytes(2, "big"))
    deadline = time.monotonic() + WS_CLOSE_WAIT
    while True:
        try:
            _fin, opcode, _payload = conn.read_frame(deadline)
        except (socket.timeout, EOFError):
            return
        if opcode == 0x8:
            return


def check_websocket(base):
    conn = _open_websocket(base)
    try:
        reason = _handshake(conn, base)
        if reason is not None:
            return reason
        conn.send_frame(0x1, b'{"type":"create","nickname":"smoke"}')
        reason = _await_joined(conn)
        try:
            _leave(conn)
        except OSError as exc:
            if reason is None:
                reason = f"could not leave: {type(exc).__name__}: {exc}"
        return reason
    finally:
        conn.sock.close()


# ---------------------------------------------------------------- main

CHECKS = [("health", check_health), ("credit", check_credit),
          ("room-link", check_room_link), ("websocket", check_websocket)]


def main(argv):
    if len(argv) != 1:
        print(USAGE, file=sys.stderr)
        return 2
    base = parse_base(argv[0])
    if base is None:
        print(USAGE, file=sys.stderr)
        return 2
    failed = False
    for name, check in CHECKS:
        try:
            reason = check(base)
        except CHECK_ERRORS as exc:
            reason = f"{type(exc).__name__}: {exc}"
        if reason is None:
            print(f"OK {name}", flush=True)
        else:
            failed = True
            print(f"FAIL {name}: {reason}", flush=True)
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
