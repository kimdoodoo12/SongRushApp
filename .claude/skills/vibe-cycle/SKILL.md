---
name: vibe-cycle
description: Run one autonomous development cycle for this repo. It takes the next Ready item from BACKLOG.md, plans it briefly, delegates the implementation with its tests, has the verifier run the tests and review the diff, merges to dev, and logs to PROGRESS.md (D-134). The strict critic review, full suites and CI run only when the owner asks for a PR to main. For `S-` design items it investigates, writes a design doc, and appends the resulting items to Ready. When Ready is empty it plans the next Roadmap milestone into Ready. Use it for "/vibe-cycle" or "/loop /vibe-cycle". Never block waiting for the user.
---

# vibe-cycle

You are the **lead**. You specify, delegate, judge evidence, and integrate. Do not write product code or tests yourself. Delegate them to subagents, and include all context in each spawn prompt, because subagents do not see this conversation.

**Never block on the user (D-028).**
- If the user is in the conversation, ask a needed decision at once. When the answer arrives, record it as a `D-NNN` under BACKLOG "Decided".
- Until the answer arrives, and always when the user is absent (for example in `/loop`), the question goes to `PROGRESS.md` under "Needs decision" and the item gets `[?]`. Then continue with the next item, or end the cycle.
- Items that need the user's own devices or accounts (for example a real-iPhone check) are never run by agents. Mark them `[?]` and list them under Needs decision.

## 0. Preflight
1. Run `git status`. If the tree is dirty and the cause is not obvious, stop the cycle and log NEEDS DECISION. Never discard work.
2. Run `git switch dev`, then `git pull --ff-only` if a remote exists.
3. Read `.claude/state/blocked.log` if it exists. It is the append-only machine trace of retry-cap hits: **never edit or delete it.** Each entry starts with `## BLOCKED-<id>`. For every id that does not yet appear in PROGRESS.md, add a PROGRESS entry that quotes the id.
4. Pick the first `[ ]` item under **Ready** in `BACKLOG.md`.
   - Skip items marked **Deferred** in their text.
   - If the id starts with `S-`, it is a design item: do step 5, then follow **Design cycle**.
   - If Ready has no `[ ]` item, follow **Planning cycle** for the first `[ ]` milestone under **Roadmap**.
   - If Roadmap has no `[ ]` milestone either, log "queue empty" in PROGRESS.md and end. If you are inside `/loop`, stop the loop.
5. Choose `NNN`, the next number after the highest one across `docs/cycles/` and `docs/design/`. Run `git switch -c cycle/NNN-<slug>` and mark the item `[~]`.

## 1. Plan
Write `docs/cycles/NNN-<slug>.md` with **Goal** (one sentence), **Plan** (what changes, where, `path:line`), **Acceptance criteria** (numbered, each checkable by a test or a command), and **Out of scope**. Keep it short. Spawn `explorer` first only if the item needs exploration. No spec review (D-134).

## 2. Implement
Spawn `implementer` with the plan, the owned files, and the area's test command. It writes or updates the tests together with the code (no separate test writer, no lock; D-134). Retry cap: 3 attempts. Commit as `feat(NNN): ...` (and `test(NNN): ...` if it helps to separate them).

## 3. Review: `verifier`
Spawn `verifier` (never the implementer; the writer never verifies) to:
- Run the test command of each touched area from CLAUDE.md (and lint or build where the area has one), with output verbatim.
- Read the diff (`git diff dev...HEAD`) and check every acceptance criterion: confirmed, refuted, or unverifiable.
- Flag any obvious correctness bug, answer leak or fairness break it sees in the diff (CLAUDE.md Multiplayer rules). This is a light review, not the release review.

A refuted criterion, a failing test or a flagged leak sends the cycle back to step 2 (attempt counter +1). Anything else worth noting goes under **Follow-ups** in BACKLOG.md.

Wait in-turn while the verifier runs Gradle (the Stop hook runs its own `gradlew test` when a turn ends; two Gradle runs collide).

