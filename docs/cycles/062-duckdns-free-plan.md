# 062 duckdns-free-plan (I-009)

## Goal
The deploy kit and the guide follow two owner decisions:
- D-066: a DuckDNS name that stays the same when the server's IP changes.
- D-068: the AWS Free plan, with its automatic closure stated plainly.

The DuckDNS token never appears in the repo, in a command line, or in output.

## Behaviour
1. **`deploy/duckdns-setup.sh <name>`**, a new script. Splitting it out was review round 1, H3. It is run with sudo, either by `setup.sh` or on its own.
   - **Order.** Each step runs only if the previous one passed:
     1. Usage: exactly 1 argument, otherwise exit 2.
     2. The name is lowercased and must match `^[a-z0-9-]{1,63}$`, otherwise exit 2.
     3. The TTY must be readable. The TTY is `${SONGRUSH_TTY:-/dev/tty}`. If it cannot be opened, the script exits 2 with the message `run it in an interactive ssh session (ssh -t)`.
     4. The token is read with `IFS= read -rs token < "$tty" || true`. Its surrounding whitespace is trimmed. It must match the UUID pattern `^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$`, case-insensitive, otherwise exit 2. The token is never printed.
     5. Under `umask 077`, a `mktemp` temp file in `${SONGRUSH_ROOT}/etc/songrush/` gets one line: `url = "https://www.duckdns.org/update?domains=<name>&token=<token>&ip="`. It is then moved with `mv -f` to `${SONGRUSH_ROOT}/etc/songrush/duckdns.curl`. There is no `chown`: under sudo, root already owns it.
     6. `duckdns.service` and `duckdns.timer` are copied from the script's folder into `${SONGRUSH_ROOT}/etc/systemd/system/`. Then `systemctl daemon-reload` and `systemctl enable --now duckdns.timer` run.
     7. One immediate update: `curl -fsS -K <curl file>`, with the reply trimmed.
        - The reply `OK` prints `DuckDNS: <name>.duckdns.org now points to this server`.
        - Anything else, such as `KO`, prints that the name or token is wrong and exits 1.
   - `SONGRUSH_ROOT` and `SONGRUSH_TTY` are test hooks with production defaults: empty and `/dev/tty`. They touch only those paths.
2. **`deploy/duckdns.service`** is a oneshot with `ExecStart=/usr/bin/curl -fsS -K /etc/songrush/duckdns.curl`. **`deploy/duckdns.timer`** has `OnBootSec=30s`, `OnUnitActiveSec=5min` and `WantedBy=timers.target`.
3. **`setup.sh <hostname>`.**
   - If the hostname, lowercased, ends in `.duckdns.org`, it runs `bash "$here/duckdns-setup.sh" "<name>"`. This happens right after the root and login-user checks and before cloud-init and apt.
   - A failed DuckDNS step stops setup before any install.
   - Nothing else changes, and the existing structure stays as the locked 055 and 057 tests require. In particular, `caddy_key`'s `mktemp "$caddy_key.XXXXXX"` form stays.
   - There is no DNS wait. Caddy retries certificates on its own, and the guide says to wait 1-2 minutes before the smoke check.
4. **Guide** (`docs/deploy-guide.md`). It must still pass the locked cycle 058 test.
   - **Address.**
     - DuckDNS signup: Google or GitHub login, create `<name>`, copy the token.
     - The token is pasted only at the setup prompt and never anywhere else.
     - `sudo bash setup.sh <name>.duckdns.org` runs in an interactive ssh session.
     - Troubleshooting: `sudo journalctl -u duckdns -n 20` shows each update. DuckDNS answers `KO` for a wrong token, which systemd still counts as success (review M8).
   - **Plan.** The AWS Free plan (D-068):
     - The account closes by itself at 6 months or when the USD 100 credit runs out, whichever comes first. Data is deleted 90 days later unless the owner upgrades.
     - Upgrading means choosing the Paid plan in the billing console.
     - The owner writes down the sign-up date and decides in month 5.
     - On the Free plan, the remaining credit is checked once a month on the billing console's credits page (review H5). The $1 Budgets alert is described as optional on the Free plan and useful only after an upgrade.
   - **After a restart.** The address stays the same. One sentence says `setup.sh` does not need to run again. Only "My IP" in the security group may need updating.
   - **sslip.io.** It stays as a short "DuckDNS가 안 될 때" fallback.
   - **Formatting.** Use the `<name>.duckdns.org` placeholder, not a real-looking name. No `[A-Z_]{3,}=` token appears inside `bash` blocks. A literal update URL can only appear in a `text` block.

