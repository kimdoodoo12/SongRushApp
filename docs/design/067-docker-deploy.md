# 067 Docker deployment (S-004)

## Questions
1. How is the image built and moved to the server without a paid registry or a new account?
2. What runs in containers: the game server only, or Caddy too? Where does DuckDNS run?
3. How do the 5 GB of audio and the catalog reach the container without being copied into the image?
4. How does the JVM fit in a container on a 1 GB t3.micro?
5. What are the logs and restart policy, and what replaces the `systemctl`/`journalctl` commands in the guide?
6. How do we move from the native install (cycles 055-062) that is being deployed now, without re-uploading audio?

## Findings
- **Local Docker.** This PC has Docker 29.9.2 (`docker --version`). The current jar is `backend/build/libs/backend-0.0.1-SNAPSHOT.jar`.
- **Runtime contract of the jar (cycles 054, 055).**
  - Inputs:
    - `SONGRUSH_DATA_DIR` (catalog and audio);
    - `SPRING_WEB_RESOURCES_STATIC_LOCATIONS` (built frontend);
    - `SPRING_WEB_RESOURCES_CACHE_CACHECONTROL_NO_CACHE`;
    - `SERVER_ADDRESS`;
    - forwarded headers from a proxy on loopback or a private network. Tomcat's internal-proxy defaults include `10/8`, `172.16/12` and `192.168/16`, so a proxy on a Docker bridge network qualifies.
  - The guard refuses a static location that overlaps the data dir.
  - The backend writes nothing to the data dir (cycle 062 review).
  - `-Xmx384m -XX:+UseSerialGC -XX:+ExitOnOutOfMemoryError`.
- **Image size.** A Temurin 17 JRE base (`eclipse-temurin:17-jre`) is about 90 MB compressed. Adding the jar (about 30 MB) and the web files (about 0.3 MB) gives an image of about 120-130 MB compressed.
- **Memory.** On 1 GB with 1 GB swap: the Docker daemon is about 40-60 MB, containerd about 20-30 MB, Caddy about 20-40 MB, and the JVM heap is 384 MB plus about 100 MB native. That fits, the same as today plus the daemons. The daemons are the one cost the native install avoided.

## Options
**A. Image build and transfer**
- **A1. Build on the PC, then `docker save | gzip | ssh … docker load`.**
  - No registry or account is needed.
  - The image is built and tested locally.
  - Each update sends about 130 MB, and only the changed layers matter for build time, not for transfer.
- **A2. Upload the jar and a Dockerfile, then `docker build` on the server.**
  - The transfer is small, but the server needs build time and pulls the base image itself.
  - The image that runs is not the one tested on the PC.
- **A3. A registry (GHCR or Docker Hub).** Standard practice, but it needs an account and a token on both machines, which is an external-account decision.

**B. What runs where**
- **B1. Docker Compose with two services, `app` (game server) and `caddy` (official `caddy:2` image).**
  - Caddy gets its certificate volume and is the only service publishing 80/443.
  - `app` is not published and is reached only on the Compose network.
  - The DuckDNS updater stays a host systemd timer (cycle 062). It touches only DNS and needs no container.
- **B2. Only `app` in Docker, Caddy stays on the host.** Fewer changes, but it is a half-Docker setup.

**C. Data**
- **C1. Bind-mount the host folders read-only:** `/opt/songrush/data:/data:ro` and `/opt/songrush/web:/web:ro`.
  - The audio already uploaded stays where it is.
  - The image carries only code.
- **C2. Bake the web files into the image and bind-mount only the data.**
  - Then a frontend change needs an image rebuild.
  - In exchange, `upload.sh` uploads no web files: the image always holds a matching frontend and backend, which fixes the version-skew follow-up.

