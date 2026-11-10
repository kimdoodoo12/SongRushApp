"""Captures the Edge DevTools Network panel on a live SongRush site (cycle 104, D-115).

Run 103's charts were redrawn from the measurements; this tool captures the
measurement itself, the browser's own Network panel, for these views:

- pageload: the home page loaded with "Disable cache" checked;
- choice: one song of a choice room, while it loads and when its audio is done;
- chat: the same two moments in a chat room;
- between (cycle 107, D-119): a chat room's gap between song 1 and song 2.
  Once song 1 is audible the Network log is cleared and 스킵 clicked; about
  2 s into the reveal DevTools is shot while still recording (with the
  prefetch-on-reveal mode on, song 2's download shows during the reveal),
  then the game; once song 2 is audible recording stops and DevTools is shot
  again.

How it works (docs/cycles/104-devtools-captures.md):
1. Edge runs as a normal process with a fresh profile in the temp folder,
   --remote-debugging-port and --auto-open-devtools-for-tabs. Playwright
   attaches with connect_over_cdp. The DevTools frontend is the page whose
   URL contains devtools_app.html (never device_mode_emulation_frame).
2. A one-time setup launch sets the DevTools settings (Network columns,
   Network tab selected, undocked), waits ~12 s so Edge writes them to the
   profile, and ends Edge. Then each view gets its own launch; before each,
   the saved session is deleted and the profile marked as cleanly exited, so
   Edge does not restore the previous tab (exactly one site tab and one
   DevTools frontend are required).
3. Presigned-URL signatures are hidden before every DevTools screenshot (every
   text node, shadow roots included, is cut at "?" when it holds a marker).
   The S3 bucket host (shown under each name with big rows; it holds the AWS
   account id) is replaced by "S3 (ap-southeast-2)" the same way.
   The screenshot is kept in memory; the deep text is read again and checked
   with perflib.contains_secret and BUCKET_PATTERN, and only a clean image is
   written to disk. A
   hit drops the image and retakes it, up to 30 times; no clean image means
   nothing is written and the tool exits 1.
4. The Edge process tree is always ended with taskkill, and the temp profile
   is deleted at the end.

Writes perf/results/shots/<label>-devtools-<view>.png and, for the song views,
<label>-game-<format>-<moment>.png, and prints each saved path (its KST time
goes to stderr). The song views' "loading" shots are taken 1 s after the
loading text appears, the "done" shots once the song's audio is done. The
between view writes <label>-devtools-between-reveal.png,
<label>-game-between-reveal.png and <label>-devtools-between-done.png.

    python -X utf8 perf/devtools_shot.py --label baseline

Opens a visible Edge window for about 3 minutes. Needs Playwright for Python,
so run it with the system Python (data/.venv does not have Playwright).
"""

import argparse
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import time
from contextlib import contextmanager
from datetime import datetime, timedelta, timezone
from pathlib import Path
from urllib.parse import urlsplit

from perflib import contains_secret

PERF_DIR = Path(__file__).resolve().parent
REPO_DIR = PERF_DIR.parent
SHOTS_DIR = PERF_DIR / "results" / "shots"

DEFAULT_BASE = "https://animeopguessr.com/"
VIEWS = ("pageload", "choice", "chat", "between")
EDGE_CANDIDATES = (
    r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe",
    r"C:\Program Files\Microsoft\Edge\Application\msedge.exe",
)
CDP_PORT = 9335
CDP_CONNECT_TIMEOUT_S = 30
EDGE_START_S = 5
DEVTOOLS_PAGE_TIMEOUT_S = 40
# How long one CDP connection is searched for the pages before reconnecting.
PAGE_LOOKUP_S = 4
# Session files under <profile>/Default that make Edge restore the last tab.
SESSION_ENTRIES = ("Sessions", "Current Session", "Current Tabs",
                   "Last Session", "Last Tabs")
