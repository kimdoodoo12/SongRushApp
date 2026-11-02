"""PreToolUse guard. Exit code 2 blocks the tool call and feeds stderr back to the agent.

Blocks:
- Edit/Write to paths locked by lock.py (tests written before implementation)
- git commit / merge while on main
- force push, push to main, hard reset, history rewrite
"""
import fnmatch
import json
import os
import re
import subprocess
import sys

def native(p):
    """Turn Git Bash style /c/Users/... into C:/Users/... on Windows."""
    m = re.match(r"^/([a-zA-Z])(/.*)?$", p or "")
    if os.name == "nt" and m:
        return f"{m.group(1).upper()}:{m.group(2) or '/'}"
    return p


PROJECT = native(os.environ.get("CLAUDE_PROJECT_DIR") or os.getcwd())
LOCK_FILE = os.path.join(PROJECT, ".claude", "state", "locked-paths.txt")

DANGEROUS_GIT = [
    (r"\bgit\s+push\b[^\n;|&]*(\s--force\b|\s--force-with-lease\b|\s-f\b|\s\+\S)", "force push is forbidden"),
    (r"\bgit\s+push\b[^\n;|&]*\bmain\b", "pushing main is the user's job; push dev or cycle/* instead"),
    (r"\bgit\s+reset\s+--hard\b", "hard reset is forbidden; use git stash or a new commit"),
    (r"\bgit\s+(rebase|filter-branch|filter-repo)\b", "history rewrite is forbidden"),
    (r"\bgit\s+branch\s+-D\s+(main|dev)\b", "deleting main/dev is forbidden"),
]


def block(msg):
    print(f"[guard] BLOCKED: {msg}", file=sys.stderr)
    sys.exit(2)


def rel(path):
    p = os.path.abspath(os.path.join(PROJECT, native(path)))
    try:
        r = os.path.relpath(p, PROJECT).replace("\\", "/")
        return r.lower() if os.name == "nt" else r
    except ValueError:
        return p.replace("\\", "/")


def locked_patterns():
    if not os.path.exists(LOCK_FILE):
        return []
    with open(LOCK_FILE, encoding="utf-8") as f:
        return [ln.strip() for ln in f if ln.strip() and not ln.startswith("#")]


def current_branch():
    try:
        # symbolic-ref also works on an unborn branch (repo with no commits yet)
        out = subprocess.run(["git", "symbolic-ref", "--short", "HEAD"], cwd=PROJECT,
                             capture_output=True, text=True, timeout=10)
        return out.stdout.strip()
    except Exception:
        return ""


def main():
    data = json.load(sys.stdin)
    tool = data.get("tool_name", "")
    ti = data.get("tool_input", {}) or {}

    if tool in ("Edit", "Write", "MultiEdit", "NotebookEdit"):
        path = ti.get("file_path") or ti.get("notebook_path") or ""
        r = rel(path)
        for pat in locked_patterns():
            if os.name == "nt":
                pat = pat.lower()
            if fnmatch.fnmatch(r, pat) or r == pat:
                block(f"{r} is locked for this cycle (pattern '{pat}'). "
                      "Tests are fixed before implementation; make the code pass them. "
                      "If a test itself is wrong, report it to the lead instead of editing it.")
        return

    if tool in ("Bash", "PowerShell"):
        cmd = ti.get("command", "")
        for pat, why in DANGEROUS_GIT:
            if re.search(pat, cmd):
                block(why)
        if re.search(r"\bgit\s+(commit|merge|cherry-pick)\b", cmd) and current_branch() == "main":
            block("you are on main; switch to dev or a cycle/* branch first")
        if "locked-paths.txt" in cmd and not re.search(r"lock\.py\s+(status)\b", cmd):
            block("do not touch the lock file directly; only the lead uses lock.py")


if __name__ == "__main__":
    main()
