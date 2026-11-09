"""Measures the song-preparation wait on a live SongRush site (cycle 102, D-113).

One user's experience, not server capacity. First it loads the home page in
3 fresh Edge contexts (page load). Then, for each answer format (chat, then
choice), it opens a fresh context, creates a room as host perf-1, starts an
N-song game, and skips every song once it is audible and its downloads have
finished. It sends no answers, plays one game per format, and leaves the
room at the end.

Writes perf/results/<UTC date>-<label>.json, prints a summary line, and
appends a row to the table in perf/README.md. URLs are saved as host and
path only: presigned query strings are never written.

Captures (cycle 103, D-114; off with --no-capture): per format a HAR without
bodies, its queries stripped (perf/results/<date>-<label>-<format>.har), and
three screenshots of the first song, loading, playing and reveal
(perf/results/shots/<date>-<label>-<format>-<moment>.png).

    python -X utf8 perf/run.py --base https://animeopguessr.com/ --songs 5 --label baseline --env-note "Korea, home Wi-Fi"

Needs only Playwright for Python with the Edge channel ("msedge").
"""

import argparse
import json
import platform
import re
import subprocess
import sys
import tempfile
import time
import urllib.request
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import urljoin, urlsplit

from playwright.sync_api import Error as PlaywrightError
from playwright.sync_api import sync_playwright

from perflib import contains_secret, median, readme_row, strip_har, strip_query, summarize

PERF_DIR = Path(__file__).resolve().parent
REPO_DIR = PERF_DIR.parent
RESULTS_DIR = PERF_DIR / "results"
SHOTS_DIR = RESULTS_DIR / "shots"
README = PERF_DIR / "README.md"

USER_AGENT = "SongRushBot/perf (+https://github.com/kimdoodoo12/SongRushApp)"
HTTP_TIMEOUT_S = 60
HOME_TTFB_REQUESTS = 3
HOME_TTFB_GAP_S = 1.0
PAGE_LOADS = 3
NICKNAME = "perf-1"
FORMAT_BUTTON = {"chat": "채팅", "choice": "객관식"}
SONG_COUNT_MIN = 5
SONG_COUNT_MAX = 30
# The format games' window (and the screenshots' size).
VIEWPORT = {"width": 1280, "height": 800}
# Korean UI and the default room look (놀이터, id play), set before the page's
# scripts run; a fresh context has neither stored anyway.
UI_SETUP_SCRIPT = """
try {
  window.localStorage.setItem('songrush.lang', 'ko')
  window.localStorage.setItem('songrush.roomTheme', 'play')
} catch (e) {}
"""
LOADING_TEXT = "음원을 불러오는 중..."
# How long to look for the loading text after the first prepare.
LOADING_SHOT_TIMEOUT_S = 10
SHOT_MOMENTS = ("loading", "playing", "reveal")
# Where the site runs: constants of this deployment, not measured.
SERVER_ENV = {
    "app": "AWS EC2 ap-southeast-2 (Sydney)",
    "storage": "S3 ap-southeast-2 (Sydney)",
}


def now_ms():
    return time.perf_counter() * 1000


def wall_ms():
    """Epoch ms, the clock of Playwright's request timing.startTime."""
    return time.time() * 1000


def log(text):
    print(text, file=sys.stderr, flush=True)


def wait_until(page, cond, timeout_s, what):
    """Polls cond while Playwright keeps dispatching events (frames, requests)."""
    deadline = time.monotonic() + timeout_s
    while not cond():
        if time.monotonic() > deadline:
            raise TimeoutError(f"timed out after {timeout_s} s waiting for {what}")
        page.wait_for_timeout(100)


def origin_of(req):
    while req.redirected_from is not None:
        req = req.redirected_from
    return req


def url_parts(url):
    parts = urlsplit(strip_query(url))
    return parts.netloc, parts.path


