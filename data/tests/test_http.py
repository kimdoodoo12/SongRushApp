"""Cycle 016 criteria 1-6b: PoliteClient with a fake transport and clock.

Timing is asserted on the fake clock's start times of each send, not on the
sleep calls, so any way the client chooses to wait is fine as long as the
attempts start when the spec says.

Imports of songrush_data.http stay inside fixtures and tests so that a
missing module fails these tests, not the collection.
"""

import email.utils
import threading
import time

import pytest

URL = "https://example.invalid/resource"
T0 = 1000.0
WALL0 = 1_800_000_000.0  # whole seconds, so HTTP-dates are exact


@pytest.fixture
def http():
    from songrush_data import http as module

    return module


class FakeClock:
    def __init__(self):
        self._now = T0
        self._wall = WALL0
        self.sleeps = []
        self._lock = threading.Lock()

    def now(self):
        return self._now

    def wall(self):
        return self._wall

    def sleep(self, seconds):
        with self._lock:
            self.sleeps.append(seconds)
            if seconds > 0:
                self._now += seconds
                self._wall += seconds

    def advance(self, seconds):
        self._now += seconds
        self._wall += seconds


class Call:
    def __init__(self, method, url, headers, body, timeout, start):
        self.method = method
        self.url = url
        self.headers = headers
        self.body = body
        self.timeout = timeout
        self.start = start
        self.end = None


class FakeTransport:
    """Plays a script of (Response or exception, duration) steps."""

    def __init__(self, clock, script):
        self.clock = clock
        self.script = list(script)
        self.calls = []

    def send(self, method, url, headers, body, timeout):
        call = Call(method, url, dict(headers or {}), body, timeout, self.clock.now())
        self.calls.append(call)
        if not self.script:
            raise AssertionError("transport called more times than scripted")
        outcome, duration = self.script.pop(0)
        self.clock.advance(duration)
        call.end = self.clock.now()
        if isinstance(outcome, BaseException):
            raise outcome
        return outcome


def resp(http, status, headers=None, body=b""):
    return http.Response(status=status, headers=dict(headers or {}), body=body)


def make(http, script, **kwargs):
    clock = FakeClock()
    transport = FakeTransport(clock, script)
    kwargs.setdefault("gap_s", 3.0)
    client = http.PoliteClient(transport, clock, **kwargs)
    return client, transport, clock


def user_agent_values(headers):
    return [v for k, v in headers.items() if k.lower() == "user-agent"]


# Criterion 1: User-Agent and max_attempts.

def test_every_request_sends_user_agent(http):
    from songrush_data.common import USER_AGENT

    client, transport, _ = make(http, [(resp(http, 200), 0), (resp(http, 200), 0)])
    client.request("GET", URL)
    client.request("POST", URL, headers={"X-Extra": "1"}, body=b"{}")
    for call in transport.calls:
        assert user_agent_values(call.headers) == [USER_AGENT]
    assert transport.calls[1].headers.get("X-Extra") == "1"
    assert transport.calls[1].method == "POST"
    assert transport.calls[1].body == b"{}"


@pytest.mark.parametrize("name", ["User-Agent", "user-agent", "USER-AGENT"])
def test_caller_user_agent_is_replaced(http, name):
    from songrush_data.common import USER_AGENT

    client, transport, _ = make(http, [(resp(http, 200), 0)])
    client.request("GET", URL, headers={name: "evil/1.0"})
    assert user_agent_values(transport.calls[0].headers) == [USER_AGENT]


def test_max_attempts_zero_raises(http):
    with pytest.raises(ValueError):
        http.PoliteClient(FakeTransport(FakeClock(), []), FakeClock(), gap_s=3.0, max_attempts=0)


def test_success_returns_transport_response(http):
    ok = resp(http, 200, {"content-type": "application/json"}, b'{"a":1}')
    client, transport, _ = make(http, [(ok, 0)])
    got = client.request("GET", URL)
    assert got.status == 200
    assert got.body == b'{"a":1}'
    assert transport.calls[0].url == URL


# Criterion 2: gap, as start times on the fake clock.

def test_first_request_does_not_wait(http):
    client, transport, clock = make(http, [(resp(http, 200), 5)])
    client.request("GET", URL)
    assert transport.calls[0].start == T0


def test_second_request_starts_gap_after_first_ended(http):
    client, transport, _ = make(http, [(resp(http, 200), 5), (resp(http, 200), 5)])
    client.request("GET", URL)
    client.request("GET", URL)
    first, second = transport.calls
    assert first.end == T0 + 5
    assert second.start == pytest.approx(first.end + 3)


