# 055 deploy-kit (I-004)

## Goal
A `deploy/` folder that installs and runs the game on one Ubuntu 24.04 server behind Caddy, and uploads the build and the 5.1 GB audio from the owner's Windows PC using only OpenSSH. It is checked offline by pytest (design 052, plus the cycle 054 review).

## Files and behaviour
- **`deploy/Caddyfile`**:
  - one site block whose address is the literal placeholder `__SONGRUSH_HOST__`, with automatic HTTPS. `setup.sh` substitutes the real hostname when it installs the file, because the Caddy apt unit reads no environment file (review H2).
  - `encode gzip`;
  - `reverse_proxy 127.0.0.1:8080`;
  - `header /assets/* Cache-Control "public, max-age=31536000, immutable"`;
  - no `log` directive.
- **`deploy/songrush.service`** (systemd):
  - `User=songrush` and `WorkingDirectory=/opt/songrush/run`. That folder holds nothing else, so there is no `public/`, `static/` or `src/main/webapp` (cycle 054 review M3).
  - `ExecStart=/usr/bin/java -Xmx384m -XX:+UseSerialGC -XX:+ExitOnOutOfMemoryError -jar /opt/songrush/app/songrush.jar`.
  - `Environment=` lines for:
    - `SERVER_ADDRESS=127.0.0.1`
    - `SONGRUSH_DATA_DIR=/opt/songrush/data`
    - `SPRING_WEB_RESOURCES_STATIC_LOCATIONS=file:/opt/songrush/web/`
    - `SPRING_WEB_RESOURCES_CACHE_CACHECONTROL_NO_CACHE=true` (review M2)
  - `Restart=on-failure` and `RestartSec=5`.
- **`deploy/setup.sh`** (run once with sudo on the server as the login user, normally `ubuntu`; `set -euo pipefail`; idempotent; usage `setup.sh <hostname>`, exit 2 with a usage line without one):
  - `apt-get install -y openjdk-17-jre-headless`;
  - Caddy from its official apt repository;
  - a 1 GB `/swapfile` if none exists;
  - the `songrush` system user;
  - `/opt/songrush/{app,web,data}` owned by the login user (`$SUDO_USER`), mode 755, files readable by all; `/opt/songrush/run` owned by `songrush`. The login user uploads directly, with no staging copy (review H4).
  - the unit copied to `/etc/systemd/system/` and enabled;
  - the Caddyfile installed to `/etc/caddy/Caddyfile` with `__SONGRUSH_HOST__` replaced by the argument (`sed`), then `systemctl reload caddy`.