class Recorder:
    """The WebSocket frames and the /audio/ and /cover/ requests of one game.

    Every request is tagged with the round (server index) current when the
    page created it; a redirect's follow-up keeps its origin's tag.
    """

    def __init__(self):
        self.rounds = {}  # server index -> prepare/ready/start/reveal times and URLs
        self.order = []  # server indexes in order of their first prepare
        self.current = None  # server index of the latest prepare
        self.tags = {}  # Playwright request -> round tag
        self.finished = []  # finished requests, read after the game
        self.pending = set()  # in-flight requests
        self.failed = []  # (request, error text)
        self.bad_frames = 0
        self.errors = []  # server error frames, for the log

    def attach(self, page):
        page.on("websocket", self._on_socket)
        page.on("request", self._on_request)
        page.on("requestfinished", self._on_finished)
        page.on("requestfailed", self._on_failed)

    def _on_socket(self, ws):
        ws.on("framereceived", self._on_received)
        ws.on("framesent", self._on_sent)

    def _parse(self, payload):
        if isinstance(payload, bytes):
            payload = payload.decode("utf-8", "replace")
        try:
            msg = json.loads(payload)
        except ValueError:
            self.bad_frames += 1
            return None
        return msg if isinstance(msg, dict) else None

    def _round(self, msg):
        index = msg.get("index")
        if not isinstance(index, int):
            return None
        return self.rounds.get(index)

    def _on_received(self, payload):
        t = now_ms()
        wall = wall_ms()
        msg = self._parse(payload)
        if msg is None:
            return
        kind = msg.get("type")
        if kind == "prepare" and isinstance(msg.get("index"), int):
            index = msg["index"]
            if index in self.rounds:
                return  # a re-prepare (resume) keeps the first time
            covers = []
            for choice in msg.get("choices") or []:
                if isinstance(choice, dict) and isinstance(choice.get("coverUrl"), str):
                    covers.append(choice["coverUrl"])
            audio = msg.get("audioUrl")
            self.rounds[index] = {
                "prepare_t": t,
                "prepare_wall": wall,
                "inflight_at_prepare": len(self.pending),
                "audio_url": audio if isinstance(audio, str) else None,
                "covers": covers,
            }
            self.order.append(index)
            self.current = index
        elif kind == "start":
            r = self._round(msg)
            if r is not None and "start_t" not in r:
                r["start_t"] = t
        elif kind == "reveal":
            r = self._round(msg)
            if r is not None and "reveal_t" not in r:
                r["reveal_t"] = t
                cover = msg.get("coverUrl")
                if isinstance(cover, str):
                    r["covers"].append(cover)
        elif kind == "error":
            self.errors.append(msg.get("code", msg.get("message", "?")))
            log(f"server error frame: {self.errors[-1]}")

    def _on_sent(self, payload):
        t = now_ms()
        msg = self._parse(payload)
        if msg is None or msg.get("type") != "ready":
            return
        r = self._round(msg)
        if r is not None and "ready_t" not in r:
            r["ready_t"] = t

    @staticmethod
    def _tracked(req):
        path = urlsplit(origin_of(req).url).path
        return path.startswith("/audio/") or path.startswith("/cover/")

    def _on_request(self, req):
        if not self._tracked(req):
            return
        origin = origin_of(req)
        self.tags[req] = self.current if origin is req else self.tags.get(origin, self.current)
        self.pending.add(req)

    def _on_finished(self, req):
        self.pending.discard(req)
        if self._tracked(req):
            self.finished.append(req)

    def _on_failed(self, req):
        self.pending.discard(req)
        if self._tracked(req):
            self.failed.append((req, req.failure))
            log(f"request failed: {strip_query(req.url)} ({req.failure})")

    def pending_in_round(self, index):
        return any(self.tags.get(req) == index for req in self.pending)

    def ordinal(self, index):
        """1-based song number of a server index; None before the first prepare."""
        return self.order.index(index) + 1 if index in self.order else None

    def started(self, n):
        return len(self.order) >= n and "start_t" in self.rounds[self.order[n - 1]]

    def revealed(self, n):
        return len(self.order) >= n and "reveal_t" in self.rounds[self.order[n - 1]]


