# 066 home-ip-guide (I-010)

## Goal
The owner's laptop gets a different public IP on another Wi-Fi network, so SSH, which is limited to "내 IP", stops working. The guide explains the one-minute fix and that players are not affected.

## Behaviour
`docs/deploy-guide.md` gains a section whose heading contains `다른 와이파이에서 서버 접속이 안 될 때`. It is placed in the troubleshooting chapter. In plain Korean, it covers:
- **Why it happens.**
  - "내 IP" is the internet line's public IP, not the laptop's. Another Wi-Fi network, a hotspot, or occasionally the home line itself changes it.
  - Only SSH and uploads are affected. Friends playing are unaffected, because ports 80 and 443 are open to everyone.
- **The symptom.** The ssh or upload command hangs and then ends with a timeout.
- **The fix, in numbered steps.**
  1. In the AWS console, open EC2, then 보안 그룹, then the server's group, then 인바운드 규칙 편집.
  2. On the SSH (22) row, set the source to "내 IP" again.
  3. Save. No restart is needed.
- **Not recommended:** opening SSH to everyone (`0.0.0.0/0`). It works with the key alone, but invites constant automated attempts.

## Acceptance criteria (pytest, from `data/`)
1. `data/tests/test_guide_home_ip.py`:
   - the heading exists;
   - its section mentions `보안 그룹`, `인바운드`, `내 IP`, `22` and `80`;
   - the section contains a numbered list of at least 3 steps.
2. The locked cycle 058 and 062 guide tests and the full data suite pass unchanged: `.venv\Scripts\python.exe -m pytest`.

## Constraint for this cycle
A live upload is running. Edit only the guide and the new test. Do not touch `deploy/`, `frontend/` or `backend/`, and do not run any build.

## Test list
- `data/tests/test_guide_home_ip.py`

## File ownership
- **Test writer:** the test file.
- **Implementer:** `docs/deploy-guide.md`.

## Result
- Test commit `9afb467` (locked). Guide commit `4cb3224` (attempt 1). Only docs were touched during the live upload.
- Red phase: 3 guide tests failed because the heading was missing; 16 parser self-tests passed. The test writer ran this check; no separate verifier run.
- Verifier: the data suite passed (`844 passed, 1 skipped`, the platform skip). `git diff 9afb467 HEAD --stat` lists only `docs/deploy-guide.md` (+16). The verifier printed the new section verbatim (guide lines 402-416).
- Review: no critic was spawned for this 16-line docs-only cycle. The lead read the verbatim section against the spec. This deviates from the usual cycle and is recorded here.
