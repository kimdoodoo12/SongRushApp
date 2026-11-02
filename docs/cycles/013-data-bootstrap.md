# 013 data-bootstrap (I-002)

## Goal
Set up `data/` as a Python 3.12 project with pytest, so the song-data tools that S-001 designs have a tested home (D-029, D-030, D-033).

## Setup and interface
- `data/requirements.txt` holds the exact pins: `pytest==9.2.0` (the current release, from `pip index versions pytest`). The S-001 tools add their own pins later.
- `data/pyproject.toml` holds only the project metadata (`name = "songrush-data"`, `requires-python = ">=3.12"`) and the pytest configuration:
  - `[tool.pytest.ini_options]` with `testpaths = ["tests"]`;
  - `pythonpath = ["."]`, so tests import `songrush_data` without installing it, however pytest is started: from `data/`, from elsewhere, or with `-I`. The hook itself runs `.venv` python `-m pytest -q` with the working directory `data/`.
  - `addopts = "-p no:cacheprovider"`, so no `.pytest_cache` lands in the tree.
- `data/songrush_data/__init__.py` defines `__version__ = "0.1.0"`.
- `data/songrush_data/common.py` defines `USER_AGENT = f"SongRushBot/{__version__} (+https://github.com/kimdoodoo12/SongRushApp)"`, following the CLAUDE.md external source rules.
- The venv commands, documented in the CLAUDE.md Commands table:
  1. From `data/`: `python -m venv .venv`
  2. `.venv\Scripts\python.exe -m pip install -r requirements.txt`
  - `data/.venv/` is gitignored.

## Acceptance criteria (each is a test)
1. `songrush_data.__version__` is a non-empty string, and `common.USER_AGENT` starts with `SongRushBot/` and contains the repo URL.
2. Every file in `data/tests/fixtures/` is UTF-8 JSON with no BOM: the raw bytes do not start with `EF BB BF`, and they decode and parse with `json.loads`. The fixtures are saved external responses and samples. Byte integrity is preserved by the `-text` git attribute, not by this test.
3. **No test can reach the network.**
   - An `autouse` fixture in `conftest.py` patches `socket.getaddrinfo`, `socket.socket.connect` and `socket.socket.connect_ex`. Each raises `RuntimeError("network disabled in tests")`, which is not an `OSError`, so urllib cannot wrap it as a URLError.
   - Tests prove each path with `pytest.raises(RuntimeError, match="network disabled")`:
     - `socket.getaddrinfo("example.com", 80)`;
     - `socket.socket().connect(("127.0.0.1", 9))`;
     - `socket.socket().connect_ex(("127.0.0.1", 9))`;
     - `urllib.request.urlopen("http://example.com")`.
4. From `data/`, `.venv\Scripts\python.exe -m pytest` exits 0. The verifier also shows that, after importing `.claude/hooks/check_on_stop.py`, `runners()["data"][0]` is `True`, and that running the returned command in the returned working directory exits 0.

## Test list
- `data/tests/test_bootstrap.py`: criteria 1-3.
- `data/tests/conftest.py`: the network guard.

## File ownership
- Setup and implementer:
  - `data/requirements.txt`, `data/pyproject.toml`;
  - `data/songrush_data/**`;
  - creating `data/.venv`, which is not committed.
- Test writer: `data/tests/test_bootstrap.py` and `data/tests/conftest.py`.
- Lead:
  - removing "(verified once bootstrapped)" from the CLAUDE.md data row, and adding the venv commands there;
  - `.gitignore`: `data/.venv/` is already ignored. Add `backend/bin/`, an IDE build output that kept the hook re-running backend tests.

## Out of scope
Every data tool (S-001 and the C- items), and any further dependencies.

## Review changes (spec round 1)
The critic ran a scratch venv with pytest 9.2.0.
- **H1.** Patching only `connect` let real DNS lookups out: `getaddrinfo` was called for `example.com`. `connect_ex` started a real TCP connection (it returned 10035). And because urllib wraps an `OSError`, a bare "raises" assertion would also pass when the machine is offline. The guard now covers `getaddrinfo`, `connect` and `connect_ex`, raises a dedicated `RuntimeError`, and the tests assert its type and message.
- **M1.** The hook does not pass `-I` to pytest; only the hook script itself runs with `-I`. The `pythonpath` rationale is corrected.
- **M2.** Criterion 4 is now checkable through the verifier's command output.
- **M3.** Not all fixtures are raw responses: one is a request body and one is a processed sample with CRLF. Criterion 2 now checks only "UTF-8 JSON with no BOM".
- **Nits:**
  - The Commands row already existed.
  - `backend/bin/` is now gitignored.
