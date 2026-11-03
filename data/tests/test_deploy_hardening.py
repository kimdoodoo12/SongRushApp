"""Cycle 057 criteria 1-6: deploy kit hardening (I-008).

docs/cycles/057-deploy-hardening.md is the specification. Every deploy/ file
is read inside a test, never at import time, so a missing file fails its
tests rather than the collection. Nothing here talks to a server.

The harness is copied from the cycle 055 tests (test_deploy_kit.py, locked):
the same bash finder, the same wrapper `tools` folder that hides ssh, sftp,
scp and rsync, and the same temp repo layout. The differences:

- The ssh stub tells its two calls apart by `find` (only the remote size
  listing contains it). Every other call exits with $STUB_FINAL_EXIT, so a
  test can make the final remote command succeed (0) or fail (1).
- setup.sh runs with stub `cloud-init` and `apt-get` first on PATH; both log
  their calls, so a test can assert they were never reached.
"""

import os
import re
import shutil
import subprocess
from pathlib import Path

import pytest

REPO = Path(__file__).resolve().parents[2]
DEPLOY = REPO / "deploy"
UNIT = DEPLOY / "songrush.service"
SETUP = DEPLOY / "setup.sh"
UPLOAD = DEPLOY / "upload.sh"

JAR_NAME = "backend-0.0.1-SNAPSHOT.jar"
PLAIN_JAR_NAME = "backend-0.0.1-SNAPSHOT-plain.jar"
AUDIO_BYTES = {
    "song-same.ogg": b"s" * 1234,
    "song-new.ogg": b"n" * 3456,
}
AUDIO_INDEX_BYTES = b'{"files": 2}\n'
CATALOG_BYTES = b"{}\n"
INDEX_BYTES = b"<!doctype html><title>songrush</title>\n"
ASSET_BYTES = b"console.log(1);\n"

HOST = "user@host"
RESTORE_COMMAND = ("ssh user@host 'cp -p /opt/songrush/app/songrush.jar.good "
                   "/opt/songrush/app/songrush.jar && sudo systemctl restart songrush'")
FINAL_NEEDLES = ("systemctl restart songrush", "http://127.0.0.1:8080/health",
                 "songrush.jar.good", "journalctl -u songrush")
FINAL_UNIQUE = ("127.0.0.1:8080/health", "journalctl", "curl ")

RUN_TIMEOUT = 120
HIDDEN_TOOLS = {"sftp", "scp", "rsync"}


# ---------------------------------------------------------------- helpers

def _read_text(path):
    assert path.is_file(), f"missing {path.relative_to(REPO).as_posix()}"
    return path.read_bytes().decode("ascii")


def _ignored_dir(directory):
    lowered = directory.lower().replace("/", "\\")
    return "windowsapps" in lowered or "\\system32" in lowered


def _find_bash():
    names = ["bash.exe", "bash"] if os.name == "nt" else ["bash"]
    found = None
    for directory in os.environ.get("PATH", "").split(os.pathsep):
        if not directory or _ignored_dir(directory):
            continue
        for name in names:
            candidate = Path(directory) / name
            if candidate.is_file():
                found = candidate
                break
        if found is not None:
            break
    if found is None and os.name == "nt":
        roots = [os.environ.get("ProgramFiles", r"C:\Program Files"),
                 os.environ.get("ProgramW6432", r"C:\Program Files")]
        for root in roots:
            for sub in (("usr", "bin"), ("bin",)):
                candidate = Path(root, "Git", *sub, "bash.exe")
                if candidate.is_file():
                    found = candidate
                    break
            if found is not None:
                break
    if found is None:
        return None
    if os.name == "nt" and found.parent.name.lower() == "bin" \
            and found.parent.parent.name.lower() != "usr":
        sibling = found.parent.parent / "usr" / "bin" / "bash.exe"
        if sibling.is_file():
            found = sibling
    return found


BASH = _find_bash()
needs_bash = pytest.mark.skipif(BASH is None, reason="bash not found on PATH or in Git for Windows")


def _hidden(name):
    return name.startswith("ssh") or name in HIDDEN_TOOLS


