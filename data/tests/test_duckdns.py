"""Cycle 062 criteria 1-5: DuckDNS address and the Free plan (I-009).

docs/cycles/062-duckdns-free-plan.md is the specification. Every deploy/ and
docs/ file is read inside a test, never at import time, so a missing file
fails its tests rather than the collection. Nothing here talks to a server
or to DuckDNS: curl and systemctl are stub scripts that only log their
arguments, and the curl stub answers with $STUB_CURL_REPLY.

The harness is copied from the cycle 055 tests (test_deploy_kit.py, locked):
the same bash finder (PATH first, ignoring WindowsApps and System32, then
Git for Windows), and the same wrapper `tools` folder that hides ssh, sftp,
scp and rsync. The stub folder comes first on PATH. A stub `id` prints 0 for
-u, so setup.sh's root check passes without root.

The DuckDNS token is never a literal in this file: each test builds a fresh
fake one with uuid.uuid4(). The token reaches duckdns-setup.sh only through
the file named by SONGRUSH_TTY, and every run writes into a temp
SONGRUSH_ROOT. The token-leak check searches every stub argv log, stdout and
stderr.

Choices the spec leaves open:
- The test root gets etc/systemd/system/ up front (it exists on a real
  server); etc/songrush/ is left for the script to create.
- The curl stub prints its reply without a newline, as DuckDNS does.
- The setup.sh text check reads code lines (blanks and full-line comments
  removed). The behaviour checks also use a stub duckdns-setup.sh that logs
  its arguments. The apt-get stub always exits 1, so a setup.sh run that
  wrongly reaches apt-get stops there and never touches the real filesystem
  (keyrings, /swapfile, /opt).

This file is ASCII-only: Hangul is built from code points with chr().
"""

import os
import re
import shutil
import subprocess
import uuid
from pathlib import Path

import pytest

REPO = Path(__file__).resolve().parents[2]
DEPLOY = REPO / "deploy"
SETUP = DEPLOY / "setup.sh"
DUCK_SETUP = DEPLOY / "duckdns-setup.sh"
DUCK_SERVICE = DEPLOY / "duckdns.service"
DUCK_TIMER = DEPLOY / "duckdns.timer"
UNIT = DEPLOY / "songrush.service"
CADDYFILE = DEPLOY / "Caddyfile"
GUIDE = REPO / "docs" / "deploy-guide.md"

EXEC_START = "/usr/bin/curl -fsS -K /etc/songrush/duckdns.curl"
NAME = "mygame"
TTY_MESSAGE = "ssh -t"

RUN_TIMEOUT = 120
HIDDEN_TOOLS = {"sftp", "scp", "rsync"}

UUID_RE = re.compile(r"[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}", re.I)
FENCE_RE = re.compile(r"^\s*(`{3,}|~{3,})\s*(.*?)\s*$")
INLINE_RE = re.compile(r"(?<!`)(`+)(?!`)(.+?)(?<!`)\1(?!`)")


def _hangul(*code_points):
    return "".join(chr(c) for c in code_points)


GUIDE_NEEDLES = [
    "duckdns.org",
    _hangul(0xD1A0, 0xD070),                        # token
    _hangul(0xBB34, 0xB8CC, 0x20, 0xD50C, 0xB79C),  # free plan
    "90" + _hangul(0xC77C),                         # 90 days
    "5" + _hangul(0xAC1C, 0xC6D4),                  # 5 months
    "journalctl -u duckdns",
    "ssh -t",
    _hangul(0xD06C, 0xB808, 0xB527),                # credit
]


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


def _logging_stub(name, tail="exit 0\n"):
    return (f"#!/bin/sh\nprintf '{name}' >> \"$STUB_LOG\"\n"
            "for a in \"$@\"; do printf ' %s' \"$a\" >> \"$STUB_LOG\"; done\n"
            "printf '\\n' >> \"$STUB_LOG\"\n" + tail)


CURL_TAIL = "printf '%s' \"$STUB_CURL_REPLY\"\nexit 0\n"
APT_TAIL = "exit \"${STUB_APT_EXIT:-0}\"\n"

