"""Unit tests for the round tag of /audio/ and /cover/ requests (cycle 107).

Run from the repo root: data/.venv/Scripts/python.exe -m pytest perf
Same style as test_between.py: a stub playwright.sync_api when the real one
is missing, a fake clock, frames fed straight into Recorder's handlers, and
fake request objects (url, redirected_from) passed to Recorder._on_request.
No browser, no network.

Bug: with prefetch-on-reveal the server sends reveal(N), scores and
prepare(N+1) back to back. The page requests round N's reveal cover (the
reveal frame's coverUrl) only after prepare(N+1) arrived, and Recorder
tagged every request with self.current (the latest prepare), so the reveal
cover was counted in round N+1.

Contract
--------
Recorder._on_request(req) for a page-made /cover/ request: if the request
path (query and host ignored) equals the path of a cover a known round
announced (its prepare choices' coverUrl or its reveal coverUrl), the tag is
that round; otherwise (no known cover matches) the tag is self.current, as
before. /audio/ requests keep the current round. Paths are compared without
query strings (presigned queries differ between frame and request).
"""

import json
import sys
import types

import pytest

# --- importing run.py without Playwright ------------------------------------


def _stub_playwright():
    try:
        import playwright.sync_api  # noqa: F401
        return
    except ImportError:
        pass
    pkg = types.ModuleType("playwright")
    api = types.ModuleType("playwright.sync_api")

    class Error(Exception):
        pass

    def sync_playwright():
        raise RuntimeError("stub: no browser in unit tests")

    api.Error = Error
    api.sync_playwright = sync_playwright
    pkg.sync_api = api
    sys.modules.setdefault("playwright", pkg)
    sys.modules.setdefault("playwright.sync_api", api)


@pytest.fixture
def run(monkeypatch):
    _stub_playwright()
    import run as run_module
    clock = {"t": 0.0}
    monkeypatch.setattr(run_module, "now_ms", lambda: clock["t"])
    monkeypatch.setattr(run_module, "wall_ms", lambda: 1_700_000_000_000.0 + clock["t"])
    monkeypatch.setattr(run_module, "_test_clock", clock, raising=False)
    return run_module


class FakeRequest:
    """The parts of a Playwright request that _on_request reads."""

    def __init__(self, url, redirected_from=None):
        self.url = url
        self.redirected_from = redirected_from
        self.redirected_to = None

    def __repr__(self):
        return f"FakeRequest({self.url!r})"


BASE = "http://localhost:8080"

# Frame URLs are relative with one presigned query; the page's request is
# absolute with another, so only the path can match.
COVER_A = "/cover/aaa111.jpg"
COVER_B = "/cover/bbb222.jpg"
COVER_R = "/cover/rrr333.jpg"
AUDIO_N = "/audio/tok-n"
AUDIO_N1 = "/audio/tok-n1"


class Feed:
    def __init__(self, run_module):
        self.run = run_module
        self.rec = run_module.Recorder()

    def recv(self, t, msg):
        self.run._test_clock["t"] = float(t)
        self.rec._on_received(json.dumps(msg))

    def prepare(self, t, index, audio, covers):
        self.recv(t, {
            "type": "prepare",
            "index": index,
            "audioUrl": audio + "?sig=frame",
            "choices": [{"coverUrl": c + "?X-Amz-Signature=frame"} for c in covers],
        })

    def start(self, t, index):
        self.recv(t, {"type": "start", "index": index,
                      "startAt": 10_000 + t, "serverTime": 7_000 + t})

    def reveal(self, t, index, cover):
        self.recv(t, {"type": "reveal", "index": index,
                      "coverUrl": cover + "?X-Amz-Signature=reveal"})

    def scores(self, t):
        self.recv(t, {"type": "scores", "scores": []})

    def request(self, t, path):
        self.run._test_clock["t"] = float(t)
        req = FakeRequest(BASE + path + "?X-Amz-Signature=page&t=1")
        self.rec._on_request(req)
        return req

    def tag(self, req):
        return self.rec.tags.get(req)


N, N1 = 3, 4


# --- (a) early order: reveal N, scores, prepare N+1, then the reveal cover ---

def test_early_order_reveal_cover_tagged_with_revealed_round(run):
    f = Feed(run)
    f.prepare(0, N, AUDIO_N, [COVER_A])
    f.start(1000, N)
    f.reveal(20_000, N, COVER_R)
    f.scores(20_001)
    f.prepare(20_002, N1, AUDIO_N1, [COVER_B])
    r = f.request(20_050, COVER_R)
    assert f.rec.current == N1
    assert f.tag(r) == N


def test_early_order_next_round_requests_tagged_with_next_round(run):
    f = Feed(run)
    f.prepare(0, N, AUDIO_N, [COVER_A])
    f.start(1000, N)
    f.reveal(20_000, N, COVER_R)
    f.scores(20_001)
    f.prepare(20_002, N1, AUDIO_N1, [COVER_B])
    r = f.request(20_050, COVER_R)
    b = f.request(20_060, COVER_B)
    audio = f.request(20_070, AUDIO_N1)
    assert f.tag(r) == N
    assert f.tag(b) == N1
    assert f.tag(audio) == N1


def test_early_order_prepare_cover_of_round_n_still_round_n(run):
    # a late request for a cover of round N's own choices, after prepare N+1
    f = Feed(run)
    f.prepare(0, N, AUDIO_N, [COVER_A])
    f.reveal(20_000, N, COVER_R)
    f.prepare(20_002, N1, AUDIO_N1, [COVER_B])
    a = f.request(20_050, COVER_A)
    assert f.tag(a) == N


# --- (b) default order: reveal N, request R, prepare N+1 ---------------------

def test_default_order_reveal_cover_tagged_with_revealed_round(run):
    f = Feed(run)
    f.prepare(0, N, AUDIO_N, [COVER_A])
    f.start(1000, N)
    f.reveal(20_000, N, COVER_R)
    r = f.request(20_050, COVER_R)
    f.prepare(25_000, N1, AUDIO_N1, [COVER_B])
    b = f.request(25_010, COVER_B)
    assert f.tag(r) == N
    assert f.tag(b) == N1


# --- (c) unknown cover falls back to the current round -----------------------

def test_unknown_cover_falls_back_to_current_round(run):
    f = Feed(run)
    f.prepare(0, N, AUDIO_N, [COVER_A])
    f.reveal(20_000, N, COVER_R)
    f.prepare(20_002, N1, AUDIO_N1, [COVER_B])
    other = f.request(20_050, "/cover/unknown999.jpg")
    assert f.tag(other) == N1


def test_audio_request_keeps_current_round(run):
    f = Feed(run)
    f.prepare(0, N, AUDIO_N, [COVER_A])
    a = f.request(10, AUDIO_N)
    assert f.tag(a) == N


def test_redirect_hop_keeps_origin_tag(run):
    f = Feed(run)
    f.prepare(0, N, AUDIO_N, [COVER_A])
    f.reveal(20_000, N, COVER_R)
    f.prepare(20_002, N1, AUDIO_N1, [COVER_B])
    origin = f.request(20_050, COVER_R)
    hop = FakeRequest("https://storage.example.test/bucket/rrr333.jpg?sig=x",
                      redirected_from=origin)
    origin.redirected_to = hop
    f.rec._on_request(hop)
    assert f.tag(hop) == f.tag(origin) == N