def _write_script(path, text):
    path.write_bytes(text.encode("ascii"))
    path.chmod(0o755)


@pytest.fixture(scope="session")
def tools_dir(tmp_path_factory):
    """Wrappers for every program next to bash, minus ssh, sftp, scp, rsync."""
    if BASH is None:
        pytest.skip("bash not found on PATH or in Git for Windows")
    tools = tmp_path_factory.mktemp("tools")
    for entry in BASH.parent.iterdir():
        if not entry.is_file():
            continue
        if os.name == "nt":
            if entry.suffix.lower() != ".exe":
                continue
            name = entry.stem
        else:
            if not os.access(entry, os.X_OK):
                continue
            name = entry.name
        if _hidden(name.lower()) or not re.fullmatch(r"[A-Za-z0-9_.+\-]+", name):
            continue
        _write_script(tools / name, f'#!/bin/sh\nexec "{entry.as_posix()}" "$@"\n')
    return tools


# The size listing (args contain find) gets the canned listing and exit 0;
# any other call is the final remote command and exits $STUB_FINAL_EXIT.
SSH_STUB = """#!/bin/sh
printf 'ssh' >> "$STUB_LOG"
for a in "$@"; do printf ' %s' "$a" >> "$STUB_LOG"; done
printf '\\n' >> "$STUB_LOG"
case "$*" in
  *find*)
    if [ -n "$STUB_LISTING" ] && [ -f "$STUB_LISTING" ]; then cat "$STUB_LISTING"; fi
    exit 0
    ;;
esac
echo "stub-remote-output"
exit "${STUB_FINAL_EXIT:-0}"
"""

SFTP_STUB = """#!/bin/sh
printf 'sftp' >> "$STUB_LOG"
for a in "$@"; do printf ' %s' "$a" >> "$STUB_LOG"; done
printf '\\n' >> "$STUB_LOG"
batch=""
prev=""
for a in "$@"; do
  if [ "$prev" = "-b" ]; then batch="$a"; fi
  case "$a" in -b?*) batch="${a#-b}" ;; esac
  prev="$a"
done
if [ -z "$batch" ] || [ "$batch" = "-" ]; then
  cat >> "$STUB_BATCH"
elif [ -f "$batch" ]; then
  cat "$batch" >> "$STUB_BATCH"
fi
exit 0
"""

RSYNC_STUB = """#!/bin/sh
printf 'rsync' >> "$STUB_LOG"
for a in "$@"; do printf ' %s' "$a" >> "$STUB_LOG"; done
printf '\\n' >> "$STUB_LOG"
exit 0
"""


def _logging_stub(name):
    return (f"#!/bin/sh\nprintf '{name}' >> \"$STUB_LOG\"\n"
            "for a in \"$@\"; do printf ' %s' \"$a\" >> \"$STUB_LOG\"; done\n"
            "printf '\\n' >> \"$STUB_LOG\"\nexit 0\n")


def _stub_dir(base):
    stubs = base / "stubs"
    stubs.mkdir()
    _write_script(stubs / "ssh", SSH_STUB)
    _write_script(stubs / "sftp", SFTP_STUB)
    _write_script(stubs / "rsync", RSYNC_STUB)
    return stubs


def _setup_stub_dir(base):
    """A PATH folder with logging cloud-init and apt-get stubs."""
    stubs = base / "setup-stubs"
    stubs.mkdir()
    for name in ("cloud-init", "apt-get"):
        _write_script(stubs / name, _logging_stub(name))
    return stubs


