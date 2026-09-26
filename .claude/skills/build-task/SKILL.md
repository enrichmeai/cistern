---
name: build-task
description: The autonomous build loop for ONE task — spec → read the spec sections and verify docs → red test → implement (hooks check each edit) → reviewer agent → fix (max 3 attempts) → /compound → PR. Use for "build #N", "build T<phase>.<n>", "take the next task", "work on X", or when the owner hands over a task to do unattended. With no argument, takes the top item of the Claude queue on the Build board.
---

# /build-task — one task, start to reviewed PR

One task per session: a fresh session per task keeps context small. If the owner asks for a
second task, finish or park this one first.

## 0. Pick and pin the spec
- No argument: take the first item of **Claude queue** on the Build board
  (`gh issue list -R enrichmeai/cistern --label board`). Argument: that issue or BACKLOG ticket.
- Run `track-open-prs` first: an open or merged PR may already do this.
- Write the spec down before anything else: acceptance criteria (from the issue, its
  `Grooming` comment, or the ticket's DoD in `docs/BACKLOG.md`), the modules it touches, the
  Solid / WAC / Solid-OIDC / MCP sections it implements (§ "Spec sources"), and how a test
  proves each criterion. Missing or ambiguous → comment the question on the issue, stop, and
  report. Do not guess scope. CTH and spec disagree → stop and raise it (ground rule 1).
- Cross-repo task (anything Penstock's `CisternTool` or the demo stack depends on): one session
  per repo, so build only this repo's half. Cistern owns the pod's HTTP/MCP surface; the
  Penstock half becomes a `/new-issue` in `enrichmeai/penstock`, linked both ways, and this
  side lands first.

## 1. Set up
Worktree off `origin/main` (`git fetch --prune && git worktree add ../cistern-<task> -b <branch> origin/main`),
one concern per branch. In the GitHub Action the checkout is already a fresh clone on a `claude/`
branch: use it as is.

## 2. Verify before writing (CLAUDE.md § "Autonomous build loop")
For every library/API/CLI flag the change will use: open its entry in CLAUDE.md § "Pinned docs"
with WebFetch, at the version the poms resolve. Note the URL you used — the reviewer re-checks it.
Not pinned → the vendor's official docs only, then add it to the pinned list in `/compound`.

## 3. Red, then green
Write the test that proves the first criterion and show it failing for the right reason. Then
implement. The PostToolUse hook syntax-checks each edited JSON/YAML/Python/shell file; the Stop
hook runs `test-compile` for the touched Maven modules before the turn ends. Fix what they report
at once.

## 4. Gates — a task is not done until these pass
Run the fast gates for what changed (CLAUDE.md § "Autonomous build loop") and quote the result
lines. `mvn verify` and the conformance job run in CI: push the branch, open the PR as **draft**,
and read the CI run — never claim a suite or a CTH number you have not seen.

## 5. Review
Launch the `reviewer` agent with the spec from step 0. Fix every BLOCKER/MAJOR and every
UNVERIFIED item; answer MINORs in one line each (fixed / why not). Re-run the reviewer after fixing.

## 6. The 3-attempt cap
An **attempt** is one fix-and-recheck cycle against the same failing gate or the same reviewer
finding. After the 3rd failed attempt, stop changing code and post on the issue (and in your
reply) a **Blocker summary**:
```
Blocked: <gate or finding>
Tried: 1. … 2. … 3. … (what each changed, what it showed)
Evidence: <error lines, doc URLs, run IDs>
Hypothesis: <best guess at the root cause>
Needs: <the decision, access or information that would unblock it>
```
Leave the branch pushed and the PR draft. Do not widen scope to route around the blocker.

## 7. Compound, then hand over
Run the `/compound` skill. Update the ticket in `docs/BACKLOG.md` (`[ ]` → `[x]`) in the same PR,
and post the DoD-checklist comment. Then mark the PR ready and end with: branch, head SHA, files
changed, gates run with their results, reviewer verdict, Compound lines, and anything for the
owner. Commits are `git commit -s`, conventional, with no AI co-author trailer. The architect
merges (`land-pr`); never merge your own PR.