# Edge writes changed DevTools settings to Default/Preferences lazily; ending
# it sooner loses them (seen in the lead's probe).
SETTINGS_FLUSH_S = 12
SHOT_ATTEMPTS = 30
NICKNAME = "perf-shot"
SONG_COUNT = "5"
LOADING_TEXT = "음원을 불러오는 중"
LOADING_TIMEOUT_MS = 60_000
DONE_TIMEOUT_MS = 90_000
# Seconds after the loading text appears before the "loading" shot; short,
# so the shot shows the downloads running even on a fast network.
LOADING_SHOT_DELAY_S = 1.0
# Seconds into the reveal before the between view's DevTools shot: late
# enough for song 2's prepare (sent when the reveal starts, in the early
# mode) to have started its download, early enough to be within the 5 s reveal.
BETWEEN_SHOT_DELAY_S = 2.0
REVEAL_TIMEOUT_MS = 30_000
# Song 2 becomes audible at most about reveal 5 s + prepare timeout + lead later.
BETWEEN_DONE_TIMEOUT_MS = 120_000
SKIP_BUTTON = "스킵"
# Seconds for DevTools' viewport-size overlay to fade before a game shot.
OVERLAY_FADE_S = 1.5
# Korean time for the shot log (fixed UTC+9, no DST).
KST = timezone(timedelta(hours=9))
# The JS bundle's row name; its presence in DevTools proves the page load
# was recorded (the first live run recorded only favicon.ico).
BUNDLE_ROW = "index-"
PAGELOAD_ATTEMPTS = 2

# Hides the Google Fonts rows in the song views, so the song's own requests fit.
FONT_FILTER = "-domain:fonts.gstatic.com -domain:fonts.googleapis.com"
# The page-load view hides only the font files: the css2 stylesheet from
# fonts.googleapis.com stays, its Content-Encoding (gzip/br) the contrast to
# the site's own JS row.
PAGELOAD_FILTER = "-domain:fonts.gstatic.com"
PAGELOAD_GOTO_TIMEOUT_MS = 60_000
GRID_SCROLL_SETTLE_S = 0.3

# DevTools settings for the one-time setup, through the frontend's own
# Settings module: waterfall on, the noisy columns off, the Content-Encoding
# response header shown (compression evidence in the page-load shot), big
# request rows (Size shows transferred and resource size), Network selected,
# undocked (docked, the panel is a narrow strip beside a blank page area).
# Big rows: Chromium's NetworkLogView names the setting
# 'network-log-large-rows'; 'network-log.large-rows' is set too, harmlessly,
# in case this Edge build uses that spelling.
SETUP_JS = """async () => {
  const C = await import('./core/common/common.js');
  const s = C.Settings.Settings.instance();
  s.createSetting('network-log-columns', {}).set({
    waterfall: {visible: true}, initiator: {visible: false},
    'fulfilled-by': {visible: false}, source: {visible: false},
    'response-header-content-encoding': {visible: true, title: 'Content-Encoding'}});
  s.createSetting('network-log-large-rows', false).set(true);
  s.createSetting('network-log.large-rows', false).set(true);
  s.createSetting('panel-selected-tab', 'network').set('network');
  s.createSetting('currentDockState', 'right').set('undocked');
}"""

