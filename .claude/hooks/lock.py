"""Lock test paths for the current cycle so the implementer cannot edit them.

usage:
  python .claude/hooks/lock.py lock backend/src/test/java/songrush/RoomTest.java "data/tests/fixtures/*"
  python .claude/hooks/lock.py unlock
  python .claude/hooks/lock.py status
"""
import os
import sys

PROJECT = os.environ.get("CLAUDE_PROJECT_DIR") or os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
LOCK_FILE = os.path.join(PROJECT, ".claude", "state", "locked-paths.txt")


def main():
    if len(sys.argv) < 2 or sys.argv[1] not in ("lock", "unlock", "status"):
        print(__doc__)
        sys.exit(1)
    cmd = sys.argv[1]
    if cmd == "lock":
        os.makedirs(os.path.dirname(LOCK_FILE), exist_ok=True)
        with open(LOCK_FILE, "a", encoding="utf-8") as f:
            for p in sys.argv[2:]:
                f.write(p.replace("\\", "/") + "\n")
    elif cmd == "unlock":
        if os.path.exists(LOCK_FILE):
            os.remove(LOCK_FILE)
    if os.path.exists(LOCK_FILE):
        with open(LOCK_FILE, encoding="utf-8") as f:
            print("locked:\n" + f.read().strip())
    else:
        print("no locked paths")


if __name__ == "__main__":
    main()