def timing_value(value):
    """A Playwright timing field in ms; -1 (not available) becomes None."""
    if value is None or value < 0:
        return None
    return round(value, 1)


def response_info(req):
    """Status, body bytes and timings of one finished request (query stripped)."""
    timing = req.timing
    response = req.response()
    try:
        size = req.sizes()["responseBodySize"]
    except PlaywrightError as err:
        log(f"no size for {strip_query(req.url)}: {err}")
        size = None
    host, path = url_parts(req.url)
    start = timing.get("startTime")
    return {
        "status": None if response is None else response.status,
        "bytes": size,
        "start": None if start is None or start <= 0 else start,  # epoch ms
        "ttfb_ms": timing_value(timing.get("responseStart")),
        "total_ms": timing_value(timing.get("responseEnd")),
        "host": host,
        "path": path,
    }


def response_infos(rec):
    """response_info of every finished request, read once per request."""
    return {req: response_info(req) for req in rec.finished}


def fetches_by_round(rec, infos):
    """(round tag, origin URL without query) -> {"first", "final", "attempts"}.

    first is the request the page made (/audio/ or /cover/), final the one
    that was not redirected further (the storage response); without a
    redirect both are the same info object. A retried download keeps its
    last attempt.
    """
    out = {}
    for req in rec.finished:
        origin = origin_of(req)
        key = (rec.tags.get(origin), strip_query(origin.url))
        entry = out.setdefault(key, {"attempts": 0})
        info = infos[req]
        if req.redirected_from is None:
            entry["first"] = info
            entry["attempts"] += 1
        if req.redirected_to is None:
            entry["final"] = info
    return out


def audio_row(fetch):
    if fetch is None or "final" not in fetch:
        return None
    first = fetch.get("first")
    final = fetch["final"]
    redirected = first is not None and first is not final
    return {
        "redirect_ms": first["total_ms"] if redirected else None,
        "redirect_status": first["status"] if redirected else None,
        "status": final["status"],
        "bytes": final["bytes"],
        "ttfb_ms": final["ttfb_ms"],
        "total_ms": final["total_ms"],
        "host": final["host"],
        "path": final["path"],
        "attempts": fetch["attempts"],
    }


def done_epoch(fetch):
    """Epoch ms of the final response's last byte, or None."""
    final = fetch["final"]
    if final["start"] is None or final["total_ms"] is None:
        return None
    return final["start"] + final["total_ms"]


def end_to_end_ms(fetch):
    """From the first request's start to the final response's last byte."""
    first, final = fetch.get("first"), fetch["final"]
    done = done_epoch(fetch)
    if first is None or first["start"] is None or done is None:
        return final["total_ms"]
    return round(done - first["start"], 1)


def covers_row(index, urls, fetches):
    """Covers shown in this round: fetched here (bytes) or not fetched (cached)."""
    if not urls:
        return None
    seen, cached = [], 0
    for url in dict.fromkeys(urls):
        fetch = fetches.get((index, url))
        if fetch is not None and "final" in fetch:
            seen.append(fetch)
        else:
            cached += 1
    sizes = [f["final"]["bytes"] for f in seen if f["final"]["bytes"] is not None]
    times = [t for t in (end_to_end_ms(f) for f in seen) if t is not None]
    return {
        "count": len(seen),
        "cached": cached,
        "total_bytes": sum(sizes) if sizes else None,
        "slowest_ms": max(times) if times else None,
        "urls": sorted({f"{f['final']['host']}{f['final']['path']}" for f in seen}),
    }


def ms_since(epoch, prepare_wall):
    return None if epoch is None else round(epoch - prepare_wall, 1)


