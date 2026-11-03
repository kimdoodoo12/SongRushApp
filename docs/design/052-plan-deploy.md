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
  - `catalog.json` is tracked in git; `audio-cache` is not, so the server needs an upload of the audio (and the catalog with it).
- **Deploy files.** None (no unit, proxy config or scripts).

## Items (revised after the plan review; each one cycle, test-checkable)
1. **S-003 design: current AWS free terms** (review M7).
   - D-065 assumed "12 months free". New accounts since 2025-07-15 may be on a credit-based Free plan instead.
   - Check the current AWS pages, within the External source rules: the eligible instance types, the duration or credits, public IPv4, data transfer out and EBS.
   - Write `docs/design/NNN-aws-free-terms.md`.
   - If the terms change the cost picture, add a Needs decision for the owner. Otherwise record the confirmed terms for the runbook.
2. **B-009 backend: behind a TLS proxy, and serving the built frontend** (review B1, M1, M3).
   - `server.forward-headers-strategy=native` goes in `application.properties`.
     - A WebSocket handshake to `/ws` with `Host: play.test`, `Origin: https://play.test` and `X-Forwarded-Proto: https` gets 101.
     - The same handshake with `Origin: https://evil.test` gets 403.
     - `X-Forwarded-For` from a loopback peer becomes the session's remote address.
   - Static files use Boot's standard resource handling only. `spring.web.resources.static-locations` is set from the environment, for example `SPRING_WEB_RESOURCES_STATIC_LOCATIONS=file:/opt/songrush/web/`. One mapping forwards `/r/{code}` and `/r/{code}/` to `/index.html`.
   - Acceptance (JUnit; a temp dir as the static location):
     - `/` serves `index.html`, and `/assets/x.js` is served with a JavaScript content type;
     - `/r/ABCDE` serves `index.html`;
     - `/../catalog.json` and `/%2e%2e/catalog.json` are not served;
     - `/ws`, `/audio/*` and `/health` behave as before;
     - with no static location set, `/` keeps today's 404 JSON;
     - startup fails with a clear message when the static location is inside or equal to `songrush.data-dir`, so `catalog.json` can never be served.
3. **I-004 deploy kit files** (review H1, H3, M1, M6, nit on the jar).
   - **Files** in `deploy/`:
     - a `Caddyfile`: `{$SONGRUSH_HOST}` with automatic HTTPS and `reverse_proxy 127.0.0.1:8080`;
     - `songrush.service`: `java -Xmx384m -XX:+UseSerialGC -XX:+ExitOnOutOfMemoryError -jar`, and `SERVER_ADDRESS=127.0.0.1`, `SONGRUSH_DATA_DIR`, `SPRING_WEB_RESOURCES_STATIC_LOCATIONS`, restarting on failure;
     - `setup.sh` (Ubuntu 24.04): Java 17, Caddy, 1 GB swap, the service user and folders;
     - `upload.sh`: uses only OpenSSH `ssh`/`sftp`, because the owner's PC has no rsync. It resumes by skipping files whose remote size matches (`sftp` with `reput`/`put -a`). It uploads the boot jar by its exact name (not `-plain.jar`), `frontend/dist`, `data/catalog.json` and `data/audio-cache/`. It checks that `ssh` and `sftp` exist and exits with a clear message if not.
   - **Tests:** pytest in `data/tests/test_deploy_kit.py`, offline.
     - Every `.sh` passes `bash -n`, skipped with a reason if bash is missing.
     - The files are ASCII with LF line endings.
     - Every `SONGRUSH_*` key in the unit is a field of `SongrushProperties` (parsed from the Java source). Every other key is a Spring standard key on an allow-list (`SERVER_ADDRESS`, `SPRING_WEB_RESOURCES_STATIC_LOCATIONS`).
     - `upload.sh --dry-run` prints the sftp batch and runs no `ssh`/`sftp`. This is checked with a PATH of stub executables that record calls.
   - **Lead, at integration:** add `"deploy": {"data"}` to `EXTRA_AREAS` in `.claude/hooks/check_on_stop.py`, so a change under `deploy/` runs the data tests.
