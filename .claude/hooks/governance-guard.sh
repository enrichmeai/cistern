#!/usr/bin/env bash
# Governance guard — mechanical enforcement of the rules in CLAUDE.md that were
# repeatedly followed by attention alone, and repeatedly nearly missed.
#
# PreToolUse/Bash. Reads the tool-call JSON on stdin, emits a permission decision.
# Fail-open by design: a guard that breaks the session gets disabled, and a disabled
# guard protects nothing.
set -uo pipefail

SHARED_CHECKOUT="${CISTERN_SHARED_CHECKOUT:-${HOME%/}/projects/cistern}"

payload=$(cat 2>/dev/null) || exit 0
cmd=$(printf '%s' "$payload" | jq -r '.tool_input.command // empty' 2>/dev/null) || exit 0
[ -n "$cmd" ] || exit 0

deny() {
  jq -nc --arg r "$1" '{hookSpecificOutput:{hookEventName:"PreToolUse",permissionDecision:"deny",permissionDecisionReason:$r}}'
  exit 0
}
warn() {
  jq -nc --arg c "$1" '{hookSpecificOutput:{hookEventName:"PreToolUse",additionalContext:$c}}'
  exit 0
}

# ---------------------------------------------------------------- 1. blanket staging
# A stray `git add -A` in a stale shared tree once reverted four merged PRs and still
# built green, because the tests that would have caught it were among the reverted files.
# Today it swept another session's file into a commit via a glob.
if printf '%s' "$cmd" | grep -qE '(^|[;&|] *)git +add +(-A\b|--all\b|\. *($|[;&|]))'; then
  deny "git add -A / git add . is blocked in this repo (CLAUDE.md: shared-checkout hazard).

A blanket stage cannot tell your work from another session's, and once reverted four merged
PRs while still building green. Stage the paths you actually touched:

  git add docs/FILE.md src/path/Thing.java

Then confirm with 'git status --porcelain' that nothing else is staged."
fi

# ---------------------------------------------------------------- 2. commit from the shared tree
# Other sessions switch branches in ~/projects/cistern. Work committed there rides
# whatever branch happens to be checked out.
if printf '%s' "$cmd" | grep -qE '(^|[;&|] *)git +(commit|cherry-pick|rebase)\b'; then
  if [ "$PWD" = "$SHARED_CHECKOUT" ] && ! printf '%s' "$cmd" | grep -q 'cistern-'; then
    deny "Committing from the shared checkout is blocked (CLAUDE.md: never commit from ~/projects/cistern).

Other sessions switch branches in this tree, so a commit here rides whatever branch is
checked out. Branch from origin/main in a worktree instead:

  git fetch --prune
  git worktree add ../cistern-<task> -b <branch> origin/main
  cd ../cistern-<task>

Note 'origin/main', not local main — local main goes stale in a shared checkout."
  fi
fi

# ---------------------------------------------------------------- 3. a merge that mostly deletes
# integration-test2 would have removed 263 files (the whole BMad install) because it
# predated that merge. Squash-merged branches look 'ahead' long after they have landed.
mergeref=$(printf '%s' "$cmd" | sed -nE 's/.*(^|[;&|] *)git +merge +(--[a-z-]+ +)*([^ ;&|]+).*/\3/p' | head -1)
if [ -n "$mergeref" ] && git rev-parse --verify --quiet "$mergeref" >/dev/null 2>&1; then
  dels=$(git diff --numstat origin/main.."$mergeref" 2>/dev/null | awk '$1=="-"||$2=="-"{next} {d+=$2} END{print d+0}')
  gone=$(git diff --diff-filter=D --name-only origin/main.."$mergeref" 2>/dev/null | wc -l | tr -d ' ')
  if [ "${gone:-0}" -ge 20 ]; then
    deny "This merge deletes ${gone} files (${dels:-?} lines) relative to origin/main.

  git diff --stat origin/main..${mergeref}

A branch that predates a large merge looks 'ahead' while actually reverting it — this is
exactly how integration-test2 would have deleted the entire BMad install. Squash-merged
work also stays 'ahead' forever, because the originals never land on main.

Confirm those deletions are intended before merging. If the branch is simply stale, it is
almost certainly already on main in squashed form and should be deleted, not merged."
  fi
fi

# ---------------------------------------------------------------- 4. merging an unclaimed PR
if printf '%s' "$cmd" | grep -qE '(^|[;&|] *)gh +pr +merge\b'; then
  warn "Before merging (CLAUDE.md + commit 65d9b0e): confirm the PR body names an owning session — an unclaimed PR is a finding, not an invitation. Check nothing is stacked on its branch (--delete-branch closes stacked PRs irrecoverably), and after merging, grep origin/main for a distinctive string from the change: a green check on the PR is not proof the merge preserved it."
fi

exit 0