def request_timeline(rec, infos, index, prepare_wall):
    """Every finished /audio/ and /cover/ request of one round, redirect hops included.

    Times are ms since the round's prepare on the epoch clock (the clock of
    prepareToAudioDone_ms). A hop that was redirected further is
    audio-redirect or cover-redirect. Sorted by start.
    """
    rows = []
    for req in rec.finished:
        origin = origin_of(req)
        if rec.tags.get(origin) != index:
            continue
        kind = "audio" if urlsplit(origin.url).path.startswith("/audio/") else "cover"
        if req.redirected_to is not None:
            kind += "-redirect"
        info = infos[req]
        end = None if info["start"] is None or info["total_ms"] is None else info["start"] + info["total_ms"]
        rows.append({
            "kind": kind,
            "path": info["path"],
            "start_ms": ms_since(info["start"], prepare_wall),
            "end_ms": ms_since(end, prepare_wall),
            "bytes": info["bytes"],
        })
    rows.sort(key=lambda r: (r["start_ms"] is None, r["start_ms"] or 0))
    return rows


def song_rows(fmt, rec, base, songs):
    infos = response_infos(rec)
    fetches = fetches_by_round(rec, infos)
    rows = []
    for n, index in enumerate(rec.order[:songs], start=1):
        r = rec.rounds[index]

        def since_prepare(key):
            return None if key not in r else round(r[key] - r["prepare_t"], 1)

        ready_before = "ready_t" in r and ("start_t" not in r or r["ready_t"] <= r["start_t"])
        audio_url = None if r["audio_url"] is None else strip_query(urljoin(base, r["audio_url"]))
        audio_fetch = None if audio_url is None else fetches.get((index, audio_url))
        audio = audio_row(audio_fetch)
        audio_done = None
        if audio is not None:
            done = done_epoch(audio_fetch)
            if done is not None:
                audio_done = round(done - r["prepare_wall"], 1)
        cover_urls = [strip_query(urljoin(base, u)) for u in r["covers"]]
        rows.append({
            "format": fmt,
            "index": n,
            "serverIndex": index,
            "readyBeforeStart": ready_before,
            "prepareToReady_ms": since_prepare("ready_t") if ready_before else None,
            "prepareToStart_ms": since_prepare("start_t"),
            "prepareToAudioDone_ms": audio_done,
            "prepareToReveal_ms": since_prepare("reveal_t"),
            "inflight_at_prepare": r["inflight_at_prepare"],
            "settledBeforeSkip": r.get("settledBeforeSkip"),
            "audio": audio,
            "covers": covers_row(index, cover_urls, fetches),
            "requests": request_timeline(rec, infos, index, r["prepare_wall"]),
        })
    return rows


def request_rows(fmt, rec, pairs):
    """Failed or uncounted requests as (format, round, host, path[, error])."""
    rows = []
    for req, error in pairs:
        host, path = url_parts(req.url)
        row = {"format": fmt, "round": rec.ordinal(rec.tags.get(origin_of(req))), "host": host, "path": path}
        if error is not None:
            row["error"] = error
        rows.append(row)
    return rows


def page_load_ms(page):
    return page.evaluate(
        """() => {
          const n = performance.getEntriesByType('navigation')[0]
          if (!n) return null
          const end = n.loadEventEnd > 0 ? n.loadEventEnd : n.loadEventStart
          return end > 0 ? Math.round((end - n.startTime) * 10) / 10 : null
        }"""
    )


def asset_urls(page, base):
    """Same-origin JS (script src, modulepreload) and CSS (stylesheet) URLs."""
    found = page.evaluate(
        """() => ({
          js: [...document.querySelectorAll('script[src], link[rel=modulepreload][href]')]
                .map((e) => e.src || e.href),
          css: [...document.querySelectorAll('link[rel=stylesheet][href]')].map((e) => e.href),
        })"""
    )
    host = urlsplit(base).netloc
    return {kind: [u for u in dict.fromkeys(urls) if urlsplit(u).netloc == host] for kind, urls in found.items()}


def measure_page_loads(browser, base):
    """PAGE_LOADS fresh-context loads of the home page; the asset URLs of the first."""
    runs, assets = [], None
    for _ in range(PAGE_LOADS):
        context = browser.new_context(locale="ko-KR")
        try:
            page = context.new_page()
            page.goto(base, wait_until="load", timeout=HTTP_TIMEOUT_S * 1000)
            runs.append(page_load_ms(page))
            if assets is None:
                assets = asset_urls(page, base)
        finally:
            context.close()
    return runs, assets