# Two clean-ups on every text node, shadow roots included:
# - a node naming the S3 bucket host (big rows show it under each name; the
#   bucket name holds the AWS account id) is replaced whole with BUCKET_LABEL;
#   the test is generous, the same markers as BUCKET_PATTERN below;
# - a node holding a presigned-URL marker is cut at its "?" and gets an
#   ellipsis (built from its code point so this file stays free of it).
# Returns how many nodes it changed.
BUCKET_LABEL = "S3 (ap-southeast-2)"
SANITIZE_JS = r"""() => {
  const bucket = /amazonaws|\.s3[.-]|songrush-audio-|(^|\D)\d{12}(\D|$)/i;
  const marker = /X-Amz-|Signature=|Credential=/;
  const tail = '?' + String.fromCharCode(0x2026);
  let fixed = 0;
  const walk = (root) => {
    const w = document.createTreeWalker(root, NodeFilter.SHOW_TEXT);
    for (let n = w.nextNode(); n; n = w.nextNode()) {
      if (bucket.test(n.nodeValue)) { n.nodeValue = '""" + BUCKET_LABEL + r"""'; fixed++; }
      if (marker.test(n.nodeValue)) { n.nodeValue = n.nodeValue.replace(/\?.*$/s, tail); fixed++; }
    }
    root.querySelectorAll('*').forEach(e => { if (e.shadowRoot) walk(e.shadowRoot); });
  };
  walk(document);
  return fixed;
}"""

# Every text node of the page, shadow roots included, as a list of strings.
# Text inside <style> and <script> is never drawn, so it is skipped (DevTools
# stylesheets would otherwise flood the summary log).
# Scrolls the Network grid to its first row. With big rows the log overflows,
# auto-scrolls to the bottom and drops the top rows from the DOM (virtual
# list), so the bundle row would be neither in the image nor in the deep
# text. Finds every scrollable data-grid / data-container element, shadow
# roots included. Returns how many it scrolled.
SCROLL_TOP_JS = """() => {
  let scrolled = 0;
  const walk = (root) => {
    root.querySelectorAll('*').forEach(e => {
      const cls = e.getAttribute('class') || '';
      if ((cls.includes('data-container') || cls.includes('data-grid'))
          && e.scrollHeight > e.clientHeight && e.scrollTop !== 0) {
        e.scrollTop = 0; scrolled++;
      }
      if (e.shadowRoot) walk(e.shadowRoot);
    });
  };
  walk(document);
  return scrolled;
}"""

DEEP_TEXT_JS = """() => {
  const out = [];
  const hidden = new Set(['STYLE', 'SCRIPT']);
  const walk = (root) => {
    const w = document.createTreeWalker(root, NodeFilter.SHOW_TEXT);
    for (let n = w.nextNode(); n; n = w.nextNode()) {
      if (!(n.parentNode && hidden.has(n.parentNode.nodeName))) out.push(n.nodeValue);
    }
    root.querySelectorAll('*').forEach(e => { if (e.shadowRoot) walk(e.shadowRoot); });
  };
  walk(document);
  return out;
}"""


# The Network panel's summary bar ("N requests", "... transferred",
# "Finish: ...", "DOMContentLoaded: ...", "Load: ..."), logged for each shot.
# The S3 bucket host or the AWS account id in it: never in an image or a log.
BUCKET_PATTERN = re.compile(r"amazonaws|\.s3\.|songrush-audio-|(?<!\d)\d{12}(?!\d)",
                            re.IGNORECASE)

FOOTER_MAX_LEN = 80
FOOTER_PATTERN = re.compile(
    r"\brequests?\b|transferred|resources|^Finish:|^DOMContentLoaded:|^Load:")


class ShotError(RuntimeError):
    """A capture step failed; the message never holds a URL query."""


# --- pure helpers (perf/test_devtools_shot.py) --------------------------------

def edge_args(profile, port, url):
    """Edge's command-line arguments (without the executable); the URL is last."""
    return [
        f"--user-data-dir={profile}",
        f"--remote-debugging-port={port}",
        "--auto-open-devtools-for-tabs",
        "--disable-extensions",
        "--no-first-run",
        "--no-default-browser-check",
        "--hide-crash-restore-bubble",
        "--lang=ko-KR",
        "--window-size=1300,900",
        "--autoplay-policy=no-user-gesture-required",
        url,
    ]


