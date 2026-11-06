# 057 deploy-hardening (I-008)

## Goal
The deploy kit survives the first run on a fresh EC2 instance. After an upload it tells the owner whether the service came up, and it keeps the last known-good jar so a rollback can be pasted in one line. All of this comes from the cycle 055 code review.

## Behaviour
1. **`setup.sh`.**
   - **Wait for cloud-init.** After the argument and root checks, and before any `apt-get`, run `cloud-init status --wait || true`. Skip this when `command -v cloud-init` fails.
   - **apt lock.** Every `apt-get` call passes `-o DPkg::Lock::Timeout=600`.
   - **Caddy repo step.** It runs every time; there is no existence guard anymore.
     - The key is downloaded and dearmored into a temp file created next to the target with `mktemp "$caddy_key.XXXXXX"`.
     - The list is downloaded into `mktemp "$caddy_list.XXXXXX"`.
     - Each temp file gets `chmod 644` and is then moved onto its target with `mv -f`.
     - A failed run never leaves a half-written key or list. Both end up world-readable, which `_apt` needs (review M3).
   - Everything else is unchanged.
2. **`upload.sh`.**
   - **The batch.** `put … app/songrush.jar.new` is the first transfer line, right after the `-mkdir` lines. The rest is unchanged, and `index.html` still comes after the other web files.
   - **The final remote command.**
     - It is one line, with no newline, joined with `&&` and `;` (review H1).
     - The remote shell code is in a single-quoted literal. Only the fixed remote root is spliced in from outside the quotes, so `$` inside the loop expands on the server, not on the PC (review H2).
     - It contains neither the substring ` -r` nor `find` (review M1, nit).
     - In order, it does the following:
       1. `mv -f songrush.jar.new songrush.jar && sudo systemctl restart songrush`;
       2. poll `curl -fsS http://127.0.0.1:8080/health` up to 60 times, 2 s apart (allowing for a slow start on 1 GB);
       3. on the first success, `cp -p songrush.jar songrush.jar.good`, print `healthy` and exit 0;
       4. if every poll fails, run `sudo journalctl -u songrush -n 50 --no-pager` (the log itself is printed) and exit 1.
     - `songrush.jar.good` is therefore always the last jar that answered `/health` (review M2).
   - **Exit status.** `upload.sh` captures the ssh status without letting `set -e` abort (`if ssh …; then`).
     - On success it prints `done.`.
     - On failure it prints a line containing `restore`, followed by the exact command to paste: `ssh <host> 'cp -p /opt/songrush/app/songrush.jar.good /opt/songrush/app/songrush.jar && sudo systemctl restart songrush'`. It then exits 1.
   - **`--dry-run`.** It prints the final command as before.
3. **`songrush.service`.** `[Unit]` gains `ConditionPathExists=/opt/songrush/app/songrush.jar`.

## Acceptance criteria (pytest, from `data/`)
`data/tests/test_deploy_hardening.py` reuses the approach of the cycle 055 harness (bash finder, wrapper `tools` folder, stub `ssh`/`sftp`). Its helpers are copied into the new file; the locked 055 and 056 test files are not edited.

1. **`setup.sh` text.**
   - Every line that invokes `apt-get` contains `DPkg::Lock::Timeout=600`.
   - `cloud-init status --wait` appears before the first `apt-get`.
   - The Caddy commands use `mktemp`. For each temp file, `chmod 644` comes before its `mv`.
   - No `if [ ! -f` guard remains around the repo step.
   - `bash -n` passes.
2. **`setup.sh` behaviour.**
   - Setup: stub `cloud-init` and `apt-get` scripts that log their calls sit first on `PATH`.
   - With no argument, it exits 2 with usage.
   - Neither stub was called.
3. **Unit.** `[Unit]` contains `ConditionPathExists=/opt/songrush/app/songrush.jar`.
4. **`upload.sh --dry-run` batch order.**
   - The first batch line that is not `-mkdir` is the jar `put` to `app/songrush.jar.new`.
   - `web/index.html` comes after `web/assets/a.js`.
5. **The final command, as printed by `--dry-run`.**
   - It is one line.
   - It contains, in this order: `mv`, `systemctl restart songrush`, `http://127.0.0.1:8080/health`, `songrush.jar.good`, `journalctl -u songrush`.
   - It contains the loop bound `60`.
   - It contains no ` -r` and no `find`.
   - A `$` survives in it, so the loop variable was not expanded locally.
6. **A real run against the stubs.**
   - The stub `ssh` tells its two calls apart by `find` (only the size listing contains it).
   - When the final call exits 0, `upload.sh` exits 0 and prints `done.`.
   - When the final call exits 1, `upload.sh` exits 1. It does not print `done.`, and it prints a line containing `restore` and `songrush.jar.good`.
7. All earlier tests pass unchanged, including the locked cycle 055 and 056 tests, and `.venv\Scripts\python.exe -m pytest` passes.

## Review changes (spec round 1)
- **High.**
  - The final command is one line, because the locked 055 real-run test looks for `mv` and `restart` on one log line.
  - The remote code is single-quoted, so local `set -u` never sees the loop variables.
- **Medium.**
  - No ` -r` in the final command, because the locked dry-run test forbids it anywhere in the output.
  - A known-good jar `.good` is written after a healthy start, replacing `.prev`, which a retry would overwrite.
  - `chmod 644` comes before `mv` for the apt files.
  - `journalctl` is executed.
- **Nits.**
  - The ssh status is captured.
  - Steps are joined with `&&`.
  - `setup.sh`'s no-argument test uses `apt-get`/`cloud-init` stubs.
  - The poll budget is 120 s.

## Test list
- `data/tests/test_deploy_hardening.py`

## File ownership
- **Test writer:** `data/tests/test_deploy_hardening.py`.
- **Implementer:** `deploy/setup.sh`, `deploy/upload.sh` and `deploy/songrush.service`. All stay ASCII with LF.

## Out of scope
- The runbook (I-006).
- Real servers.

## Result
- Tests `2630618` (locked), implementation `1e39b91` (attempt 1).
- Red phase (verifier): 13 of 17 failed on the missing behaviour. The 4 passing ones are guards: bash syntax, no stub calls without an argument, the harness, and `done.` on success.
- Verifier:
  - The deploy tests (056 + 055 + 057) gave 56 passed; the full data suite gave 751 passed.
  - `git diff 2630618 HEAD --stat` lists only the three `deploy/` files. All `deploy/` files are ASCII with LF (`i/lf w/lf`).
  - The real final command was run in a sandbox with stub `sudo`, `curl` and `sleep`:
    - healthy case: exit 0, `healthy`, and `songrush.jar.good` exists;
    - unhealthy case: exit 1 after 60 polls, the journal line printed, and no `.good`.

## Code review (critic)
No Blocker, High or Medium.
- Confirmed:
  - the EXIT trap and `trap -` placement;
  - leftover `*.XXXXXX` files are ignored by apt;
  - `chmod 644` comes before `mv`;
  - the precedence of `&&`, `;` and `for`, reproduced: a failed restart skips the poll and exits 1 with the journal;
  - the `printf %q` key in the hint;
  - `ConditionPathExists`.
- Nits (follow-up):
  - the restore hint on a first deploy with no `.good` yet;
  - the restore `cp` over the live jar;
  - a failed `.good` copy reports failure;
  - the worst-case poll is about 420 s.