def http_get(url, accept_encoding=None):
    """(status, raw body bytes, content-encoding, ms to headers) of a plain GET."""
    headers = {"User-Agent": USER_AGENT}
    if accept_encoding is not None:
        headers["Accept-Encoding"] = accept_encoding
    req = urllib.request.Request(url, headers=headers)
    t0 = time.perf_counter()
    with urllib.request.urlopen(req, timeout=HTTP_TIMEOUT_S) as resp:
        ttfb = (time.perf_counter() - t0) * 1000
        body = resp.read()  # urllib does not decode, so this is the wire size
        return resp.status, len(body), resp.headers.get("Content-Encoding"), round(ttfb, 1)


def measure_assets(urls):
    assets = []
    for kind in ("js", "css"):
        for url in urls[kind]:
            status, size, encoding, _ = http_get(url, accept_encoding="gzip, br")
            assets.append({
                "kind": kind,
                "url": strip_query(url),
                "status": status,
                "bytes": size,
                "encoding": encoding,
            })
    return assets


def kind_totals(assets, kind):
    rows = [a for a in assets if a["kind"] == kind]
    if not rows:
        return None, None
    encodings = {a["encoding"] or "identity" for a in rows}
    encoding = encodings.pop() if len(encodings) == 1 else "mixed"
    return sum(a["bytes"] for a in rows), (None if encoding == "identity" else encoding)


def home_ttfb(base):
    """TTFB of plain cold requests: DNS, TCP and TLS are included each time."""
    runs = []
    for i in range(HOME_TTFB_REQUESTS):
        if i > 0:
            time.sleep(HOME_TTFB_GAP_S)
        runs.append(http_get(base)[3])
    return runs


def git_commit():
    try:
        out = subprocess.run(
            ["git", "rev-parse", "--short", "HEAD"],
            cwd=REPO_DIR, capture_output=True, text=True, check=True, timeout=30,
        )
    except (OSError, subprocess.SubprocessError) as err:
        log(f"git commit unknown: {err}")
        return None
    return out.stdout.strip()


def leave_room(page):
    """Game over -> 대기실로 -> 나가기, back to the start screen."""
    try:
        page.get_by_role("button", name="대기실로", exact=True).click(timeout=60_000)
        page.get_by_role("button", name="나가기", exact=True).click(timeout=30_000)
        page.get_by_role("button", name="방 만들기", exact=True).wait_for(timeout=30_000)
    except PlaywrightError as err:  # includes Playwright's TimeoutError
        # Closing the context closes the socket, so the server drops the
        # player anyway; the log says the polite leave did not happen.
        log(f"leave did not complete ({type(err).__name__}): {err}")
        return False
    return True


def shoot(page, path):
    path.parent.mkdir(parents=True, exist_ok=True)
    # The game page scrolls to the chat input; the stage head and the load
    # line are at the top, so scroll back and capture the whole page.
    page.evaluate("window.scrollTo(0, 0)")
    page.screenshot(path=str(path), full_page=True)
    log(f"screenshot {rel(path)}")


def shoot_loading(page, rec, path, timeout):
    """The first song's loading screenshot, and whether the loading text was on it.

    Taken once the text is visible or, when it does not show within
    LOADING_SHOT_TIMEOUT_S, right then (loadingTextVisible false).
    """
    wait_until(page, lambda: len(rec.order) >= 1, timeout, "first prepare")
    visible = True
    try:
        page.get_by_text(LOADING_TEXT, exact=True).first.wait_for(
            state="visible", timeout=LOADING_SHOT_TIMEOUT_S * 1000)
    except PlaywrightError as err:  # includes Playwright's TimeoutError
        log(f"loading text not seen ({type(err).__name__}); screenshot taken anyway")
        visible = False
    shot_ms = round(now_ms() - rec.rounds[rec.order[0]]["prepare_t"], 1)
    shoot(page, path)
    return {"loadingTextVisible": visible, "loadingShotAfterPrepare_ms": shot_ms}


