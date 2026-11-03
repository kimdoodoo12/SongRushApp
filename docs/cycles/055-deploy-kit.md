# 055 deploy-kit (I-004)

## Goal
A `deploy/` folder that installs and runs the game on one Ubuntu 24.04 server behind Caddy, and uploads the build and the 5.1 GB audio from the owner's Windows PC using only OpenSSH. It is checked offline by pytest (design 052, plus the cycle 054 review).

## Files and behaviour
- **`deploy/Caddyfile`**:
  - one site block `{$SONGRUSH_HOST}` with automatic HTTPS;
  - `encode gzip`;
  - `reverse_proxy 127.0.0.1:8080`;
  - `header /assets/* Cache-Control "public, max-age=31536000, immutable"`;
  - no access log.
- **`deploy/songrush.service`** (systemd):
  - `User=songrush` and `WorkingDirectory=/opt/songrush/run`. That folder holds nothing else, so there is no `public/`, `static/` or `src/main/webapp` (cycle 054 review M3).
  - `ExecStart=/usr/bin/java -Xmx384m -XX:+UseSerialGC -XX:+ExitOnOutOfMemoryError -jar /opt/songrush/app/songrush.jar`.
  - `Environment=` lines for:
    - `SERVER_ADDRESS=127.0.0.1`
    - `SONGRUSH_DATA_DIR=/opt/songrush/data`
    - `SPRING_WEB_RESOURCES_STATIC_LOCATIONS=file:/opt/songrush/web/`
    - `SPRING_WEB_RESOURCES_CACHE_CACHECONTROL_NO_CACHE=true` (review M2)
  - `Restart=on-failure` and `RestartSec=5`.
- **`deploy/setup.sh`** (run once as root on the server; `set -euo pipefail`; idempotent):
  - `apt-get install -y openjdk-17-jre-headless`;
  - Caddy from its official apt repository (the commands in Caddy's install docs);
  - a 1 GB `/swapfile` if none exists;
  - the `songrush` system user;
  - the folders `/opt/songrush/{app,web,data,run}` owned by it;
  - the unit copied to `/etc/systemd/system/` and enabled;
  - the Caddyfile copied to `/etc/caddy/Caddyfile`, with `SONGRUSH_HOST` taken from its first argument and written to `/etc/default/caddy` (`SONGRUSH_HOST=<arg>`);
  - with no argument it exits 2 with a usage line.
- **`deploy/upload.sh`** (run on the owner's PC in Git Bash):
  - **Usage.** `upload.sh [--dry-run] <user@host> [<ssh key>]`. With missing arguments it exits 2 with a usage line. It checks `ssh` and `sftp` with `command -v` and exits 3 with a message naming the missing one. It never calls rsync (absent on the owner's PC; review H1).
  - **Batch.** It writes an `sftp` batch that uploads:
    - `backend/build/libs/backend-0.0.1-SNAPSHOT.jar`, by its exact name, as `app/songrush.jar` (never `-plain.jar`);
    - `frontend/dist/` to `web/`;
    - `data/catalog.json` and `data/audio-cache/` to `data/`.
  - **Resuming.** `reput` resumes partial files. To skip finished ones, it lists remote sizes with one `ssh <host> 'find /opt/songrush/... -printf "%s %P\n"'` and leaves out files whose size matches. Exception: `web/index.html` is always uploaded (review M2).
  - **Upload user.** It uploads into a staging folder `/home/<user>/songrush-upload/`. A final `ssh` runs `sudo install`/`cp -r` into `/opt/songrush/*` and then `sudo systemctl restart songrush`, so web files are in place before start (cycle 054 review M4).
  - **`--dry-run`.** It prints the batch and the ssh commands and runs neither `ssh` nor `sftp`, except the remote size listing, which is also skipped: every file is listed.
  - **Missing local files.** If the jar or `frontend/dist/index.html` is missing, it exits 4 with a message telling the owner what to build.

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
5. **Caddyfile.** It contains `{$SONGRUSH_HOST}`, `reverse_proxy 127.0.0.1:8080`, and no `log` directive.
6. **`upload.sh` behaviour.** Bash runs it with a PATH whose first entry is a temp folder of stub `ssh`, `sftp` and `rsync` scripts that append their argv to a log, plus a temp copy of the repo layout holding a fake jar, `dist/index.html`, `catalog.json` and two audio files. Skipped without bash.
   - `--dry-run user@host` exits 0. The stub log is empty. The output names `app/songrush.jar`, `web/index.html`, both audio files and `catalog.json`, and never `-plain.jar`.
   - No arguments: exit 2.
   - A PATH without `sftp` gives exit 3, and the message names `sftp`.
   - With the jar missing: exit 4.
   - A real run against the stubs:
     - the stub `ssh` answers the size listing with a matching size for one audio file and for `index.html`;
     - that audio file is left out of the sftp batch the stub received, and `index.html` stays in;
     - `rsync` is never called.
7. **`setup.sh`.** It exits 2 with a usage line when run with no argument under bash; it is not executed otherwise. It contains `set -euo pipefail`, `openjdk-17-jre-headless`, `/swapfile` and `systemctl enable`.
8. `.venv\Scripts\python.exe -m pytest` passes in full.

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