# Prints 0 for -u (root), the login user's group for -gn, else 0.
ID_STUB = """#!/bin/sh
case "$1" in
  -u) echo 0 ;;
  -gn) echo ubuntu ;;
  *) echo 0 ;;
esac
exit 0
"""


def _stub_dir(base):
    """PATH folder: logging systemctl, curl, cloud-init, apt-get; silent id."""
    stubs = base / "stubs"
    stubs.mkdir()
    _write_script(stubs / "systemctl", _logging_stub("systemctl"))
    _write_script(stubs / "curl", _logging_stub("curl", CURL_TAIL))
    _write_script(stubs / "cloud-init", _logging_stub("cloud-init"))
    _write_script(stubs / "apt-get", _logging_stub("apt-get", APT_TAIL))
    _write_script(stubs / "id", ID_STUB)
    return stubs


def _fake_token():
    return str(uuid.uuid4())


def _root(base):
    root = base / "root"
    (root / "etc" / "systemd" / "system").mkdir(parents=True)
    return root


def _tty(base, content):
    path = base / "tty.txt"
    path.write_bytes(content.encode("ascii"))
    return path


def _run(script, args, base, stubs, tools, root, tty, reply="OK", apt_exit=1,
         sudo_user=None):
    env = dict(os.environ)
    env["PATH"] = os.pathsep.join(str(d) for d in (stubs, tools))
    env["STUB_LOG"] = (base / "stub.log").as_posix()
    env["STUB_CURL_REPLY"] = reply
    env["STUB_APT_EXIT"] = str(apt_exit)
    env["SONGRUSH_ROOT"] = root.as_posix()
    env["SONGRUSH_TTY"] = tty.as_posix()
    if sudo_user is None:
        env.pop("SUDO_USER", None)
    else:
        env["SUDO_USER"] = sudo_user
    return subprocess.run(
        [str(BASH), Path(script).as_posix(), *args],
        cwd=base, env=env, stdin=subprocess.DEVNULL,
        capture_output=True, timeout=RUN_TIMEOUT,
    )


def _output(result):
    return (result.stdout.decode("utf-8", "replace")
            + result.stderr.decode("utf-8", "replace"))


def _log(base):
    path = base / "stub.log"
    return path.read_text(encoding="utf-8") if path.exists() else ""


def _calls(base, name):
    return [line for line in _log(base).splitlines()
            if line == name or line.startswith(name + " ")]


def _files_under(root):
    return sorted(p.relative_to(root).as_posix() for p in root.rglob("*") if p.is_file())


def _curl_file(root):
    return root / "etc" / "songrush" / "duckdns.curl"


def _expected_url_line(name, token):
    return f'url = "https://www.duckdns.org/update?domains={name}&token={token}&ip="'


def _duck_run(tmp_path, tools_dir, args, tty_content, reply="OK"):
    assert DUCK_SETUP.is_file(), "missing deploy/duckdns-setup.sh"
    stubs = _stub_dir(tmp_path)
    root = _root(tmp_path)
    tty = _tty(tmp_path, tty_content)
    result = _run(DUCK_SETUP, args, tmp_path, stubs, tools_dir, root, tty, reply=reply)
    return result, root


def _assert_no_leak(tmp_path, result, token):
    assert token not in _log(tmp_path), "the token appears in a stub argv log"
    assert token.encode("ascii") not in result.stdout, "the token appears in stdout"
    assert token.encode("ascii") not in result.stderr, "the token appears in stderr"


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


def _unit_sections(path):
    """{section: [key=value lines]} of a systemd unit file."""
    sections = {}
    current = None
    for raw in _read_text(path).split("\n"):
        line = raw.strip()
        if not line or line.startswith(("#", ";")):
            continue
        if line.startswith("[") and line.endswith("]"):
            current = line
            sections.setdefault(current, [])
            continue
        sections.setdefault(current, []).append(line)
    return sections


def _values(lines, key):
    prefix = key + "="
    return [line[len(prefix):].strip() for line in lines if line.startswith(prefix)]


