"""Cycle 016 criterion 9: the test network guard lets loopback through only."""

import asyncio
import socket

import pytest


def test_socketpair_works():
    a, b = socket.socketpair()
    with a, b:
        a.sendall(b"ping")
        assert b.recv(4) == b"ping"


def test_asyncio_event_loop_runs():
    async def main():
        await asyncio.sleep(0)
        return 42

    assert asyncio.run(main()) == 42


@pytest.mark.parametrize("host", ["localhost", "127.0.0.1"])
def test_getaddrinfo_allows_loopback_names(host):
    infos = socket.getaddrinfo(host, 80, 0, socket.SOCK_STREAM)
    assert infos


def test_connect_to_loopback_listener_works():
    with socket.socket() as listener:
        listener.bind(("127.0.0.1", 0))
        listener.listen(1)
        with socket.socket() as client:
            client.connect(listener.getsockname())
            conn, _ = listener.accept()
            conn.close()


def test_getaddrinfo_example_com_is_blocked():
    with pytest.raises(RuntimeError, match="network disabled in tests"):
        socket.getaddrinfo("example.com", 80)


def test_getaddrinfo_keyword_host_is_blocked():
    with pytest.raises(RuntimeError, match="network disabled in tests"):
        socket.getaddrinfo(host="example.com", port=80)


@pytest.mark.parametrize("address", [("10.0.0.1", 80), ("10.0.0.1", 9), ("8.8.8.8", 53)])
def test_connect_to_non_loopback_is_blocked(address):
    with socket.socket() as sock:
        with pytest.raises(RuntimeError, match="network disabled in tests"):
            sock.connect(address)


@pytest.mark.parametrize("address", [("10.0.0.1", 80), ("10.0.0.1", 9)])
def test_connect_ex_to_non_loopback_is_blocked(address):
    with socket.socket() as sock:
        with pytest.raises(RuntimeError, match="network disabled in tests"):
            sock.connect_ex(address)


def test_connect_by_hostname_is_blocked():
    with socket.socket() as sock:
        with pytest.raises(RuntimeError, match="network disabled in tests"):
            sock.connect(("example.com", 80))