def shot_paths(label):
    """The output paths of one run, keyed by view and moment."""
    paths = {"pageload": SHOTS_DIR / f"{label}-devtools-pageload.png"}
    for fmt in ("choice", "chat"):
        for moment in ("loading", "done"):
            paths[f"{fmt}-{moment}"] = SHOTS_DIR / f"{label}-devtools-{fmt}-{moment}.png"
            paths[f"game-{fmt}-{moment}"] = SHOTS_DIR / f"{label}-game-{fmt}-{moment}.png"
    for moment in ("reveal", "done"):
        paths[f"between-{moment}"] = SHOTS_DIR / f"{label}-devtools-between-{moment}.png"
    paths["game-between-reveal"] = SHOTS_DIR / f"{label}-game-between-reveal.png"
    return paths


def deep_text_has_secret(texts):
    """True when any of the texts holds a presigned-URL marker."""
    return any(contains_secret(text) for text in texts)


def deep_text_has_bucket(texts):
    """True when any of the texts names the S3 bucket host (or holds a
    standalone 12-digit number, the shape of the AWS account id in it)."""
    return any(BUCKET_PATTERN.search(text) for text in texts)


def unsafe_text(text):
    """True when text must not be printed or logged (secret or bucket host)."""
    return contains_secret(text) or BUCKET_PATTERN.search(text) is not None


def default_edge():
    for candidate in EDGE_CANDIDATES:
        if os.path.exists(candidate):
            return candidate
    return EDGE_CANDIDATES[0]


def view_list(text):
    views = [v.strip() for v in text.split(",") if v.strip()]
    if not views:
        raise argparse.ArgumentTypeError("views must not be empty")
    unknown = [v for v in views if v not in VIEWS]
    if unknown:
        raise argparse.ArgumentTypeError(
            f"unknown view(s) {', '.join(unknown)}; choose from {','.join(VIEWS)}")
    return views


def parse_args(argv):
    parser = argparse.ArgumentParser(
        description="Capture the Edge DevTools Network panel on a live SongRush site "
                    "(page load, one choice song, one chat song, the gap between two songs).")
    parser.add_argument("--label", required=True, help="run label (goes into the file names)")
    parser.add_argument("--base", default=DEFAULT_BASE, help=f"site root (default {DEFAULT_BASE})")
    parser.add_argument("--views", type=view_list, default=list(VIEWS),
                        help=f"comma list of {','.join(VIEWS)} (default all)")
    parser.add_argument("--edge", default=default_edge(),
                        help="path to msedge.exe (default: Program Files (x86), then Program Files)")
    args = parser.parse_args(argv)
    if not args.base.endswith("/"):
        args.base += "/"
    return args


# --- live part -----------------------------------------------------------------

def log(text):
    print(text, file=sys.stderr, flush=True)


def rel(path):
    return path.relative_to(REPO_DIR).as_posix()


def kill_tree(proc):
    result = subprocess.run(["taskkill", "/PID", str(proc.pid), "/T", "/F"],
                            capture_output=True, text=True)
    if result.returncode != 0 and proc.poll() is None:
        log(f"taskkill exited {result.returncode} and Edge (pid {proc.pid}) is still running")


def connect(pw, port):
    """Attaches to Edge over CDP, retrying while Edge is still starting."""
    from playwright.sync_api import Error as PlaywrightError

    deadline = time.monotonic() + CDP_CONNECT_TIMEOUT_S
    while True:
        try:
            return pw.chromium.connect_over_cdp(f"http://127.0.0.1:{port}")
        except PlaywrightError:
            if time.monotonic() > deadline:
                raise ShotError(f"could not attach to Edge on port {port} "
                                f"within {CDP_CONNECT_TIMEOUT_S} s")
            time.sleep(1)


def all_pages(browser):
    return [pg for ctx in browser.contexts for pg in ctx.pages]


def find_pages(browser, origin, timeout_s):
    """The DevTools frontend pages and the site pages, once both are seen or
    after timeout_s (either list may then be empty)."""
    deadline = time.monotonic() + timeout_s
    while True:
        pages = all_pages(browser)
        # Only the DevTools frontend; device_mode_emulation_frame is a
        # different page and must not be used.
        dts = [pg for pg in pages if "devtools_app.html" in pg.url]
        sites = [pg for pg in pages if pg.url.startswith(origin)]
        if (dts and sites) or time.monotonic() >= deadline:
            return dts, sites
        time.sleep(0.5)


