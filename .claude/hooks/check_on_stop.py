"""Stop / SubagentStop hook: run the tests of the areas this session changed, during a cycle.

- Runs only while lock.py has locked test paths. With no lock this is not an implementation phase:
  it exits at once and runs nothing, so a session that only answers questions never waits on tests.
- Sessions share one working folder, so `git status` also shows other sessions' work. Only areas
  this session wrote are tested, read from its own transcript. A session that delegated to a writing
  agent, or changed files from the shell, gets every changed area, because its files are unknown.
- Exit 2 (block stopping, stderr goes to the agent) when tests fail, up to MAX_RETRIES per session.
- After the cap, allow stopping and append a BLOCKED entry to .claude/state/blocked.log.
  blocked.log is the append-only machine trace: never edited or deleted. Each entry starts with
  a unique id (BLOCKED-<UTC time>-<session>); the lead copies entries whose id is not yet in
  PROGRESS.md, so nothing is imported twice.
- Areas that are not bootstrapped yet are skipped with a notice (never counted as a pass in reports).
- Read-only agents (verifier, critic, explorer) are never blocked: they report failures, they cannot fix them.
"""
import json
import os
import re
import subprocess
import sys
from datetime import datetime, timezone

PROJECT = os.environ.get("CLAUDE_PROJECT_DIR") or os.getcwd()
STATE = os.path.join(PROJECT, ".claude", "state")
LOCK_FILE = os.path.join(STATE, "locked-paths.txt")
MAX_RETRIES = 3
WIN = os.name == "nt"
READ_ONLY_AGENTS = {"verifier", "critic", "explorer", "Explore", "Plan", "web-fetch", "claude-code-guide"}
EDIT_TOOLS = {"Edit", "Write", "MultiEdit", "NotebookEdit"}
AGENT_TOOLS = {"Agent", "Task"}
SHELL_TOOLS = {"Bash", "PowerShell"}
# Shell commands that change files or mark this session as a cycle's lead.
SHELL_WRITE = re.compile(r"lock\.py\s+lock\b|\bgit\s+(commit|merge|cherry-pick|apply)\b|\bsed\s+-i\b"
                         r"|\b(Set-Content|Add-Content|Out-File)\b")


def sh(args, cwd=PROJECT, timeout=900):
    return subprocess.run(args, cwd=cwd, capture_output=True, text=True,
                          encoding="utf-8", errors="replace", timeout=timeout)


def changed_files():
    files = set()
    st = sh(["git", "status", "--porcelain"])
    for ln in st.stdout.splitlines():
        files.add(ln[3:].strip().strip('"'))
    base = sh(["git", "rev-parse", "--verify", "--quiet", "dev"])
    if base.returncode == 0:
        d = sh(["git", "diff", "--name-only", "dev...HEAD"])
        files.update(x for x in d.stdout.splitlines() if x)
    return files


def top_folder(path):
    """The top-level project folder of a path, or None when it is outside the project."""
    try:
        r = os.path.relpath(os.path.abspath(os.path.join(PROJECT, path)), PROJECT).replace(os.sep, "/")
    except ValueError:
        return None
    if r.startswith("..") or "/" not in r:
        return None
    return r.split("/", 1)[0]


def written_areas(data):
    """Top-level folders this session wrote, or None when that cannot be known."""
    if data.get("agent_type"):
        return None  # a writing subagent: READ_ONLY_AGENTS already returned earlier
    session = data.get("session_id")
    written = set()
    try:
        with open(data["transcript_path"], encoding="utf-8", errors="replace") as f:
            for line in f:
                entry = json.loads(line)
                # A forked transcript also holds the parent session's calls, under the parent's id.
                if entry.get("type") != "assistant" or entry.get("sessionId") != session:
                    continue
                content = (entry.get("message") or {}).get("content")
                for block in content if isinstance(content, list) else []:
                    if not isinstance(block, dict) or block.get("type") != "tool_use":
                        continue
                    name, tool_input = block.get("name"), block.get("input") or {}
                    if name in EDIT_TOOLS:
                        top = top_folder(tool_input.get("file_path") or tool_input.get("notebook_path") or "")
                        if top:
                            written.add(top)
                    elif name in AGENT_TOOLS and tool_input.get("subagent_type") not in READ_ONLY_AGENTS:
                        return None
                    elif name in SHELL_TOOLS and SHELL_WRITE.search(tool_input.get("command") or ""):
                        return None
    except (KeyError, OSError, ValueError):
        return None
    return written


