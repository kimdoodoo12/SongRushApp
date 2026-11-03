"""Cycle 058 criteria 1-6: the owner's deploy guide, docs/deploy-guide.md.

docs/cycles/058-runbook.md is the specification. The guide is read inside a
test, never at import time, so a missing guide fails its tests rather than
the collection.

Every rule is a pure function from guide text to a list of problems (empty
means the rule holds). The guide tests assert that each list is empty. The
self-tests at the bottom run the same functions on an inline sample guide
that passes every rule, and on one broken copy per rule, so each rule is
shown to catch what it is meant to catch.

This file is ASCII-only: Hangul is built from code points with chr().

Parsing choices (the spec leaves these open):
- A fence is a line of 3+ backticks or tildes, any indentation. Its language
  is the first word of the info string; a closing fence uses the same
  character, at least as many, and no info string. An unclosed fence is a
  problem.
- Inline code spans are matched per line, outside fences. A span "starts
  with a command word" when its first whitespace-separated word is one.
- Criterion 3: bash lines ending in a backslash are joined first. Each line
  is split by shlex (posix, punctuation "&;|"), cut into segments at control
  operators, leading "sudo" words are dropped, and a segment starting with
  "cd" is skipped. A script's arguments are the words after it in its
  segment. A line shlex cannot split is a problem.
- Criterion 6, IPv4: dotted quads are checked, and so are dashed quads in an
  sslip.io name (a-b-c-d.sslip.io encodes the IP a.b.c.d).
"""

import ipaddress
import re
import shlex
from pathlib import Path

import pytest

REPO = Path(__file__).resolve().parents[2]
GUIDE = REPO / "docs" / "deploy-guide.md"
DEPLOY = REPO / "deploy"
UNIT = DEPLOY / "songrush.service"

COMMAND_WORDS = {"ssh", "scp", "sudo", "bash", "python", "cd", "npm",
                 "journalctl", "icacls"}
SCRIPT_ARGS = {"setup.sh": (1, 1), "upload.sh": (1, 2), "smoke.py": (1, 1)}
EXTRA_ENV_KEYS = {"SONGRUSH_HOST"}
ALLOWED_NETWORKS = [ipaddress.ip_network(n) for n in
                    ("192.0.2.0/24", "198.51.100.0/24", "203.0.113.0/24")]
ALLOWED_IPS = {ipaddress.ip_address("127.0.0.1"),
               ipaddress.ip_address("0.0.0.0")}

# The restart heading word from the spec ("when started again" in Korean).
RESTART_WORD = "".join(chr(c) for c in
                       (0xB2E4, 0xC2DC, 0x20, 0xCF30, 0xC744, 0x20, 0xB54C))

REQUIRED_STRINGS = [
    "ap-northeast-2", "t3.micro", "Ubuntu Server 24.04", "16 GiB", "gp3",
    "sslip.io", "Budgets", "Paid", "scp", "journalctl -u songrush",
    "smoke.py", "songrush.jar.good", "D-066", "D-067", "D-068",
]
PORTS = [("22", "SSH"), ("80", "HTTP"), ("443", "HTTPS")]

FENCE_RE = re.compile(r"^\s*(`{3,}|~{3,})\s*(.*?)\s*$")
INLINE_RE = re.compile(r"(?<!`)(`+)(?!`)(.+?)(?<!`)\1(?!`)")
HEADING_RE = re.compile(r"^\s{0,3}(#{1,6})\s+(.*)$")
ENV_RE = re.compile(r"(?<![A-Za-z0-9_])([A-Z_]{3,})=")
UNIT_ENV_RE = re.compile(r"^Environment=([A-Za-z_][A-Za-z0-9_]*)=", re.M)
AKIA_RE = re.compile(r"AKIA[0-9A-Z]{16}")
DOTTED_IP_RE = re.compile(r"(?<![\d.])(\d{1,3}(?:\.\d{1,3}){3})(?!\.?\d)")
DASHED_IP_RE = re.compile(r"(?<![\d-])(\d{1,3}(?:-\d{1,3}){3})\.sslip\.io",
                          re.I)
EMAIL_RE = re.compile(
    r"(?<![A-Za-z0-9._%+<>-])([A-Za-z0-9._%+<>-]+)"
    r"@([A-Za-z0-9<>_-]+(?:\.[A-Za-z0-9<>_-]+)+)")
CONTROL_OPS = {"&&", "||", ";", "|", "&", ";;"}


# ---------------------------------------------------------------- parsers

