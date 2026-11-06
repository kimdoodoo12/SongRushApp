"""Cycle 016 criterion 6c: UrllibTransport against a loopback http.server.

The test network guard allows loopback (127.0.0.0/8), so these tests talk to
a real ThreadingHTTPServer on 127.0.0.1 with a port chosen by the OS.
"""

import socket
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import pytest

TIMEOUT = 5.0
TEST_UA = "SongRushBot-test/1 (+loopback)"


class Handler(BaseHTTPRequestHandler):
    def log_message(self, format, *args):
        pass

    def _send(self, status, body=b"", headers=None):
        self.send_response(status)
        for name, value in (headers or {}).items():
            self.send_header(name, value)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path == "/ok":
            self._send(200, b"hello", {"X-Test": "yes"})
        elif self.path == "/404":
            self._send(404, b"not here")
        elif self.path == "/429":
            self._send(429, b"slow down", {"Retry-After": "5"})
        elif self.path == "/redirect":
            self._send(302, b"", {"Location": "/echo-ua"})
        elif self.path == "/echo-ua":
            ua = self.headers.get("User-Agent", "")
            self._send(200, ua.encode("utf-8"))
        elif self.path == "/truncated":
            self.send_response(200)
            self.send_header("Content-Length", "1000")
            self.end_headers()
            self.wfile.write(b"0123456789")
            self.wfile.flush()
            self.close_connection = True
        else:
            self._send(500, b"unknown path")


@pytest.fixture
def no_proxy(monkeypatch):
    # Keep any environment or registry proxy out of loopback requests.
    for name in ("http_proxy", "HTTP_PROXY", "https_proxy", "HTTPS_PROXY", "all_proxy", "ALL_PROXY"):
        monkeypatch.delenv(name, raising=False)
    monkeypatch.setenv("no_proxy", "*")


@pytest.fixture
def server(no_proxy):
    httpd = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    httpd.daemon_threads = True
    thread = threading.Thread(target=httpd.serve_forever, kwargs={"poll_interval": 0.05}, daemon=True)
    thread.start()
    try:
        yield "http://127.0.0.1:%d" % httpd.server_address[1]
    finally:
        httpd.shutdown()
        httpd.server_close()
        thread.join(5)


@pytest.fixture
def transport():
    from songrush_data.http import UrllibTransport

    return UrllibTransport()


def ua_headers():
    return {"User-Agent": TEST_UA}


def test_200_returns_response_with_lowercase_headers(server, transport):
    from songrush_data.http import Response

    got = transport.send("GET", server + "/ok", ua_headers(), None, TIMEOUT)
    assert isinstance(got, Response)
    assert got.status == 200
    assert got.body == b"hello"
    assert got.headers["x-test"] == "yes"
    assert all(k == k.lower() for k in got.headers)


def test_404_is_returned_not_raised(server, transport):
    got = transport.send("GET", server + "/404", ua_headers(), None, TIMEOUT)
    assert got.status == 404
    assert got.body == b"not here"


def test_429_keeps_retry_after_header(server, transport):
    got = transport.send("GET", server + "/429", ua_headers(), None, TIMEOUT)
    assert got.status == 429
    assert got.headers["retry-after"] == "5"


def test_redirect_keeps_user_agent(server, transport):
    got = transport.send("GET", server + "/redirect", ua_headers(), None, TIMEOUT)
    assert got.status == 200
    assert got.body == TEST_UA.encode("utf-8")


def test_truncated_body_raises_transport_error(server, transport):
    from songrush_data.http import TransportError

    with pytest.raises(TransportError):
        transport.send("GET", server + "/truncated", ua_headers(), None, TIMEOUT)


def test_refused_port_raises_transport_error(no_proxy, transport):
    from songrush_data.http import TransportError

    with socket.socket() as probe:
        probe.bind(("127.0.0.1", 0))
        port = probe.getsockname()[1]
    with pytest.raises(TransportError):
        transport.send("GET", "http://127.0.0.1:%d/" % port, ua_headers(), None, TIMEOUT)
