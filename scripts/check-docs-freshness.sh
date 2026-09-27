#!/usr/bin/env bash
# Fails when a doc names a target/ build path pinned to a release-looking version, or
# spells out a literal SNAPSHOT jar name. Both go stale the moment a release ships or
# main's version bumps — see issue #133 (MCP setup docs pointed at target/ jars months
# after the bridge jar became a published Release asset).
#
# A wildcard build-from-source path (cistern-app/target/cistern-app-*.jar) is fine and
# stays fine forever: main sits at <next>-SNAPSHOT permanently (RELEASE.md § Version
# discipline), so a target/ path can never legitimately carry a dotted release version,
# and a doc never needs to spell SNAPSHOT out literally.
#
# A fenced code block immediately preceded by an HTML comment containing
# "docs-freshness: allow" is skipped — for the handful of places (RELEASE.md's local
# rehearsal) that deliberately use a throwaway, never-committed, never-published version
# string like `0.1.0-check` to prove the release workflow's own path logic.
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."

files=(README.md RELEASE.md)
while IFS= read -r -d '' f; do files+=("$f"); done < <(find docs -name '*.md' -print0 | sort -z)

fail=0

scannable() {
  awk '
    /docs-freshness: allow/ { skip=1; next }
    skip && /^```/ { if (!infence) { infence=1 } else { infence=0; skip=0 }; next }
    skip && infence { next }
    { print NR": "$0 }
  ' "$1"
}

for f in "${files[@]}"; do
  [ -f "$f" ] || continue
  content="$(scannable "$f")"

  if match=$(printf '%s\n' "$content" | grep -E '[A-Za-z0-9._-]*-SNAPSHOT[A-Za-z0-9._-]*\.jar'); then
    echo "check-docs-freshness: $f spells out a literal SNAPSHOT jar name — use a wildcard (cistern-app-*.jar) or the current released version" >&2
    echo "$match" >&2
    fail=1
  fi

  if match=$(printf '%s\n' "$content" | grep -E 'target/[A-Za-z0-9-]+-[0-9]+\.[0-9]+\.[0-9]+[A-Za-z0-9.-]*\.jar'); then
    echo "check-docs-freshness: $f names a target/ build path pinned to a release version — that path only exists after a local build, where the version is always SNAPSHOT" >&2
    echo "$match" >&2
    fail=1
  fi
done

if [ "$fail" -eq 0 ]; then
  echo "check-docs-freshness: OK (${#files[@]} files scanned)"
fi

exit "$fail"
