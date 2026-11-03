# 058 runbook (I-006)

## Goal
A plain-Korean guide that the owner can follow alone. It goes from creating the AWS account to a working public URL using the deploy kit (cycles 054-057), and it covers costs and recovery.

## Location
The guide is `docs/deploy-guide.md`, not `deploy/README.md` as design 052 said. The locked cycle 055 test requires every file under `deploy/` to be ASCII, so a Korean README cannot live there. `deploy/` gets no new file.

## Content (in this order, plain Korean, no jargon without a one-line explanation)
1. **What you get.** A one-paragraph summary, and the time and cost at a glance from design 053.
2. **Before you start.** The decisions still open:
   - D-068 sign-up plan: the guide follows the recommended Paid plan, with a Budgets alert.
   - D-066 hostname: the guide follows the recommended `<ip with dashes>.sslip.io`.
   - D-067 preview risk: no action needed.
   Each comes with a note on what changes if the owner picks otherwise.
3. **AWS console steps.**
   - Account sign-up and choosing the Paid plan.
   - A Budgets alert at $1 actual spend. This is one of the two credit-earning activities, and the guide says so.
   - Region `ap-northeast-2` (Seoul).
   - Launching an EC2 instance, also the second credit activity:
     - Ubuntu Server 24.04 LTS;
     - `t3.micro`;
     - a new key pair, downloaded as `.pem`;
     - 16 GiB gp3;
     - a security group allowing SSH from "My IP" only, plus HTTP and HTTPS from anywhere.
   - Noting the public IPv4 address.
   - A warning that stopping and starting changes the IP. The fix (Elastic IP) is described as optional, with its cost.
4. **The address.** `<a-b-c-d>.sslip.io` for IP `a.b.c.d`, with one worked example.
5. **On this PC, once.** Git Bash.
   - Build the backend jar and the frontend, with the exact commands from CLAUDE.md, in fenced `bash` blocks.
   - Put the `.pem` file somewhere and protect it with `chmod 600`.
6. **On the server, once.** Copy `deploy/` to the server, connect over ssh, and run `sudo bash setup.sh <hostname>`.
   - If it stops with an apt lock message, wait a minute and run it again. The script is safe to rerun.
7. **Upload and start.** `bash deploy/upload.sh <user@host> <key>`, about 5 GB the first time.
   - It resumes if interrupted.
   - It prints `done.` or the journal lines plus a restore command.
   - On the first deploy there is no known-good jar yet (follow-up from 057).
8. **Check.** `python deploy/smoke.py https://<hostname>`. It should print four `OK` lines. If it fails, check this list in order: DNS or IP, the security group, the Caddy certificate (`journalctl -u caddy`), then the service (`journalctl -u songrush`).
9. **Updating later.** Rebuild, run `upload.sh` again (only changed songs go up), then run the smoke check.
10. **Costs and stopping.**
    - The figures from design 053.
    - How to stop the instance. EBS and the public IP still cost a little while it is stopped.
    - How to delete everything.
    - The Free plan closure and deletion rule, for an owner who chose Free.

## Acceptance criteria (pytest, from `data/`)
`data/tests/test_deploy_guide.py`:
1. `docs/deploy-guide.md` exists, is UTF-8 and contains Hangul.
2. **Fenced blocks.** Every command the guide asks the owner to type is inside a fenced block marked `bash`. Every fenced block in the guide is marked `bash`, and none is left unmarked.
3. **Every script it runs exists.**
   - Each `deploy/<name>` path, and each bare `setup.sh`, `upload.sh`, `smoke.py` or `songrush.service` mentioned in a `bash` block, names a file that exists in `deploy/`.
   - Each `bash deploy/X.sh` or `python deploy/smoke.py` invocation passes the number of positional arguments its usage line names. Placeholders like `<hostname>` count as arguments.
4. **Every env-style key it sets is known.** Any `[A-Z_]{3,}=` token inside a `bash` block is either set by `deploy/songrush.service` or is `SONGRUSH_HOST`.
5. **Required facts.** The guide mentions each of these at least once:
   - `ap-northeast-2`, `t3.micro`, `Ubuntu Server 24.04`, `16`, `gp3`;
   - the port numbers `22`, `80` and `443`;
   - `sslip.io`;
   - `Budgets`, `Paid`;
   - `journalctl -u songrush`, `smoke.py`, `songrush.jar.good`;
   - the D-066, D-067 and D-068 ids.
6. **No secrets.**
   - No string that looks like an AWS access key (`AKIA[0-9A-Z]{16}`).
   - No real IPv4 other than the documentation ranges `192.0.2.x`, `198.51.100.x` and `203.0.113.x`, plus `127.0.0.1`.
   - No email address.
7. The full `.venv\Scripts\python.exe -m pytest` passes.

## Test list
- `data/tests/test_deploy_guide.py`

## File ownership
- **Test writer:** `data/tests/test_deploy_guide.py`.
- **Implementer (writer of the guide):** `docs/deploy-guide.md`.
- **Lead:** at integration, adds `docs/deploy-guide.md` to the PROGRESS plan and to I-007.

## Out of scope
- Running anything on AWS (I-007).
- Changing the deploy scripts.
