"""Cycle 055 criteria 1-7: the deploy kit in deploy/.

docs/cycles/055-deploy-kit.md is the specification. Every deploy/ file is
read inside a test, never at import time, so a missing file fails its tests
rather than the collection. Nothing here talks to a server: upload.sh runs
under bash against stub ssh, sftp and rsync scripts that only log their
arguments, and setup.sh is only run with no argument (the usage path).

Finding bash: the first bash on PATH, ignoring WindowsApps and System32 (the
WSL launchers there are not Git Bash); otherwise Git's usr/bin/bash.exe or
bin/bash.exe. Git's bin/bash.exe is a launcher that puts usr/bin back on PATH,
so the sibling usr/bin/bash.exe is preferred when it exists. Without any bash
the bash tests are skipped with a reason.

Controlling PATH: Git's usr/bin also holds real ssh.exe and sftp.exe, so
upload.sh never sees that folder itself. It gets the stub folder first and
then a tools folder of small wrappers that exec every program of the bash
folder (bash's own usr/bin) except ssh*, sftp, scp and rsync. The exit-3 case
gets a PATH of only a folder holding the ssh stub.

The real run's canned listing answers the remote size query
(cd /opt/songrush/data && find audio-cache ... -printf "%s %p\\n"): one .ogg
at its local size, one smaller, one absent, plus catalog.json and
audio-cache/index.json at their local sizes, which must still be uploaded.
"""

import os
import re
import shlex
import shutil
import subprocess
from pathlib import Path, PurePosixPath

import pytest

REPO = Path(__file__).resolve().parents[2]
DEPLOY = REPO / "deploy"
CADDYFILE = DEPLOY / "Caddyfile"
UNIT = DEPLOY / "songrush.service"
SETUP = DEPLOY / "setup.sh"
UPLOAD = DEPLOY / "upload.sh"
PROPERTIES = (REPO / "backend" / "src" / "main" / "java" / "com" / "songrush"
              / "SongrushProperties.java")

ALLOWED_OTHER_KEYS = {
    "SERVER_ADDRESS",
    "SPRING_WEB_RESOURCES_STATIC_LOCATIONS",
    "SPRING_WEB_RESOURCES_CACHE_CACHECONTROL_NO_CACHE",
}

JAR_NAME = "backend-0.0.1-SNAPSHOT.jar"
PLAIN_JAR_NAME = "backend-0.0.1-SNAPSHOT-plain.jar"
SAME_AUDIO = "song-same.ogg"      # remote has it at the local size: skipped
SMALL_AUDIO = "song-small.ogg"    # remote copy is smaller: reput
NEW_AUDIO = "song-new.ogg"        # absent remotely: put
AUDIO_BYTES = {
    SAME_AUDIO: b"s" * 1234,
    SMALL_AUDIO: b"p" * 2345,
    NEW_AUDIO: b"n" * 3456,
}
AUDIO_INDEX_BYTES = b'{"files": 3}\n'
CATALOG_BYTES = b"{}\n"
INDEX_BYTES = b"<!doctype html><title>songrush</title>\n"
ASSET_BYTES = b"console.log(1);\n"

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