def attach(pw, origin):
    """Connects over CDP and finds the one DevTools page and the one site page.

    A CDP connection made before the DevTools target exists never shows that
    page (seen in the first live run), so when either page is missing after
    PAGE_LOOKUP_S the connection is closed and made again, until
    DEVTOOLS_PAGE_TIMEOUT_S. More than one of either (a restored tab brings
    its own DevTools) is not ready either: driving one tab while watching the
    other tab's DevTools records nothing.
    """
    deadline = time.monotonic() + DEVTOOLS_PAGE_TIMEOUT_S
    while True:
        browser = connect(pw, CDP_PORT)
        dts, sites = find_pages(browser, origin, PAGE_LOOKUP_S)
        if len(dts) == 1 and len(sites) == 1:
            return browser, dts[0], sites[0]
        browser.close()
        counts = f"{len(dts)} DevTools page(s), {len(sites)} site page(s)"
        if time.monotonic() >= deadline:
            raise ShotError(f"expected 1 DevTools page and 1 site page within "
                            f"{DEVTOOLS_PAGE_TIMEOUT_S} s; found {counts}")
        log(f"{counts}; reconnecting")


def reset_session(profile):
    """Stops the next launch from restoring the previous launch's tab.

    Edge is ended with taskkill, so it counts the exit as a crash and would
    reopen the old tab (with a second DevTools) next to the start URL. The
    saved session files are deleted, and Preferences is marked as a clean
    exit; everything else in it, the DevTools settings included, is kept.
    """
    default = Path(profile) / "Default"
    for name in SESSION_ENTRIES:
        entry = default / name
        if entry.is_dir():
            shutil.rmtree(entry)
        elif entry.exists():
            entry.unlink()
    prefs_path = default / "Preferences"
    if not prefs_path.exists():
        raise ShotError("the profile has no Default/Preferences: the DevTools "
                        "settings of the setup launch were not saved")
    prefs = json.loads(prefs_path.read_text(encoding="utf-8"))
    profile_prefs = prefs.setdefault("profile", {})
    profile_prefs["exit_type"] = "Normal"
    profile_prefs["exited_cleanly"] = True
    prefs_path.write_text(json.dumps(prefs, ensure_ascii=False, separators=(",", ":")),
                          encoding="utf-8")


@contextmanager
def edge_session(pw, edge, profile, base):
    """Launches Edge on base, yields (DevTools page, site page), always ends Edge."""
    origin = "{0.scheme}://{0.netloc}/".format(urlsplit(base))
    proc = subprocess.Popen([edge] + edge_args(profile, CDP_PORT, base))
    try:
        # Give Edge time to open the DevTools target before attaching.
        time.sleep(EDGE_START_S)
        browser, dt, site = attach(pw, origin)
        time.sleep(2)
        try:
            yield dt, site
        finally:
            browser.close()
    finally:
        kill_tree(proc)
        time.sleep(3)


def setup_profile(pw, edge, profile, base):
    with edge_session(pw, edge, profile, base) as (dt, _site):
        dt.evaluate(SETUP_JS)
        log(f"DevTools settings set; waiting {SETTINGS_FLUSH_S} s for Edge to save them")
        time.sleep(SETTINGS_FLUSH_S)


def footer_text(texts):
    """The Network summary bar's parts among the deep texts, joined by " | ".

    Only short nodes without "{" count, so no stylesheet text matches.
    """
    parts = [t.strip() for t in texts
             if len(t.strip()) <= FOOTER_MAX_LEN and "{" not in t
             and not unsafe_text(t) and FOOTER_PATTERN.search(t.strip())]
    return " | ".join(parts) if parts else "(summary bar not found)"