def parse_fences(text):
    """Return (blocks, outside, problems).

    blocks: list of (lang, [body lines], opening line number).
    outside: list of (line number, line) not inside or on a fence.
    """
    blocks, outside, problems = [], [], []
    current = None  # (char, length, lang, body, start)
    for number, line in enumerate(text.splitlines(), 1):
        match = FENCE_RE.match(line)
        if current is None:
            if match:
                marker, info = match.group(1), match.group(2)
                lang = info.split()[0] if info else ""
                current = (marker[0], len(marker), lang, [], number)
            else:
                outside.append((number, line))
            continue
        char, length, lang, body, start = current
        if (match and match.group(1)[0] == char
                and len(match.group(1)) >= length and not match.group(2)):
            blocks.append((lang, body, start))
            current = None
        else:
            body.append(line)
    if current is not None:
        problems.append("line %d: fence is never closed" % current[4])
        blocks.append((current[2], current[3], current[4]))
    return blocks, outside, problems


def bash_lines(text):
    """(opening line number, logical line) for each bash-block line."""
    blocks, _, _ = parse_fences(text)
    result = []
    for lang, body, start in blocks:
        if lang != "bash":
            continue
        pending = ""
        for line in body:
            if line.rstrip().endswith("\\"):
                pending += line.rstrip()[:-1] + " "
                continue
            result.append((start, pending + line))
            pending = ""
        if pending:
            result.append((start, pending))
    return result


def split_segments(line):
    """shlex words of one bash line, cut at control operators."""
    lexer = shlex.shlex(line, posix=True, punctuation_chars="&;|")
    lexer.whitespace_split = True
    segments, current = [], []
    for word in lexer:
        if word in CONTROL_OPS:
            segments.append(current)
            current = []
        else:
            current.append(word)
    segments.append(current)
    return [s for s in segments if s]


def script_name(word):
    """The deploy/ file a word refers to, or None."""
    for prefix in ("./deploy/", "deploy/"):
        if word.startswith(prefix):
            return word[len(prefix):]
    if word in SCRIPT_ARGS:
        return word
    return None


# ---------------------------------------------------------------- rules

def check_hangul(text):
    if any(0xAC00 <= ord(c) <= 0xD7A3 for c in text):
        return []
    return ["no Hangul syllable in the guide"]


def check_fences(text):
    blocks, outside, problems = parse_fences(text)
    for lang, body, start in blocks:
        if lang not in ("bash", "text"):
            problems.append("line %d: fence language %r is not bash or text"
                            % (start, lang))
        if lang == "bash":
            for line in body:
                if ".\\" in line:
                    problems.append("line %d block: '.\\' in %r"
                                    % (start, line))
                if "chmod" in line:
                    problems.append("line %d block: chmod in %r"
                                    % (start, line))
    for number, line in outside:
        for match in INLINE_RE.finditer(line):
            words = match.group(2).split()
            if words and words[0] in COMMAND_WORDS:
                problems.append("line %d: inline code %r starts with a "
                                "command word" % (number, match.group(2)))
    return problems


def check_scripts(text, deploy_files):
    problems = []
    for start, line in bash_lines(text):
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        try:
            segments = split_segments(line)
        except ValueError as error:
            problems.append("block at line %d: shlex cannot split %r (%s)"
                            % (start, line, error))
            continue
        for words in segments:
            while words and words[0] == "sudo":
                words = words[1:]
            if not words or words[0] == "cd":
                continue
            for index, word in enumerate(words):
                name = script_name(word)
                if name is None:
                    continue
                if not name:
                    continue
                if name not in deploy_files:
                    problems.append("block at line %d: %r is not a file in "
                                    "deploy/" % (start, word))
                if name not in SCRIPT_ARGS:
                    continue
                args = [w for w in words[index + 1:]]
                if name == "upload.sh":
                    args = [w for w in args if w != "--dry-run"]
                low, high = SCRIPT_ARGS[name]
                if not low <= len(args) <= high:
                    problems.append("block at line %d: %s given %d "
                                    "argument(s) in %r"
                                    % (start, name, len(args), line))
                break
    return problems


def unit_env_keys(unit_text):
    return set(UNIT_ENV_RE.findall(unit_text))


def check_env_keys(text, allowed):
    problems = []
    for start, line in bash_lines(text):
        for key in ENV_RE.findall(line):
            if key not in allowed:
                problems.append("block at line %d: unknown key %s="
                                % (start, key))
    return problems


def restart_section(text):
    """Lines of the section whose heading contains RESTART_WORD, or None."""
    _, outside, _ = parse_fences(text)
    outside_numbers = {n for n, _ in outside}
    lines = text.splitlines()
    for index, line in enumerate(lines):
        match = HEADING_RE.match(line)
        if not match or index + 1 not in outside_numbers:
            continue
        if RESTART_WORD not in match.group(2):
            continue
        level = len(match.group(1))
        section = []
        for later_index in range(index + 1, len(lines)):
            later = HEADING_RE.match(lines[later_index])
            if (later and later_index + 1 in outside_numbers
                    and len(later.group(1)) <= level):
                break
            section.append(lines[later_index])
        return section
    return None


