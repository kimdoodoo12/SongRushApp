"""Polite HTTP client and file cache shared by the data tools (cycle 016).

PoliteClient enforces the CLAUDE.md external source rules in one place: the
SongRushBot User-Agent, one request at a time, a gap between requests, a
timeout of at least 60 s, Retry-After on 429, backoff on 5xx and transport
errors, and no retry on other 4xx. FileCache stores responses atomically.
docs/cycles/016-polite-http.md is the specification.
"""

import email.utils
import http.client
import json
import os
import re
import threading
import time
import urllib.error
import urllib.request
import uuid
from dataclasses import dataclass, field
from datetime import timezone
from pathlib import Path
from typing import Callable, Protocol

from songrush_data.common import USER_AGENT

MIN_TIMEOUT_S = 60.0
DEFAULT_RETRY_AFTER_S = 30.0


@dataclass
class Response:
    status: int
    headers: dict[str, str] = field(default_factory=dict)  # lower-case keys
    body: bytes = b""


class TransportError(Exception):
    """The request did not produce an HTTP response (refused, timeout, cut short)."""


class HttpError(Exception):
    """A request ended with a non-2xx status. Not related to urllib's HTTPError."""

    def __init__(self, status: int, url: str, attempts: int):
        super().__init__("HTTP %d for %s after %d attempt(s)" % (status, url, attempts))
        self.status = status
        self.url = url
        self.attempts = attempts


class Clock(Protocol):
    def now(self) -> float: ...

    def wall(self) -> float: ...

    def sleep(self, seconds: float) -> None: ...


class SystemClock:
    """The real clock: monotonic time, epoch time and time.sleep."""

    def now(self) -> float:
        return time.monotonic()

    def wall(self) -> float:
        return time.time()

    def sleep(self, seconds: float) -> None:
        time.sleep(seconds)


class Transport(Protocol):
    def send(
        self,
        method: str,
        url: str,
        headers: dict[str, str],
        body: bytes | None,
        timeout: float,
    ) -> Response: ...


def _lower_headers(items) -> dict[str, str]:
    headers: dict[str, str] = {}
    for name, value in items:
        key = name.lower()
        headers[key] = headers[key] + ", " + value if key in headers else value
    return headers


class UrllibTransport:
    """Transport on urllib.request. Returns every status as a Response.

    Accept-Encoding is not sent, so bodies arrive uncompressed. Bodies are
    read whole into memory.
    """

    def send(self, method, url, headers, body, timeout) -> Response:
        # Headers go through Request(headers=...) so redirects copy them.
        request = urllib.request.Request(url, data=body, headers=dict(headers or {}), method=method)
        # A fresh opener per call reads the current proxy environment.
        opener = urllib.request.build_opener()
        try:
            with opener.open(request, timeout=timeout) as reply:
                data = reply.read()
                return Response(status=reply.status, headers=_lower_headers(reply.headers.items()), body=data)
        except urllib.error.HTTPError as error:
            # HTTPError subclasses URLError and OSError, so it must come first.
            try:
                data = error.read()
            except (OSError, http.client.HTTPException) as read_error:
                raise TransportError("%s %s: error body cut short: %r" % (method, url, read_error)) from read_error
            finally:
                error.close()
            return Response(status=error.code, headers=_lower_headers(error.headers.items()), body=data)
        except (urllib.error.URLError, OSError, http.client.HTTPException) as error:
            raise TransportError("%s %s: %r" % (method, url, error)) from error


def _header(headers: dict[str, str], name: str) -> str | None:
    for key, value in headers.items():
        if key.lower() == name:
            return value
    return None


def _retry_after_s(value: str | None, wall: float) -> float:
    """Seconds to wait for a Retry-After value; missing or invalid means 30 s."""
    if value is None:
        return DEFAULT_RETRY_AFTER_S
    value = value.strip()
    if value.isascii() and value.isdigit():
        return float(int(value))
    try:
        when = email.utils.parsedate_to_datetime(value)
    except (TypeError, ValueError, IndexError):
        return DEFAULT_RETRY_AFTER_S
    if when is None:
        return DEFAULT_RETRY_AFTER_S
    if when.tzinfo is None:
        when = when.replace(tzinfo=timezone.utc)
    return max(0.0, when.timestamp() - wall)