4. **I-005 smoke check script** (review H2, M4).
   - `deploy/smoke.py <base-url>`, Python stdlib only, with a minimal hand-written WebSocket client (handshake plus masked text frames). It checks four things:
     - `/health` returns `{"ok":true}`;
     - `/` links a module script, and that bundle contains the D-023 credit line (UTF-8);
     - `/r/ABCDE` returns the same `index.html`;
     - a `create` over `ws(s)://host/ws` with an `Origin` of the base URL gets `joined`, then a `leave` is sent.
   - It exits 0 only if all four pass, printing one line per check.
   - Acceptance (pytest, loopback only, as the conftest guard allows): a stdlib stub HTTP+WebSocket server on 127.0.0.1 gives exit 0; a stub missing the credit line, or answering `error`, gives exit 1 naming the failed check.
5. **I-006 owner runbook** (review M5, M7). `deploy/README.md` in plain Korean:
   - AWS console steps: region `ap-northeast-2`; an Ubuntu 24.04 instance of the type S-003 confirms; 30 GB gp3; a key pair; a security group allowing 22 from the owner's IP, plus 80 and 443;
   - the hostname per D-066, then `setup.sh`, `upload.sh`, `systemctl start songrush` and `smoke.py`;
   - a cost section from S-003.
   - Acceptance (pytest):
     - the commands to run appear only inside fenced `bash` blocks;
     - every `deploy/<file>` named in them exists;
     - every `[A-Z_]{3,}=` key in them is set by the unit or the Caddyfile.
6. **I-007 deploy and smoke (owner's account), `[?]`.**
   - The owner follows the runbook. An agent then runs `smoke.py` against the public URL and records the service memory from `systemctl status` (review M6).
   - It needs the owner's AWS account and D-066.

**Dropped from M-4 (review M2):** per-IP limits. The global caps are enough for friends-only play (200 lobbies, 8 per room, frame and chat limits); per-IP caps risk false positives behind carrier NAT. It is kept as a follow-up.

## Needs decision (recorded, not asked: owner asleep, sleep mode)
- **D-066 domain for HTTPS.** HTTPS needs a hostname. Options:
  - **(a)** A free hostname that maps to the IP, such as `<ip>.sslip.io`, with Caddy's automatic certificate. It costs nothing and needs no account, but the name is ugly and changes with the IP. **Recommended to start.**
  - **(b)** A bought domain (about 15,000 KRW a year) pointed at an Elastic IP.
  - **(c)** DuckDNS, which is free but needs an account.
- **D-067 D-025/D-057 revisit.** The milestone asks to revisit these before going public. Options:
  - **(a)** Keep the risk accepted for friends-only play. Nothing to build.
  - **(b)** Encrypt each audio file per round and send the key at `startAt`. This means a backend and frontend change of about one cycle, and it hides the song until it plays.
  - **Recommendation:** (a). The threat is a friend running a modified client, not strangers: the hostname is not secret, since certificates appear in public CT logs. (b) can be added later as an item.
- Item 5 and the actual EC2 work are not run without the owner.

## Not planned (outside the milestone)
- Docker, CI/CD, monitoring, custom error pages, a CDN.

## Review changes (plan round 1)
- **Blocker.** Behind Caddy's TLS the WebSocket same-origin check would refuse every handshake. B-009 sets `server.forward-headers-strategy=native` and tests the proxied handshake.
- **High.**
  - Upload uses OpenSSH `sftp`, since rsync is absent on the owner's PC.
  - The smoke check follows the bundle for the credit line, because `index.html` has none.
  - Deploy-kit tests run with the data area, through a hook mapping.
- **Medium.**
  - No backend change for the bind address (relaxed binding).
  - Per-IP limits dropped.
  - Static files use Boot's standard handling.
  - The kit is split from the smoke script.
  - README criteria are made mechanical.
  - OOM exits the JVM.
  - AWS terms are re-checked in S-003.
- **Nits.** `catalog.json` is tracked; the jar is named exactly; the D-067 reasoning is corrected.