## Acceptance criteria (pytest, from `data/`)
`data/tests/test_duckdns.py` reuses the cycle 055 harness approach. Its helpers are copied, not imported.
1. **Units.**
   - `duckdns.service` is `Type=oneshot` with exactly the `ExecStart` above.
   - The timer has `OnBootSec=30s`, `OnUnitActiveSec=5min` and `WantedBy=timers.target`.
   - Both are ASCII with LF line endings.
2. **`duckdns-setup.sh` behaviour.**
   - Setup: stub `systemctl` and `curl` log their argv. A temp `SONGRUSH_ROOT`. A token input file with a trailing newline passed as `SONGRUSH_TTY`.
   - **Argument and name errors.** No argument, `Bad_Name`, `a.b` and a 64-character name each exit 2. No stub is called and no file is written.
   - **TTY and token errors.**
     - A missing TTY file exits 2 with the `ssh -t` message.
     - An empty token, a non-UUID token, and a token containing `"` each exit 2. No curl file is written.
   - **A good token with the curl stub answering `OK`.**
     - It exits 0.
     - The curl file under the root holds exactly the expected `url = ...` line.
     - The file mode is 600, checked where the OS supports it and skipped with a reason on Windows.
     - The units are copied, and `systemctl enable --now duckdns.timer` was called.
     - curl was called with `-K <root>/etc/songrush/duckdns.curl`.
     - The token string appears in no argv log, no stdout and no stderr.
   - **The curl stub answering `KO`.** It exits 1, and the message says the name or token is wrong.
   - **An uppercase name.** `MyName` writes `domains=myname`.
3. **`setup.sh` integration.**
   - The text runs `duckdns-setup.sh` before the first `apt-get` line, and only for `.duckdns.org` hostnames, compared case-insensitively.
   - A behaviour run under stubbed `id` (root) with `SUDO_USER=ubuntu`, a `.duckdns.org` hostname and a failing DuckDNS step exits non-zero. The `apt-get` stub is never called.
4. **Secrets.** No UUID-shaped string appears in these files:
   - `deploy/*.sh`, `deploy/*.service`, `deploy/*.timer`, `deploy/Caddyfile`;
   - `docs/deploy-guide.md`.
   `smoke.py`'s RFC 6455 GUID is out of scope (review B1).
5. **Guide content.**
   - It mentions `duckdns.org`, `토큰`, `무료 플랜`, `90일`, `5개월`, `journalctl -u duckdns`, `ssh -t` and `크레딧`.
   - No inline code contains `token=`.
6. **Earlier tests.** The locked cycle 055-058 tests and the whole data suite pass unchanged (`.venv\Scripts\python.exe -m pytest`).

## Review changes (spec round 1)
- **Blocker.** The UUID scan was narrowed. `smoke.py` contains the RFC 6455 GUID.
- **High.**
  - The "bad name" case now uses names that pass `setup.sh`'s hostname rule.
  - DuckDNS moved to a separate script that runs before apt. This keeps the test hooks to two paths and the tests offline.
  - The DNS wait was dropped.
  - The guide says the Free plan never charges, so the Budgets alert cannot warn before closure. A monthly credit check replaces it.
- **Medium.**
  - `read` is guarded against `set -e`, with a TTY check.
  - The UUID token format is validated.
  - A `KO` reply from the timer is diagnosable through `journalctl`.
  - No `chown`.
- **Nits.**
  - Placeholder name.
  - Lowercased suffix.

## Test list
- `data/tests/test_duckdns.py`

## File ownership
- **Test writer:** `data/tests/test_duckdns.py`.
- **Implementer:**
  - the new `deploy/duckdns-setup.sh`, `deploy/duckdns.service` and `deploy/duckdns.timer`;
  - `deploy/setup.sh`;
  - `docs/deploy-guide.md`.

## Out of scope
- `upload.sh`, `smoke.py`.
- A custom domain.