class PoliteClient:
    def __init__(
        self,
        transport: Transport,
        clock: Clock,
        *,
        gap_s: float,
        timeout_s: float = 60.0,
        max_attempts: int = 3,
        backoff_s: tuple[float, ...] = (10.0, 20.0),
        max_retry_after_s: float = 300.0,
        log: Callable[[str], None] | None = None,
    ):
        if max_attempts < 1:
            raise ValueError("max_attempts must be at least 1, got %r" % (max_attempts,))
        if timeout_s < MIN_TIMEOUT_S:
            raise ValueError("timeout_s must be at least %g, got %r" % (MIN_TIMEOUT_S, timeout_s))
        if gap_s < 0:
            raise ValueError("gap_s must not be negative, got %r" % (gap_s,))
        if not backoff_s:
            raise ValueError("backoff_s must have at least one value")
        self._transport = transport
        self._clock = clock
        self._gap_s = float(gap_s)
        self._timeout_s = float(timeout_s)
        self._max_attempts = max_attempts
        self._backoff_s = tuple(float(s) for s in backoff_s)
        self._max_retry_after_s = float(max_retry_after_s)
        self._log = log
        self._lock = threading.Lock()
        # Earliest monotonic time the next attempt may start; None before the first.
        self._not_before: float | None = None

    def request(self, method: str, url: str, *, headers=None, body=None) -> Response:
        sent = {k: v for k, v in (headers or {}).items() if k.lower() != "user-agent"}
        sent["User-Agent"] = USER_AGENT
        with self._lock:
            for attempt in range(1, self._max_attempts + 1):
                self._wait_turn()
                last = attempt == self._max_attempts
                # Every attempt counts as "previous", whatever its outcome.
                self._not_before = None
                try:
                    reply = self._transport.send(method, url, dict(sent), body, self._timeout_s)
                except TransportError as error:
                    if last:
                        raise
                    wait = self._backoff(attempt)
                    self._schedule(wait)
                    self._note("retry %d/%d for %s %s after transport error (%s), waiting %g s"
                               % (attempt, self._max_attempts, method, url, error, wait))
                    continue
                finally:
                    if self._not_before is None:
                        self._schedule(0.0)

                status = reply.status
                if 200 <= status < 300:
                    return reply
                if status == 429:
                    wait = _retry_after_s(_header(reply.headers, "retry-after"), self._clock.wall())
                    # Store the full not-before time, even over the cap.
                    self._schedule(wait)
                    if wait > self._max_retry_after_s or last:
                        raise HttpError(status, url, attempt)
                    self._note("retry %d/%d for %s %s after 429, waiting %g s"
                               % (attempt, self._max_attempts, method, url, wait))
                    continue
                if 500 <= status < 600:
                    if last:
                        raise HttpError(status, url, attempt)
                    wait = self._backoff(attempt)
                    self._schedule(wait)
                    self._note("retry %d/%d for %s %s after %d, waiting %g s"
                               % (attempt, self._max_attempts, method, url, status, wait))
                    continue
                raise HttpError(status, url, attempt)
        raise AssertionError("unreachable: the retry loop always returns or raises")

    def _wait_turn(self) -> None:
        if self._not_before is None:
            return
        delay = self._not_before - self._clock.now()
        if delay > 0:
            self._clock.sleep(delay)

    def _schedule(self, wait: float) -> None:
        """Set the next start to this attempt's end plus max(gap, wait)."""
        self._not_before = self._clock.now() + max(self._gap_s, wait)

    def _backoff(self, attempt: int) -> float:
        return self._backoff_s[min(attempt - 1, len(self._backoff_s) - 1)]

    def _note(self, message: str) -> None:
        if self._log is not None:
            self._log(message)


_KEY_PATTERN = re.compile(r"^[a-z0-9][a-z0-9._-]{0,119}$")
_RESERVED_NAMES = frozenset(
    {"con", "prn", "aux", "nul"}
    | {"com%d" % i for i in range(1, 10)}
    | {"lpt%d" % i for i in range(1, 10)}
)
_REPLACE_RETRY_SLEEPS = (0.1, 0.3, 1.0)


def _is_valid_key(key) -> bool:
    if not isinstance(key, str) or not _KEY_PATTERN.fullmatch(key):
        return False
    if key.endswith("."):
        return False
    return key.split(".", 1)[0] not in _RESERVED_NAMES


def _check_key(key) -> str:
    if not _is_valid_key(key):
        raise ValueError("invalid cache key: %r" % (key,))
    return key


class FileCache:
    """One file per key under root. Writes are atomic (temp file + os.replace)."""

    def __init__(self, root: Path, *, sleep: Callable[[float], None] = time.sleep):
        self.root = Path(root)
        self._sleep = sleep

    def _path(self, key: str) -> Path:
        return self.root / _check_key(key)

    def get(self, key: str) -> bytes | None:
        path = self._path(key)
        try:
            return path.read_bytes()
        except FileNotFoundError:
            return None

    def put(self, key: str, data: bytes) -> None:
        path = self._path(key)
        self.root.mkdir(parents=True, exist_ok=True)
        # The temp name starts with "~", so it never matches the key pattern.
        temp = self.root / ("~tmp-" + uuid.uuid4().hex)
        try:
            with open(temp, "wb") as handle:
                handle.write(data)
                handle.flush()
                os.fsync(handle.fileno())
            self._replace(temp, path)
        except BaseException:
            temp.unlink(missing_ok=True)
            raise

    def _replace(self, temp: Path, path: Path) -> None:
        # Windows Defender can hold the file briefly; retry PermissionError.
        for delay in _REPLACE_RETRY_SLEEPS:
            try:
                os.replace(temp, path)
                return
            except PermissionError:
                self._sleep(delay)
        os.replace(temp, path)

    def delete(self, key: str) -> None:
        self._path(key).unlink(missing_ok=True)

    def clear(self, prefix: str) -> int:
        if not isinstance(prefix, str) or prefix == "":
            raise ValueError("clear needs a non-empty prefix")
        if not self.root.is_dir():
            return 0
        count = 0
        for path in self.root.iterdir():
            name = path.name
            if _is_valid_key(name) and name.startswith(prefix) and path.is_file():
                path.unlink(missing_ok=True)
                count += 1
        return count


def _parse_json(data):
    try:
        return True, json.loads(data)
    except (ValueError, TypeError):
        return False, None


def cached_json(cache: FileCache, key: str, fetch: Callable[[], bytes]) -> object:
    """Return the JSON cached under key, fetching and storing it when missing or corrupt."""
    data = cache.get(key)
    if data is not None:
        ok, value = _parse_json(data)
        if ok:
            return value
        cache.delete(key)
    data = fetch()
    ok, value = _parse_json(data)
    if not ok:
        raise ValueError("fetched data for cache key %r is not valid JSON" % (key,))
    cache.put(key, data)
    return value