def scroll_grid_top(dt):
    dt.evaluate(SCROLL_TOP_JS)
    time.sleep(GRID_SCROLL_SETTLE_S)


def devtools_shot(dt, path):
    """Screenshots DevTools with signatures hidden; retakes until the text is clean."""
    for attempt in range(SHOT_ATTEMPTS):
        scroll_grid_top(dt)
        dt.evaluate(SANITIZE_JS)
        # Kept in memory: an image is written only after its check passes.
        png = dt.screenshot()
        # A row can appear between the clean-up and the screenshot, so the
        # image may hold text the clean-up never saw: check again after it.
        texts = dt.evaluate(DEEP_TEXT_JS)
        if not deep_text_has_secret(texts) and not deep_text_has_bucket(texts):
            path.write_bytes(png)
            if attempt > 0:
                log(f"{rel(path)}: clean after {attempt} retake(s)")
            log(f"{rel(path)} network summary: {footer_text(texts)}")
            saved(path)
            return
        time.sleep(0.3)
    raise ShotError(f"no clean DevTools image after {SHOT_ATTEMPTS} attempts; "
                    f"{rel(path)} not written")


def game_shot(site, path):
    # DevTools shows a viewport-size overlay while the mouse is over the
    # page; move it off and let the overlay fade.
    site.mouse.move(0, 0)
    time.sleep(OVERLAY_FADE_S)
    # Full page from the top, as perf/run.py takes them.
    site.evaluate("window.scrollTo(0, 0)")
    site.screenshot(path=str(path), full_page=True)
    saved(path)


def saved(path):
    """Prints the saved path, and logs its KST wall time."""
    log(f"{rel(path)} saved at {datetime.now(KST).strftime('%H:%M')} KST")
    print(rel(path), flush=True)


def open_network(dt, filter_text):
    dt.get_by_role("tab", name="Network").click()
    time.sleep(1)
    box = dt.get_by_role("textbox", name="Filter").first
    box.click()
    dt.keyboard.press("Control+A")
    dt.keyboard.press("Delete")
    if filter_text:
        dt.keyboard.type(filter_text)
    time.sleep(0.5)


def capture_pageload(dt, site, base, paths):
    # The panel is open and recording before the navigation, so the page
    # load's requests are logged.
    open_network(dt, PAGELOAD_FILTER)
    dt.get_by_role("checkbox", name="Disable cache").check()
    log("Disable cache is checked; it stays checked in this profile for the later views")
    for attempt in range(1, PAGELOAD_ATTEMPTS + 1):
        dt.get_by_role("button", name="Clear network log").click()
        time.sleep(1)
        # goto, not reload: a reload of the start page can be restored
        # without the requests showing.
        site.goto(base, wait_until="load", timeout=PAGELOAD_GOTO_TIMEOUT_MS)
        site.wait_for_load_state("networkidle")
        time.sleep(2)
        scroll_grid_top(dt)
        if any(BUNDLE_ROW in t for t in dt.evaluate(DEEP_TEXT_JS)):
            break
        log(f"page load attempt {attempt}: no {BUNDLE_ROW}* row in DevTools")
    else:
        raise ShotError(f"the page load was not recorded in DevTools after "
                        f"{PAGELOAD_ATTEMPTS} attempts; no pageload image taken")
    dt.keyboard.press("Control+E")  # stop recording
    time.sleep(1)
    devtools_shot(dt, paths["pageload"])


def start_game(dt, site, fmt):
    """Creates a fresh room in the format, clears the Network log, starts the game."""
    open_network(dt, FONT_FILTER)
    site.evaluate("() => { try { localStorage.clear(); sessionStorage.clear(); } catch (e) {} }")
    site.reload()
    site.wait_for_timeout(2000)
    site.get_by_role("button", name="한국어").click()
    site.wait_for_timeout(500)
    site.get_by_label("닉네임").fill(NICKNAME)
    site.get_by_role("button", name="방 만들기").click()
    if fmt == "choice":
        site.get_by_role("group", name="정답 형식").get_by_role("button", name="객관식").click()
    site.locator("select").first.select_option(SONG_COUNT)
    # Only this song's requests should show.
    dt.get_by_role("button", name="Clear network log").click()
    time.sleep(0.5)
    site.get_by_role("button", name="게임 시작").click()