## 4. Merge to dev
No PR and no GitHub CI for a cycle (D-134).
1. Add a **Result** section to the cycle doc (commits, the verifier's command output, criteria verdicts).
2. `git switch dev`, `git pull --ff-only`, `git merge --no-ff cycle/NNN-<slug> -m "merge cycle NNN: <title>"`, `git push origin dev`.
3. Mark the item `[x]` in BACKLOG.md and update PROGRESS.md (rules below), then commit `docs(NNN): record the cycle` on `dev` and push.

PROGRESS.md is **the owner's progress report** (user, 2026-11-02):
- **Append** one entry at the **end** of `## Log`: `### <date> · <plain Korean title> — 완료|막힘|결정 필요`, then 2-4 plain Korean sentences on what now exists in game terms and why it matters. End with `기록: docs/cycles/NNN-slug.md`.
- **Rewrite `## 다음 진행 예정` every time** (the last section): the next 3-5 items in plain words. Old plans are not kept.
- No commit SHAs, severity labels, agent names, attempt counts, test counts or file paths in PROGRESS.md.
- Keep `## Needs decision` and `## Log` headings in English verbatim (`notify_discord.py` matches them).

## 5. Release check (only when the owner asks for a PR to main)
This is where the strict checks live (D-134). Run it only when the owner asks to open (or update) the PR from `dev` to `main`.
1. Spawn `critic` with `git diff origin/main...dev`, the cycle docs merged since the last release, and the CLAUDE.md rules: "Adversarially review everything that will deploy: correctness bugs, answer leaks before a round ends, fairness (one client hearing or seeing something earlier), server authority, scope against each cycle's acceptance criteria. Rate Blocker/High/Medium/Nit with `path:line`."
2. Spawn `verifier` to run the full test suites of every area (`backend` with `--rerun-tasks`, `frontend` test + lint + build, `data` if it changed) with output verbatim.
3. Browser check by the lead for user-visible changes, against a local server with the production settings (record what was seen in the release note).
4. Blocker or High: fix them first (a normal cycle per fix), then repeat this section. Medium and Nit go to Follow-ups.
5. Push `dev`, then open or update the PR: `gh pr create --base main --head dev` (or `gh pr edit`), with a plain Korean body: what changes on the site when it is merged, and the checks that ran. GitHub CI runs on that PR; wait for it with `gh pr checks <n> --watch`. **Never merge it**: merging to `main` deploys and is the owner's alone.

## 6. Retro
If the same kind of failure happened twice in this cycle, append one line to "Pitfalls learned" in CLAUDE.md.

Then update section "기록" of `docs/vibe-coding-system.md`. It is the user's portfolio record, so write facts only and cite a commit SHA or a PROGRESS entry for each one.
- Keep these counts current: total cycles, cycles that passed on the first attempt, BLOCKED cycles.
- Add any case where a hook blocked something, and any bug the verifier or a release check caught.
- If hooks, agents, or this skill changed in a way that alters how the system works, describe the change and why. Routine permission allowlist edits are not recorded there (user, 2026-11-03).

Commit the record changes with the section 4 commit (`docs(NNN): record the cycle`) and push `dev`.

## Design cycle (`S-` items)
A design item answers open questions and turns the answers into implementation items. Its output is a design doc and new backlog items, not product code.

1. **Investigate.** Spawn `explorer` with the item's questions, the relevant `docs/design/` files (and `docs/research/` once it exists), and the CLAUDE.md External source rules quoted verbatim. Live requests are allowed only within those rules. When a later test needs a real response, save it under `data/tests/fixtures/` (or the area's own test fixtures folder; metadata JSON only, never media files).
2. **Write** `docs/design/NNN-<slug>.md` with: **Questions**, **Findings** (each with evidence: URL and status, command output, or `path:line`), **Options** (at least two per real choice, with trade-offs), **Decision** (mark "needs user decision" for policy, legal, cost, external accounts, or product direction), **Resulting items**.
3. **Review.** Spawn `critic` with the design doc, the backlog item, and the CLAUDE.md rules. Ask it to check that the simplest workable option was chosen, that the rules hold, that failure modes are covered, and that every resulting item is testable. Revise for Blocker and High findings, at most 2 rounds.
4. **Queue the work.** Append each resulting item to the **end of Ready** with the next free id in its area (`B-` backend, `F-` frontend, `C-` catalog and data tools in `data/`, `I-` infra, `S-` further design), a `(from S-NNN)` note, and acceptance criteria a test can check. Anything marked "needs user decision" goes under **Needs decision** in both BACKLOG.md (`[?]`, options, recommendation) and PROGRESS.md (one bullet line); dependent items stay out of Ready.
5. **Merge** as in section 4. The requests made and the fixtures saved go in the design doc, not in PROGRESS.md.

## Planning cycle (Ready is empty)
Turns one Roadmap milestone into Ready items, so `/loop` keeps going without the user writing tickets.

1. Branch `cycle/NNN-plan-<milestone-slug>` and mark the milestone `[~]`.
2. Spawn `explorer` to report what already exists on `dev` that the milestone touches (`path:line`).
3. Write `docs/design/NNN-plan-<slug>.md`: the milestone text verbatim, what exists, and 3-8 items that together deliver the milestone. Each item is one cycle's worth of work with test-checkable acceptance criteria. **Add nothing the milestone does not ask for.**
4. Spawn `critic` with the doc and the milestone text: "flag items outside the milestone, items too big for one cycle, and criteria that are not testable". Revise for Blocker and High, at most 2 rounds.
5. Append the items to Ready with `(from M-x)`, mark the milestone `[x]`, and merge as in section 4.

## Blocked path
If 3 attempts are used up, or the work requires a human decision:
1. Commit any work in progress on the cycle branch.
2. Run `git switch dev` without merging.
3. Mark the item `[!]` (or `[?]`).
4. Log BLOCKED (or NEEDS DECISION) in PROGRESS.md, with the last verifier output and what was tried.

## Evidence rules
- A subagent saying "done", "passes", or "approved" is data, not evidence. Accept only command output that the verifier actually ran.
- Agreement between agents without code or command output behind it counts as **unresolved**.
- An "UNVERIFIED" notice from the stop hook is never a pass.
- Cycles no longer lock tests (D-134), so the stop hook reports failing tests without blocking; such a notice is never a pass. `lock.py` stays for anyone who wants it.