def test_no_extra_wait_when_gap_already_elapsed(http):
    client, transport, clock = make(http, [(resp(http, 200), 1), (resp(http, 200), 1)])
    client.request("GET", URL)
    clock.advance(10)
    before = clock.now()
    client.request("GET", URL)
    assert transport.calls[1].start == pytest.approx(before)


def test_gap_and_retry_after_take_max_not_sum(http):
    script = [(resp(http, 429, {"retry-after": "1"}), 0.5), (resp(http, 200), 0.5)]
    client, transport, _ = make(http, script, gap_s=3.0)
    assert client.request("GET", URL).status == 200
    first, second = transport.calls
    assert second.start == pytest.approx(first.end + 3)


def test_transport_error_attempt_counts_as_previous(http):
    script = [(http.TransportError("boom"), 2), (resp(http, 200), 0)]
    client, transport, _ = make(http, script, gap_s=15.0)
    assert client.request("GET", URL).status == 200
    first, second = transport.calls
    # backoff 10 s vs gap 15 s: max, counted from the failed attempt's end
    assert second.start == pytest.approx(first.end + 15)


# Criterion 3: timeout.

def test_default_timeout_reaches_transport_as_at_least_60(http):
    client, transport, _ = make(http, [(resp(http, 200), 0)])
    client.request("GET", URL)
    assert transport.calls[0].timeout >= 60


def test_custom_timeout_reaches_transport(http):
    client, transport, _ = make(http, [(resp(http, 200), 0)], timeout_s=90.0)
    client.request("GET", URL)
    assert transport.calls[0].timeout == 90.0


def test_timeout_below_60_raises(http):
    clock = FakeClock()
    transport = FakeTransport(clock, [(resp(http, 200), 0)])
    with pytest.raises(ValueError):
        client = http.PoliteClient(transport, clock, gap_s=3.0, timeout_s=30.0)
        client.request("GET", URL)
    assert transport.calls == []


# Criterion 4: 429 and Retry-After.

def test_429_retry_after_seconds_then_success(http):
    script = [(resp(http, 429, {"retry-after": "7"}), 0), (resp(http, 200), 0)]
    client, transport, _ = make(http, script)
    assert client.request("GET", URL).status == 200
    first, second = transport.calls
    assert second.start == pytest.approx(first.end + 7)


def test_three_429_raise_and_next_request_waits(http):
    r429 = lambda: resp(http, 429, {"retry-after": "120"})
    script = [(r429(), 0), (r429(), 0), (r429(), 0), (resp(http, 200), 0)]
    client, transport, _ = make(http, script)
    with pytest.raises(http.HttpError) as info:
        client.request("GET", URL)
    assert info.value.status == 429
    assert info.value.attempts == 3
    assert info.value.url == URL
    assert len(transport.calls) == 3
    last_429_end = transport.calls[2].end
    client.request("GET", URL)
    assert transport.calls[3].start >= last_429_end + 120 - 1e-6


def test_429_without_retry_after_waits_30(http):
    script = [(resp(http, 429), 0), (resp(http, 200), 0)]
    client, transport, _ = make(http, script)
    client.request("GET", URL)
    first, second = transport.calls
    assert second.start == pytest.approx(first.end + 30)


def test_429_invalid_retry_after_waits_30(http):
    script = [(resp(http, 429, {"retry-after": "soon"}), 0), (resp(http, 200), 0)]
    client, transport, _ = make(http, script)
    client.request("GET", URL)
    first, second = transport.calls
    assert second.start == pytest.approx(first.end + 30)


def test_429_over_cap_refuses_and_stores_full_wait(http):
    script = [(resp(http, 429, {"retry-after": "99999"}), 0), (resp(http, 200), 0)]
    client, transport, clock = make(http, script)
    with pytest.raises(http.HttpError) as info:
        client.request("GET", URL)
    assert info.value.status == 429
    assert info.value.attempts == 1
    assert len(transport.calls) == 1
    end = transport.calls[0].end
    # Attempt-2 rule: while backed off, the next request refuses at once.
    sleeps_before = len(clock.sleeps)
    with pytest.raises(http.BackedOffError) as refused:
        client.request("GET", URL)
    assert refused.value.attempts == 0
    assert len(transport.calls) == 1
    assert [s for s in clock.sleeps[sleeps_before:] if s > 0] == []
    clock.advance(99999)
    assert client.request("GET", URL).status == 200
    assert transport.calls[1].start >= end + 99999 - 1e-6


