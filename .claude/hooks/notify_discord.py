"""Dev notifications to Discord (for the human supervising the loop).

Modes (first argv):
  notification  Notification hook: Claude is waiting on a permission prompt or idle input.
                Also usage-limit auto-resume events (quota_auto_resume_fired / _stale / _disabled).
  stop          Project Stop hook: report NEW "Needs decision" items in PROGRESS.md or NEW BLOCKED entries.
  halt          Global Stop hook: Claude finished its turn and is idle. Sends the last message excerpt.
  failure       StopFailure hook: the turn ended on an API error (usage limit, overload, network...).
  end           SessionEnd hook: the session closed.
  flush-loop    Internal: background retrier for the outbox.

Delivery: a message that cannot be sent (for example the network is down, which is
exactly when a connection failure happens) is appended to an outbox file and a detached
background retrier resends it every 30 s for up to 12 h. Every hook run also flushes
the outbox first, so queued alerts go out as soon as anything runs again.

The webhook URL comes from the BA_DEV_DISCORD_WEBHOOK environment variable and is never
committed or written to the outbox. If it is unset or not a Discord webhook URL, nothing is sent.
Always exits 0: a notification failure must never block or break a cycle.
"""
import hashlib
import json
import os
import re
import subprocess
import sys
import time
import urllib.request

PROJECT = os.environ.get("CLAUDE_PROJECT_DIR") or os.getcwd()
STATE = os.path.join(PROJECT, ".claude", "state")
SEEN_FILE = os.path.join(STATE, "notify-seen.json")
GLOBAL_STATE = os.path.join(os.path.expanduser("~"), ".claude", "state")
OUTBOX = os.path.join(GLOBAL_STATE, "discord-outbox.jsonl")
RETRIER_PID = os.path.join(GLOBAL_STATE, "discord-retrier.pid")
WEBHOOK_RE = re.compile(r"^https://(discord\.com|discordapp\.com)/api/webhooks/\d+/[\w-]+$")
UA = "ClaudeDevNotify/0.2"
LIMIT = 1900  # Discord content limit is 2000
RETRY_EVERY_S = 30
RETRY_FOR_S = 12 * 3600


def webhook_url():
    url = (os.environ.get("BA_DEV_DISCORD_WEBHOOK") or "").strip()
    test_url = os.environ.get("BA_DEV_NOTIFY_TEST_URL")  # local test server only
    if test_url and test_url.startswith("http://127.0.0.1:"):
        return test_url
    return url if WEBHOOK_RE.match(url) else None


def post(text):
    url = webhook_url()
    if not url:
        return True  # nothing configured: treat as delivered, never queue
    body = json.dumps({"content": text[:LIMIT], "allowed_mentions": {"parse": []}}).encode("utf-8")
    req = urllib.request.Request(url, data=body, method="POST", headers={
        "Content-Type": "application/json",
        "User-Agent": UA,
    })
    try:
        with urllib.request.urlopen(req, timeout=8) as r:
            return 200 <= r.status < 300
    except Exception as e:  # never fail the hook
        print(f"[notify] send failed: {type(e).__name__}", file=sys.stderr)
        return False


def read_outbox():
    try:
        with open(OUTBOX, encoding="utf-8") as f:
            return [json.loads(ln) for ln in f if ln.strip()]
    except Exception:
        return []


def write_outbox(items):
    os.makedirs(GLOBAL_STATE, exist_ok=True)
    tmp = OUTBOX + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        for it in items:
            f.write(json.dumps(it, ensure_ascii=False) + "\n")
    os.replace(tmp, OUTBOX)


def flush_outbox():
    """Try to send queued messages in order. Returns the number still queued."""
    items = read_outbox()
    if not items:
        return 0
    left = []
    for i, it in enumerate(items):
        if post(it["text"]):
            continue
        left = items[i:]
        break
    write_outbox(left)
    return len(left)


def retrier_running():
    try:
        with open(RETRIER_PID, encoding="utf-8") as f:
            pid = int(f.read().strip())
    except Exception:
        return False
    if sys.platform == "win32":
        out = subprocess.run(["tasklist", "/FI", f"PID eq {pid}", "/NH"],
                             capture_output=True, text=True).stdout
        return str(pid) in out
    try:
        os.kill(pid, 0)
        return True
    except OSError:
        return False


def start_retrier():
    if retrier_running():
        return
    flags = 0
    if sys.platform == "win32":
        flags = 0x00000008 | 0x00000200 | 0x08000000  # DETACHED | NEW_GROUP | NO_WINDOW
    subprocess.Popen([sys.executable, "-I", os.path.abspath(__file__), "flush-loop"],
                     stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                     close_fds=True, creationflags=flags, cwd=os.path.expanduser("~"))


