"""Cycle 016 attempt 2: PoliteClient hardening after the critic review.

Pins H1 (a stored wait over the cap refuses with BackedOffError instead of
sleeping silently; taken waits are logged once; huge Retry-After values do
not overflow), M1 (TransportError backoff timing), N3 (Retry-After cap
boundary and naive HTTP-dates read as UTC) and N4 (the thread test checks
that every thread finished without raising).

The fake clock and transport are copied from test_http.py on purpose: test
files do not import each other. Imports of songrush_data.http stay inside
fixtures so a missing name fails the tests, not the collection.
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


def positive(sleeps):
    return [s for s in sleeps if s > 0]


def waiting_lines(logs):
    return [line for line in logs if "waiting" in line]


# H1: a stored wait over the cap refuses before sending or sleeping.

def test_backed_off_error_is_http_error_subclass(http):
    assert issubclass(http.BackedOffError, http.HttpError)


def test_next_request_after_over_cap_refusal_raises_backed_off(http):
    script = [(resp(http, 429, {"retry-after": "86400"}), 0), (resp(http, 200), 0)]
    client, transport, clock = make(http, script)
    with pytest.raises(http.HttpError) as first:
        client.request("GET", URL)
    assert first.value.status == 429
    assert first.value.attempts == 1
    assert len(transport.calls) == 1

    sleeps_before = len(clock.sleeps)
    with pytest.raises(http.BackedOffError) as info:
        client.request("GET", URL)
    assert isinstance(info.value, http.HttpError)
    assert info.value.status == 429
    assert info.value.attempts == 0
    assert info.value.url == URL
    assert len(transport.calls) == 1
    assert positive(clock.sleeps[sleeps_before:]) == []


def test_backed_off_refusal_repeats_without_sending(http):
    script = [(resp(http, 429, {"retry-after": "86400"}), 0)]
    client, transport, clock = make(http, script)
    with pytest.raises(http.HttpError):
        client.request("GET", URL)
    for _ in range(3):
        with pytest.raises(http.BackedOffError):
            client.request("GET", URL)
    assert len(transport.calls) == 1
    assert positive(clock.sleeps) == []


def test_retry_after_exactly_cap_is_waited_within_request(http):
    script = [(resp(http, 429, {"retry-after": "300"}), 0), (resp(http, 200), 0)]
    client, transport, _ = make(http, script, max_retry_after_s=300.0)
    assert client.request("GET", URL).status == 200
    first, second = transport.calls
    assert second.start == pytest.approx(first.end + 300)


def test_retry_after_one_over_cap_refuses(http):
    script = [(resp(http, 429, {"retry-after": "301"}), 0), (resp(http, 200), 0)]
    client, transport, _ = make(http, script, max_retry_after_s=300.0)
    with pytest.raises(http.HttpError) as info:
        client.request("GET", URL)
    assert info.value.status == 429
    assert info.value.attempts == 1
    assert len(transport.calls) == 1


def test_stored_wait_at_cap_is_waited_by_next_request(http):
    script = [(resp(http, 429, {"retry-after": "300"}), 0), (resp(http, 200), 0)]
    client, transport, _ = make(http, script, max_attempts=1, max_retry_after_s=300.0)
    with pytest.raises(http.HttpError):
        client.request("GET", URL)
    end = transport.calls[0].end
    assert client.request("GET", URL).status == 200
    assert transport.calls[1].start == pytest.approx(end + 300)


def test_stored_wait_below_cap_is_waited_by_next_request(http):
    script = [(resp(http, 429, {"retry-after": "120"}), 0), (resp(http, 200), 0)]
    client, transport, _ = make(http, script, max_attempts=1)
    with pytest.raises(http.HttpError):
        client.request("GET", URL)
    end = transport.calls[0].end
    assert client.request("GET", URL).status == 200
    assert transport.calls[1].start == pytest.approx(end + 120)


# H1: every wait the client takes is logged exactly once.

def test_stored_wait_taken_by_next_request_is_logged_once(http):
    logs = []
    script = [(resp(http, 429, {"retry-after": "100"}), 0), (resp(http, 200), 0)]
    client, transport, _ = make(http, script, gap_s=0.0, max_attempts=1, log=logs.append)
    with pytest.raises(http.HttpError):
        client.request("GET", URL)
    before = len(logs)
    assert client.request("GET", URL).status == 200
    assert transport.calls[1].start == pytest.approx(transport.calls[0].end + 100)
    assert len(waiting_lines(logs[before:])) == 1


def test_retry_wait_inside_request_is_logged_once(http):
    logs = []
    script = [(resp(http, 429, {"retry-after": "7"}), 0), (resp(http, 200), 0)]
    client, _, _ = make(http, script, gap_s=0.0, log=logs.append)
    assert client.request("GET", URL).status == 200
    assert len(waiting_lines(logs)) == 1


# H1: a huge Retry-After is clamped, never OverflowError.

HUGE = "1" + "0" * 399  # 400 digits


def test_huge_retry_after_raises_http_error_not_overflow(http):
    script = [(resp(http, 429, {"retry-after": HUGE}), 0), (resp(http, 200), 0)]
    client, transport, _ = make(http, script)
    try:
        client.request("GET", URL)
    except OverflowError as error:  # pragma: no cover - the failure being pinned
        pytest.fail("OverflowError escaped: %r" % (error,))
    except http.HttpError as error:
        assert error.status == 429
    else:
        pytest.fail("a 429 with a huge Retry-After must raise HttpError")
    assert len(transport.calls) == 1


def test_huge_retry_after_then_next_request_backs_off(http):
    script = [(resp(http, 429, {"retry-after": HUGE}), 0), (resp(http, 200), 0)]
    client, transport, clock = make(http, script)
    with pytest.raises(http.HttpError):
        client.request("GET", URL)
    with pytest.raises(http.BackedOffError) as info:
        client.request("GET", URL)
    assert info.value.attempts == 0
    assert len(transport.calls) == 1
    assert positive(clock.sleeps) == []


# M1: TransportError backoff timing with the default backoff.

def test_transport_error_backoff_10_then_20(http):
    script = [
        (http.TransportError("refused"), 1),
        (http.TransportError("reset"), 2),
        (resp(http, 200), 0),
    ]
    client, transport, _ = make(http, script, gap_s=3.0)
    assert client.request("GET", URL).status == 200
    a, b, c = transport.calls
    assert b.start == pytest.approx(a.end + 10)
    assert c.start == pytest.approx(b.end + 20)


# N3: naive HTTP-dates (-0000 or no zone) are read as UTC.

@pytest.mark.parametrize("zone", [" -0000", ""])
def test_naive_http_date_is_utc(http, zone):
    gmt = email.utils.formatdate(WALL0 + 40, usegmt=True)
    assert gmt.endswith(" GMT")
    date = gmt[: -len(" GMT")] + zone
    script = [(resp(http, 429, {"retry-after": date}), 0), (resp(http, 200), 0)]
    client, transport, _ = make(http, script, gap_s=3.0)
    assert client.request("GET", URL).status == 200
    first, second = transport.calls
    assert second.start == pytest.approx(first.end + 40)


# N4: concurrent callers never overlap, and every thread finishes cleanly.

def test_threads_all_finish_without_errors_and_never_overlap(http):
    clock = FakeClock()
    state = {"inside": 0, "max": 0, "sent": 0}
    guard = threading.Lock()

    class SlowTransport:
        def send(self, method, url, headers, body, timeout):
            with guard:
                state["inside"] += 1
                state["sent"] += 1
                state["max"] = max(state["max"], state["inside"])
            time.sleep(0.05)
            with guard:
                state["inside"] -= 1
            return http.Response(status=200, headers={}, body=b"")

    client = http.PoliteClient(SlowTransport(), clock, gap_s=0.0)
    errors = []
    results = []

    def worker():
        try:
            results.append(client.request("GET", URL).status)
        except BaseException as error:  # collected and asserted below
            errors.append(error)

    threads = [threading.Thread(target=worker) for _ in range(4)]
    for t in threads:
        t.start()
    for t in threads:
        t.join(5)
    assert [t.is_alive() for t in threads] == [False] * 4
    assert errors == []
    assert results == [200] * 4
    assert state["sent"] == 4
    assert state["max"] == 1