def _fake_repo(base):
    """A temp copy of the repo layout that upload.sh reads."""
    repo = base / "repo"
    (repo / "deploy").mkdir(parents=True)
    shutil.copyfile(UPLOAD, repo / "deploy" / "upload.sh")
    libs = repo / "backend" / "build" / "libs"
    libs.mkdir(parents=True)
    (libs / JAR_NAME).write_bytes(b"fake jar")
    (libs / PLAIN_JAR_NAME).write_bytes(b"plain jar, never uploaded")
    dist = repo / "frontend" / "dist"
    (dist / "assets").mkdir(parents=True)
    (dist / "index.html").write_bytes(INDEX_BYTES)
    (dist / "assets" / "a.js").write_bytes(ASSET_BYTES)
    data = repo / "data"
    (data / "audio-cache").mkdir(parents=True)
    (data / "catalog.json").write_bytes(CATALOG_BYTES)
    (data / "audio-cache" / "index.json").write_bytes(AUDIO_INDEX_BYTES)
    for name, content in AUDIO_BYTES.items():
        (data / "audio-cache" / name).write_bytes(content)
    return repo


def _listing(base):
    """The stub ssh's answer to the remote size listing: one file present."""
    path = base / "listing.txt"
    path.write_bytes(f"{len(AUDIO_BYTES['song-same.ogg'])} audio-cache/song-same.ogg\n"
                     .encode("ascii"))
    return path


def _run(script_cwd, script, args, path_dirs, base, listing=None, final_exit=0):
    env = dict(os.environ)
    env["PATH"] = os.pathsep.join(str(d) for d in path_dirs)
    env["STUB_LOG"] = (base / "stub.log").as_posix()
    env["STUB_BATCH"] = (base / "batch.log").as_posix()
    env["STUB_LISTING"] = listing.as_posix() if listing is not None else ""
    env["STUB_FINAL_EXIT"] = str(final_exit)
    return subprocess.run(
        [str(BASH), script, *args],
        cwd=script_cwd, env=env, stdin=subprocess.DEVNULL,
        capture_output=True, timeout=RUN_TIMEOUT,
    )


def _output(result):
    return (result.stdout.decode("utf-8", "replace")
            + result.stderr.decode("utf-8", "replace"))


def _log(base, name):
    path = base / name
    return path.read_text(encoding="utf-8") if path.exists() else ""


def _code_lines(text):
    """Lines of a shell script with full-line comments and blanks removed."""
    lines = []
    for raw in text.split("\n"):
        line = raw.strip()
        if line and not line.startswith("#"):
            lines.append(line)
    return lines


def _invokes_apt_get(line):
    return re.search(r"(?<![\w./-])apt-get(?![\w.-])", line) is not None


def _var_refs(name):
    return (f"${name}", "${" + name + "}")


def _mentions(line, name):
    return any(ref in line for ref in _var_refs(name))


def _batch_lines(text):
    lines = []
    for raw in text.splitlines():
        line = raw.strip()
        if line and not line.startswith("#"):
            lines.append(line)
    return lines


def _dry_run(tmp_path, tools_dir):
    assert UPLOAD.is_file(), "missing deploy/upload.sh"
    repo = _fake_repo(tmp_path)
    stubs = _stub_dir(tmp_path)
    result = _run(repo, "deploy/upload.sh", ["--dry-run", HOST], [stubs, tools_dir], tmp_path)
    out = _output(result)
    assert result.returncode == 0, out
    assert _log(tmp_path, "stub.log") == "", "dry run called a stub"
    return out


def _final_line(out):
    """The single output line that holds the final remote command.

    It is found by what only the final command has (the health poll and the
    journal), so a printed restore hint does not count as a second line.
    """
    holders = [line for line in out.splitlines()
               if any(needle in line for needle in FINAL_UNIQUE)]
    assert holders, f"dry run prints no final command:\n{out}"
    assert len(holders) == 1, \
        "final command is spread over several lines:\n" + "\n".join(holders)
    return holders[0]


# ---------------------------------------------------------------- criterion 1

def test_setup_every_apt_get_waits_for_the_lock():
    lines = [line for line in _code_lines(_read_text(SETUP)) if _invokes_apt_get(line)]
    assert lines, "setup.sh invokes no apt-get"
    for line in lines:
        assert "DPkg::Lock::Timeout=600" in line, f"apt-get without lock timeout: {line!r}"