def _parse_fences(text):
    """(blocks, outside): blocks are (lang, [body lines]); outside are lines."""
    blocks, outside = [], []
    current = None
    for line in text.splitlines():
        match = FENCE_RE.match(line)
        if current is None:
            if match:
                info = match.group(2)
                current = (match.group(1)[0], len(match.group(1)),
                           info.split()[0] if info else "", [])
            else:
                outside.append(line)
            continue
        char, length, lang, body = current
        if (match and match.group(1)[0] == char
                and len(match.group(1)) >= length and not match.group(2)):
            blocks.append((lang, body))
            current = None
        else:
            body.append(line)
    if current is not None:
        blocks.append((current[2], current[3]))
    return blocks, outside


# ---------------------------------------------------------------- criterion 1

def test_duckdns_service_is_oneshot_with_exact_exec_start():
    service = _unit_sections(DUCK_SERVICE).get("[Service]", [])
    assert _values(service, "Type") == ["oneshot"], service
    assert _values(service, "ExecStart") == [EXEC_START], service


def test_duckdns_timer_schedule_and_install():
    sections = _unit_sections(DUCK_TIMER)
    timer = sections.get("[Timer]", [])
    assert _values(timer, "OnBootSec") == ["30s"], timer
    assert _values(timer, "OnUnitActiveSec") == ["5min"], timer
    assert _values(sections.get("[Install]", []), "WantedBy") == ["timers.target"], sections


@pytest.mark.parametrize("path", [DUCK_SERVICE, DUCK_TIMER, DUCK_SETUP], ids=lambda p: p.name)
def test_duckdns_files_are_ascii_with_lf_only(path):
    assert path.is_file(), f"missing deploy/{path.name}"
    data = path.read_bytes()
    assert all(b < 0x80 for b in data), f"{path.name} is not ASCII"
    assert b"\r" not in data, f"{path.name} has CR line endings"


# ---------------------------------------------------------------- criterion 2

@needs_bash
def test_duckdns_setup_passes_bash_syntax_check():
    assert DUCK_SETUP.is_file(), "missing deploy/duckdns-setup.sh"
    result = subprocess.run([str(BASH), "-n", DUCK_SETUP.name], cwd=DEPLOY,
                            capture_output=True, timeout=RUN_TIMEOUT)
    assert result.returncode == 0, _output(result)


@needs_bash
@pytest.mark.parametrize("args", [[], ["Bad_Name"], ["a.b"], ["a" * 64], [NAME, "extra"]],
                         ids=["no-arg", "underscore", "dot", "64-chars", "two-args"])
def test_duckdns_setup_bad_arguments_exit_2_and_touch_nothing(tmp_path, tools_dir, args):
    token = _fake_token()
    result, root = _duck_run(tmp_path, tools_dir, args, token + "\n")
    out = _output(result)
    assert result.returncode == 2, out
    assert _log(tmp_path) == "", "a stub was called"
    assert _files_under(root) == [], "a file was written under SONGRUSH_ROOT"
    _assert_no_leak(tmp_path, result, token)


@needs_bash
def test_duckdns_setup_63_char_name_is_accepted(tmp_path, tools_dir):
    name = "a" * 63
    token = _fake_token()
    result, root = _duck_run(tmp_path, tools_dir, [name], token + "\n")
    assert result.returncode == 0, _output(result)
    assert f"domains={name}&" in _curl_file(root).read_text(encoding="ascii")


@needs_bash
def test_duckdns_setup_missing_tty_exits_2_with_ssh_t_message(tmp_path, tools_dir):
    assert DUCK_SETUP.is_file(), "missing deploy/duckdns-setup.sh"
    stubs = _stub_dir(tmp_path)
    root = _root(tmp_path)
    missing = tmp_path / "no-such-tty"
    result = _run(DUCK_SETUP, [NAME], tmp_path, stubs, tools_dir, root, missing)
    out = _output(result)
    assert result.returncode == 2, out
    assert TTY_MESSAGE in out, out
    assert _log(tmp_path) == "", "a stub was called"
    assert _files_under(root) == [], "a file was written under SONGRUSH_ROOT"