- **`deploy/upload.sh`** (run on the owner's PC in Git Bash; usage `upload.sh [--dry-run] <user@host> [<ssh key>]`):
  - **Arguments and tools.**
    - Missing arguments: exit 2 with a usage line.
    - Before any external command, it checks `ssh` and `sftp` with `command -v` (a builtin) and exits 3 with a message naming the missing one.
    - It never calls rsync.
  - **Local files.** If `backend/build/libs/backend-0.0.1-SNAPSHOT.jar` or `frontend/dist/index.html` is missing, it exits 4 with a message telling the owner what to build.
  - **Remote sizes.** One `ssh <host> 'cd /opt/songrush/data && find audio-cache -type f -printf "%s %p\n" 2>/dev/null || true'` gets the remote audio sizes; a first run has none.
  - **The batch (review B1, H1).** It writes one sftp batch, run with `sftp -b <file>`, and uses no `-r`:
    - `-mkdir` for each needed remote directory (the `-` prefix only on `mkdir`, so an existing directory is fine);
    - for each `data/audio-cache/*.ogg`: skip if the remote size equals the local size; `reput` if the remote file is smaller; `put` if it is missing or larger;
    - always `put`, whatever their size: `data/catalog.json`, `data/audio-cache/index.json`, every file under `frontend/dist/` (to `web/` with the same relative paths), and the jar as `app/songrush.jar.new`.
  - **After the batch.** One `ssh` runs `mv /opt/songrush/app/songrush.jar.new /opt/songrush/app/songrush.jar && sudo systemctl restart songrush`. The rename never overwrites a jar a running JVM has open (review H4), and the web files are in place before the start (cycle 054 review M4).
  - **`--dry-run`.** It prints the batch it would send, assuming nothing exists remotely, and the final ssh command. It runs neither `ssh` nor `sftp`.

## Acceptance criteria (pytest, from `data/`)
`data/tests/test_deploy_kit.py`, offline (the conftest guard stays on):
1. **Syntax.** `bash -n` passes for `setup.sh` and `upload.sh`. When `bash` is not on PATH, the test is skipped with a reason.
2. **Encoding.** Every file in `deploy/` is ASCII with LF line endings only.
3. **Unit env keys.**
   - Every `SONGRUSH_*` key in `songrush.service` maps to a component of the `SongrushProperties` record, parsed from `backend/src/main/java/com/songrush/SongrushProperties.java` (`dataDir` corresponds to `SONGRUSH_DATA_DIR`).
   - Every other key is on the allow-list `SERVER_ADDRESS`, `SPRING_WEB_RESOURCES_STATIC_LOCATIONS`, `SPRING_WEB_RESOURCES_CACHE_CACHECONTROL_NO_CACHE`.
   - The unit's `ExecStart` contains `-Xmx384m`, `-XX:+ExitOnOutOfMemoryError` and `songrush.jar`.
   - `WorkingDirectory` is `/opt/songrush/run`.
4. **Static location versus data dir.** The unit's static location and data dir neither equal nor contain each other. This is the cycle 054 guard rule, checked on the strings with `PurePosixPath`.
5. **Caddyfile.** It contains `__SONGRUSH_HOST__`, `reverse_proxy 127.0.0.1:8080`, and no `log` directive.
6. **`upload.sh` behaviour.** These tests run only under a Git-for-Windows or Unix bash.
   - **Finding bash (review H3).** The helper finds bash on `PATH`, ignoring anything under `WindowsApps` or `System32` (the WSL launcher), and then tries `C:\Program Files\Git\usr\bin\bash.exe` or `C:\Program Files\Git\bin\bash.exe`. If none is found, the tests are skipped with a reason.
   - **Fixture.** A temp copy of the repo layout holds a fake jar, `frontend/dist/index.html` and `frontend/dist/assets/a.js`, `data/catalog.json`, `data/audio-cache/index.json` and three `.ogg` files.
   - **Stubs.** The stub folder holds `ssh`, `sftp` and `rsync` scripts that append their argv to a log. The `sftp` stub also copies the `-b` batch file into the log. The `ssh` stub prints a canned size listing.
   - **Cases.**
     - `--dry-run user@host` exits 0 and the stub log is empty. The output contains a `put` for each `.ogg`, for `catalog.json`, for `index.html` and for `app/songrush.jar.new`. It contains no `-plain.jar` and no ` -r`.
     - No arguments: exit 2.
     - With `PATH` set to a folder holding only the `ssh` stub: exit 3, and the message names `sftp`.
     - With the jar missing: exit 4.
     - A real run with the stub folder first on `PATH`, followed by bash's own `usr/bin`. The canned listing gives one `.ogg` the same size, one smaller and leaves one missing, and also lists `catalog.json` at its local size. Then:
       - the batch skips the same-size `.ogg`, uses `reput` for the smaller one and `put` for the missing one;
       - it still has `put` lines for `catalog.json`, `index.json`, `index.html`, `assets/a.js` and the jar;
       - every `mkdir` line starts with `-`;
       - after the sftp call, ssh is called with a command containing `mv` and `systemctl restart songrush`;
       - `rsync` is never called.
7. **`setup.sh`.** It exits 2 with a usage line when run with no argument under bash (found as above); it is not executed otherwise. It contains `set -euo pipefail`, `openjdk-17-jre-headless`, `/swapfile`, `systemctl enable`, and a `sed` that replaces `__SONGRUSH_HOST__`.
8. `.venv\Scripts\python.exe -m pytest` passes in full.

## Review changes (spec round 1)
- **Blocker.** The critic ran `sftp -b` against a local `sftp-server`. `reput` on a missing or same-size file aborts the batch, `put -r` nests on the second run, and `mkdir` on an existing folder fails. The batch is now generated file by file, with `-mkdir`, `put` and `reput` and no `-r`.
- **High.**
  - Only `.ogg` files are skipped by size; the catalog, the index, the web files and the jar are always uploaded.
  - The hostname is substituted into the Caddyfile at setup, since the Caddy unit reads no environment file.
  - The test helper avoids the WSL `bash.exe`, and the exit-3 case uses a stub-only `PATH`.
  - There is no staging folder: the login user owns the upload folders, and the jar is renamed into place.
- **Medium.** The dry run assumes an empty remote.

## Test list
- `data/tests/test_deploy_kit.py`; fixtures go under `data/tests/fixtures/deploy/` if needed.

## File ownership
- **Test writer:** `data/tests/test_deploy_kit.py` and `data/tests/fixtures/deploy/`.
- **Implementer:** `deploy/Caddyfile`, `deploy/songrush.service`, `deploy/setup.sh` and `deploy/upload.sh`.
- **Lead:** maps `deploy/` to the data area in the Stop hook (`EXTRA_AREAS`), done in this cycle.

## Out of scope
- `smoke.py` (I-005).
- The README (I-006).
- Running anything on a real server.

## Result
- **Commits.** The tests are `4cca79b` (locked) and the implementation is `185085d` (attempt 1). The spec revision was first lost to a Python escape error in the lead's edit script. The test writer had started from the pre-review spec, so it was re-briefed and rewrote the tests before the lock.
- **Red phase (verifier, PowerShell).** 16 of 20 failed on missing `deploy/` files, with 0 skips (the bash finder avoided the WSL launcher). The 699 earlier tests passed.
- **Verifier.**
  - `pytest tests/test_deploy_kit.py`: 20 passed, with no skips. The full suite: 715 passed.
  - `git diff 4cca79b HEAD --stat` lists only the four `deploy/` files, and they are ASCII with LF.
  - A real `sftp -D /usr/lib/ssh/sftp-server.exe -b` run of the dry-run batch:
    - it exited 0 on an empty remote and again on the existing tree;
    - `reput` resumed a truncated `.ogg` to a byte-identical file;
    - there was no `web/dist` nesting.
- **Hook.** The lead mapped `deploy/` to the data area in the Stop hook (`EXTRA_AREAS`).

## Code review (critic)
No Blocker or High. Confirmed as correct:
- the Caddy `header ... { defer }` block;
- the official repo commands and `caddy validate`;
- swap idempotence and the user and ownership setup (the backend never writes to the data dir);
- quoting and host-injection checks;
- `sftp -b` stopping before `mv` and restart on a failed `put`.

Mediums, queued as I-008 before the owner's first deploy:
- the dpkg lock right after boot;
- a half-written Caddy repo file blocking reruns;
- no health check after restart, and no `.prev` jar.

Nits, also in I-008:
- the jar uploaded last widens the version-skew window;
- a reboot before the first upload makes the service restart forever.
