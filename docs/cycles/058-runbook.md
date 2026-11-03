# 058 runbook (I-006)

## Goal
A plain-Korean guide the owner can follow alone, from creating the AWS account to a working public URL with the deploy kit (cycles 054-057). It covers costs, stopping and restarting, and recovery.

## Location
The guide lives at `docs/deploy-guide.md`, not `deploy/README.md` as design 052 said. The locked cycle 055 test requires every file under `deploy/` to be ASCII, so a Korean README cannot live there, and `deploy/` gets no new file. At integration the lead points I-006 and I-007 in BACKLOG at the new path.

The Stop hook maps top-level folders to test areas. The lead adds `"docs": {"data"}` to `EXTRA_AREAS` so that a guide-only change still runs the guide test (review M4).

## Content (in this order, in plain Korean; any jargon gets a one-line explanation)
1. **What you get.** One paragraph, plus the time and cost at a glance (design 053).
2. **Before you start: the open decisions.** The guide follows these recommendations, and each comes with a note on what changes if the owner picks otherwise:
   - **D-068.** The Paid plan with a Budgets alert.
   - **D-066.** A `<ip-with-dashes>.sslip.io` address.
   - **D-067.** No action.
3. **AWS console.**
   - **Account.** Sign up and choose the Paid plan.
   - **Budgets alert.** An alert on actual cost at $1. The guide explains in one line that charges covered by credits may not count toward it. Setting it up is a credit-earning activity.
   - **Region.** `ap-northeast-2` (Seoul).
   - **The instance.** Launching it is the second credit-earning activity.
     - Ubuntu Server 24.04 LTS on `t3.micro`.
     - A new key pair, downloaded as a `.pem` file.
     - 16 GiB of gp3 storage.
     - A security group that allows SSH (22) from "My IP" only, and HTTP (80) and HTTPS (443) from anywhere (`0.0.0.0/0`).
   - **IP address.** Note down the public IPv4.
4. **The address.** For IP `a.b.c.d` the address is `a-b-c-d.sslip.io`. One worked example uses the documentation range `203.0.113.10`.
5. **On this PC, once, in Git Bash.**
   - The `.pem` file goes into `~/.ssh/`. The guide does not use `chmod`, which has no effect in Git Bash (review H2).
   - Use the Git Bash `ssh`, not PowerShell's `ssh`.
   - Build in bash form (review H1). No `.\` paths appear in the guide:
     - `cd backend && ./gradlew.bat build`
     - `cd frontend && npm ci && npm run build`
6. **On the server, once.**
   - Copy the kit with `scp -r -i ~/.ssh/<key.pem> deploy ubuntu@<address>:~`.
   - Connect with `ssh -i ~/.ssh/<key.pem> ubuntu@<address>`. On the first connection, answer `yes` to the host-key question.
   - Run `cd deploy && sudo bash setup.sh <address>`.
   - If it stops with an apt lock message, wait a minute and run it again. It is safe to rerun.
7. **Upload and start.**
   - Run `bash deploy/upload.sh ubuntu@<address> ~/.ssh/<key.pem>`. The first upload is about 5 GB, and an interrupted upload resumes.
   - It ends with `done.`, or with server log lines and a restore command.
   - On the very first deploy there is no known-good jar yet (`songrush.jar.good`), so the restore command cannot work then. Read the log instead.
8. **Check.**
   - Run `python deploy/smoke.py https://<address>`. Four `OK` lines are expected, shown as a `text` block.
   - If it fails, check these in order:
     1. the address matches the current IP;
     2. the security group;
     3. the certificate (`sudo journalctl -u caddy -n 50`);
     4. the game server (`sudo journalctl -u songrush -n 50`).
   - A key error such as `UNPROTECTED PRIVATE KEY FILE` gets a fix using Windows file permissions (`icacls` to remove inherited access), as a `bash` block run in Git Bash.
9. **Updating later.** Rebuild, run `upload.sh` again (only changed songs are sent), then run `smoke.py`.
10. **Stopping, restarting, costs** (review H3, M5).
    - **While stopped.** The disk (EBS) is still billed. An automatically assigned public IPv4 is released and not billed. An Elastic IP, if added, is billed while the instance is stopped.
    - **After starting again.** The IP changes, so:
      1. work out the new address;
      2. update "My IP" in the security group if the home IP changed;
      3. rerun `sudo bash setup.sh <new address>` on the server to get a new certificate;
      4. use the new address with `upload.sh` and `smoke.py`.
    - **Elastic IP.** An Elastic IP keeps the address. It costs the same $0.005/h while the instance runs and is billed while it is stopped.
    - **Deleting everything.** How to remove all of it.
    - **If the Free plan was chosen.** The account closes automatically, and data is deleted 90 days later.

## Formatting rules for the guide (checked by the test)
- Commands appear only in fenced blocks marked `bash`. Example output appears in fenced blocks marked `text`. No other fence languages are used, and no fence is left unmarked.
- Inline code never starts with a command word: `ssh`, `scp`, `sudo`, `bash`, `python`, `cd`, `npm`, `journalctl` or `icacls`.
- Placeholders are written without spaces, for example `<address>` or `<key.pem>`. The guide says once that the angle brackets are replaced too.