def _bad_tokens():
    token = _fake_token()
    return {
        "empty": "\n",
        "not-uuid": "not-a-duckdns-token\n",
        "quote": token[:-1] + '"' + "\n",
        "uuid-plus-quote": token + '"' + "\n",
    }


@needs_bash
@pytest.mark.parametrize("kind", sorted(_bad_tokens()))
def test_duckdns_setup_bad_token_exits_2_without_curl_file(tmp_path, tools_dir, kind):
    content = _bad_tokens()[kind]
    result, root = _duck_run(tmp_path, tools_dir, [NAME], content)
    out = _output(result)
    assert result.returncode == 2, out
    assert not _curl_file(root).exists(), "a curl file was written for a bad token"
    assert _files_under(root) == [], _files_under(root)
    assert _calls(tmp_path, "curl") == [], _log(tmp_path)
    assert _calls(tmp_path, "systemctl") == [], _log(tmp_path)


@needs_bash
def test_duckdns_setup_good_token_ok_reply(tmp_path, tools_dir):
    token = _fake_token()
    result, root = _duck_run(tmp_path, tools_dir, [NAME], token + "\n", reply="OK")
    out = _output(result)
    assert result.returncode == 0, out

    curl_file = _curl_file(root)
    assert curl_file.is_file(), f"no curl file; files: {_files_under(root)}"
    content = curl_file.read_bytes().decode("ascii")
    assert content.rstrip("\n") == _expected_url_line(NAME, token), "unexpected curl file line"
    assert content.count("\n") <= 1, "the curl file holds more than one line"

    units = root / "etc" / "systemd" / "system"
    for unit in (DUCK_SERVICE, DUCK_TIMER):
        copied = units / unit.name
        assert copied.is_file(), f"{unit.name} was not copied; files: {_files_under(root)}"
        assert copied.read_bytes() == unit.read_bytes(), f"{unit.name} copy differs"

    systemctl = _calls(tmp_path, "systemctl")
    assert "systemctl daemon-reload" in systemctl, systemctl
    assert "systemctl enable --now duckdns.timer" in systemctl, systemctl
    assert systemctl.index("systemctl daemon-reload") \
        < systemctl.index("systemctl enable --now duckdns.timer"), systemctl

    curls = _calls(tmp_path, "curl")
    assert len(curls) == 1, curls
    assert " -K " + curl_file.as_posix() + " " in curls[0] + " ", curls
    assert "-fsS" in curls[0].split(), curls

    assert f"DuckDNS: {NAME}.duckdns.org now points to this server" in out, out
    _assert_no_leak(tmp_path, result, token)


@needs_bash
def test_duckdns_setup_rerun_replaces_the_curl_file(tmp_path, tools_dir):
    first = _fake_token()
    result, root = _duck_run(tmp_path, tools_dir, [NAME], first + "\n")
    assert result.returncode == 0, _output(result)
    second = _fake_token()
    tty = _tty(tmp_path, second + "\n")
    result = _run(DUCK_SETUP, [NAME], tmp_path, tmp_path / "stubs", tools_dir, root, tty)
    assert result.returncode == 0, _output(result)
    content = _curl_file(root).read_text(encoding="ascii")
    assert content.rstrip("\n") == _expected_url_line(NAME, second)
    leftovers = [f for f in _files_under(root) if f.startswith("etc/songrush/")
                 and f != "etc/songrush/duckdns.curl"]
    assert leftovers == [], f"temp files left behind: {leftovers}"


@needs_bash
@pytest.mark.skipif(os.name == "nt", reason="Windows does not keep POSIX file modes")
def test_duckdns_setup_curl_file_mode_is_600(tmp_path, tools_dir):
    token = _fake_token()
    result, root = _duck_run(tmp_path, tools_dir, [NAME], token + "\n")
    assert result.returncode == 0, _output(result)
    mode = _curl_file(root).stat().st_mode & 0o777
    assert mode == 0o600, oct(mode)


