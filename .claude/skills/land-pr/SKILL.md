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
git worktree add ../cistern-<task> -b <branch> main
cd ../cistern-<task>
```

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

Sign off (`-s`), and keep the `Co-Authored-By` trailer.

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

## Merge and verify

```bash
gh pr merge <n> --repo enrichmeai/<repo> --squash --delete-branch
git checkout main && git pull && mvn -q verify
```

**`--delete-branch` closes any PR stacked on that branch.** If someone has stacked on you, tell
them before merging, or their PR dies and cannot be reopened once its base is gone.

Post-merge, confirm the content actually landed — `grep` for a distinctive string from your
change on `main`. A green CI run on the PR is not proof the merge preserved it.

## When not to merge

- The change alters security posture or public positioning → open it and let the owner decide.
- The PR contains anything about revenue, pricing or partner economics → it does not belong in
  a public repo at all. Close it; a withdrawn PR still leaves a visible diff.