def test_setup_waits_for_cloud_init_before_apt_get():
    lines = _code_lines(_read_text(SETUP))
    apt = [i for i, line in enumerate(lines) if _invokes_apt_get(line)]
    wait = [i for i, line in enumerate(lines) if "cloud-init status --wait" in line]
    assert apt, "setup.sh invokes no apt-get"
    assert wait, "setup.sh never runs cloud-init status --wait"
    assert wait[0] < apt[0], "cloud-init status --wait comes after the first apt-get"
    guard = [line for line in lines if "command -v cloud-init" in line]
    assert guard, "the cloud-init wait is not guarded by command -v cloud-init"


@pytest.mark.parametrize("target", ["caddy_key", "caddy_list"])
def test_setup_caddy_files_go_through_mktemp_chmod_mv(target):
    lines = _code_lines(_read_text(SETUP))
    temp_var = None
    pattern = re.compile(r'(\w+)=\$\(\s*mktemp\s+"\$(?:\{' + target + r'\}|' + target
                         + r')\.XXXXXX"\s*\)')
    for line in lines:
        match = pattern.search(line)
        if match:
            temp_var = match.group(1)
            break
    assert temp_var, f'setup.sh has no <var>=$(mktemp "${target}.XXXXXX")'
    chmods = [i for i, line in enumerate(lines)
              if re.search(r"\bchmod\s+644\b", line) and _mentions(line, temp_var)]
    moves = [i for i, line in enumerate(lines)
             if re.search(r"\bmv\s+-f\b", line) and _mentions(line, temp_var)
             and _mentions(line, target)]
    assert chmods, f"no chmod 644 on ${temp_var}"
    assert moves, f"no mv -f ${temp_var} onto ${target}"
    assert chmods[0] < moves[0], f"chmod 644 ${temp_var} comes after its mv"


def test_setup_caddy_repo_step_has_no_existence_guard():
    for line in _code_lines(_read_text(SETUP)):
        if re.search(r"\bif\s+\[\s+!\s+-f\b", line):
            assert "caddy" not in line.lower(), f"Caddy repo step is still guarded: {line!r}"


@needs_bash
def test_setup_passes_bash_syntax_check():
    assert SETUP.is_file(), "missing deploy/setup.sh"
    result = subprocess.run([str(BASH), "-n", SETUP.name], cwd=DEPLOY,
                            capture_output=True, timeout=RUN_TIMEOUT)
    assert result.returncode == 0, _output(result)


# ---------------------------------------------------------------- criterion 2

@needs_bash
def test_setup_without_argument_calls_neither_cloud_init_nor_apt_get(tmp_path, tools_dir):
    assert SETUP.is_file(), "missing deploy/setup.sh"
    shutil.copyfile(SETUP, tmp_path / "setup.sh")
    stubs = _setup_stub_dir(tmp_path)
    result = _run(tmp_path, "setup.sh", [], [stubs, tools_dir], tmp_path)
    out = _output(result)
    assert result.returncode == 2, out
    assert "usage" in out.lower(), out
    assert _log(tmp_path, "stub.log") == "", "setup.sh called a stub before its checks"


@needs_bash
def test_harness_setup_stubs_come_first(tmp_path, tools_dir):
    stubs = _setup_stub_dir(tmp_path)
    _write_script(tmp_path / "probe.sh", "command -v cloud-init\ncommand -v apt-get\n"
                                         "apt-get update\n")
    result = _run(tmp_path, "probe.sh", [], [stubs, tools_dir], tmp_path)
    out = _output(result)
    assert result.returncode == 0, out
    assert "setup-stubs/cloud-init" in out and "setup-stubs/apt-get" in out, out
    assert _log(tmp_path, "stub.log") == "apt-get update\n"


# ---------------------------------------------------------------- criterion 3

def test_unit_section_requires_the_jar():
    section = None
    unit = []
    for raw in _read_text(UNIT).split("\n"):
        line = raw.strip()
        if not line or line.startswith(("#", ";")):
            continue
        if line.startswith("[") and line.endswith("]"):
            section = line
            continue
        if section == "[Unit]":
            unit.append(line)
    assert "ConditionPathExists=/opt/songrush/app/songrush.jar" in unit, unit


# ---------------------------------------------------------------- criterion 4