def run_format(browser, fmt, args, capture):
    """One fresh context, one N-song game in this answer format.

    capture is None (no captures) or one format's capture_plan entry; the
    HAR goes to its temp path when the context closes.
    """
    options = {"locale": "ko-KR", "viewport": VIEWPORT}
    if capture is not None:
        options["record_har_path"] = str(capture["har"])
        options["record_har_content"] = "omit"
    context = browser.new_context(**options)
    shot_info = None
    try:
        context.add_init_script(UI_SETUP_SCRIPT)
        page = context.new_page()
        rec = Recorder()
        rec.attach(page)
        page.goto(args.base, wait_until="load", timeout=HTTP_TIMEOUT_S * 1000)

        page.get_by_label("닉네임").fill(NICKNAME)
        page.get_by_role("button", name="방 만들기", exact=True).click()
        page.locator(".lobby").wait_for(timeout=30_000)
        page.get_by_role("group", name="정답 형식").get_by_role(
            "button", name=FORMAT_BUTTON[fmt], exact=True).click()
        page.get_by_label("곡 수").select_option(str(args.songs))
        page.get_by_role("button", name="게임 시작", exact=True).click()

        timeout = args.song_timeout
        if capture is not None:
            shot_info = shoot_loading(page, rec, capture["shots"]["loading"], timeout)
        for n in range(1, args.songs + 1):
            wait_until(page, lambda: rec.started(n), timeout, f"{fmt} song {n} start")
            page.locator(".stage-hint").wait_for(state="visible", timeout=timeout * 1000)
            if capture is not None and n == 1:
                shoot(page, capture["shots"]["playing"])
            index = rec.order[n - 1]
            # One song at a time: this round's downloads finish before the
            # skip, so the next song's downloads do not overlap them.
            try:
                wait_until(page, lambda: not rec.pending_in_round(index), timeout,
                           f"{fmt} song {n} downloads")
                rec.rounds[index]["settledBeforeSkip"] = True
            except TimeoutError as err:
                log(f"{err}; skipping anyway")
                rec.rounds[index]["settledBeforeSkip"] = False
            page.get_by_role("button", name="스킵", exact=True).click()
            wait_until(page, lambda: rec.revealed(n), timeout, f"{fmt} song {n} reveal")
            page.locator(".reveal").wait_for(state="visible", timeout=timeout * 1000)
            if capture is not None and n == 1:
                shoot(page, capture["shots"]["reveal"])
            log(f"{fmt} song {n}/{args.songs} done")

        try:
            wait_until(page, lambda: not rec.pending, timeout, "leftover audio/cover requests")
        except TimeoutError as err:
            log(f"{err}; {len(rec.pending)} request(s) recorded as uncounted")
        uncounted = request_rows(fmt, rec, [(req, None) for req in rec.pending])
        left = leave_room(page)
        if rec.bad_frames:
            log(f"{rec.bad_frames} unreadable WebSocket frame(s) ignored")
        return {
            "songs": song_rows(fmt, rec, args.base, args.songs),
            "failed": request_rows(fmt, rec, rec.failed),
            "uncounted": uncounted,
            "left": left,
            "shots": shot_info,
        }
    finally:
        context.close()


def rel(path):
    return path.relative_to(REPO_DIR).as_posix()


def capture_plan(stem, tmp_dir):
    """Per format: the temp HAR Playwright writes, the final HAR and the shots."""
    plan = {}
    for fmt in ("chat", "choice"):
        plan[fmt] = {
            "har": Path(tmp_dir) / f"{fmt}.har",
            "har_out": RESULTS_DIR / f"{stem}-{fmt}.har",
            "shots": {m: SHOTS_DIR / f"{stem}-{fmt}-{m}.png" for m in SHOT_MOMENTS},
        }
    return plan


