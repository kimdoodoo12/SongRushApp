"""Shared pytest setup for the data tools.

The autouse fixture below cuts every test off from the network (cycle 013,
criterion 3). It raises RuntimeError rather than an OSError so that urllib
cannot wrap it as a URLError and a test cannot mistake it for an offline
machine.
"""

import socket

import pytest

NETWORK_DISABLED = "network disabled in tests"


def _blocked(*args, **kwargs):
    raise RuntimeError(NETWORK_DISABLED)


@pytest.fixture(autouse=True)
def no_network(monkeypatch):
    monkeypatch.setattr(socket, "getaddrinfo", _blocked)
    monkeypatch.setattr(socket.socket, "connect", _blocked)
    monkeypatch.setattr(socket.socket, "connect_ex", _blocked)