def test_429_http_date_retry_after(http):
    date = email.utils.formatdate(WALL0 + 40, usegmt=True)
    script = [(resp(http, 429, {"retry-after": date}), 0), (resp(http, 200), 0)]
    client, transport, _ = make(http, script)
    assert client.request("GET", URL).status == 200
    first, second = transport.calls
    assert second.start == pytest.approx(first.end + 40)


def test_429_past_http_date_waits_only_the_gap(http):
    date = email.utils.formatdate(WALL0 - 100, usegmt=True)
    script = [(resp(http, 429, {"retry-after": date}), 0), (resp(http, 200), 0)]
    client, transport, _ = make(http, script, gap_s=3.0)
    assert client.request("GET", URL).status == 200
    first, second = transport.calls
    assert second.start == pytest.approx(first.end + 3)


# Criterion 5: 5xx and transport errors.

def test_5xx_backs_off_10_then_20(http):
    script = [(resp(http, 503), 0), (resp(http, 503), 0), (resp(http, 200), 0)]
    logs = []
    client, transport, _ = make(http, script, log=logs.append)
    assert client.request("GET", URL).status == 200
    a, b, c = transport.calls
    assert b.start == pytest.approx(a.end + 10)
    assert c.start == pytest.approx(b.end + 20)
    assert len(logs) == 2


def test_5xx_last_backoff_value_repeats(http):
    script = [(resp(http, 500), 0)] * 3 + [(resp(http, 200), 0)]
    client, transport, _ = make(http, script, max_attempts=4)
    assert client.request("GET", URL).status == 200
    calls = transport.calls
    assert calls[1].start == pytest.approx(calls[0].end + 10)
    assert calls[2].start == pytest.approx(calls[1].end + 20)
    assert calls[3].start == pytest.approx(calls[2].end + 20)


def test_three_5xx_raise_http_error(http):
    script = [(resp(http, 502), 0)] * 3
    client, transport, _ = make(http, script)
    with pytest.raises(http.HttpError) as info:
        client.request("GET", URL)
    assert info.value.status == 502
    assert info.value.attempts == 3
    assert len(transport.calls) == 3


def test_three_transport_errors_reraise(http):
    script = [(http.TransportError("down"), 1)] * 3
    client, transport, _ = make(http, script)
    with pytest.raises(http.TransportError):
        client.request("GET", URL)
    assert len(transport.calls) == 3


def test_http_error_is_not_urllib_http_error(http):
    import urllib.error

    assert not issubclass(http.HttpError, urllib.error.HTTPError)


# Criterion 6: other 4xx.

@pytest.mark.parametrize("status", [403, 404])
def test_other_4xx_raise_after_one_attempt(http, status):
    client, transport, _ = make(http, [(resp(http, status), 0)])
    with pytest.raises(http.HttpError) as info:
        client.request("GET", URL)
    assert info.value.status == status
    assert info.value.attempts == 1
    assert info.value.url == URL
    assert len(transport.calls) == 1


# Criterion 6b: max_attempts=1 (the AniDB rule).

@pytest.mark.parametrize("kind", ["429", "503", "transport"])
def test_single_attempt_sends_once_without_sleeping(http, kind):
    if kind == "429":
        outcome = resp(http, 429, {"retry-after": "5"})
        expected = http.HttpError
    elif kind == "503":
        outcome = resp(http, 503)
        expected = http.HttpError
    else:
        outcome = http.TransportError("refused")
        expected = http.TransportError
    client, transport, clock = make(http, [(outcome, 0)], max_attempts=1)
    with pytest.raises(expected):
        client.request("GET", URL)
    assert len(transport.calls) == 1
    assert [s for s in clock.sleeps if s > 0] == []
    assert clock.now() == T0


def test_single_attempt_success_returns(http):
    client, transport, _ = make(http, [(resp(http, 200), 0)], max_attempts=1)
    assert client.request("GET", URL).status == 200
    assert len(transport.calls) == 1


# One at a time: concurrent callers never overlap inside send.

def test_requests_are_sequential_across_threads(http):
    clock = FakeClock()
    state = {"inside": 0, "max": 0}
    guard = threading.Lock()

    class SlowTransport:
        def send(self, method, url, headers, body, timeout):
            with guard:
                state["inside"] += 1
                state["max"] = max(state["max"], state["inside"])
            time.sleep(0.05)
            with guard:
                state["inside"] -= 1
            return http.Response(status=200, headers={}, body=b"")

    client = http.PoliteClient(SlowTransport(), clock, gap_s=0.0)
    threads = [threading.Thread(target=client.request, args=("GET", URL)) for _ in range(4)]
    for t in threads:
        t.start()
    for t in threads:
        t.join(5)
    assert state["max"] == 1