## Decision
- **A1:** build and test on the PC, then stream the image over ssh. No account is needed, and the image that runs is the one tested. The image is tagged with the git short SHA and also `songrush:current`. The previous tag is kept as `songrush:good` after a healthy start; this replaces `.jar.good` from cycle 057.
- **B1:** Docker Compose with `app` and `caddy`. The `docker compose` plugin comes from Docker's official apt repository. setup.sh installs it, with the existing `DPkg::Lock::Timeout`.
- **C2:** the web files go inside the image. `/opt/songrush/data` is bind-mounted read-only at `/data`. Inside the container, `SONGRUSH_DATA_DIR=/data` and `SPRING_WEB_RESOURCES_STATIC_LOCATIONS=file:/app/web/`; the guard still holds.
- **Images.**
  - `app`: `eclipse-temurin:17-jre`, running as a non-root user.
  - `caddy`: the official `caddy:2`, pinned to a minor version.
  - Healthchecks: the `app` image has none, because the JRE image has no curl. Compose waits for the port, and `upload.sh` polls `/health` through `docker compose exec caddy wget -qO- http://app:8080/health`. `caddy:2` is Alpine-based and has busybox `wget`.
- **Restart and logs.**
  - `restart: unless-stopped` on both services.
  - `-XX:+ExitOnOutOfMemoryError` stays.
  - Logs use Docker's `json-file` driver with `max-size: 10m` and `max-file: 3`.
  - The guide's commands become `docker compose logs app --tail 50` and `docker compose ps`.
- **JVM.** `JAVA_TOOL_OPTIONS=-Xmx384m -XX:+UseSerialGC -XX:+ExitOnOutOfMemoryError`. The container memory limit is `mem_limit: 640m`, so a leak cannot take the whole machine down.
- **Caddy.**
  - The same Caddyfile, with `reverse_proxy app:8080`.
  - Certificates live in a named volume `caddy_data`, so a restart keeps them and avoids Let's Encrypt rate limits.
  - The hostname comes from `SONGRUSH_HOST` in a `.env` file next to the compose file, which setup.sh writes. Caddy expands `{$SONGRUSH_HOST}` from the container environment.
- **Migration from the native install.**
  - A new `deploy/docker/migrate.sh` runs once on the server. It:
    1. stops and disables the native `songrush` service and the host `caddy` service;
    2. leaves `/opt/songrush/data` in place;
    3. starts Compose.
  - The DuckDNS timer and its token file are unchanged.
  - Rollback: `docker compose down`, then re-enable the native services. These are documented, not automated.
- **Files.**
  - `deploy/docker/Dockerfile`
  - `deploy/docker/compose.yaml`
  - `deploy/docker/Caddyfile`
  - `deploy/docker/setup-docker.sh`: installs Docker and Compose and writes `.env`. It reuses `duckdns-setup.sh` when the name ends in `.duckdns.org`.
  - `deploy/docker/deploy.sh`: replaces `upload.sh` for code. It runs the local build, `docker save`, the ssh load, `compose up -d` and the health poll, and keeps the `good` tag.
  - `deploy/docker/upload-data.sh`: the audio and catalog part of today's `upload.sh`, with the same resumable sftp rules. It is needed only when songs change.
  - The native files stay for one release as the documented rollback. They are removed in a later cycle.
- **Tests.** pytest in `data/tests/`, like cycles 055-062. Checks:
  - the Dockerfile has a pinned base, a non-root user and no audio copied;
  - the compose file has the `ro` data mount, only `caddy` publishing ports, log rotation, `mem_limit` and restart policies;
  - the scripts pass `bash -n`, are ASCII with LF, and pass stub-PATH behaviour tests, as in cycle 055;
  - one optional local check: `docker build` succeeds and the container answers `/health` on a free port with a tiny fixture data dir. It is skipped when Docker is not running.
- **Guide.** `docs/deploy-guide.md` switches to the Docker flow, and the native steps move to an appendix as the rollback.

## Resulting items
1. **I-011 Docker image and compose.**
   - Files: the Dockerfile, `compose.yaml` and the Caddyfile under `deploy/docker/`.
   - Tests: static checks, plus the optional local `docker build` and `/health` check with a fixture data dir.
2. **I-012 Docker scripts.**
   - Files: `setup-docker.sh`, `deploy.sh`, `upload-data.sh` and `migrate.sh`.
   - Tests: stub-PATH behaviour tests for every one; the token never appears in argv or output.
3. **I-013 Guide and live migration.**
   - Guide: rewrite `docs/deploy-guide.md` for Docker, with a rollback appendix.
   - Live migration: the owner runs `migrate.sh` with the lead watching. This last step needs the owner's server, and is `[?]` until then.

No needs-decision items: A3 (a registry account) was not chosen.