@needs_bash
def test_duckdns_setup_ko_reply_exits_1_naming_name_or_token(tmp_path, tools_dir):
    token = _fake_token()
    result, _ = _duck_run(tmp_path, tools_dir, [NAME], token + "\n", reply="KO")
    out = _output(result)
    assert result.returncode == 1, out
    lowered = out.lower()
    assert "wrong" in lowered and "name" in lowered and "token" in lowered, out
    assert "now points to this server" not in out, out
    _assert_no_leak(tmp_path, result, token)


@needs_bash
def test_duckdns_setup_lowercases_the_name(tmp_path, tools_dir):
    token = _fake_token()
    result, root = _duck_run(tmp_path, tools_dir, ["MyName"], token + "\n")
    assert result.returncode == 0, _output(result)
    content = _curl_file(root).read_text(encoding="ascii")
    assert content.rstrip("\n") == _expected_url_line("myname", token)


# ---------------------------------------------------------------- criterion 3

def test_setup_runs_duckdns_setup_before_first_apt_get():
    lines = _code_lines(_read_text(SETUP))
    duck = [i for i, line in enumerate(lines) if "duckdns-setup.sh" in line]
    apt = [i for i, line in enumerate(lines) if _invokes_apt_get(line)]
    assert duck, "setup.sh never runs duckdns-setup.sh"
    assert apt, "setup.sh invokes no apt-get"
    assert duck[0] < apt[0], "duckdns-setup.sh comes after the first apt-get"
    suffix = [i for i, line in enumerate(lines) if ".duckdns.org" in line]
    assert suffix and suffix[0] < duck[0], "no .duckdns.org test before duckdns-setup.sh"


def _setup_copy(base, duck_stub):
    """A temp deploy/ folder; duck_stub replaces duckdns-setup.sh when given."""
    folder = base / "deploy"
    folder.mkdir()
    shutil.copyfile(SETUP, folder / "setup.sh")
    for path in (UNIT, CADDYFILE):
        if path.is_file():
            shutil.copyfile(path, folder / path.name)
    if duck_stub:
        _write_script(folder / "duckdns-setup.sh", _logging_stub("duckdns-setup", "exit 1\n"))
    else:
        for path in (DUCK_SETUP, DUCK_SERVICE, DUCK_TIMER):
            assert path.is_file(), f"missing deploy/{path.name}"
            shutil.copyfile(path, folder / path.name)
    return folder / "setup.sh"


@needs_bash
@pytest.mark.parametrize("host", ["mygame.duckdns.org", "MyGame.DuckDNS.org"])
def test_setup_failed_duckdns_step_stops_before_apt_get(tmp_path, tools_dir, host):
    assert SETUP.is_file(), "missing deploy/setup.sh"
    script = _setup_copy(tmp_path, duck_stub=False)
    stubs = _stub_dir(tmp_path)
    root = _root(tmp_path)
    tty = _tty(tmp_path, "\n")
    result = _run(script, [host], tmp_path, stubs, tools_dir, root, tty, sudo_user="ubuntu")
    out = _output(result)
    assert result.returncode != 0, out
    assert _calls(tmp_path, "apt-get") == [], _log(tmp_path)
    assert _calls(tmp_path, "cloud-init") == [], _log(tmp_path)
    assert _files_under(root) == [], _files_under(root)


@needs_bash
@pytest.mark.parametrize("host", ["mygame.duckdns.org", "MyGame.DuckDNS.org"])
def test_setup_passes_the_duckdns_name(tmp_path, tools_dir, host):
    assert SETUP.is_file(), "missing deploy/setup.sh"
    script = _setup_copy(tmp_path, duck_stub=True)
    stubs = _stub_dir(tmp_path)
    root = _root(tmp_path)
    tty = _tty(tmp_path, "\n")
    result = _run(script, [host], tmp_path, stubs, tools_dir, root, tty, sudo_user="ubuntu")
    out = _output(result)
    assert result.returncode != 0, out
    duck = _calls(tmp_path, "duckdns-setup")
    assert len(duck) == 1, _log(tmp_path)
    assert duck[0].split()[1:] and [w.lower() for w in duck[0].split()[1:]] == ["mygame"], duck
    assert _calls(tmp_path, "apt-get") == [], _log(tmp_path)


