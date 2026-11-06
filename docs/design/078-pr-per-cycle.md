# 078 PR per cycle (D-093)

Owner decision D-093 (2026-11-06): every cycle reaches `dev` through a PR, and merges only after GitHub CI passes on it.

## Questions
1. Why is a passing verifier not enough before `dev`?
2. How can CI gate a merge when the repository has no branch protection?
3. How do cycles merge a PR while merging `dev` into `main` stays with the owner?

## Findings
- **CI ran after the merge.** The skill merged locally and pushed `dev`. `.github/workflows/ci.yml` runs on `push` to `dev` and `main` and on `pull_request`, and cycles opened no PR to `dev`, so the first CI run came after the merge.
- **No step required reading the result.** A search of `.claude/skills`, `.claude/hooks` and `.claude/agents` for `gh run`, `gh pr checks` and `github actions` found nothing. Sessions did read CI by hand; cycle 075 exists because of that.
- **The verifier runs on the developer's PC.** CI runs on a clean Linux runner, so the two catch different failures.
- **PR runs test the merge result.** The `pull_request` event checks out the merge of the PR into its base, for example `refs/remotes/pull/3/merge` in run 42235430597.
- **No branch protection.** `gh api repos/kimdoodoo12/SongRushApp/branches/main/protection` and `.../rulesets` both return 403 `Upgrade to GitHub Pro or make this repository public to enable this feature`.
- **`guard.py` does not check `gh` commands.** It checks `git` commands only.
- **Flaky failures are real.** `TierSocketTest` failed on CI several times, and commit 539d29b passed on push and failed on its PR run (cycle 075).

## Options
| Option | Merge gated by CI | Needs a PR merge command | Actions minutes |
|---|---|---|---|
| Keep the local merge, check CI after the push | No, a failure is found on `dev` | No | Same |
| PR per cycle, merge with `gh pr merge` | Yes | Yes, and the same command merges `dev` into `main` | One extra CI run |
| **PR per cycle, local merge after CI passes** | Yes | No | One extra CI run |

## Decision
Use the third option.

- **Merge:** after CI passes, the lead merges locally and pushes `dev`. GitHub marks the PR merged when its head reaches `dev`. PR 4 confirmed this: its state was `MERGED` after the push.
- **PR merge commands:** the procedure never uses `gh pr merge` or `gh api` to merge a PR.
- **Tested commit:** the lead checks that the local head equals the PR head before merging.
- **Lock:** tests stay locked until CI passes, so a fix for a CI failure cannot edit them.
- **Retry cap:** a CI failure caused by the change counts as a failed attempt, under the same cap of 3.
- **Flaky failures:** a failure in a test the diff did not touch gets one retrigger with an empty commit, and no attempt is counted.
- **CI unavailable:** the PR stays open and unmerged, the item goes to Needs decision, and `/loop` ends.
- **Bookkeeping:** BACKLOG, PROGRESS and record changes go to `dev` in one commit after the merge, so "완료" is logged only after CI has passed.

## Limits
- **Enforcement:** the skill and permission rules protect the `main` merge, not GitHub. The token is the owner's, so it can technically merge any PR. Enforcing this in GitHub needs GitHub Pro or a public repository.
- **Coverage:** `ci.yml` has `backend` and `frontend` jobs only, so `data/` tests do not run on CI. A data-only PR shows green from the verifier's local run alone.
- **Deployment:** unchanged. `ci.yml` and `docker.yml` are still separate, so a failing `main` run does not stop the deploy.
- **Extra runs:** the bookkeeping push to `dev` starts another CI run and Docker build.

## Owner items
These change GitHub Actions or permission settings, which stay with the owner (D-083, D-084).
- Remove `push: dev` from `ci.yml`. CI now runs three times per cycle: on the cycle PR, on the push to `dev`, and on the open `dev` to `main` PR. Removing it leaves two.
- Make `docker.yml` run CI before `build`, so a red `main` cannot deploy.
- Add a `data` job to `ci.yml`, so `data/` tests run on CI.
- Review the gitignored `.claude/settings.local.json`, whose broad `gh` rules can reach a PR merge through `gh api`.
- Fix the allow rules for `git push -u origin cycle/`, which do not match real branch names, so `/loop` may prompt outside auto mode.
