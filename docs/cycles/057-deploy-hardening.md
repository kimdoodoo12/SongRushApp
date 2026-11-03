# 057 deploy-hardening (I-008)

## Goal
The deploy kit survives the first run on a fresh EC2 instance, and it tells the owner whether the service came up after an upload. It also keeps the previous jar for a rollback. All of these come from the cycle 055 code review.

## Behaviour
1. **`setup.sh`.**
   - After the argument and root checks, and before any `apt-get`, it runs `cloud-init status --wait || true`. This is skipped when `cloud-init` is absent: `command -v cloud-init`.
   - Every `apt-get` call passes `-o DPkg::Lock::Timeout=600`.
   - The Caddy repo step runs every time. It no longer checks whether the files exist.
     - The key is downloaded to a temp file made by `mktemp` and dearmored to a second temp file, which is then moved into place with `mv`.
     - The list is downloaded to a temp file and moved into place with `mv`.
     - A failed or interrupted run therefore never leaves a half-written key or list behind.
   - Everything else is unchanged.
2. **`upload.sh`.**
   - **The batch.** `put … app/songrush.jar.new` is the first transfer line, right after the `-mkdir` lines and before any web or data file. The rest of the order is unchanged: `index.html` comes after the other web files.
   - **The final ssh command.** One remote shell command does the following, in order:
     1. If `songrush.jar` exists, copy it to `songrush.jar.prev` with `cp -p`, overwriting.
     2. `mv` `songrush.jar.new` onto `songrush.jar`.
     3. `sudo systemctl restart songrush`.
     4. Poll `curl -fsS http://127.0.0.1:8080/health` up to 30 times, 2 s apart. On the first success it prints `healthy` and exits 0.
     5. If every poll fails, it prints `sudo journalctl -u songrush -n 50 --no-pager` and exits 1.
   - **Exit status.** `upload.sh` exits with the ssh command's status. It prints `done.` only on success. On failure it prints a line telling the owner that `songrush.jar.prev` can be restored.
   - **`--dry-run`.** It prints this whole command as before.
3. **`songrush.service`.** It gains `ConditionPathExists=/opt/songrush/app/songrush.jar` under `[Unit]`.

## Acceptance criteria (pytest, from `data/`)
`data/tests/test_deploy_hardening.py` reuses the approach of the cycle 055 harness: bash finder, wrapper `tools` folder and stub `ssh`/`sftp`. Helpers are copied into the new file; the locked 055 test file is not edited.
1. **`setup.sh` text.**
   - Every line that invokes `apt-get` contains `DPkg::Lock::Timeout=600`.
   - `cloud-init status --wait` appears before the first `apt-get` line.
   - The Caddy repo commands use `mktemp` and `mv`, and no `if [ ! -f` guard surrounds them.
   - `bash -n` passes.
2. **`setup.sh` behaviour.** Run with no argument it still exits 2 with usage before anything else, so the stub log is empty.
3. **Unit.** `[Unit]` contains `ConditionPathExists=/opt/songrush/app/songrush.jar`.
4. **`upload.sh --dry-run` batch order.** In the printed batch:
   - the first line that is not `-mkdir` is the jar `put` to `app/songrush.jar.new`;
   - `web/index.html` comes after `web/assets/a.js`.
5. **The final command, as printed by `--dry-run`.** It contains, in this order:
   - `songrush.jar.prev`;
   - `mv`;
   - `systemctl restart songrush`;
   - `http://127.0.0.1:8080/health`;
   - `journalctl -u songrush`.
   It also contains a loop bound of 30.
6. **A real run against the stubs.**
   - The stub `ssh` answers the size listing, then answers the final command. One variant exits 0 and prints `healthy`; another exits 1.
   - With exit 0, `upload.sh` exits 0 and prints `done.`.
   - With exit 1, `upload.sh` exits non-zero, does not print `done.`, and prints a line containing `songrush.jar.prev`.
7. All earlier tests pass unchanged, including the locked cycle 055 and 056 tests. `.venv\Scripts\python.exe -m pytest` passes.

## Test list
- `data/tests/test_deploy_hardening.py`

## File ownership
- **Test writer:** `data/tests/test_deploy_hardening.py`.
- **Implementer:** `deploy/setup.sh`, `deploy/upload.sh` and `deploy/songrush.service`. All stay ASCII with LF.

## Out of scope
- The runbook (I-006).
- Real servers.