def capture_song(dt, site, fmt, paths):
    start_game(dt, site, fmt)
    site.get_by_text(LOADING_TEXT).first.wait_for(timeout=LOADING_TIMEOUT_MS)
    time.sleep(LOADING_SHOT_DELAY_S)
    devtools_shot(dt, paths[f"{fmt}-loading"])
    game_shot(site, paths[f"game-{fmt}-loading"])

    site.wait_for_selector(".stage-hint", timeout=DONE_TIMEOUT_MS)
    time.sleep(1)
    dt.keyboard.press("Control+E")  # stop recording
    time.sleep(1)
    devtools_shot(dt, paths[f"{fmt}-done"])
    game_shot(site, paths[f"game-{fmt}-done"])


def capture_between(dt, site, paths):
    """The gap between song 1 and song 2 of a chat game (cycle 107)."""
    start_game(dt, site, "chat")
    site.wait_for_selector(".stage-hint", timeout=DONE_TIMEOUT_MS)  # song 1 audible
    time.sleep(1)
    # Only the gap's requests should show: song 2's download, and the reveal cover.
    dt.get_by_role("button", name="Clear network log").click()
    time.sleep(0.5)
    site.get_by_role("button", name=SKIP_BUTTON, exact=True).click()
    site.wait_for_selector("section.reveal", timeout=REVEAL_TIMEOUT_MS)
    # Start the overlay fade now, so the game shot below still lands in the reveal.
    site.mouse.move(0, 0)
    time.sleep(BETWEEN_SHOT_DELAY_S)
    devtools_shot(dt, paths["between-reveal"])  # still recording
    if not site.locator("section.reveal").first.is_visible():
        log("the reveal card was gone before the game shot; "
            f"{rel(paths['game-between-reveal'])} shows the screen after it")
    game_shot(site, paths["game-between-reveal"])

    # The hint is hidden during the reveal and shows again once song 2 is audible.
    site.wait_for_selector(".stage-hint", timeout=BETWEEN_DONE_TIMEOUT_MS)
    time.sleep(1)
    dt.keyboard.press("Control+E")  # stop recording
    time.sleep(1)
    devtools_shot(dt, paths["between-done"])


def safe_message(err):
    """The error's text, unless it could hold a presigned URL or the bucket host."""
    text = f"{type(err).__name__}: {err}"
    if unsafe_text(text) or "?" in text:
        return f"{type(err).__name__} (message hidden: it may contain a URL query or storage host)"
    return text


def main(argv=None):
    args = parse_args(argv)
    if not os.path.exists(args.edge):
        log(f"Edge not found at {args.edge}; pass --edge")
        return 2
    from playwright.sync_api import sync_playwright

    paths = shot_paths(args.label)
    SHOTS_DIR.mkdir(parents=True, exist_ok=True)
    profile = tempfile.mkdtemp(prefix="songrush-devtools-")
    try:
        with sync_playwright() as pw:
            setup_profile(pw, args.edge, profile, args.base)
            for view in args.views:
                log(f"view {view}")
                reset_session(profile)
                with edge_session(pw, args.edge, profile, args.base) as (dt, site):
                    if view == "pageload":
                        capture_pageload(dt, site, args.base, paths)
                    elif view == "between":
                        capture_between(dt, site, paths)
                    else:
                        capture_song(dt, site, view, paths)
    except Exception as err:  # reported, then the tool exits 1
        log(f"capture failed: {safe_message(err)}")
        return 1
    finally:
        shutil.rmtree(profile, ignore_errors=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
