> **Status: draft on hold (D-082).** The owner is researching operations and deployment before anything is built. Round-2 review findings (Actions minutes, first-certificate order, Nginx DNS caching, WebSocket timeouts, awslogs blocking mode, OIDC/SSM least privilege, release directories, deploy gating, first image bootstrap) are listed below and not yet folded in.

# 067 Docker deployment, CI/CD and operations (S-004)

This design covers the owner's stack decisions of 2026-11-04:
- D-071: Docker.
- D-072: GitHub Actions CI/CD with automatic deployment when a change is merged to `main`.
- D-073: GHCR as the image registry.
- D-074: an Actions health check that alerts on Discord.
- D-075: Nginx with certbot.
- D-078: CloudWatch Logs.

D-079 and D-080 confirm Java 17, no database, the build tools, and Ubuntu 24.04 on t3.micro in Seoul.

STOMP (D-076) and S3/CloudFront (D-077) get their own designs (S-005 and S-006). This design must not block them:
- The image runs whatever the jar is.
- After S-006, the audio mount goes away.

## Questions
1. How does a merge to `main` become a running server, and which secrets does that need?
2. How do Nginx and certbot run, and how is the certificate renewed and reloaded?
3. Where do the 5 GB of audio and the catalog live until S-006 moves the audio to S3?
4. How does everything fit in 1 GB of memory and a 16 GB disk?
5. How do logs reach CloudWatch, and what does the instance need for that?
6. How does the Actions health check alert on Discord without false alarms?
7. How do we switch from the native install that is running now, and how do we roll back?

## Findings
- **The jar's runtime contract** (cycles 054 and 055):
  - Configuration comes from `SONGRUSH_DATA_DIR`, `SPRING_WEB_RESOURCES_STATIC_LOCATIONS` and `SPRING_WEB_RESOURCES_CACHE_CACHECONTROL_NO_CACHE`.
  - Forwarded headers are trusted from private ranges. The critic read Spring Boot 4.1.1's `RemoteIpValve` defaults, which include `172.16.0.0/12` and `192.168.0.0/16` and so cover Docker bridge networks.
  - The static-location guard forbids overlap with the data dir.
  - The backend never writes to the data dir.
- **Tomcat binding in a container.** `SERVER_ADDRESS` must be unset or `0.0.0.0` inside the container. `127.0.0.1` would make the app unreachable from the proxy container (review M1).
- **Disk.**
  - Native need: about 12 GB (design 053).
  - Docker adds docker-ce and containerd (a few hundred MB).
  - containerd's image store (the default on Engine 29, confirmed on this PC) keeps both compressed and unpacked layers, so each image counts roughly twice.
  - The JRE image is about 200 MB unpacked.
  - Without pruning, every deploy adds tens of MB (review H4).
- **Memory on 1 GB with 1 GB swap.**

  | Part | Approx. memory |
  |---|---|
  | dockerd + containerd | 60-90 MB |
  | Nginx | 10 MB |
  | JVM | 384 MB heap + ~100 MB native |
  | CloudWatch log driver | runs inside dockerd, no extra process |

  It fits. Swap stays mandatory (review H2).
- **GitHub Actions.**
  - Hosted runners can build images and push to GHCR with the built-in `GITHUB_TOKEN` (`packages: write`).
  - To deploy, a runner must SSH to the server. The runner's IP is not fixed, so the security group's SSH rule, today "My IP" only (cycle 066), cannot allow it as it stands.
- **Scheduled workflows** run at most every 5 minutes. They can be delayed under load and are disabled after 60 days without repository activity.

## Options
**A. How Actions reaches the server**
- **A1. Open SSH (22) to everyone.** Key-only login. This is simple but invites constant scanning (cycle 066 advised against it).
- **A2. AWS Systems Manager (SSM) Run Command.**
  - Actions authenticates to AWS with GitHub OIDC (no stored AWS keys) and runs `docker compose pull && up -d` on the instance.
  - SSH stays limited to "My IP".
  - Needs: the SSM agent (preinstalled on Ubuntu AMIs) and an IAM instance role. The same role also serves CloudWatch (D-078) and later S3 (D-077).
  - Needs: an IAM OIDC provider and a deploy role for GitHub, in the owner's account.
- **A3. Temporarily allow the runner's IP in the security group during the job.** Needs AWS credentials in Actions anyway and is fragile.

**B. Nginx and certificates**
- **B1. Containers.** `nginx:1.27-alpine` and `certbot/certbot` in Compose.
  - certbot uses the webroot method through a shared volume.
  - A renewal loop in the certbot container runs every 12 h.
  - After a renewal, Nginx reloads from a deploy hook (`docker compose exec nginx nginx -s reload` run by a host systemd timer).
  - The first certificate is obtained by setup with a temporary HTTP-only Nginx config.
- **B2. Nginx and certbot on the host, app only in Docker.** Fewer moving parts, but half of the deploy stays outside Compose.

**C. Image transfer** (decided by D-073: GHCR)
- GHCR images can be public or private.
- For a private image, the server needs a read token stored on the instance.
- A public image needs no token on the server. It reveals only code that is already in the repository, never audio or answers (the audio is not in the image).