@needs_bash
@pytest.mark.parametrize("host", ["game.example.com", "duckdns.org.example.com",
                                  "myduckdns.org"])
def test_setup_other_hostnames_skip_duckdns(tmp_path, tools_dir, host):
    assert SETUP.is_file(), "missing deploy/setup.sh"
    script = _setup_copy(tmp_path, duck_stub=True)
    stubs = _stub_dir(tmp_path)
    root = _root(tmp_path)
    tty = _tty(tmp_path, "\n")
    # The failing apt-get stub stops setup.sh at its first apt-get.
    result = _run(script, [host], tmp_path, stubs, tools_dir, root, tty, sudo_user="ubuntu")
    out = _output(result)
    assert result.returncode != 0, out
    assert _calls(tmp_path, "duckdns-setup") == [], _log(tmp_path)
    assert _calls(tmp_path, "apt-get"), f"setup.sh never reached apt-get:\n{out}"


# ---------------------------------------------------------------- criterion 4

def _secret_scan_files():
    files = []
    for pattern in ("*.sh", "*.service", "*.timer"):
        files.extend(sorted(DEPLOY.glob(pattern)))
    files.append(CADDYFILE)
    files.append(GUIDE)
    return files


def test_no_uuid_shaped_string_in_deploy_files_or_guide():
    files = _secret_scan_files()
    names = {p.name for p in files}
    for required in ("duckdns-setup.sh", "duckdns.service", "duckdns.timer", "setup.sh",
                     "Caddyfile", "deploy-guide.md"):
        assert required in names, f"scan list lacks {required}"
    for path in files:
        assert path.is_file(), f"missing {path.relative_to(REPO).as_posix()}"
        text = path.read_bytes().decode("utf-8")
        found = UUID_RE.search(text)
        assert found is None, \
            f"{path.relative_to(REPO).as_posix()} holds a UUID-shaped string at {found.start()}"


def test_uuid_pattern_catches_a_fake_token():
    token = _fake_token()
    assert UUID_RE.search(f"token={token}&ip=")
    assert UUID_RE.search(token.upper())
    assert not UUID_RE.search(token.replace("-", ""))


# ---------------------------------------------------------------- criterion 5

def _guide():
    assert GUIDE.is_file(), "missing docs/deploy-guide.md"
    return GUIDE.read_bytes().decode("utf-8")


def test_guide_mentions_duckdns_and_free_plan_terms():
    text = _guide()
    missing = [needle.encode("unicode_escape").decode("ascii")
               for needle in GUIDE_NEEDLES if needle not in text]
    assert not missing, f"guide lacks: {missing}"


def test_guide_inline_code_has_no_token_assignment():
    _, outside = _parse_fences(_guide())
    bad = [m.group(2) for line in outside for m in INLINE_RE.finditer(line)
           if "token=" in m.group(2).lower()]
    assert not bad, f"inline code holds token=: {bad}"


def test_guide_update_url_only_in_text_blocks():
    blocks, outside = _parse_fences(_guide())
    bad = [line for line in outside if "duckdns.org/update" in line]
    for lang, body in blocks:
        if lang != "text":
            bad.extend(line for line in body if "duckdns.org/update" in line)
    assert not bad, f"update URL outside a text block: {bad}"


# ---------------------------------------------------------------- the harness itself

@needs_bash
def test_harness_stubs_come_first_and_id_reports_root(tmp_path, tools_dir):
    stubs = _stub_dir(tmp_path)
    root = _root(tmp_path)
    tty = _tty(tmp_path, "\n")
    probe = tmp_path / "probe.sh"
    _write_script(probe, "command -v curl\ncommand -v systemctl\ncommand -v sftp || echo NO-SFTP\n"
                         "id -u\ncurl -K x\n")
    result = _run(probe, [], tmp_path, stubs, tools_dir, root, tty, reply="KO")
    out = _output(result)
    assert result.returncode == 0, out
    assert "stubs/curl" in out and "stubs/systemctl" in out, out
    assert "NO-SFTP" in out, out
    assert "\n0\n" in out and out.endswith("KO"), out
    assert _log(tmp_path) == "curl -K x\n"
