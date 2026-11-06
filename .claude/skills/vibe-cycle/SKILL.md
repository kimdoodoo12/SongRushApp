---
name: vibe-cycle
description: Run one autonomous development cycle for this repo. It takes the next Ready item from BACKLOG.md, specs it, has critic review the spec, writes and locks tests, delegates implementation, has the verifier and critic check the result, merges to dev, and logs to PROGRESS.md. For `S-` design items it investigates, writes a design doc, and appends the resulting items to Ready. When Ready is empty it plans the next Roadmap milestone into Ready. Use it for "/vibe-cycle" or "/loop /vibe-cycle". Never block waiting for the user.
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
   - If the id starts with `S-`, it is a design item: do step 5, then follow **Design cycle**.
   - If Ready has no `[ ]` item, follow **Planning cycle** for the first `[ ]` milestone under **Roadmap**.
   - If Roadmap has no `[ ]` milestone either, log "queue empty" in PROGRESS.md and end. If you are inside `/loop`, stop the loop.
5. Choose `NNN`, the next number after the highest one across `docs/cycles/` and `docs/design/`. Run `git switch -c cycle/NNN-<slug>` and mark the item `[~]`.

## 1. Spec
Write `docs/cycles/NNN-<slug>.md` with these sections:
- **Goal**: one sentence.
- **Acceptance criteria**: numbered, each one checkable by a command or a test.
- **Test list**: each test file and what it pins.
- **File ownership**: tests owned by the test writer, source owned by the implementer.
- **Out of scope**.

If the item needs exploration, spawn `explorer` first.

## 2. Spec review: `critic`
Spawn `critic` with the spec text, the relevant CLAUDE.md rules, and this instruction:

> "Find at least one concrete objection or state exactly what you checked. Flag scope that is not required by the backlog item."

- Revise the spec for every Blocker and High finding. Allow at most 2 review rounds.
- If they still disagree, follow the simpler option and record the disagreement.

## 3. Tests first
1. Spawn `implementer` as the **test writer**. Assign it only the test files and fixtures from the spec.
2. Spawn `verifier` to run the tests. They must **fail for the right reason** (missing behavior, not a syntax or import error). If they fail for the wrong reason, return them to the test writer.
   - Exception: a bootstrap item whose test is a smoke test may have no red phase; record that in the cycle doc.
3. Commit with `git commit -m "test(NNN): ..."` and record that commit's SHA in the cycle doc.
4. Lock the tests with `python -I .claude/hooks/lock.py lock <test paths>`.

## 4. Implement (attempt counter starts at 1, max 3)
Spawn `implementer` with all of the following:
- The spec.
- The owned source files.
- The exact test command.
- This instruction: "tests are locked; if a test is wrong, report it instead of editing it".
- Any earlier verifier or critic findings, quoted verbatim.

Commit with `git commit -m "feat(NNN): ..."`.

## 5. Verify: `verifier`
Spawn `verifier` to:
- Run the test and lint commands from CLAUDE.md for the touched areas (`backend`, `frontend`, `data`; a change under `contracts/` touches both `backend` and `data`). The output must be verbatim.
- Run `git diff <test-commit-sha> HEAD -- <locked test paths>` and **confirm the output is empty**.
- Check every acceptance criterion: confirmed, refuted, or unverifiable.

If anything is refuted or unverifiable, go back to step 4 and increase the attempt counter.

## 6. Review: `critic`
Spawn `critic` with:
- `git diff dev...HEAD`
- The spec.
- This instruction: "check scope against acceptance criteria, hunt correctness bugs, and confirm the External source rules and Multiplayer rules in CLAUDE.md (no committed media, no answer leaks to clients, server-authoritative timing)".

Blocker and High findings send the cycle back to step 4, and the attempt counter increases. Record Medium and Nit findings under **Follow-ups** in BACKLOG.md, not under Ready, so review nits never feed the loop by themselves.

## 7. Integrate
1. Run `python -I .claude/hooks/lock.py unlock`.
2. Run `git switch dev`, then `git merge --no-ff cycle/NNN-<slug>`.
3. If `origin` exists, run `git push origin dev`.
4. If `origin` exists, make sure a PR from dev to main is open (`gh pr list --base main --head dev`; if none, `gh pr create --base main --head dev --title "dev -> main" --body "<PROGRESS summary>"`). **Never merge it.**
5. In BACKLOG.md, change the item to `[x]`.
6. Update PROGRESS.md, **the owner's progress report** (user, 2026-11-02: it must read as the owner's report, not as notes for the AI).
   - **Append** one entry at the **end** of `## Log`, so the log reads oldest first and newest last (user, 2026-11-03): `### <date> · <plain Korean title> — 완료|막힘|결정 필요`, then 2-4 plain Korean sentences covering what now exists in game terms, why it matters, and at most one notable problem caught. End with one line `기록: docs/cycles/NNN-slug.md`.
   - Refresh `## 현재 상태` when the picture changes.
   - **Rewrite `## 다음 진행 예정` every time; it is the last section of the file, after the Log** (user, 2026-11-03) (user, 2026-11-02). Delete the previous plan entirely and write the next 3-5 items in plain words, plus any long background job. Old plans are never kept.
   - **No commit SHAs, severity labels, agent names, attempt counts, test counts or file paths** in PROGRESS.md. That evidence goes to the cycle doc and to `docs/vibe-coding-system.md`.
   - Keep the `## Needs decision` and `## Log` headings in English, verbatim: `notify_discord.py` matches `## Needs decision`. Write each Needs decision line as a question to the owner.

