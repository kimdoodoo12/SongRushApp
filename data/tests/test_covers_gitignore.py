"""Cycle 090 criterion 9: the cover cache is ignored by git.

docs/cycles/090-cover-tool.md is the specification. Same check as
test_audio.py's test_audio_cache_is_git_ignored: git check-ignore with
--no-index, skipped when git or the work tree is not available.
"""

import shutil
import subprocess
from pathlib import Path

import pytest

REPO_ROOT = Path(__file__).resolve().parents[2]


@pytest.mark.parametrize("relative", [
    "data/cover-cache/x.jpg",
    "data/cover-cache/index.json",
    "data/cover-cache/.covers.lock",
])
def test_cover_cache_is_git_ignored(relative):
    git = shutil.which("git")
    if git is None:
        pytest.skip("git is not available; cannot check .gitignore for %s" % relative)
    inside = subprocess.run([git, "rev-parse", "--is-inside-work-tree"], cwd=REPO_ROOT,
                            capture_output=True, text=True)
    if inside.returncode != 0:
        pytest.skip("%s is not a git work tree; cannot check .gitignore" % REPO_ROOT)
    result = subprocess.run([git, "check-ignore", "-q", "--no-index", relative], cwd=REPO_ROOT,
                            capture_output=True, text=True)
    assert result.returncode == 0, "%s is not ignored by git (%s)" % (relative, result.stderr.strip())
