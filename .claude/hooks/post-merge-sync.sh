#!/usr/bin/env bash
# Post-merge sync — the other half of governance-guard rule 5.
#
# Rule 5 refuses to stage in a stale shared checkout. This runs the moment a merge
# lands, so the staleness is reported at the point it is created rather than
# discovered later by a refusal.
#
# PostToolUse/Bash. Fail-open: a hook that breaks the session gets disabled, and a
# disabled hook protects nothing.
set -uo pipefail

SHARED_CHECKOUT="/Users/josepharuja/projects/cistern"

payload=$(cat 2>/dev/null) || exit 0
cmd=$(printf '%s' "$payload" | jq -r '.tool_input.command // empty' 2>/dev/null) || exit 0
printf '%s' "$cmd" | grep -qE '(^|[;&|] *)gh +pr +merge\b' || exit 0

git -C "$SHARED_CHECKOUT" fetch --prune -q 2>/dev/null

behind=$(git -C "$SHARED_CHECKOUT" rev-list --count HEAD..origin/main 2>/dev/null || echo 0)
[ "${behind:-0}" -gt 0 ] || exit 0

dirty=$(git -C "$SHARED_CHECKOUT" status --porcelain 2>/dev/null | grep -vc '^??' || echo 0)
head=$(git -C "$SHARED_CHECKOUT" log --oneline -1 2>/dev/null)

msg="A merge just landed and the shared checkout is now ${behind} commit(s) behind origin/main.

  ${SHARED_CHECKOUT}
  at: ${head}

Pull it before anything is staged there — governance-guard rule 5 will refuse staging until
you do:

  git -C ${SHARED_CHECKOUT} merge --ff-only origin/main"

if [ "${dirty:-0}" -gt 0 ]; then
  msg="${msg}

${dirty} tracked file(s) are modified in that tree. Check each against origin/main before
discarding OR committing it — a working copy that predates the merge will revert it."
fi

jq -nc --arg c "$msg" '{hookSpecificOutput:{hookEventName:"PostToolUse",additionalContext:$c}}'
exit 0
