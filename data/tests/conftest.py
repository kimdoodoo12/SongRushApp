"""Shared pytest setup for the data tools.

The autouse fixture below cuts every test off from the network (cycle 013,
criterion 3). It raises RuntimeError rather than an OSError so that urllib
cannot wrap it as a URLError and a test cannot mistake it for an offline
machine.

Cycle 016 lets loopback through, so tests can use socket.socketpair(),
asyncio on Windows and a local http.server:
- getaddrinfo for localhost, 127.0.0.1 and ::1 is allowed;
- connect and connect_ex to 127.0.0.0/8 and ::1 are allowed;
- everything else still raises RuntimeError.
"""

import ipaddress
import socket

import pytest

NETWORK_DISABLED = "network disabled in tests"

LOOPBACK_HOSTS = {"localhost", "127.0.0.1", "::1"}

_real_getaddrinfo = socket.getaddrinfo
_real_connect = socket.socket.connect
_real_connect_ex = socket.socket.connect_ex


def _host_text(host):
    if isinstance(host, (bytes, bytearray)):
        try:
            return bytes(host).decode("ascii")
        except UnicodeDecodeError:
            return None
    if isinstance(host, str):
        return host
    return None


def _is_loopback_host(host):
    text = _host_text(host)
    return text is not None and text.lower() in LOOPBACK_HOSTS


def _is_loopback_address(address):
    """True only for an (ip, port[, ...]) tuple whose ip is loopback."""
    if not isinstance(address, tuple) or not address:
        return False
    text = _host_text(address[0])
    if text is None:
        return False
    try:
        ip = ipaddress.ip_address(text.split("%", 1)[0])
    except ValueError:
        return False
    if ip.version == 4:
        return ip in ipaddress.ip_network("127.0.0.0/8")
    return ip == ipaddress.ip_address("::1")


def _guarded_getaddrinfo(*args, **kwargs):
    host = args[0] if args else kwargs.get("host")
    if _is_loopback_host(host):
        return _real_getaddrinfo(*args, **kwargs)
    raise RuntimeError(NETWORK_DISABLED)


def _guarded_connect(self, address):
    if _is_loopback_address(address):
        return _real_connect(self, address)
    raise RuntimeError(NETWORK_DISABLED)


def _guarded_connect_ex(self, address):
    if _is_loopback_address(address):
        return _real_connect_ex(self, address)
    raise RuntimeError(NETWORK_DISABLED)


@pytest.fixture(autouse=True)
def no_network(monkeypatch):
    monkeypatch.setattr(socket, "getaddrinfo", _guarded_getaddrinfo)
    monkeypatch.setattr(socket.socket, "connect", _guarded_connect)
    monkeypatch.setattr(socket.socket, "connect_ex", _guarded_connect_ex)
