"""Measures the song-preparation wait on a live SongRush site (cycle 102, D-113).

One user's experience, not server capacity: for each answer format (chat,
then choice) it opens a fresh Edge context, creates a room as host perf-1,
starts an N-song game, and skips every song once it is audible. It sends no
answers, plays one game per format, and leaves the room at the end.

Writes perf/results/<UTC date>-<label>.json, prints a summary line, and
appends a row to the table in perf/README.md. URLs are saved as host and
path only: presigned query strings are never written.

    python -X utf8 perf/run.py --base https://animeopguessr.com/ --songs 5 --label baseline

Needs only Playwright for Python with the Edge channel ("msedge").
"""

import argparse
import json
import re
import subprocess
import sys
import time
import urllib.request
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import urljoin, urlsplit

from playwright.sync_api import Error as PlaywrightError
from playwright.sync_api import sync_playwright

from perflib import median, readme_row, strip_query, summarize

PERF_DIR = Path(__file__).resolve().parent
REPO_DIR = PERF_DIR.parent
RESULTS_DIR = PERF_DIR / "results"
README = PERF_DIR / "README.md"

USER_AGENT = "SongRushBot/perf (+https://github.com/kimdoodoo12/SongRushApp)"
HTTP_TIMEOUT_S = 60
HOME_TTFB_REQUESTS = 3
HOME_TTFB_GAP_S = 1.0
NICKNAME = "perf-1"
FORMAT_BUTTON = {"chat": "채팅", "choice": "객관식"}
SONG_COUNT_MIN = 5
SONG_COUNT_MAX = 30
# Leftover cover or audio requests get this long to finish before a run is read.
SETTLE_S = 15
# Text that would mean a presigned URL leaked into the result.
SECRET_MARKERS = ("X-Amz-", "Signature=", "Credential=")


def now_ms():
    return time.perf_counter() * 1000


def log(text):
    print(text, file=sys.stderr, flush=True)


def wait_until(page, cond, timeout_s, what):
    """Polls cond while Playwright keeps dispatching events (frames, requests)."""
    deadline = time.monotonic() + timeout_s
    while not cond():
        if time.monotonic() > deadline:
            raise TimeoutError(f"timed out after {timeout_s} s waiting for {what}")
        page.wait_for_timeout(100)


class Recorder:
    """The WebSocket frames and finished requests of one format's run."""

    def __init__(self):
        self.rounds = {}  # server index -> prepare/ready/start/reveal times and URLs
        self.order = []  # server indexes in order of their first prepare
        self.finished = []  # finished Playwright requests, read after the run
        self.pending = set()  # in-flight /audio/ and /cover/ requests
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
                "audio_url": audio if isinstance(audio, str) else None,
                "covers": covers,
            }
            self.order.append(index)
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
        if self._tracked(req):
            self.pending.add(req)

    def _on_finished(self, req):
        self.pending.discard(req)
        if self._tracked(req):
            self.finished.append(req)

    def _on_failed(self, req):
        self.pending.discard(req)
        if self._tracked(req):
            log(f"request failed: {strip_query(req.url)} ({req.failure})")

    def started(self, n):
        return len(self.order) >= n and "start_t" in self.rounds[self.order[n - 1]]

    def revealed(self, n):
        return len(self.order) >= n and "reveal_t" in self.rounds[self.order[n - 1]]


def origin_of(req):
    while req.redirected_from is not None:
        req = req.redirected_from
    return req


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
    parts = urlsplit(strip_query(req.url))
    return {
        "status": None if response is None else response.status,
        "bytes": size,
        "start": timing.get("startTime"),
        "ttfb_ms": timing_value(timing.get("responseStart")),
        "total_ms": timing_value(timing.get("responseEnd")),
        "host": parts.netloc,
        "path": parts.path,
    }


def fetches_by_origin(finished):
    """Origin URL (query stripped) -> {"first": info, "final": info}.

    first is the request the page made (/audio/ or /cover/), final the one
    that was not redirected further (the S3 response); without a redirect
    both are the same info object.
    """
    out = {}
    for req in finished:
        rec = out.setdefault(strip_query(origin_of(req).url), {})
        info = response_info(req)
        if req.redirected_from is None:
            rec["first"] = info
        if req.redirected_to is None:
            rec["final"] = info
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
    }


def end_to_end_ms(fetch):
    """From the first request's start to the final response's last byte."""
    first, final = fetch.get("first"), fetch["final"]
    if first is None or first["start"] is None or final["start"] is None or final["total_ms"] is None:
        return final["total_ms"]
    return round(final["start"] + final["total_ms"] - first["start"], 1)


def covers_row(urls, fetches):
    if not urls:
        return None
    seen = [f for f in (fetches.get(u) for u in dict.fromkeys(urls)) if f is not None and "final" in f]
    sizes = [f["final"]["bytes"] for f in seen if f["final"]["bytes"] is not None]
    times = [t for t in (end_to_end_ms(f) for f in seen) if t is not None]
    return {
        "count": len(seen),
        "total_bytes": sum(sizes) if sizes else None,
        "slowest_ms": max(times) if times else None,
        "urls": sorted({f"{f['final']['host']}{f['final']['path']}" for f in seen}),
    }