def check_required(text):
    problems = ["missing %r" % s for s in REQUIRED_STRINGS if s not in text]
    for port, proto in PORTS:
        port_re = re.compile(r"(?<![A-Za-z0-9])%s(?![A-Za-z0-9])" % port)
        proto_re = re.compile(r"(?<![A-Za-z0-9])%s(?![A-Za-z0-9])" % proto)
        if not any(port_re.search(l) and proto_re.search(l)
                   for l in text.splitlines()):
            problems.append("no line with both %s and %s" % (port, proto))
    section = restart_section(text)
    if section is None:
        problems.append("no heading containing the restart word")
    elif not any("setup.sh" in l for l in section):
        problems.append("setup.sh is not in the restart section")
    return problems


def ip_allowed(address):
    if address in ALLOWED_IPS:
        return True
    return any(address in network for network in ALLOWED_NETWORKS)


def check_secrets(text):
    problems = ["AWS key id %r" % m for m in AKIA_RE.findall(text)]
    candidates = DOTTED_IP_RE.findall(text)
    candidates += [m.replace("-", ".") for m in DASHED_IP_RE.findall(text)]
    for candidate in candidates:
        try:
            address = ipaddress.IPv4Address(candidate)
        except ValueError:
            continue
        if not ip_allowed(address):
            problems.append("IPv4 %s is outside the documentation ranges"
                            % candidate)
    for local, domain in EMAIL_RE.findall(text):
        if not re.search(r"[A-Za-z<>]", domain.rsplit(".", 1)[-1]):
            continue
        if local == "ubuntu":
            continue
        if "<" in domain or ">" in domain:
            continue
        if domain.lower().endswith("sslip.io"):
            continue
        problems.append("email %s@%s" % (local, domain))
    return problems


# ---------------------------------------------------------------- guide tests

def read_guide():
    assert GUIDE.is_file(), "%s does not exist" % GUIDE
    return GUIDE.read_bytes().decode("utf-8")


def deploy_files():
    return {p.name for p in DEPLOY.iterdir() if p.is_file()}


def test_guide_exists_utf8_with_hangul():
    assert check_hangul(read_guide()) == []


def test_guide_fences_and_inline_code():
    assert check_fences(read_guide()) == []


def test_guide_scripts_and_arguments():
    assert check_scripts(read_guide(), deploy_files()) == []


def test_guide_env_keys():
    allowed = unit_env_keys(UNIT.read_text(encoding="utf-8")) | EXTRA_ENV_KEYS
    assert check_env_keys(read_guide(), allowed) == []


def test_guide_required_strings():
    assert check_required(read_guide()) == []


def test_guide_no_secrets():
    assert check_secrets(read_guide()) == []


# ---------------------------------------------------------------- self-tests

HANGUL = "".join(chr(c) for c in (0xC548, 0xB0B4))  # a two-syllable word

SAMPLE = "\n".join([
    "# Guide " + HANGUL,
    "",
    "Decisions D-066, D-067 and D-068. Choose the Paid plan and a Budgets",
    "alert. Region ap-northeast-2, Ubuntu Server 24.04 on t3.micro, 16 GiB",
    "of gp3. Allow SSH on port 22 from My IP only.",
    "Allow HTTP 80 and HTTPS 443 from 0.0.0.0/0.",
    "For 203.0.113.10 the address is `203-0-113-10.sslip.io`; the file",
    "songrush.jar.good is the known-good jar. Copy with scp; the program",
    "named ssh, and `smoke.py` checks it. Mail goes to ubuntu@example.com.",
    "",
    "```bash",
    "cd backend && ./gradlew.bat build",
    "cd frontend && npm ci && npm run build",
    "scp -r -i ~/.ssh/<key.pem> deploy ubuntu@<address>:~",
    "ssh -i ~/.ssh/<key.pem> ubuntu@<address>",
    "cd deploy && sudo bash setup.sh <address>",
    "bash deploy/upload.sh ubuntu@<address> ~/.ssh/<key.pem>",
    "bash deploy/upload.sh --dry-run ubuntu@<address>",
    "python deploy/smoke.py https://<address>",
    "sudo journalctl -u songrush -n 50",
    "SONGRUSH_HOST=<address> SERVER_ADDRESS=127.0.0.1 true",
    "```",
    "",
    "```text",
    "OK health",
    "```",
    "",
    "## " + RESTART_WORD,
    "",
    "```bash",
    "# comment heading-like line inside a fence",
    "sudo bash setup.sh <new-address>",
    "```",
    "",
    "## Next",
    "",
])