## Acceptance criteria (pytest, from `data/`)
`data/tests/test_deploy_guide.py`:
1. `docs/deploy-guide.md` exists, is UTF-8 and contains Hangul.
2. **Fences.**
   - Every fence is marked `bash` or `text`.
   - No inline code span starts with one of the command words above.
   - No `bash` block contains `.\` or `chmod`.
3. **Scripts and arguments**, counted with `shlex` on each `bash` line, after skipping `cd ... &&` prefixes and `sudo`:
   - every `deploy/<name>`, and every bare `setup.sh`, `upload.sh` or `smoke.py` token, names a file that exists in `deploy/`;
   - `setup.sh` is given exactly 1 argument;
   - `upload.sh` is given 1 or 2 arguments, not counting `--dry-run`;
   - `smoke.py` is given exactly 1 argument.
4. **Env-style keys.** Every `[A-Z_]{3,}=` token in a `bash` block is set by `deploy/songrush.service` or is `SONGRUSH_HOST`.
5. **Required strings.** The guide mentions each of these at least once:
   - `ap-northeast-2`
   - `t3.micro`
   - `Ubuntu Server 24.04`
   - `16 GiB`
   - `gp3`
   - `22`, `80` and `443`, each next to its protocol name (`SSH`, `HTTP`, `HTTPS`) on the same line
   - `sslip.io`
   - `Budgets`
   - `Paid`
   - `scp`
   - `journalctl -u songrush`
   - `smoke.py`
   - `songrush.jar.good`
   - `setup.sh` inside the restart section, which is found by a heading containing the word for restart used in the guide (`다시 켰을 때`)
   - `D-066`, `D-067` and `D-068`
6. **No secrets.**
   - No `AKIA[0-9A-Z]{16}`.
   - No IPv4 outside `192.0.2.0/24`, `198.51.100.0/24`, `203.0.113.0/24`, `127.0.0.1` and `0.0.0.0`.
   - No `name@domain.tld` email, except where the local part is `ubuntu` or the domain contains `<`/`>` or ends in `sslip.io`.
7. The full `.venv\Scripts\python.exe -m pytest` passes.

## Review changes (spec round 1)
- **High.**
  - Git Bash build commands are used, because `.\gradlew.bat` loses its backslash in bash.
  - The `chmod` step is gone: it is a no-op on NTFS in Git Bash, and an `icacls` fix is offered instead.
  - A restart section was added: the IP and the address change, so `setup.sh` is rerun.
- **Medium.**
  - Email and IP rules allow `ubuntu@` and `0.0.0.0`.
  - The fence and argument rules are made mechanical.
  - The Stop hook maps `docs` to data.
  - The IPv4 and EBS cost wording is corrected.
- **Nit.**
  - The port checks are tied to their protocol names.
  - The `scp` step is pinned.

## Test list
- `data/tests/test_deploy_guide.py`

## File ownership
- **Test writer:** `data/tests/test_deploy_guide.py`.
- **Implementer:** `docs/deploy-guide.md`.
- **Lead:** the hook mapping and the BACKLOG paths.

## Out of scope
- Running anything on AWS (I-007).
- Changing the deploy scripts. `upload.sh`'s `.\gradlew.bat` hint is a follow-up.

## Result
- **Commits.**
  - Tests: `c3512ac` (locked).
  - Guide: `a5b190c` (attempt 1) and `2ff2baa` (attempt 2, after the review High).
  - Stop hook: `9a33364`, which maps `docs/` to the data tests.
- **Red phase (verifier).** The 6 guide tests failed because the guide did not exist; the 33 parser self-tests passed, and each rule was proven to bite.
- **Verifier, attempt 1.**
  - 39 guide tests passed; the full suite passed with 790.
  - The guide's local commands ran as written in Git Bash: `./gradlew.bat build`, then `npm ci && npm run build`, then the `upload.sh --dry-run` batch.
  - The `icacls ... //inheritance:r //grant:r "${USERNAME}:R"` fix left only the user's read access on a dummy key.
- **Verifier, attempt 2.** The same tests pass. Each fenced block in the restart section is local-only or server-only.

## Code review (critic)
- **Attempt 1, High.** The restart section had one block mixing a local `ssh` with server-side `setup.sh` and `exit` lines. Pasted as one, it either feeds the host-key prompt or runs on the PC and closes Git Bash. It was split in attempt 2.
- **Mediums, fixed in attempt 2.**
  - Wait for the certificate before the smoke check.
  - A `Permission denied (publickey)` help entry.
  - Do not reuse `setup.sh`'s key-less upload hint.
  - A `pwd` check.
- **Confirmed.**
  - Step order and paths: `setup.sh` and `upload.sh` locate their files themselves.
  - Costs match design 053.
  - No secrets are pasted anywhere.
