"""Cycle 066 criterion 1: the guide section for a changed home IP.

docs/cycles/066-home-ip-guide.md is the specification. The guide is read
inside a test, never at import time, so a missing guide fails its tests
rather than the collection.

Each rule is a pure function from guide text to a list of problems (empty
means the rule holds). The self-tests at the bottom run the same functions
on an inline sample that passes, and on one broken copy per rule.

This file is ASCII-only: Hangul is built from code points with chr().

Parsing choices (the spec leaves these open; they match test_deploy_guide):
- A heading is an ATX heading ("#" to "######") outside fenced blocks.
- The section runs from the line after the heading to the next heading of
  the same or a higher level (fewer or equal "#"), outside fences.
- "22" and "80" must appear as whole numbers (no digit or letter adjacent).
- A numbered list item is a line outside fences of the form "1. text" or
  "1) text", any indentation. At least 3 such items are required.
"""

import re
from pathlib import Path

import pytest

REPO = Path(__file__).resolve().parents[2]
GUIDE = REPO / "docs" / "deploy-guide.md"


def hangul(*codes):
    return "".join(chr(c) for c in codes)


# "When the server cannot be reached from another Wi-Fi" in Korean.
HEADING_TEXT = hangul(0xB2E4, 0xB978, 0x20,
                      0xC640, 0xC774, 0xD30C, 0xC774, 0xC5D0, 0xC11C, 0x20,
                      0xC11C, 0xBC84, 0x20,
                      0xC811, 0xC18D, 0xC774, 0x20,
                      0xC548, 0x20, 0xB420, 0x20, 0xB54C)
SECURITY_GROUP = hangul(0xBCF4, 0xC548, 0x20, 0xADF8, 0xB8F9)
INBOUND = hangul(0xC778, 0xBC14, 0xC6B4, 0xB4DC)
MY_IP = hangul(0xB0B4) + " IP"

REQUIRED_WORDS = [SECURITY_GROUP, INBOUND, MY_IP]
REQUIRED_NUMBERS = ["22", "80"]
MIN_STEPS = 3

FENCE_RE = re.compile(r"^\s*(`{3,}|~{3,})\s*(.*?)\s*$")
HEADING_RE = re.compile(r"^\s{0,3}(#{1,6})\s+(.*)$")
STEP_RE = re.compile(r"^\s*\d+[.)]\s+\S")


# ---------------------------------------------------------------- parsers

def outside_fence_flags(lines):
    """For each line, True when it is outside every fenced block."""
    flags = []
    current = None  # (char, length)
    for line in lines:
        match = FENCE_RE.match(line)
        if current is None:
            if match:
                current = (match.group(1)[0], len(match.group(1)))
                flags.append(False)
            else:
                flags.append(True)
            continue
        char, length = current
        if (match and match.group(1)[0] == char
                and len(match.group(1)) >= length and not match.group(2)):
            current = None
        flags.append(False)
    return flags


def find_section(text):
    """(section lines, outside-fence flags) for the heading, or None."""
    lines = text.splitlines()
    flags = outside_fence_flags(lines)
    for index, line in enumerate(lines):
        match = HEADING_RE.match(line)
        if not match or not flags[index]:
            continue
        if HEADING_TEXT not in match.group(2):
            continue
        level = len(match.group(1))
        end = len(lines)
        for later in range(index + 1, len(lines)):
            later_match = HEADING_RE.match(lines[later])
            if (later_match and flags[later]
                    and len(later_match.group(1)) <= level):
                end = later
                break
        return lines[index + 1:end], flags[index + 1:end]
    return None


# ---------------------------------------------------------------- rules

def check_heading(text):
    if find_section(text) is None:
        return ["no heading containing %r" % HEADING_TEXT]
    return []


def check_mentions(text):
    found = find_section(text)
    if found is None:
        return ["no section to check"]
    body = "\n".join(found[0])
    problems = ["section does not mention %r" % w
                for w in REQUIRED_WORDS if w not in body]
    for number in REQUIRED_NUMBERS:
        pattern = r"(?<![A-Za-z0-9])%s(?![A-Za-z0-9])" % number
        if not re.search(pattern, body):
            problems.append("section does not mention port %s" % number)
    return problems


