"""Captures the Edge DevTools Network panel on a live SongRush site (cycle 104, D-115).

Run 103's charts were redrawn from the measurements; this tool captures the
measurement itself, the browser's own Network panel, for three views:

- pageload: the home page loaded with "Disable cache" checked;
- choice: one song of a choice room, while it loads and when its audio is done;
- chat: the same two moments in a chat room.

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
   After the screenshot the deep text is read again and checked with
   perflib.contains_secret; a hit deletes the image and retakes it, up to 30
   times. No clean image: the image is deleted and the tool exits 1.
4. The Edge process tree is always ended with taskkill, and the temp profile
   is deleted at the end.

Writes perf/results/shots/<label>-devtools-<view>.png and, for the song views,
<label>-game-<format>-<moment>.png, and prints each saved path.

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
from pathlib import Path
from urllib.parse import urlsplit

from perflib import contains_secret

PERF_DIR = Path(__file__).resolve().parent
REPO_DIR = PERF_DIR.parent
SHOTS_DIR = PERF_DIR / "results" / "shots"

DEFAULT_BASE = "https://animeopguessr.com/"
VIEWS = ("pageload", "choice", "chat")
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
# The JS bundle's row name; its presence in DevTools proves the page load
# was recorded (the first live run recorded only favicon.ico).
BUNDLE_ROW = "index-"
PAGELOAD_ATTEMPTS = 2

# Hides the Google Fonts rows in the song views, so the song's own requests fit.
FONT_FILTER = "-domain:fonts.gstatic.com -domain:fonts.googleapis.com"

# DevTools settings for the one-time setup, through the frontend's own
# Settings module: waterfall on, the noisy columns off, Network selected,
# undocked (docked, the panel is a narrow strip beside a blank page area).
SETUP_JS = """async () => {
  const C = await import('./core/common/common.js');
  const s = C.Settings.Settings.instance();
  s.createSetting('network-log-columns', {}).set({
    waterfall: {visible: true}, initiator: {visible: false},
    'fulfilled-by': {visible: false}, source: {visible: false}});
  s.createSetting('panel-selected-tab', 'network').set('network');
  s.createSetting('currentDockState', 'right').set('undocked');
}"""

# Cuts every text node holding a presigned-URL marker at its "?" and appends
# an ellipsis (built from its code point so this file stays free of it).
# Walks shadow roots too. Returns how many nodes it changed.
SANITIZE_JS = r"""() => {
  const marker = /X-Amz-|Signature=|Credential=/;
  const tail = '?' + String.fromCharCode(0x2026);
  let fixed = 0;
  const walk = (root) => {
    const w = document.createTreeWalker(root, NodeFilter.SHOW_TEXT);
    for (let n = w.nextNode(); n; n = w.nextNode()) {
      if (marker.test(n.nodeValue)) { n.nodeValue = n.nodeValue.replace(/\?.*$/s, tail); fixed++; }
    }
    root.querySelectorAll('*').forEach(e => { if (e.shadowRoot) walk(e.shadowRoot); });
  };
  walk(document);
  return fixed;
}"""

# Every text node of the page, shadow roots included, as a list of strings.
DEEP_TEXT_JS = """() => {
  const out = [];
  const walk = (root) => {
    const w = document.createTreeWalker(root, NodeFilter.SHOW_TEXT);
    for (let n = w.nextNode(); n; n = w.nextNode()) out.push(n.nodeValue);
    root.querySelectorAll('*').forEach(e => { if (e.shadowRoot) walk(e.shadowRoot); });
  };
  walk(document);
  return out;
}"""


# The Network panel's summary bar ("N requests", "... transferred",
# "Finish: ...", "DOMContentLoaded: ...", "Load: ..."), logged for each shot.
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
    return paths


def deep_text_has_secret(texts):
    """True when any of the texts holds a presigned-URL marker."""
    return any(contains_secret(text) for text in texts)


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
                    "(page load, one choice song, one chat song).")
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
    """The Network summary bar's parts among the deep texts, joined by " | "."""
    parts = [t.strip() for t in texts if FOOTER_PATTERN.search(t.strip())]
    return " | ".join(parts) if parts else "(summary bar not found)"


def devtools_shot(dt, path):
    """Screenshots DevTools with signatures hidden; retakes until the text is clean."""
    for attempt in range(SHOT_ATTEMPTS):
        dt.evaluate(SANITIZE_JS)
        dt.screenshot(path=str(path))
        # A row can appear between the clean-up and the screenshot, so the
        # image may hold text the clean-up never saw: check again after it.
        texts = dt.evaluate(DEEP_TEXT_JS)
        if not deep_text_has_secret(texts):
            if attempt > 0:
                log(f"{rel(path)}: clean after {attempt} retake(s)")
            log(f"{rel(path)} network summary: {footer_text(texts)}")
            print(rel(path), flush=True)
            return
        path.unlink()
        time.sleep(0.3)
    raise ShotError(f"no clean DevTools image after {SHOT_ATTEMPTS} attempts; "
                    f"{rel(path)} not kept")


def game_shot(site, path):
    # Full page from the top, as perf/run.py takes them.
    site.evaluate("window.scrollTo(0, 0)")
    site.screenshot(path=str(path), full_page=True)
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
    open_network(dt, "")
    dt.get_by_role("checkbox", name="Disable cache").check()
    log("Disable cache is checked; it stays checked in this profile for the later views")
    for attempt in range(1, PAGELOAD_ATTEMPTS + 1):
        dt.get_by_role("button", name="Clear network log").click()
        time.sleep(1)
        # goto, not reload: a reload of the start page can be restored
        # without the requests showing.
        site.goto(base, wait_until="load")
        site.wait_for_load_state("networkidle")
        time.sleep(2)
        if any(BUNDLE_ROW in t for t in dt.evaluate(DEEP_TEXT_JS)):
            break
        log(f"page load attempt {attempt}: no {BUNDLE_ROW}* row in DevTools")
    else:
        raise ShotError(f"the page load was not recorded in DevTools after "
                        f"{PAGELOAD_ATTEMPTS} attempts; no pageload image taken")
    dt.keyboard.press("Control+E")  # stop recording
    time.sleep(1)
    devtools_shot(dt, paths["pageload"])


def capture_song(dt, site, fmt, paths):
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


def safe_message(err):
    """The error's text, unless it could hold a presigned URL."""
    text = f"{type(err).__name__}: {err}"
    if contains_secret(text) or "?" in text:
        return f"{type(err).__name__} (message hidden: it may contain a URL query)"
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
