# 052 plan: M-4 deploy

## Milestone (verbatim)
> **M-4** Deploy: a public URL friends can use, with the credit line (D-023). Hosting (D-010) needs a user decision first. Revisit D-025 and D-057 (audio fingerprints) before planning.

Hosting is settled by D-065: AWS EC2 free tier (t3.micro class, 1 GB RAM, up to 30 GB EBS).

## What exists (explorer survey on `dev` `efaf2f7`)
- **Backend config.**
  - `application.properties` sets only the app name and the whitelabel flag, so the port is the default 8080.
  - `songrush.data-dir` defaults to `../data` (`SongrushProperties.java:9-10`). The audio cache is `<data-dir>/audio-cache` (`AudioController.java:42`).
  - There is no profile, no forward-headers setting and no JVM options.
- **Audio.** `GET /audio/{token}` streams with `transferTo`, returns 200 only, has no Range and sends `no-store` (`AudioController.java:45-83`). Tokens are 128-bit, live 148 s and are reusable (`AudioTokens.java`, `Room.java:32-55`).
- **Frontend.**
  - It reaches the backend same-origin: `ws(s)://<host>/ws` (`App.jsx:18-19`) and relative audio URLs. The share link is `/r/<code>` (`LobbyScreen.jsx:44`), parsed by `App.jsx:24`, so any host needs an SPA fallback for `/r/*`.
  - The backend serves no static files.
  - The WebSocket handler relies on Spring's default same-origin check (`WebSocketConfig.java:22`), so a proxy must keep `Host`/`Origin`.
- **Credit line (D-023).** Already rendered as `<footer className="credit">` (`App.jsx:10, 187`) and tested (`App.flow.test.jsx:132`).
- **Limits.**
  - 200 lobbies (`GameSocketHandler.java:58`), 8 players per room, 4096-byte frames, a 200-character chat, and the per-player bucket of 5 at 2/s.
  - There is **no per-IP limit** on connections or lobby creation, and none on `/audio`.
- **Data.**
  - `catalog.json` is 5.5 MB and is read into memory at startup.
  - `audio-cache` is 5.1 GB: 1,500 `.ogg` files plus `index.json`.
  - Neither is in git, so the server needs an upload.
- **Deploy files.** None (no unit, proxy config or scripts).

## Items (each one cycle, test-checkable)
1. **B-009 backend: serve the built frontend.**
   - `songrush.web-dir` (unset by default) points at a built `frontend/dist`. When it is set:
     - `GET /` and `GET /r/<code>` return its `index.html`;
     - `GET /assets/<file>` returns that file with its content type;
     - unknown non-API paths return 404 JSON as today;
     - `/ws`, `/audio/*` and `/health` are unchanged;
     - a path that escapes the directory (`..`, encoded or not) is 404.
   - When it is unset, nothing changes.
   - Acceptance (JUnit, temp dir as web-dir): each of these responses, including the path-escape cases, and the unset case keeps the current 404 for `/`.
2. **B-010 backend: per-IP limits for a public URL.**
   - Limits per client IP:
     - at most 12 open WebSocket connections; the next handshake is refused, or closed with 1008;
     - at most 10 `create` per minute; the next gets `error{code:"rate-limited"}`.
   - The client IP is the socket's remote address, or the first `X-Forwarded-For` hop only when `songrush.trust-proxy=true` (the deploy proxy sets it).
   - Acceptance (JUnit with the existing ws fixtures):
     - the 13th connection from one IP is refused while one from another IP works;
     - the 11th create within a minute is refused and is allowed again after the window (with the mutable clock);
     - `X-Forwarded-For` is ignored unless trust is on.
3. **I-004 deploy kit (no account needed).** A `deploy/` folder:
   - a `Caddyfile` template: automatic HTTPS for `{$SONGRUSH_HOST}`, `reverse_proxy` to `127.0.0.1:8080` that keeps Host, WebSocket upgrades as Caddy's default;
   - a `songrush.service` systemd unit: Java 17, `-Xmx384m -XX:+UseSerialGC`, `SONGRUSH_DATA_DIR`, `SONGRUSH_WEB_DIR`, `SONGRUSH_TRUST_PROXY=true`, restart on failure, server bound to `127.0.0.1`;
   - `setup.sh` for Ubuntu 24.04: installs Java 17 and Caddy, adds 1 GB swap, creates the user and folders, installs the unit;
   - `upload.sh`: a resumable `rsync` of the jar, `frontend/dist`, `data/catalog.json` and `data/audio-cache/` to the host;
   - the backend reads `server.address` from `SONGRUSH_BIND` (default unchanged).
   - Acceptance (pytest in `data/tests/`, offline):
     - every script passes `bash -n` (when bash exists; otherwise skipped with a reason);
     - the unit names exactly the env keys the backend binds (checked against `SongrushProperties` and `application.properties`);
     - the scripts are ASCII and LF;
     - `upload.sh --dry-run` prints `rsync -n` commands and never runs `ssh`, or `rsync` without `-n`.
4. **I-005 owner runbook (docs).** `deploy/README.md` in plain Korean, step by step:
   - the AWS console: account, region `ap-northeast-2`, launch an Ubuntu 24.04 t3.micro with 30 GB gp3, a key pair, and a security group with 22 from the owner's IP only, plus 80 and 443;
   - then DNS, `setup.sh`, `upload.sh`, starting the service and checking `/health`;
   - a free-tier cost section: what stays free, the public IPv4 charge, data transfer out at about 5 MB per song per player, and how to stop the instance.
   - Acceptance (pytest): every command the README tells the owner to run names a file that exists in `deploy/`, and every env key it mentions is one the unit sets.
5. **I-006 deploy and smoke test (owner's account).**
   - The owner runs the runbook. An agent then runs `deploy/smoke.py <url>`: `/health` ok, `/` contains the credit line, `/r/ABCDE` serves the app, and a WebSocket `create` gets `joined`.
   - `smoke.py` itself is offline-tested against a local backend in item 3's cycle (or this one).
   - **Marked `[?]`**: it needs the owner's AWS account and domain.

## Needs decision (recorded, not asked: owner asleep, sleep mode)
- **D-066 domain for HTTPS.** HTTPS needs a hostname. Options:
  - **(a)** A free hostname that maps to the IP, such as `<ip>.sslip.io`, with Caddy's automatic certificate. It costs nothing and needs no account, but the name is ugly and changes with the IP. **Recommended to start.**
  - **(b)** A bought domain (about 15,000 KRW a year) pointed at an Elastic IP.
  - **(c)** DuckDNS, which is free but needs an account.
- **D-067 D-025/D-057 revisit.** The milestone asks to revisit these before going public. Options:
  - **(a)** Keep the risk accepted for friends-only play. Nothing to build.
  - **(b)** Encrypt each audio file per round and send the key at `startAt`. This means a backend and frontend change of about one cycle, and it hides the song until it plays.
  - **Recommendation:** (a). The site is shared only with friends by link, and (b) can be added later as an item.
- Item 5 and the actual EC2 work are not run without the owner.

## Not planned (outside the milestone)
- Docker, CI/CD, monitoring, custom error pages, a CDN.