## Decision
- **Compose project** (`deploy/docker/compose.yaml`, top-level `name: songrush`), three services:

  | Service | Image | Ports | Mounts | Other |
  |---|---|---|---|---|
  | `app` | `ghcr.io/<owner>/songrush` | not published | `/opt/songrush/data:/data:ro` until S-006 | env listed below; `mem_limit: 640m`; `restart: unless-stopped` |
  | `nginx` | `nginx:1.27-alpine`, pinned by digest | 80 and 443, the only service that publishes any | the certbot webroot and the certificate volume | `restart: unless-stopped` |
  | `certbot` | `certbot/certbot`, pinned by digest | none | the same two volumes | runs `renew` every 12 h |

  - The `app` environment: `SONGRUSH_DATA_DIR=/data`, `SPRING_WEB_RESOURCES_STATIC_LOCATIONS=file:/app/web/`, `SPRING_WEB_RESOURCES_CACHE_CACHECONTROL_NO_CACHE=true`, and no `SERVER_ADDRESS`.
  - Logging is set per service in Compose (D-078). Every service uses the `awslogs` driver:
    - log group `/songrush`;
    - one stream per service;
    - `awslogs-create-group: true`;
    - retention set once to 14 days (by setup, through the AWS CLI under the instance role).
- **Image.**
  - `deploy/docker/Dockerfile` is multi-stage. It builds the frontend with Node, builds the backend with Gradle, and runs on `eclipse-temurin:17-jre`.
  - All base images are pinned by digest.
  - It runs as a non-root user from a `WORKDIR` that has no `public/`, `static/` or `webapp/` folder (cycle 054, M3).
  - The web files are baked in at `/app/web`, so the frontend and backend always match (review: this solves cycle 054's M2 and M4).
  - The entrypoint is in exec form: `java -Xmx384m -XX:+UseSerialGC -XX:+ExitOnOutOfMemoryError -jar /app/songrush.jar`.
  - The image is public on GHCR (C). The server pulls without a token.
- **Deployment path (A2, SSM).**
  - **`ci.yml`**, on every push and pull request: backend `gradlew test`, frontend `npm ci` + `npm test` + `npm run lint` + `npm run build`, and data `pytest`.
  - **`deploy.yml`**, on a push to `main`:
    1. build the image and push it as `:sha-<short>` and `:main`;
    2. assume the deploy role via OIDC;
    3. send an SSM command to the instance, which runs `/opt/songrush/docker/deploy-remote.sh <sha>`.
  - **`deploy-remote.sh`**, on the server:
    1. `docker compose pull app`, then `up -d app` with that tag;
    2. poll `http://app:8080/health` through the Nginx container, for 120 s at most;
    3. on success, record the tag as `good` and prune images other than `good` and the current one;
    4. on failure, restart the previous `good` tag and exit non-zero, so the workflow fails visibly.
  - **Manual equivalent.** The owner can run `deploy-remote.sh <sha>` over SSH for a manual deploy or a rollback.
- **Monitoring (D-074).** `monitor.yml` runs every 5 minutes.
  - It calls `deploy/smoke.py https://songrush.duckdns.org`.
  - It alerts on Discord only after 2 consecutive failures. The workflow keeps the last result in the Actions cache.
  - It sends one recovery message when the site is healthy again.
  - The Discord webhook URL is a repository secret (`DISCORD_WEBHOOK`), separate from the dev-notification webhook.
- **Server preparation (`deploy/docker/setup-docker.sh`, run once with sudo).** It does what the native `setup.sh` does for the OS, minus Java and Caddy:
  - waits for cloud-init, with apt lock timeouts;
  - creates swap;
  - creates `/opt/songrush/{data,docker}`;
  - runs DuckDNS through `duckdns-setup.sh`;
  - installs Docker Engine and the Compose plugin from Docker's apt repository;
  - installs the compose file and the scripts;
  - obtains the first certificate;
  - installs the reload timer.

  The Compose files and scripts on the server are updated by `deploy-remote.sh`, which fetches them from the same commit through the image (copied out of `/app/deploy`). The repository is the only source.
- **Data until S-006.** The existing upload path for audio stays as it is (cycle 055's `upload.sh`, audio part only) and is not used by CI. After S-006 the audio lives in S3, and the mount and the upload of audio are removed.
- **Owner console steps.** Each is a guide section, marked as needing the owner's account:
  1. Attach an IAM instance role with:
     - `AmazonSSMManagedInstanceCore`;
     - CloudWatch Logs write for `/songrush*`;
     - later, S3 read for S-006.
  2. Create the GitHub OIDC provider and a deploy role that may only send the SSM command to this instance.
  3. Add these to GitHub Secrets and Variables: `AWS_DEPLOY_ROLE_ARN`, `AWS_REGION`, `INSTANCE_ID`, `DISCORD_WEBHOOK`.
  4. Make the GHCR package public after the first push.
- **Migration from the running native install (`deploy/docker/migrate.sh`).**
  1. Run `setup-docker.sh`.
  2. Stop and `mask` the native `songrush` and `caddy` services, so they cannot take 80/443 back after a reboot (review N3).
  3. Run `docker compose up -d`. certbot obtains a new certificate; Caddy's certificate is not reused, and one new issuance is well within Let's Encrypt limits.
  4. Run the smoke check.

  Rollback: `docker compose down`, then `unmask` and start the native services. This is documented. The native kit stays in `deploy/` for one release and is then removed.
- **Disk budget.** About 12 GB native, plus about 1 GB for Docker and two images, and the 5 GB of audio until S-006: about 14 GB on 16 GB. After S-006 it drops to about 9 GB. The prune step keeps it bounded.

## Resulting items
1. **I-011 Image.**
   - The multi-stage Dockerfile, `.dockerignore` and the `compose.yaml` with three services.
   - Static tests in pytest:
     - pinned digests;
     - non-root user;
     - no audio in the image;
     - only Nginx publishes ports;
     - no `SERVER_ADDRESS`;
     - the NO_CACHE env is set;
     - `awslogs` options;
     - `mem_limit`;
     - restart policies.
   - Verifier step (not pytest, review M4): `docker build`, run with a fixture data dir on a free port, `/health` and `smoke.py` against it. The command output goes into the cycle doc.
2. **I-012 Nginx and certbot.**
   - The Nginx config: HTTP to HTTPS redirect, `/.well-known/acme-challenge/` webroot, WebSocket upgrade headers on `/ws`, `X-Forwarded-Proto` and `-For`, and a `Host` passthrough (cycle 054's same-origin rule).
   - The certbot renew loop and the reload timer.
   - Tests:
     - static checks on the config;
     - a verifier step that runs Nginx in front of the app container with a self-signed certificate and checks the 101 handshake through it.
3. **I-013 Server scripts.**
   - `setup-docker.sh`, `deploy-remote.sh` and `migrate.sh`.
   - Stub-PATH behaviour tests as in cycle 055:
     - `sudo docker` usage;
     - the health poll and the rollback to `good`;
     - prune;
     - DuckDNS reuse;
     - the token never appears in argv or output.
4. **I-014 GitHub Actions.**
   - `ci.yml`, `deploy.yml` and `monitor.yml`.
   - Tests:
     - workflow YAML parses;
     - required jobs and permissions are present (`id-token: write` and `packages: write` only where needed);
     - secrets are referenced only through `secrets.*`;
     - pinned action versions;
     - the monitor alerts only after 2 consecutive failures, through a small tested Python helper.
5. **I-015 Guide and owner steps.** `docs/deploy-guide.md` rewritten for the Docker flow and the console steps above, with the native flow kept as a rollback appendix.
6. **I-016 Live migration (owner present, `[?]`).** The owner does the console steps and runs `migrate.sh`. The lead runs the smoke check. Then the first merge to `main` proves the CI/CD path.

**Order.** I-011 to I-015 can be built now, offline; I-016 needs the owner. S-005 (STOMP) and S-006 (S3) follow and reuse this pipeline.

## Review changes (round 1, on the earlier Docker-only draft)
The critic's points that still apply are folded in:
- swap and folder creation in setup (H2);
- pruning and the disk budget (H4);
- the full env list, with no `SERVER_ADDRESS` (M1);
- `sudo docker` (M3);
- the image check as a verifier step (M4);
- `mask` (N3);
- an exec-form entrypoint without `JAVA_TOOL_OPTIONS` (N2).

H1 (keep Caddy on the host) and H3 (Caddy certificate volume) are superseded by D-075 (Nginx). M5 (resumable `docker save` transfer) is superseded by D-073 (GHCR pull).

## Review round 2 (open, not folded in while on hold)
- **Blocker.** A 5-minute Actions monitor on a private repository uses about 8,640 of 2,000 free minutes a month. Resolved by D-081: the repository goes public.
- **High.**
  - **H1. First certificate order.** The native Caddy must stop before certbot runs.
  - **H2. Nginx DNS caching.** Nginx keeps the old app IP after a redeploy. Add a resolver with a variable `proxy_pass`, or reload after each deploy. The health poll must go through Nginx.
  - **H3. WebSocket timeout.** Nginx's default 60 s `proxy_read_timeout` drops idle WebSockets.
  - **H4. awslogs blocking mode.** Without the role, containers fail to start. Use `mode: non-blocking`.
  - **H5. OIDC and SSM least privilege.** Pin `sub` to `main` and allow only a dedicated SSM document.
- **Medium.**
  - **M1. Public image.** `.dockerignore` must exclude `data/`. The image must not contain `catalog.json`.
  - **M2. Log retention.** Set it in the console, not through the AWS CLI on the server.
  - **M3. Releases.** Use per-commit release directories with a `current` link, so Compose and Nginx changes and rollbacks apply together.
  - **M4. Deploy gating.** Gate on CI, add concurrency control, and poll SSM with a long timeout.
  - **M5. First image.** Bootstrap it with `workflow_dispatch`.
- **Nits.**
  - Disk is about 13 GB, then about 8 GB.
  - Add the SSM agent's memory.
  - Run certbot from a host timer.
  - Restart the SSM agent after attaching the role.
  - A deploy restarts games in progress.