def send(text):
    """Send now; on failure queue it and make sure the background retrier runs."""
    stamp = time.strftime("%m-%d %H:%M")
    queued_before = flush_outbox()
    if queued_before == 0 and post(text):
        return True
    items = read_outbox()
    items.append({"text": f"{text}\n_(queued {stamp}, delivered late)_"})
    write_outbox(items)
    start_retrier()
    return False


def flush_loop():
    os.makedirs(GLOBAL_STATE, exist_ok=True)
    with open(RETRIER_PID, "w", encoding="utf-8") as f:
        f.write(str(os.getpid()))
    deadline = time.time() + RETRY_FOR_S
    try:
        while time.time() < deadline:
            if flush_outbox() == 0:
                return
            time.sleep(RETRY_EVERY_S)
    finally:
        try:
            os.remove(RETRIER_PID)
        except OSError:
            pass


def project_name():
    return os.path.basename(os.path.normpath(PROJECT))


def excerpt(s, n=600):
    s = (s or "").strip()
    return s if len(s) <= n else s[:n] + " ..."


def on_notification(data):
    kind = data.get("notification_type") or "notification"
    msg = data.get("message") or "(no message)"
    label = {
        "permission_prompt": "Waiting for permission",
        "idle_prompt": "Waiting for input",
        "quota_auto_resume_fired": "Usage limit reset - resumed automatically",
        "quota_auto_resume_stale": "Usage limit reset while the PC slept - press Enter in the session to continue",
        "quota_auto_resume_disabled": "Auto-resume stopped - send a prompt to continue",
    }.get(kind, kind)
    send(f"**[{project_name()}] {label}**\n{msg}")


def on_failure(data):
    err = data.get("error") or "unknown"
    label = {
        "rate_limit": "Stopped: usage/rate limit",
        "overloaded": "Stopped: API overloaded",
        "authentication_failed": "Stopped: authentication failed",
        "billing_error": "Stopped: billing error",
    }.get(err, f"Stopped: API error ({err})")
    detail = data.get("error_details") or data.get("last_assistant_message") or "(no details)"
    send(f"**[{project_name()}] {label}**\n{excerpt(str(detail))}")


def on_halt(data):
    if data.get("stop_hook_active"):
        return  # re-fire while another Stop hook is blocking; report once per real stop
    last = excerpt(data.get("last_assistant_message") or "", 500)
    send(f"**[{project_name()}] Claude stopped (idle)**\n{last or '(no message)'}")


def on_end(data):
    reason = data.get("reason") or "unknown"
    send(f"**[{project_name()}] Session ended** ({reason})")


def needs_decision_items():
    path = os.path.join(PROJECT, "PROGRESS.md")
    if not os.path.exists(path):
        return []
    with open(path, encoding="utf-8") as f:
        text = f.read()
    m = re.search(r"^## Needs decision\s*\n(.*?)(?=^## |\Z)", text, re.S | re.M)
    if not m:
        return []
    return [ln.strip()[2:].strip() for ln in m.group(1).splitlines() if ln.strip().startswith("- ")]


def blocked_digest():
    path = os.path.join(STATE, "blocked.log")
    if not os.path.exists(path):
        return ""
    with open(path, "rb") as f:
        return hashlib.sha256(f.read()).hexdigest()


def load_seen():
    try:
        with open(SEEN_FILE, encoding="utf-8") as f:
            return json.load(f)
    except Exception:
        return {"decisions": None, "blocked": ""}


def on_stop(data):
    if data.get("stop_hook_active"):
        return
    seen = load_seen()
    items = needs_decision_items()
    blocked = blocked_digest()
    first_run = seen.get("decisions") is None
    new_items = [] if first_run else [i for i in items if i not in seen["decisions"]]
    blocked_changed = bool(blocked) and blocked != seen.get("blocked", "") and not first_run

    lines = []
    if new_items:
        lines.append(f"New decisions needed ({len(new_items)}, {len(items)} open in total):")
        lines += [f"- {i}" for i in new_items]
    if blocked_changed:
        lines.append("A backlog item hit the retry cap (BLOCKED). See .claude/state/blocked.log.")
    if lines:
        send(f"**[{project_name()}] Cycle report**\n" + "\n".join(lines))

    os.makedirs(STATE, exist_ok=True)
    with open(SEEN_FILE, "w", encoding="utf-8") as f:
        json.dump({"decisions": items, "blocked": blocked}, f, ensure_ascii=False)


def main():
    mode = sys.argv[1] if len(sys.argv) > 1 else "notification"
    if mode == "flush-loop":
        try:
            flush_loop()
        except Exception:
            pass
        sys.exit(0)
    try:
        data = json.load(sys.stdin)
    except Exception:
        data = {}
    try:
        handler = {
            "stop": on_stop,
            "halt": on_halt,
            "failure": on_failure,
            "end": on_end,
        }.get(mode, on_notification)
        handler(data)
    except Exception as e:
        print(f"[notify] error: {type(e).__name__}: {e}", file=sys.stderr)
    sys.exit(0)


if __name__ == "__main__":
    main()