SAMPLE_DEPLOY = {"setup.sh", "upload.sh", "smoke.py", "songrush.service",
                 "Caddyfile"}
SAMPLE_ENV = {"SERVER_ADDRESS"} | EXTRA_ENV_KEYS


def all_problems(text):
    return {
        "hangul": check_hangul(text),
        "fences": check_fences(text),
        "scripts": check_scripts(text, SAMPLE_DEPLOY),
        "env": check_env_keys(text, SAMPLE_ENV),
        "required": check_required(text),
        "secrets": check_secrets(text),
    }


def test_self_sample_passes_every_rule():
    assert all_problems(SAMPLE) == {k: [] for k in all_problems(SAMPLE)}


def test_self_file_is_ascii():
    data = Path(__file__).read_bytes()
    assert all(b < 0x80 for b in data)


def test_self_unit_env_keys_parsed():
    unit = ("[Service]\nEnvironment=SERVER_ADDRESS=127.0.0.1\n"
            "Environment=SONGRUSH_DATA_DIR=/x\nExecStart=/bin/true\n")
    assert unit_env_keys(unit) == {"SERVER_ADDRESS", "SONGRUSH_DATA_DIR"}


def test_self_restart_section_bounds():
    section = restart_section(SAMPLE)
    assert section is not None
    assert any("setup.sh" in line for line in section)
    assert not any(line.startswith("## Next") for line in section)


BROKEN = [
    ("hangul", lambda s: s.replace(HANGUL, "x").replace(RESTART_WORD, "y")),
    ("fences", lambda s: s.replace("```text", "```")),
    ("fences", lambda s: s.replace("```text", "```console")),
    ("fences", lambda s: s + "\n```bash\nnever closed\n"),
    ("fences", lambda s: s.replace("named ssh,", "named `ssh -i k`,")),
    ("fences", lambda s: s.replace("checks it", "checks `sudo bash x`")),
    ("fences", lambda s: s.replace("./gradlew.bat", ".\\gradlew.bat")),
    ("fences", lambda s: s.replace("scp -r", "chmod 400 k\nscp -r")),
    ("scripts", lambda s: s.replace("deploy/smoke.py", "deploy/smoke2.py")),
    ("scripts", lambda s: s.replace("setup.sh <address>", "setup.sh")),
    ("scripts", lambda s: s.replace("setup.sh <address>",
                                    "setup.sh <a> <b>")),
    ("scripts", lambda s: s.replace("upload.sh --dry-run ubuntu@<address>",
                                    "upload.sh --dry-run")),
    ("scripts", lambda s: s.replace("upload.sh ubuntu@<address> ~/",
                                    "upload.sh a b ~/")),
    ("scripts", lambda s: s.replace("https://<address>",
                                    "https://<address> extra")),
    ("scripts", lambda s: s.replace("python deploy/smoke.py https://<address>",
                                    "python smoke.py")),
    ("scripts", lambda s: s.replace("sudo journalctl", "echo 'open")),
    ("env", lambda s: s.replace("SONGRUSH_HOST=", "AWS_SECRET=")),
    ("required", lambda s: s.replace("t3.micro", "t2.micro")),
    ("required", lambda s: s.replace("D-067", "D-06")),
    ("required", lambda s: s.replace("songrush.jar.good", "the jar")),
    ("required", lambda s: s.replace("port 22", "port twenty-two")),
    ("required", lambda s: s.replace("HTTPS 443", "HTTPS\n443")),
    ("required", lambda s: s.replace("## " + RESTART_WORD, "## Restart")),
    ("required", lambda s: s.replace("sudo bash setup.sh <new-address>",
                                     "true")),
    ("secrets", lambda s: s + "\nkey AKIAABCDEFGHIJKLMNOP\n"),
    ("secrets", lambda s: s.replace("For 203.0.113.10", "For 54.180.1.2")),
    ("secrets", lambda s: s.replace("203-0-113-10.sslip.io",
                                    "54-180-1-2.sslip.io")),
    ("secrets", lambda s: s + "\nwrite to kimdoo@example.com\n"),
]


@pytest.mark.parametrize("rule, breaker", BROKEN,
                         ids=["%s-%d" % (r, i) for i, (r, _) in
                              enumerate(BROKEN)])
def test_self_each_rule_bites(rule, breaker):
    broken = breaker(SAMPLE)
    assert broken != SAMPLE, "the breaker did not change the sample"
    assert all_problems(broken)[rule] != []


def test_self_email_exceptions():
    text = ("ubuntu@203.0.113.10 root@<address>.com "
            "admin@203-0-113-10.sslip.io me@<x>")
    assert check_secrets(text) == []
    assert check_secrets("root@example.com") != []