def scrubbed_har(path):
    """The HAR at path with its URL queries stripped, as JSON text.

    Raises ValueError when a presigned-URL marker is still in the text.
    """
    har = json.loads(path.read_text(encoding="utf-8"))
    text = json.dumps(strip_har(har), ensure_ascii=False, indent=1) + "\n"
    if contains_secret(text):
        raise ValueError(f"the {path.stem} HAR still contains a presigned-URL marker after stripping")
    return text


def environment(browser, note, local_time):
    return {
        "client": {
            "os": f"{platform.system()} {platform.release()} ({platform.version()})",
            "browser": "Microsoft Edge (Playwright channel msedge)",
            "version": browser.version,
            "note": note,
        },
        "server": dict(SERVER_ENV),
        "local_time": local_time,
    }


def result_path(date, label):
    safe = re.sub(r"[^A-Za-z0-9._-]+", "-", label).strip("-") or "run"
    path = RESULTS_DIR / f"{date}-{safe}.json"
    n = 2
    while path.exists():
        path = RESULTS_DIR / f"{date}-{safe}-{n}.json"
        n += 1
    return path


def append_readme_row(row):
    lines = README.read_text(encoding="utf-8").splitlines()
    table = [i for i, line in enumerate(lines) if line.startswith("|")]
    if not table:
        raise RuntimeError(f"no results table in {README}")
    lines.insert(table[-1] + 1, row)
    README.write_text("\n".join(lines) + "\n", encoding="utf-8", newline="\n")


def song_count(text):
    n = int(text)
    if not SONG_COUNT_MIN <= n <= SONG_COUNT_MAX:
        raise argparse.ArgumentTypeError(f"songs must be {SONG_COUNT_MIN}-{SONG_COUNT_MAX}")
    return n


def parse_args(argv):
    parser = argparse.ArgumentParser(
        description="Measure the song-preparation wait on a live SongRush site "
                    "(one chat game and one choice game, every song skipped).")
    parser.add_argument("--base", required=True, help="site root, e.g. https://animeopguessr.com/")
    parser.add_argument("--label", required=True, help="run label, e.g. baseline (goes into the file name)")
    parser.add_argument("--songs", type=song_count, default=5,
                        help=f"songs per format, {SONG_COUNT_MIN}-{SONG_COUNT_MAX} (default 5)")
    parser.add_argument("--song-timeout", type=float, default=120,
                        help="seconds to wait for each song's start, downloads and reveal, "
                             "and for leftover downloads at the end (default 120)")
    parser.add_argument("--headed", action="store_true", help="show the browser window")
    parser.add_argument("--no-readme", action="store_true", help="do not append the row to perf/README.md")
    parser.add_argument("--no-capture", action="store_true", help="do not record the HARs and screenshots")
    parser.add_argument("--env-note", default=None,
                        help='free text about the client network, e.g. "Korea, home Wi-Fi" '
                             "(saved as environment.client.note)")
    args = parser.parse_args(argv)
    if not args.base.endswith("/"):
        args.base += "/"
    return args


def summary_line(label, medians, page, path):
    def show(value, spec):
        return "-" if value is None else spec.format(value)

    parts = []
    for fmt in ("chat", "choice"):
        m = medians.get(fmt)
        if m is None:
            parts.append(f"{fmt} -")
            continue
        star = "*" if m["audio_missing"] > 0 else ""
        parts.append(f"{fmt} ready {m['readyBeforeStart']}/{m['songs']}, "
                     f"p->audio done {show(m['prepareToAudioDone_ms'], '{:.0f}')}{star} ms")
    allm = medians["all"]
    audio_mb = None if allm["audio_bytes"] is None else allm["audio_bytes"] / 1e6
    return (
        f"perf {label}: {'; '.join(parts)}; audio {show(audio_mb, '{:.2f}')} MB at "
        f"{show(allm['audio_mb_per_s'], '{:.2f}')} MB/s; page load "
        f"{show(page['load_ms_min'], '{:.0f}')}-{show(page['load_ms_max'], '{:.0f}')} ms "
        f"-> {path.relative_to(REPO_DIR).as_posix()}"
    )


