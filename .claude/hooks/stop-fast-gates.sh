#!/usr/bin/env bash
# Stop: before Claude ends a turn, run the fast gate for what this branch touches, and feed any
# failure back. The Maven counterpart of the Gradle hook in enrichmeai/penstock.
#   - `mvn test-compile` (main + test sources, no tests run) for each Maven module with a changed
#     *.java / pom.xml, plus the modules it depends on (-am). A changed root pom.xml compiles all.
# Scoped on purpose — CLAUDE.md: `mvn verify` and the conformance job belong in CI.
#   - change set = diff vs the merge-base with origin/main, plus uncommitted and untracked files;
#   - skipped when that change set is identical to the last one that passed;
#   - one retry only: if a stop was already blocked (stop_hook_active) and it still fails, the stop
#     is allowed with a warning to the owner, so a broken build can never loop forever.
# Opt out for one session: CLAUDE_SKIP_STOP_COMPILE=1.
set -uo pipefail

[ "${CLAUDE_SKIP_STOP_COMPILE:-}" = "1" ] && exit 0
input=$(cat)
active=$(printf '%s' "$input" | jq -r '.stop_hook_active // false')

root="${CLAUDE_PROJECT_DIR:-$(pwd)}"
cd "$root" || exit 0
[ -f pom.xml ] || exit 0

base=$(git merge-base HEAD origin/main 2>/dev/null || echo HEAD)
all_changed=$( { git diff --name-only "$base" 2>/dev/null; git ls-files --others --exclude-standard 2>/dev/null; } | sort -u)
[ -z "$all_changed" ] && exit 0

changed=$(printf '%s\n' "$all_changed" | grep -E '\.java$|(^|/)pom\.xml$' || true)
[ -z "$changed" ] && exit 0

modules=$(grep -Eo '<module>[^<]+</module>' pom.xml | sed -E 's#</?module>##g')
if printf '%s\n' "$changed" | grep -qx 'pom.xml'; then
  pl=""   # the parent changed: every module
else
  pl=$(for m in $(printf '%s\n' "$changed" | cut -d/ -f1 | sort -u); do
         printf '%s\n' "$modules" | grep -qx "$m" && printf '%s,' "$m"
       done)
  pl=${pl%,}
  [ -z "$pl" ] && exit 0   # Java outside the reactor (integration-kit samples): not this gate's
fi

if ! command -v mvn >/dev/null 2>&1; then
  jq -n --arg m "Maven compile gate did NOT run (mvn not on PATH) for ${pl:-all modules} — report it as not run." '{systemMessage: $m}'
  exit 0
fi

stamp_file="$(git rev-parse --git-dir)/claude-stop-check.stamp"
stamp=$( { echo "$pl"; for f in $changed; do [ -f "$f" ] && { echo "$f"; cat "$f"; }; done; } | sha256sum | cut -d' ' -f1)
[ -f "$stamp_file" ] && [ "$(cat "$stamp_file")" = "$stamp" ] && exit 0

args=(-q -B test-compile)
[ -n "$pl" ] && args=(-q -B -pl "$pl" -am test-compile)
label=${pl:-all modules}

msg=""
notrun=""
if ! out=$(mvn "${args[@]}" 2>&1); then
  if printf '%s' "$out" | grep -qE 'Could not resolve dependencies|Could not transfer artifact|Connection (refused|reset|timed out)|UnknownHostException|Non-resolvable parent POM|release version [0-9]+ not supported|invalid target release|Unsupported class file major version'; then
    # The environment, not the code: say the gate did not run — never that it passed.
    notrun="Maven compile gate did NOT run (dependency download failed, or this JDK is older than the build needs) for $label — report it as not run."
  else
    msg=$(printf 'test-compile failed for %s:\n%s\n' "$label" "$(printf '%s' "$out" | grep -E '\[ERROR\]' | grep -vE 'Re-run Maven|-> \[Help|^\[ERROR\] *$|For more information|To see the full stack' | head -60)")
  fi
fi

if [ -z "$msg" ]; then
  if [ -n "$notrun" ]; then jq -n --arg m "$notrun" '{systemMessage: $m}'; exit 0; fi
  echo "$stamp" > "$stamp_file"
  exit 0
fi
if [ "$active" = "true" ]; then
  jq -n --arg m "$msg" '{systemMessage: ("Stop allowed, but the fast gate still fails — treat this turn as BLOCKED.\n" + $m)}'
  exit 0
fi
jq -n --arg m "$msg" '{decision: "block", reason: ($m + "\nFix these, or if this is attempt 3, stop and write the Blocker summary (CLAUDE.md § \"Autonomous build loop\").")}'
exit 0
