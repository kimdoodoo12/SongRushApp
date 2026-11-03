# 062 duckdns-free-plan (I-009)

## Goal
The deploy kit and the guide follow the owner's decisions. D-066: a DuckDNS name that stays fixed when the IP changes. D-068: the AWS Free plan, with its automatic account closure stated clearly. The DuckDNS token never appears in the repo or in any command line.

## Behaviour
1. **`setup.sh <hostname>`.**
   - The usage stays one argument, so the locked cycle 055 and 057 tests still apply.
   - When the hostname ends in `.duckdns.org`:
     - The name is the part before `.duckdns.org`. It must match `^[a-z0-9-]{1,63}$`, otherwise the script exits 2 with a message.
     - The token is asked for with `read -rs` from `/dev/tty`, after the root checks and before any apt step. An empty token exits 2. The token is never an argument and is never echoed.
     - The token goes to `/etc/songrush/duckdns.curl`, mode 600, owner root. The file holds one line, `url = "https://www.duckdns.org/update?domains=<name>&token=<token>&ip="`. curl reads it with `-K`, so the token never shows in a process list. The file is written with `umask 077` and moved into place from a temp file.
     - `deploy/duckdns.service` and `deploy/duckdns.timer` are installed to `/etc/systemd/system/`, and the timer is enabled.
       - The service is a oneshot that runs `/usr/bin/curl -fsS -K /etc/songrush/duckdns.curl`.
       - The timer fires `OnBootSec=30s` and then `OnUnitActiveSec=5min`.
     - The update runs once right away. setup.sh stops with a clear message if the reply is not exactly `OK`. A `KO` reply means a wrong name or token.
     - setup.sh then waits up to 60 s for the name to resolve to this server's public IP, read from `curl -fsS https://checkip.amazonaws.com`, before it reloads Caddy. If the name still does not match, it warns and continues.
   - Any other hostname keeps today's behaviour, with no DuckDNS step.
2. **The guide** (`docs/deploy-guide.md`).
   - **Address.** It switches to DuckDNS: sign up with a Google or GitHub login, create a name, and copy the token. The guide warns never to paste the token anywhere except the setup prompt.
   - **Running setup.** It runs `sudo bash setup.sh <name>.duckdns.org`.
   - **Account plan.** It switches to the AWS Free plan (D-068):
     - The account closes by itself when 6 months pass or the USD 100 credit runs out, whichever comes first.
     - Data is deleted 90 days later unless the account is upgraded.
     - Upgrading is done by choosing the Paid plan in the billing console. The guide shows how.
     - A dated reminder: write down the sign-up date and decide in month 5.
     - The Budgets alert stays, so the owner can watch the credit.
   - **After a restart.** The address stays the same. No `setup.sh` rerun is needed for the address; the section says so and mentions `setup.sh` only to say that. Only the security group's "My IP" may need updating.
   - **sslip.io.** It is kept as a one-paragraph fallback ("DuckDNS가 안 될 때").
   - The guide must keep passing the locked cycle 058 guide test unchanged. That test requires `sslip.io`, `Budgets`, `Paid`, the D-ids, and `setup.sh` in the restart section.

## Acceptance criteria (pytest, from `data/`)
`data/tests/test_duckdns.py` reuses the cycle 055 harness approach, with its helpers copied:
1. **Units.**
   - `deploy/duckdns.service` is `Type=oneshot` with exactly `ExecStart=/usr/bin/curl -fsS -K /etc/songrush/duckdns.curl`.
   - `deploy/duckdns.timer` has `OnBootSec=30s`, `OnUnitActiveSec=5min` and `WantedBy=timers.target`.
   - Both are ASCII with LF line endings.
2. **`setup.sh` text.**
   - The token is read with `read -rs` from `/dev/tty`.
   - `umask 077`, or an explicit `chmod 600`, is applied to the token file.
   - `/etc/songrush/duckdns.curl` and `systemctl enable` for the timer appear.
   - No `echo` or `printf` line prints the token variable to stdout or stderr. Writing it into the curl file is allowed.
   - The DuckDNS update URL appears only in the line that writes the curl file.
3. **`setup.sh` behaviour, with stubs.** Bash runs with stub `id` (root), `apt-get`, `cloud-init`, `systemctl`, `curl` and `caddy`, and with `/dev/tty` replaced by an input file through a test hook variable `SONGRUSH_TTY` (default `/dev/tty`). A sandbox root variable `SONGRUSH_ROOT` (default empty) prefixes every system path the script writes.
   - **Bad name.** `bad_name!.duckdns.org` exits 2 before any stub is called.
   - **Empty token.** It exits 2.
   - **Good token.**
     - The curl file is written under the sandbox with mode 600 (checked where the OS supports it, skipped on Windows with a reason).
     - The curl stub is called with `-K <sandbox>/etc/songrush/duckdns.curl`.
     - The token string never appears in any stub's argv log or in stdout or stderr.
   - **Update rejected.** The curl stub answers `KO` and the script stops with a non-zero exit.
   - **Plain hostname.** `game.example.com` asks for no token and installs no DuckDNS unit.
4. **Secrets.** No file under `deploy/` or `docs/` contains a 36-character UUID-shaped string (DuckDNS tokens are UUIDs).
5. **The guide.** It mentions `duckdns.org`, `토큰`, `무료 플랜`, `90일` and `5개월`. Inline code never contains the word `token=`.
6. **Earlier tests.** The locked cycle 055, 056, 057 and 058 tests and the rest of the data suite pass unchanged: `.venv\Scripts\python.exe -m pytest`.

## Test list
- `data/tests/test_duckdns.py`

## File ownership
- **Test writer:** `data/tests/test_duckdns.py`.
- **Implementer:** `deploy/setup.sh`, the new `deploy/duckdns.service` and `deploy/duckdns.timer`, and `docs/deploy-guide.md`.

## Out of scope
- Changing `upload.sh` or `smoke.py`.
- A custom domain.