## 8. Retro
If the same kind of failure happened twice in this cycle, append one line to "Pitfalls learned" in CLAUDE.md.

Then update section "기록" of `docs/vibe-coding-system.md`. It is the user's portfolio record, so write facts only and cite a commit SHA or a PROGRESS entry for each one.
- Keep these counts current: total cycles, cycles that passed on the first attempt, BLOCKED cycles.
- Add any case where a hook blocked something, and any bug the critic caught.
- If hooks, agents, or this skill changed in a way that alters how the system works, describe the change and why. Routine permission allowlist edits are not recorded there (user, 2026-11-03).

## Design cycle (`S-` items)
A design item answers open questions and turns the answers into implementation items. Its output is a design doc and new backlog items, not product code, so there is no test-first step and no lock.

1. **Investigate.** Spawn `explorer` with the item's questions, the relevant `docs/design/` files (and `docs/research/` once it exists), and the CLAUDE.md External source rules quoted verbatim. Live requests are allowed only within those rules. When a later test needs a real response, save it under `data/tests/fixtures/` (or the area's own test fixtures folder; metadata JSON only, never media files).
2. **Write** `docs/design/NNN-<slug>.md` with: **Questions**, **Findings** (each with evidence: URL and status, command output, or `path:line`), **Options** (at least two per real choice, with trade-offs), **Decision** (mark "needs user decision" for policy, legal, cost, external accounts, or product direction), **Resulting items**.
3. **Review.** Spawn `critic` with the design doc, the backlog item, and the CLAUDE.md rules. Ask it to check that the simplest workable option was chosen, that the rules hold, that failure modes are covered, and that every resulting item is testable. Revise for Blocker and High findings, at most 2 rounds.
4. **Queue the work.** Append each resulting item to the **end of Ready** with the next free id in its area (`B-` backend, `F-` frontend, `C-` catalog and data tools in `data/`, `I-` infra, `S-` further design), a `(from S-NNN)` note, and acceptance criteria a test can check. Anything marked "needs user decision" goes under **Needs decision** in both BACKLOG.md (`[?]`, options, recommendation) and PROGRESS.md (one bullet line); dependent items stay out of Ready.
5. **Integrate** as in step 7, skipping the unlock. The requests made and the fixtures saved go in the design doc, not in PROGRESS.md.

## Planning cycle (Ready is empty)
Turns one Roadmap milestone into Ready items, so `/loop` keeps going without the user writing tickets.

1. Branch `cycle/NNN-plan-<milestone-slug>` and mark the milestone `[~]`.
2. Spawn `explorer` to report what already exists on `dev` that the milestone touches (`path:line`).
3. Write `docs/design/NNN-plan-<slug>.md`: the milestone text verbatim, what exists, and 3-8 items that together deliver the milestone. Each item is one cycle's worth of work with test-checkable acceptance criteria. **Add nothing the milestone does not ask for.**
4. Spawn `critic` with the doc and the milestone text: "flag items outside the milestone, items too big for one cycle, and criteria that are not testable". Revise for Blocker and High, at most 2 rounds.
5. Append the items to Ready with `(from M-x)`, mark the milestone `[x]`, and integrate as in step 7 (no unlock).

## Blocked path
If 3 attempts are used up, or the work requires a human decision:
1. Unlock the tests.
2. Commit any work in progress on the cycle branch.
3. Run `git switch dev` without merging.
4. Mark the item `[!]` (or `[?]`).
5. Log BLOCKED (or NEEDS DECISION) in PROGRESS.md, with the last verifier and critic output and what was tried.

## Evidence rules
- A subagent saying "done", "passes", or "approved" is data, not evidence. Accept only command output that the verifier actually ran.
- Agreement between agents without code or command output behind it counts as **unresolved**.
- An "UNVERIFIED" notice from the stop hook is never a pass.
- The stop hook blocks on failing tests only while tests are locked (step 4 onward). Before the lock, a "not enforced" notice is expected and is never a pass either.