SSH_STUB = """#!/bin/sh
printf 'ssh' >> "$STUB_LOG"
for a in "$@"; do printf ' %s' "$a" >> "$STUB_LOG"; done
printf '\\n' >> "$STUB_LOG"
case "$*" in
  *find*)
    if [ -n "$STUB_LISTING" ] && [ -f "$STUB_LISTING" ]; then cat "$STUB_LISTING"; fi
    ;;
esac
exit 0
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


def _stub_dir(base):
    stubs = base / "stubs"
    stubs.mkdir()
    _write_script(stubs / "ssh", SSH_STUB)
    _write_script(stubs / "sftp", SFTP_STUB)
    _write_script(stubs / "rsync", RSYNC_STUB)
    return stubs


def _ssh_only_dir(base):
    """A PATH folder holding only the ssh stub (the exit-3 case)."""
    stubs = base / "ssh-only"
    stubs.mkdir()
    _write_script(stubs / "ssh", SSH_STUB)
    return stubs


def _fake_repo(base, with_jar=True, with_index_html=True):
    """A temp copy of the repo layout that upload.sh reads."""
    repo = base / "repo"
    (repo / "deploy").mkdir(parents=True)
    shutil.copyfile(UPLOAD, repo / "deploy" / "upload.sh")
    libs = repo / "backend" / "build" / "libs"
    libs.mkdir(parents=True)
    if with_jar:
        (libs / JAR_NAME).write_bytes(b"fake jar")
    (libs / PLAIN_JAR_NAME).write_bytes(b"plain jar, never uploaded")
    dist = repo / "frontend" / "dist"
    (dist / "assets").mkdir(parents=True)
    if with_index_html:
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
    """The stub ssh's answer to the remote size listing ("%s %p" from data/).

    SAME_AUDIO at its local size, SMALL_AUDIO smaller, NEW_AUDIO absent;
    catalog.json and audio-cache/index.json at their local sizes, so a size
    rule applied to them would wrongly skip them.
    """
    lines = [
        f"{len(AUDIO_BYTES[SAME_AUDIO])} audio-cache/{SAME_AUDIO}",
        f"{len(AUDIO_BYTES[SMALL_AUDIO]) - 1000} audio-cache/{SMALL_AUDIO}",
        f"{len(AUDIO_INDEX_BYTES)} audio-cache/index.json",
        f"{len(CATALOG_BYTES)} catalog.json",
    ]
    path = base / "listing.txt"
    path.write_bytes(("\n".join(lines) + "\n").encode("ascii"))
    return path


def _run(script_cwd, script, args, path_dirs, base, listing=None):
    env = dict(os.environ)
    env["PATH"] = os.pathsep.join(str(d) for d in path_dirs)
    env["STUB_LOG"] = (base / "stub.log").as_posix()
    env["STUB_BATCH"] = (base / "batch.log").as_posix()
    env["STUB_LISTING"] = listing.as_posix() if listing is not None else ""
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


def _unit_lines():
    lines = []
    for raw in _read_text(UNIT).split("\n"):
        line = raw.strip()
        if line and not line.startswith(("#", ";")):
            lines.append(line)
    return lines


def _unit_values(key):
    prefix = key + "="
    return [line[len(prefix):] for line in _unit_lines() if line.startswith(prefix)]


def _unit_env():
    env = {}
    for value in _unit_values("Environment"):
        for assignment in shlex.split(value):
            name, sep, val = assignment.partition("=")
            assert sep, f"Environment entry without '=': {assignment!r}"
            env[name] = val
    return env


def _split_top_level(text):
    parts, depth, current = [], 0, []
    for ch in text:
        if ch in "(<[":
            depth += 1
        elif ch in ")>]":
            depth -= 1
        if ch == "," and depth == 0:
            parts.append("".join(current))
            current = []
        else:
            current.append(ch)
    if "".join(current).strip():
        parts.append("".join(current))
    return parts


def _record_components():
    source = PROPERTIES.read_text(encoding="utf-8")
    match = re.search(r"\brecord\s+SongrushProperties\s*\(", source)
    assert match, "SongrushProperties is not a record"
    depth, start = 1, match.end()
    index = start
    while depth:
        ch = source[index]
        if ch == "(":
            depth += 1
        elif ch == ")":
            depth -= 1
        index += 1
    header = source[start:index - 1]
    header = re.sub(r'"(?:[^"\\]|\\.)*"', '""', header)
    previous = None
    while previous != header:
        previous = header
        header = re.sub(r"@[\w.]+\s*(\([^()]*\))?", " ", header)
    names = []
    for part in _split_top_level(header):
        identifier = re.findall(r"[A-Za-z_]\w*", part)
        if identifier:
            names.append(identifier[-1])
    return names


def _env_name(component):
    return "SONGRUSH_" + re.sub(r"(?<=[a-z0-9])([A-Z])", r"_\1", component).upper()


# ---------------------------------------------------------------- criterion 1

@needs_bash
@pytest.mark.parametrize("script", [SETUP, UPLOAD], ids=lambda p: p.name)
def test_shell_scripts_pass_bash_syntax_check(script):
    assert script.is_file(), f"missing deploy/{script.name}"
    result = subprocess.run([str(BASH), "-n", script.name], cwd=DEPLOY,
                            capture_output=True, timeout=RUN_TIMEOUT)
    assert result.returncode == 0, _output(result)


# ---------------------------------------------------------------- criterion 2

def test_deploy_files_exist():
    missing = [p.name for p in (CADDYFILE, UNIT, SETUP, UPLOAD) if not p.is_file()]
    assert not missing, f"missing in deploy/: {missing}"


def test_every_deploy_file_is_ascii_with_lf_only():
    assert DEPLOY.is_dir(), "deploy/ is missing"
    files = [p for p in DEPLOY.rglob("*") if p.is_file()]
    assert files, "deploy/ is empty"
    for path in files:
        data = path.read_bytes()
        rel = path.relative_to(REPO).as_posix()
        assert all(b < 0x80 for b in data), f"{rel} is not ASCII"
        assert b"\r" not in data, f"{rel} has CR line endings"


# ---------------------------------------------------------------- criterion 3

def test_properties_parser_reads_data_dir():
    assert "dataDir" in _record_components()
    assert _env_name("dataDir") == "SONGRUSH_DATA_DIR"


def test_unit_env_keys_map_to_properties_or_allow_list():
    env = _unit_env()
    assert env, "songrush.service has no Environment= entries"
    components = {_env_name(c) for c in _record_components()}
    for key in env:
        if key.startswith("SONGRUSH_"):
            assert key in components, f"{key} matches no SongrushProperties component"
        else:
            assert key in ALLOWED_OTHER_KEYS, f"{key} is not on the allow-list"
    for key in ("SONGRUSH_DATA_DIR", *ALLOWED_OTHER_KEYS):
        assert key in env, f"songrush.service does not set {key}"


def test_unit_exec_start_and_working_directory():
    exec_start = _unit_values("ExecStart")
    assert len(exec_start) == 1, f"expected one ExecStart, got {exec_start}"
    for needle in ("-Xmx384m", "-XX:+ExitOnOutOfMemoryError", "songrush.jar"):
        assert needle in exec_start[0], f"ExecStart lacks {needle}"
    assert "-plain.jar" not in exec_start[0]
    assert _unit_values("WorkingDirectory") == ["/opt/songrush/run"]


# ---------------------------------------------------------------- criterion 4

def _related(a, b):
    return a == b or a in b.parents or b in a.parents


def test_static_location_and_data_dir_are_disjoint():
    env = _unit_env()
    data_dir = PurePosixPath(env["SONGRUSH_DATA_DIR"])
    locations = [loc.strip() for loc in env["SPRING_WEB_RESOURCES_STATIC_LOCATIONS"].split(",")]
    assert locations and all(locations)
    for location in locations:
        assert location.startswith("file:"), f"static location {location!r} is not a file: URL"
        static = PurePosixPath(location[len("file:"):])
        assert static.is_absolute() and data_dir.is_absolute()
        assert not _related(static, data_dir), f"{static} and {data_dir} overlap"


def test_disjoint_rule_catches_overlap():
    root = PurePosixPath("/opt/songrush")
    assert _related(root / "data", root / "data" / "web")
    assert _related(root, root / "data")
    assert _related(root / "data", PurePosixPath("/opt/songrush/data/"))
    assert not _related(root / "web", root / "data")


# ---------------------------------------------------------------- criterion 5

def test_caddyfile_site_proxy_and_no_log():
    text = _read_text(CADDYFILE)
    assert "__SONGRUSH_HOST__" in text
    assert "reverse_proxy 127.0.0.1:8080" in text
    for raw in text.split("\n"):
        line = raw.split("#", 1)[0].strip()
        if line:
            assert line.split()[0] != "log", f"Caddyfile has a log directive: {raw!r}"


# ---------------------------------------------------------------- criterion 6

def _batch_lines(text):
    lines = []
    for raw in text.splitlines():
        line = raw.strip()
        if line and not line.startswith("#"):
            lines.append(line)
    return lines


def _command(line):
    return line.split()[0]


def _lines_naming(lines, needle):
    return [line for line in lines if needle in line]


def _assert_put(lines, needle, text):
    named = _lines_naming(lines, needle)
    assert any(_command(line) == "put" for line in named), \
        f"no put line for {needle}:\n{text}"


@needs_bash
def test_upload_dry_run_prints_plan_and_calls_nothing(tmp_path, tools_dir):
    assert UPLOAD.is_file(), "missing deploy/upload.sh"
    repo = _fake_repo(tmp_path)
    stubs = _stub_dir(tmp_path)
    result = _run(repo, "deploy/upload.sh", ["--dry-run", "user@host"],
                  [stubs, tools_dir], tmp_path)
    out = _output(result)
    assert result.returncode == 0, out
    assert _log(tmp_path, "stub.log") == "", "dry run called a stub"
    assert _log(tmp_path, "batch.log") == ""
    lines = _batch_lines(out)
    for needle in (*AUDIO_BYTES, "catalog.json", "index.html", "app/songrush.jar.new"):
        _assert_put(lines, needle, out)
    assert "-plain.jar" not in out
    assert " -r" not in out
    assert "mv " in out and "systemctl restart songrush" in out, out


@needs_bash
def test_upload_without_arguments_exits_2(tmp_path, tools_dir):
    assert UPLOAD.is_file(), "missing deploy/upload.sh"
    repo = _fake_repo(tmp_path)
    stubs = _stub_dir(tmp_path)
    result = _run(repo, "deploy/upload.sh", [], [stubs, tools_dir], tmp_path)
    assert result.returncode == 2, _output(result)
    assert "usage" in _output(result).lower()
    assert _log(tmp_path, "stub.log") == ""


@needs_bash
def test_upload_without_sftp_exits_3_naming_it(tmp_path):
    assert UPLOAD.is_file(), "missing deploy/upload.sh"
    repo = _fake_repo(tmp_path)
    ssh_only = _ssh_only_dir(tmp_path)
    result = _run(repo, "deploy/upload.sh", ["user@host"], [ssh_only], tmp_path)
    out = _output(result)
    assert result.returncode == 3, out
    assert "sftp" in out
    assert _log(tmp_path, "stub.log") == ""


@needs_bash
@pytest.mark.parametrize("missing", ["jar", "index.html"])
def test_upload_without_build_output_exits_4(tmp_path, tools_dir, missing):
    assert UPLOAD.is_file(), "missing deploy/upload.sh"
    repo = _fake_repo(tmp_path, with_jar=missing != "jar",
                      with_index_html=missing != "index.html")
    stubs = _stub_dir(tmp_path)
    result = _run(repo, "deploy/upload.sh", ["user@host"], [stubs, tools_dir], tmp_path)
    assert result.returncode == 4, _output(result)
    assert _log(tmp_path, "batch.log") == ""


@needs_bash
def test_upload_real_run_follows_batch_rules(tmp_path, tools_dir):
    assert UPLOAD.is_file(), "missing deploy/upload.sh"
    repo = _fake_repo(tmp_path)
    stubs = _stub_dir(tmp_path)
    listing = _listing(tmp_path)
    result = _run(repo, "deploy/upload.sh", ["user@host"], [stubs, tools_dir], tmp_path,
                  listing=listing)
    out = _output(result)
    assert result.returncode == 0, out

    log_lines = [line for line in _log(tmp_path, "stub.log").splitlines() if line]
    log = "\n".join(log_lines)
    calls = [line.split(" ", 1)[0] for line in log_lines]
    assert "rsync" not in calls, log
    assert calls.count("sftp") == 1, log
    sftp_at = calls.index("sftp")
    assert " -b " in log_lines[sftp_at] + " ", log
    assert any(call == "ssh" and "find" in line and "audio-cache" in line
               for call, line in zip(calls, log_lines)), log
    assert any(call == "ssh" and "mv " in line and "systemctl restart songrush" in line
               for call, line in zip(calls[sftp_at + 1:], log_lines[sftp_at + 1:])), \
        f"no mv + restart ssh after sftp:\n{log}"

    text = _log(tmp_path, "batch.log")
    assert text, "sftp received no batch"
    lines = _batch_lines(text)
    assert not _lines_naming(lines, SAME_AUDIO), f"same-size {SAME_AUDIO} not skipped:\n{text}"
    small = _lines_naming(lines, SMALL_AUDIO)
    assert small and all(_command(line) == "reput" for line in small), text
    new = _lines_naming(lines, NEW_AUDIO)
    assert new and all(_command(line) == "put" for line in new), text
    for needle in ("catalog.json", "audio-cache/index.json", "index.html", "assets/a.js",
                   "app/songrush.jar.new"):
        _assert_put(lines, needle, text)
    for line in lines:
        if _command(line).lstrip("-") == "mkdir":
            assert _command(line) == "-mkdir", f"mkdir without '-': {line!r}"
    assert " -r" not in text, text
    assert "-plain.jar" not in text, text


# ---------------------------------------------------------------- criterion 7

def test_setup_contents():
    text = _read_text(SETUP)
    for needle in ("set -euo pipefail", "openjdk-17-jre-headless", "/swapfile",
                   "systemctl enable"):
        assert needle in text, f"setup.sh lacks {needle}"
    assert any("sed" in line.split() and "__SONGRUSH_HOST__" in line
               for line in text.split("\n")), "setup.sh has no sed replacing __SONGRUSH_HOST__"


@needs_bash
def test_setup_without_argument_exits_2_with_usage(tmp_path, tools_dir):
    assert SETUP.is_file(), "missing deploy/setup.sh"
    copy = tmp_path / "setup.sh"
    shutil.copyfile(SETUP, copy)
    result = _run(tmp_path, "setup.sh", [], [tools_dir], tmp_path)
    assert result.returncode == 2, _output(result)
    assert "usage" in _output(result).lower()


# ---------------------------------------------------------------- the harness itself

@needs_bash
def test_harness_hides_real_ssh_and_sftp(tmp_path, tools_dir):
    """The stub PATHs expose the stubs and no real ssh or sftp."""
    script = tmp_path / "probe.sh"
    _write_script(script, "command -v sftp || echo NO-SFTP\ncommand -v ssh\n"
                          "command -v find dirname mktemp cat\n")
    ssh_only = _ssh_only_dir(tmp_path)
    result = _run(tmp_path, "probe.sh", [], [ssh_only, tools_dir], tmp_path)
    out = _output(result)
    assert result.returncode == 0, out
    assert "NO-SFTP" in out, out
    assert "ssh-only/ssh" in out, out

    stubs = _stub_dir(tmp_path)
    result = _run(tmp_path, "probe.sh", [], [stubs, tools_dir], tmp_path)
    out = _output(result)
    assert result.returncode == 0, out
    assert "stubs/sftp" in out and "stubs/ssh" in out, out


@needs_bash
def test_harness_ssh_stub_answers_size_listing(tmp_path, tools_dir):
    stubs = _stub_dir(tmp_path)
    listing = _listing(tmp_path)
    script = tmp_path / "probe.sh"
    _write_script(script, "ssh user@host 'cd /opt/songrush/data && find audio-cache "
                          "-type f -printf \"%s %p\n\"'\n")
    result = _run(tmp_path, "probe.sh", [], [stubs, tools_dir], tmp_path, listing=listing)
    out = _output(result)
    assert result.returncode == 0, out
    assert f"{len(AUDIO_BYTES[SAME_AUDIO])} audio-cache/{SAME_AUDIO}" in out, out
