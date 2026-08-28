---
name: track-open-prs
description: Survey what is in flight across cistern, penstock and the site before starting, reviewing or landing anything — fetch first, find the PR that already does it, spot cross-PR collisions and instructions that a later policy reversed. Use before dispatching work, opening a PR, or reporting repo state to anyone.
---

# Track open PRs

Several sessions work these repos at once. The recurring failure is not a bad review — it
is **work built against a state that has already moved**. On 2026-08-22 a session wrote a
full dispatch brief for a tool that had already shipped in two releases, in a repo that had
been renamed, because it read a stale local checkout and never fetched.

## Fetch before you look at anything

```bash
git fetch origin --prune
git log origin/main --oneline -10
git status --porcelain          # in the shared checkout this should be EMPTY
```

A local `main` that has not been fetched will give you a confident, precise, wrong answer
about divergence. This applies to reading **your own** repo, not just published artifacts.

## Survey what is in flight

```bash
for R in enrichmeai/cistern enrichmeai/penstock; do
  echo "== $R"
  gh pr list --repo $R --state open \
    --json number,title,headRefName,isDraft,mergeable,reviewDecision \
    --jq '.[] | "\(.number)\t\(.mergeable)\t\(.reviewDecision // "-")\t\(.title)"'
done
```

Then ask, in this order:

1. **Does an open PR already do this?** Search before writing. A merged PR counts too —
   check `gh pr list --state merged --limit 20` and the releases.
2. **Is it already released?** `gh release list --repo <r>` and the CHANGELOG. Something
   shipped is not something to design.
3. **Which PRs touch the same files?** Two open PRs on one file is a collision waiting for
   whoever merges second:
   ```bash
   gh pr view <n> --repo <r> --json files --jq '.files[].path'
   ```
4. **Is anything stacked?** A PR whose base is another branch breaks when the base merges
   with `--delete-branch`. Check `baseRefName`.

## Check for instructions a later change reversed

A PR that has been open for days can carry guidance that policy has since overturned — an
open PR's `land-pr` skill still said "keep the `Co-Authored-By` trailer" after CLAUDE.md
had reversed exactly that. Before approving or following any instruction in an open PR,
check it against current `origin/main` CLAUDE.md and the most recent docs commits.

## Never leave work uncommitted in the shared checkout

`~/projects/cistern` is shared and other sessions switch branches in it. Edits sitting
there untracked are lost the moment someone moves the tree — CLAUDE.md rules, three new
skills and a set of doc edits were lost exactly this way. Work from a worktree off
`origin/main` and commit:

```bash
git worktree add ../cistern-<task> -b <branch> origin/main
```

Pushed without a PR is also lost work. Open the PR.

## Before reporting repo state to another session

Fetch, then state the commit you read and when. Say which claims you verified and which you
are relaying. A peer acting on your stale summary compounds the error, and correcting it
costs more than checking did.

## The sibling repo has the same discipline

Penstock carries `pr-sweep` (its PR #29), arrived at independently from the same failure in
the other direction. Keep the two cross-referenced rather than letting them diverge: a rule
learned in one repo this week almost certainly applies to the other, because the sessions
and the shared checkouts are the same.