# A change under these top-level folders also runs these areas' tests
# (shared cross-language contracts are checked by both JUnit and pytest).
EXTRA_AREAS = {"contracts": {"backend", "data"}, "deploy": {"data"}, "docs": {"data"}}


def runners():
    """area -> (ready, cmd, cwd, timeout)."""
    frontend = os.path.join(PROJECT, "frontend")
    npm = "npm.cmd" if WIN else "npm"
    frontend_ready = (os.path.exists(os.path.join(frontend, "package.json"))
                      and os.path.isdir(os.path.join(frontend, "node_modules")))

    backend = os.path.join(PROJECT, "backend")
    gradlew = os.path.join(backend, "gradlew.bat" if WIN else "gradlew")

    data = os.path.join(PROJECT, "data")
    py = os.path.join(data, ".venv", "Scripts", "python.exe") if WIN else os.path.join(data, ".venv", "bin", "python")
    data_ready = os.path.exists(py) and os.path.isdir(os.path.join(data, "tests"))

    return {
        # settings.json caps the whole Stop hook at 900 s, so each runner stays below it.
        # The first Gradle download is done manually in the bootstrap cycle, never here.
        "frontend": (frontend_ready, [npm, "test", "--silent"], frontend, 850),
        "backend": (os.path.exists(gradlew), [gradlew, "test", "--console=plain", "-q"], backend, 850),
        "data": (data_ready, [py, "-m", "pytest", "-q"], data, 850),
    }


def counter_path(session):
    return os.path.join(STATE, f"stop-retries-{session}.txt")


def main():
    data = json.loads(sys.stdin.buffer.read().decode("utf-8"))
    session = (data.get("session_id") or "nosession")[:40]
    agent = data.get("agent_type") or ""
    if agent in READ_ONLY_AGENTS:
        sys.exit(0)
    # SubagentStop also fires for Claude Code's internal agents (prompt suggestions, /btw),
    # which arrive with an empty agent_type. They never touch code, so never test or block them.
    if data.get("hook_event_name") == "SubagentStop" and not agent:
        sys.exit(0)
    if not os.path.exists(LOCK_FILE):
        sys.exit(0)
    areas = {f.split("/", 1)[0] for f in changed_files() if "/" in f}
    written = written_areas(data)
    if written is not None:
        areas &= written
    for top, extra in EXTRA_AREAS.items():
        if top in areas:
            areas |= extra
    failures, notices = [], []
    for area, (ready, cmd, cwd, timeout) in runners().items():
        if area not in areas:
            continue
        if not ready:
            notices.append(f"{area}: changed but no test runner bootstrapped -> UNVERIFIED")
            continue
        try:
            r = sh(cmd, cwd=cwd, timeout=timeout)
        except subprocess.TimeoutExpired:
            failures.append(f"{area}: tests timed out")
            continue
        if r.returncode != 0:
            tail = "\n".join((r.stdout + r.stderr).strip().splitlines()[-40:])
            failures.append(f"{area}: `{' '.join(cmd)}` exit {r.returncode}\n{tail}")

    os.makedirs(STATE, exist_ok=True)
    cp = counter_path(session)
    if not failures:
        if os.path.exists(cp):
            os.remove(cp)
        if notices:
            print("[stop-check] " + "; ".join(notices), file=sys.stderr)
        sys.exit(0)

    n = int(open(cp).read()) if os.path.exists(cp) else 0
    n += 1
    with open(cp, "w") as f:
        f.write(str(n))
    if n > MAX_RETRIES:
        with open(os.path.join(STATE, "blocked.log"), "a", encoding="utf-8") as f:
            stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
            branch = sh(["git", "rev-parse", "--abbrev-ref", "HEAD"]).stdout.strip()
            f.write(f"## BLOCKED-{stamp}-{session[:8]} branch={branch} agent={agent or 'lead'}\n"
                    f"tests still failing after {MAX_RETRIES} retries\n"
                    + "\n".join(failures) + "\n---\n")
        os.remove(cp)
        print(f"[stop-check] retry cap reached; recorded in .claude/state/blocked.log. "
              "Mark the backlog item BLOCKED in PROGRESS.md.", file=sys.stderr)
        sys.exit(0)
    print(f"[stop-check] tests failing (attempt {n}/{MAX_RETRIES}). Fix before finishing:\n\n"
          + "\n\n".join(failures), file=sys.stderr)
    sys.exit(2)


if __name__ == "__main__":
    main()
