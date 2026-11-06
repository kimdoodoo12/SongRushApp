# 078 PR per cycle (D-093)

Owner decision D-093 (2026-11-06): every cycle reaches `dev` through a PR, and merges only after GitHub CI passes on it.

## Questions
1. Why is a passing verifier not enough before `dev`?
2. How can CI gate a merge when the repository has no branch protection?
3. How do agents merge a PR without gaining the power to merge `dev` into `main`?

## Findings
- **CI ran after the merge.** The skill merged locally and pushed `dev`. `.github/workflows/ci.yml` runs on `push` to `dev` and `main` and on `pull_request`, and cycles opened no PR, so the first CI run came after the merge.
- **Nothing read the result.** A search of `.claude/skills`, `.claude/hooks` and `.claude/agents` for `gh run`, `gh pr checks` and `github actions` found nothing.
- **The verifier runs on the developer's PC.** CI runs on a clean Linux runner, so the two catch different failures.
- **No branch protection.** `gh api repos/kimdoodoo12/SongRushApp/branches/main/protection` and `.../rulesets` both return 403 `Upgrade to GitHub Pro or make this repository public to enable this feature`.
- **`guard.py` does not cover `gh pr merge`.** It checks `git` commands only, and `gh pr merge` is not in the permission allowlist.

## Options
| Option | Merge gated by CI | New power for agents | Actions minutes |
|---|---|---|---|
| Keep the local merge, check CI after the push | No, a failure is found on `dev` | None | Same |
| PR per cycle, merge with `gh pr merge` | Yes | Can merge any PR, including `dev` to `main` | One extra CI run |
| **PR per cycle, local merge after CI passes** | Yes | None | One extra CI run |

## Decision
Use the third option.

- **Merge:** after CI passes, the lead merges locally and pushes `dev`. GitHub marks the PR merged when its head reaches `dev`.
- **`gh pr merge`:** agents never run it, and it stays out of the allowlist.
- **Lock:** tests stay locked until CI passes, so a fix for a CI failure cannot edit them.
- **Retry cap:** a CI failure counts as a failed attempt, under the same cap of 3.
- **CI unavailable:** if CI cannot finish, the PR stays open and unmerged, and the item goes to Needs decision.
- **Allowlist:** `gh run list` and `gh run view` are added, so a failure can be read during `/loop`. `gh run rerun` is left out, because rerunning a `main` run would deploy again.

## Limits
- **Enforcement:** the skill enforces the wait, not GitHub. Enforcing it in GitHub needs GitHub Pro or a public repository.
- **Deployment:** unchanged. `ci.yml` and `docker.yml` are still separate, so a failing `main` run does not stop the deploy (see the owner item below).
- **Bookkeeping:** BACKLOG and PROGRESS updates after the merge still go straight to `dev`. They are documents, and they say "완료" only once the merge has happened.

## Owner items
These change GitHub Actions, which the owner builds (D-083, D-084), so this cycle leaves them alone.
- Remove `push: dev` from `ci.yml`. Otherwise CI runs twice per cycle, once on the PR and once on the push to `dev`.
- Make `docker.yml` run CI before `build`, so a red `main` cannot deploy.
