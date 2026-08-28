---
name: land-pr
description: Take a change from branch to merged main in the Cistern repos — worktree, commit, checks, Copilot review, merge, post-merge verification. Use for any code or docs change to cistern, penstock or the site.
---

# Land a PR

## Branch from a worktree, never the shared checkout

Other sessions work these repos concurrently. Committing from `~/projects/cistern` once swept
stale files into a `git add -A` and **silently reverted four merged PRs** — which compiled and
passed, because the tests that would have caught it were among the reverted files.

```bash
git fetch --prune
git worktree add ../cistern-<task> -b <branch> origin/main
cd ../cistern-<task>
```

Branch from `origin/main` after fetching, never local `main` — a stale local ref is the
failure mode this skill exists to prevent, and one PR was written against `CLAUDE.md` text a
merged commit had already replaced.

One ticket per branch, branched from `main`, never stacked on another feature branch unless
the stack is deliberate and stated in the PR body.

## Before committing

- `mvn -q verify` — the whole build, not the one module you touched. Widening a record breaks
  callers in other modules; a `-pl` build will not see them.
- If a stale-class error appears (`cannot access X`), `mvn clean install -DskipTests` and retry
  before believing it.
- `git status` — if files you did not touch are modified, **stop**. They belong to someone
  else. Commit only your paths explicitly; never `git add -A` in a shared tree.

## Commit message

Explain **why**, not what — the diff already says what. Record the decision, the alternative
rejected, and anything that was surprising. These messages are the project's reasoning
archive, and reviewers read them before the code.

Sign off (`-s`). **No AI co-author trailers** — commits are the committer's alone
(CLAUDE.md, owner directive 2026-08-28, reversing 2026-07-17). History before that cutover
keeps its trailers and is not rewritten. Outbound contributions to other projects carry no AI
attribution anywhere.

## Open the PR, then wait properly

```bash
gh pr create --repo enrichmeai/<repo> --base main --head <branch> --title ... --body ...
gh pr checks <n> --repo enrichmeai/<repo>
```

**Wait for Copilot's review as well as the checks.** It has found real defects here —
`localhost` vs `127.0.0.1` on macOS, which would have produced a spurious 401 in a demo whose
whole subject is refusals.

**Verify what a reviewer tells you before acting on it.** Copilot cited a file and a line; the
claim held. A peer's confident numbers have not always. Check, then act.

## Hand it over — the owner merges

**`CLAUDE.md`: "the architect merges — dev agents never self-merge."** Your job ends at a PR
that is green, reviewed and described. Say what is ready and stop.

Merge only when the owner asks you to, in this conversation, for these PRs. That instruction
is not standing: it covers what was asked, not the next thing that becomes mergeable.

When you have been asked:

```bash
gh pr merge <n> --repo enrichmeai/<repo> --squash --delete-branch
git fetch --prune && git -C <a-clean-worktree> log --oneline -1 origin/main
```

**`--delete-branch` closes any PR stacked on that branch**, and a closed PR whose base is gone
cannot be reopened. Check for stacked PRs first and tell whoever owns them — this happened
once and cost a rebuild.

Post-merge, confirm the content actually landed: `grep` for a distinctive string from your
change on `origin/main`. A green CI run on the PR is not proof the merge preserved it.

## When not to merge

- The change alters security posture or public positioning → open it and let the owner decide.
- The PR contains anything about revenue, pricing or partner economics → it does not belong in
  a public repo at all. Close it; a withdrawn PR still leaves a visible diff.