def check_steps(text):
    found = find_section(text)
    if found is None:
        return ["no section to check"]
    lines, flags = found
    steps = [l for l, outside in zip(lines, flags)
             if outside and STEP_RE.match(l)]
    if len(steps) < MIN_STEPS:
        return ["section has %d numbered step(s), needs at least %d"
                % (len(steps), MIN_STEPS)]
    return []


# ---------------------------------------------------------------- guide tests

def read_guide():
    assert GUIDE.is_file(), "%s does not exist" % GUIDE
    return GUIDE.read_bytes().decode("utf-8")


def test_guide_has_home_ip_heading():
    assert check_heading(read_guide()) == []


def test_guide_home_ip_section_mentions():
    assert check_mentions(read_guide()) == []


def test_guide_home_ip_section_numbered_steps():
    assert check_steps(read_guide()) == []


# ---------------------------------------------------------------- self-tests

SAMPLE = "\n".join([
    "# Guide",
    "",
    "## 11. Trouble",
    "",
    "### " + HEADING_TEXT,
    "",
    MY_IP + " changes; ports 80 and 443 stay open.",
    "",
    "1. EC2 > " + SECURITY_GROUP + " > " + INBOUND + " edit.",
    "2. SSH (22) row: " + MY_IP + ".",
    "3. Save.",
    "",
    "```text",
    "# not a heading inside a fence",
    "```",
    "",
    "### Next",
    "",
    "4. a later list item",
    "",
])


def all_problems(text):
    return {
        "heading": check_heading(text),
        "mentions": check_mentions(text),
        "steps": check_steps(text),
    }


def test_self_sample_passes_every_rule():
    assert all_problems(SAMPLE) == {k: [] for k in all_problems(SAMPLE)}


def test_self_file_is_ascii():
    data = Path(__file__).read_bytes()
    assert all(b < 0x80 for b in data)


def test_self_section_bounds():
    lines, _ = find_section(SAMPLE)
    assert any(line.startswith("3. Save") for line in lines)
    assert not any(line.startswith("### Next") for line in lines)
    assert not any("later list item" in line for line in lines)


def test_self_fence_does_not_end_section():
    lines, _ = find_section(SAMPLE)
    assert any("not a heading inside a fence" in line for line in lines)


BROKEN = [
    ("heading", lambda s: s.replace("### " + HEADING_TEXT, "### Trouble")),
    ("heading", lambda s: s.replace("### " + HEADING_TEXT,
                                    "```text\n### " + HEADING_TEXT
                                    + "\n```")),
    ("mentions", lambda s: s.replace(SECURITY_GROUP, "SG")),
    ("mentions", lambda s: s.replace(INBOUND, "inbound")),
    ("mentions", lambda s: s.replace(MY_IP, "my address")),
    ("mentions", lambda s: s.replace("(22)", "(2222)")),
    ("mentions", lambda s: s.replace("ports 80", "ports 8080")),
    # A mention after the section ends does not count.
    ("mentions", lambda s: s.replace("ports 80", "ports")
     .replace("4. a later", "port 80, 4. a later")),
    ("steps", lambda s: s.replace("3. Save.", "Save.")),
    ("steps", lambda s: s.replace("3. Save.", "```text\n3. Save.\n```")),
    ("steps", lambda s: s.replace("2. SSH", "2.SSH")),
    # A same-level heading before the steps ends the section.
    ("steps", lambda s: s.replace("1. EC2", "### Other\n\n1. EC2")),
]


@pytest.mark.parametrize("rule, breaker", BROKEN,
                         ids=["%s-%d" % (r, i) for i, (r, _) in
                              enumerate(BROKEN)])
def test_self_each_rule_bites(rule, breaker):
    broken = breaker(SAMPLE)
    assert broken != SAMPLE, "the breaker did not change the sample"
    assert all_problems(broken)[rule] != []