def song_rows(fmt, rec, base, songs):
    fetches = fetches_by_origin(rec.finished)
    rows = []
    for n, index in enumerate(rec.order[:songs], start=1):
        r = rec.rounds[index]

        def since_prepare(key):
            return None if key not in r else round(r[key] - r["prepare_t"], 1)

        audio_url = None if r["audio_url"] is None else strip_query(urljoin(base, r["audio_url"]))
        cover_urls = [strip_query(urljoin(base, u)) for u in r["covers"]]
        rows.append({
            "format": fmt,
            "index": n,
            "serverIndex": index,
            "prepareToReady_ms": since_prepare("ready_t"),
            "prepareToStart_ms": since_prepare("start_t"),
            "prepareToReveal_ms": since_prepare("reveal_t"),
            "audio": audio_row(None if audio_url is None else fetches.get(audio_url)),
            "covers": covers_row(cover_urls, fetches),
        })
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


def run_format(browser, fmt, args, want_assets):
    """One fresh context, one N-song game in this answer format."""
    context = browser.new_context(locale="ko-KR")
    try:
        page = context.new_page()
        rec = Recorder()
        rec.attach(page)
        page.goto(args.base, wait_until="load", timeout=HTTP_TIMEOUT_S * 1000)
        load_ms = page_load_ms(page)
        assets = asset_urls(page, args.base) if want_assets else None

        page.get_by_label("닉네임").fill(NICKNAME)
        page.get_by_role("button", name="방 만들기", exact=True).click()
        page.locator(".lobby").wait_for(timeout=30_000)
        page.get_by_role("group", name="정답 형식").get_by_role(
            "button", name=FORMAT_BUTTON[fmt], exact=True).click()
        page.get_by_label("곡 수").select_option(str(args.songs))
        page.get_by_role("button", name="게임 시작", exact=True).click()

        timeout = args.song_timeout
        for n in range(1, args.songs + 1):
            wait_until(page, lambda: rec.started(n), timeout, f"{fmt} song {n} start")
            page.locator(".stage-hint").wait_for(state="visible", timeout=timeout * 1000)
            page.get_by_role("button", name="스킵", exact=True).click()
            wait_until(page, lambda: rec.revealed(n), timeout, f"{fmt} song {n} reveal")
            page.locator(".reveal").wait_for(state="visible", timeout=timeout * 1000)
            log(f"{fmt} song {n}/{args.songs} done")

        try:
            wait_until(page, lambda: not rec.pending, SETTLE_S, "leftover audio/cover requests")
        except TimeoutError as err:
            log(f"{err}; {len(rec.pending)} request(s) not counted")
        left = leave_room(page)
        if rec.bad_frames:
            log(f"{rec.bad_frames} unreadable WebSocket frame(s) ignored")
        songs = song_rows(fmt, rec, args.base, args.songs)
        return {"songs": songs, "load_ms": load_ms, "assets": assets, "left": left}
    finally:
        context.close()


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
                        help="seconds to wait for each song's start and reveal (default 120)")
    parser.add_argument("--headed", action="store_true", help="show the browser window")
    parser.add_argument("--no-readme", action="store_true", help="do not append the row to perf/README.md")
    args = parser.parse_args(argv)
    if not args.base.endswith("/"):
        args.base += "/"
    return args


def main(argv=None):
    args = parse_args(argv)
    timestamp = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    runs = {}
    with sync_playwright() as pw:
        browser = pw.chromium.launch(
            channel="msedge",
            headless=not args.headed,
            args=["--autoplay-policy=no-user-gesture-required"],
        )
        try:
            for fmt in ("chat", "choice"):
                runs[fmt] = run_format(browser, fmt, args, want_assets=(fmt == "chat"))
        finally:
            browser.close()

    assets = measure_assets(runs["chat"]["assets"])
    js_bytes, js_encoding = kind_totals(assets, "js")
    css_bytes, css_encoding = kind_totals(assets, "css")
    js_names = [a["url"].rsplit("/", 1)[-1] for a in assets if a["kind"] == "js"]
    ttfb_runs = home_ttfb(args.base)
    songs = runs["chat"]["songs"] + runs["choice"]["songs"]
    load_runs = {fmt: runs[fmt]["load_ms"] for fmt in runs}

    result = {
        "label": args.label,
        "timestamp": timestamp,
        "commit": git_commit(),
        "build": js_names[0] if js_names else None,
        "base": strip_query(args.base),
        "songsPerFormat": args.songs,
        "page": {
            "load_ms": median(load_runs.values()),
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
        "songs": songs,
        "medians": summarize(songs),
    }
    if result["build"] is None:
        result["build"] = "-"

    text = json.dumps(result, ensure_ascii=False, indent=2) + "\n"
    leaked = [m for m in SECRET_MARKERS if m in text]
    if leaked:
        log(f"refusing to write: the result contains {leaked}")
        return 1
    RESULTS_DIR.mkdir(exist_ok=True)
    path = result_path(timestamp[:10], args.label)
    path.write_text(text, encoding="utf-8", newline="\n")
    row = readme_row(result)
    if not args.no_readme:
        append_readme_row(row)

    m = result["medians"]

    def show(value, spec):
        return "-" if value is None else spec.format(value)

    print(
        f"perf {args.label}: prepare->start chat "
        f"{show(m.get('chat', {}).get('prepareToStart_ms'), '{:.0f}')} ms, choice "
        f"{show(m.get('choice', {}).get('prepareToStart_ms'), '{:.0f}')} ms; audio "
        f"{show(None if m['all']['audio_bytes'] is None else m['all']['audio_bytes'] / 1e6, '{:.2f}')} MB at "
        f"{show(m['all']['audio_mb_per_s'], '{:.2f}')} MB/s; page load "
        f"{show(result['page']['load_ms'], '{:.0f}')} ms -> {path.relative_to(REPO_DIR).as_posix()}"
    )
    print(row)
    return 0


if __name__ == "__main__":
    sys.exit(main())