@needs_bash
def test_dry_run_batch_puts_the_jar_first(tmp_path, tools_dir):
    lines = _batch_lines(_dry_run(tmp_path, tools_dir))
    transfers = [line for line in lines if not line.startswith("-mkdir")]
    assert transfers, lines
    first = transfers[0].split()
    assert first[0] == "put" and "app/songrush.jar.new" in transfers[0], \
        f"first transfer is not the jar: {transfers[0]!r}"
    assert lines.index(transfers[0]) == len(lines) - len(transfers), \
        "a -mkdir line comes after the first transfer"
    index_at = [i for i, line in enumerate(lines) if "web/index.html" in line]
    asset_at = [i for i, line in enumerate(lines) if "web/assets/a.js" in line]
    assert index_at and asset_at, lines
    assert asset_at[-1] < index_at[0], "web/index.html is uploaded before web/assets/a.js"


# ---------------------------------------------------------------- criterion 5

@needs_bash
def test_dry_run_final_command_is_one_line_in_order(tmp_path, tools_dir):
    line = _final_line(_dry_run(tmp_path, tools_dir))
    mv = re.search(r"\bmv\b", line)
    assert mv, f"no mv in final command: {line!r}"
    positions = [mv.start()]
    for needle in FINAL_NEEDLES:
        at = line.find(needle)
        assert at >= 0, f"final command lacks {needle!r}: {line!r}"
        positions.append(at)
    assert positions == sorted(positions), f"final command steps out of order: {line!r}"


@needs_bash
def test_dry_run_final_command_polls_60_times(tmp_path, tools_dir):
    line = _final_line(_dry_run(tmp_path, tools_dir))
    assert re.search(r"(?<!\d)60(?!\d)", line), f"no loop bound 60: {line!r}"


@needs_bash
def test_dry_run_final_command_has_no_recursion_and_no_find(tmp_path, tools_dir):
    line = _final_line(_dry_run(tmp_path, tools_dir))
    assert " -r" not in line, line
    assert "find" not in line, line


@needs_bash
def test_dry_run_final_command_keeps_remote_dollar(tmp_path, tools_dir):
    line = _final_line(_dry_run(tmp_path, tools_dir))
    assert "$" in line, f"no $ survives, so the loop was expanded locally: {line!r}"


# ---------------------------------------------------------------- criterion 6

def _real_run(tmp_path, tools_dir, final_exit):
    assert UPLOAD.is_file(), "missing deploy/upload.sh"
    repo = _fake_repo(tmp_path)
    stubs = _stub_dir(tmp_path)
    listing = _listing(tmp_path)
    result = _run(repo, "deploy/upload.sh", [HOST], [stubs, tools_dir], tmp_path,
                  listing=listing, final_exit=final_exit)
    log_lines = [line for line in _log(tmp_path, "stub.log").splitlines() if line]
    calls = [line.split(" ", 1)[0] for line in log_lines]
    assert "sftp" in calls, "\n".join(log_lines)
    finals = [line for call, line in zip(calls, log_lines)
              if call == "ssh" and "find" not in line]
    assert finals, "the final ssh call was never made:\n" + "\n".join(log_lines)
    return result, _output(result)


@needs_bash
def test_real_run_healthy_prints_done(tmp_path, tools_dir):
    result, out = _real_run(tmp_path, tools_dir, final_exit=0)
    assert result.returncode == 0, out
    assert "done." in out, out


@needs_bash
def test_real_run_unhealthy_exits_1_with_restore_hint(tmp_path, tools_dir):
    result, out = _real_run(tmp_path, tools_dir, final_exit=1)
    assert result.returncode == 1, out
    assert "done." not in out, out
    assert any("restore" in line and "songrush.jar.good" in line
               for line in out.splitlines()), f"no restore line naming songrush.jar.good:\n{out}"


@needs_bash
def test_real_run_unhealthy_prints_the_exact_restore_command(tmp_path, tools_dir):
    result, out = _real_run(tmp_path, tools_dir, final_exit=1)
    assert result.returncode == 1, out
    assert RESTORE_COMMAND in out, f"missing exact restore command:\n{out}"