def main(argv=None):
    args = parse_args(argv)
    timestamp = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    local_time = datetime.now().astimezone().isoformat(timespec="seconds")
    # The JSON's name is fixed up front: the HARs and screenshots share its stem.
    path = result_path(timestamp[:10], args.label)
    runs = {}
    har_texts = {}
    # Playwright's raw HARs hold presigned URLs: they stay in this temp folder,
    # removed on exit, and only stripped and checked copies are written.
    with tempfile.TemporaryDirectory(prefix="songrush-perf-") as tmp_dir:
        plan = None if args.no_capture else capture_plan(path.stem, tmp_dir)
        with sync_playwright() as pw:
            browser = pw.chromium.launch(
                channel="msedge",
                headless=not args.headed,
                args=["--autoplay-policy=no-user-gesture-required"],
            )
            try:
                env = environment(browser, args.env_note, local_time)
                load_runs, asset_list = measure_page_loads(browser, args.base)
                for fmt in ("chat", "choice"):
                    runs[fmt] = run_format(browser, fmt, args, None if plan is None else plan[fmt])
            finally:
                browser.close()
        for fmt in plan or {}:
            try:
                har_texts[fmt] = scrubbed_har(plan[fmt]["har"])
            except ValueError as err:
                log(f"refusing to write the result and HARs: {err}")
                return 1

    assets = measure_assets(asset_list)
    js_bytes, js_encoding = kind_totals(assets, "js")
    css_bytes, css_encoding = kind_totals(assets, "css")
    js_names = [a["url"].rsplit("/", 1)[-1] for a in assets if a["kind"] == "js"]
    ttfb_runs = home_ttfb(args.base)
    songs = runs["chat"]["songs"] + runs["choice"]["songs"]
    loads = [v for v in load_runs if v is not None]

    result = {
        "label": args.label,
        "timestamp": timestamp,
        "commit": git_commit(),
        "build": js_names[0] if js_names else "-",
        "base": strip_query(args.base),
        "songsPerFormat": args.songs,
        "environment": env,
        "captures": None if plan is None else {
            fmt: {
                "har": rel(plan[fmt]["har_out"]),
                "shots": {m: rel(p) for m, p in plan[fmt]["shots"].items()},
                **(runs[fmt]["shots"] or {}),
            }
            for fmt in plan
        },
        "page": {
            "load_ms": median(load_runs),
            "load_ms_min": min(loads) if loads else None,
            "load_ms_max": max(loads) if loads else None,
            "load_ms_runs": load_runs,
            "js_bytes": js_bytes,
            "js_encoding": js_encoding,
            "css_bytes": css_bytes,
            "css_encoding": css_encoding,
            "assets": assets,
            "home_ttfb_ms": median(ttfb_runs),
            "home_ttfb_runs": ttfb_runs,
        },
        "leftRoom": {fmt: runs[fmt]["left"] for fmt in runs},
        "failedRequests": runs["chat"]["failed"] + runs["choice"]["failed"],
        "uncountedRequests": runs["chat"]["uncounted"] + runs["choice"]["uncounted"],
        "songs": songs,
        "medians": summarize(songs),
    }

    text = json.dumps(result, ensure_ascii=False, indent=2) + "\n"
    if contains_secret(text):
        log("refusing to write: the result contains a presigned-URL marker")
        return 1
    RESULTS_DIR.mkdir(exist_ok=True)
    for fmt, har_text in har_texts.items():
        plan[fmt]["har_out"].write_text(har_text, encoding="utf-8", newline="\n")
        log(f"HAR {rel(plan[fmt]['har_out'])}")
    path.write_text(text, encoding="utf-8", newline="\n")
    row = readme_row(result)
    if not args.no_readme:
        append_readme_row(row)
    print(summary_line(args.label, result["medians"], result["page"], path))
    print(row)
    return 0


if __name__ == "__main__":
    sys.exit(main())
