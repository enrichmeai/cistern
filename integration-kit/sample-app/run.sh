#!/usr/bin/env bash
# Runs the sample app against the kit: compiles once (TypeScript -> dist/), then tells the story.
#
#   ./sample-app/run.sh                       # mode=today (default): anonymous app, 401 outside the grant
#   KIT_MODE=after-88 ./sample-app/run.sh     # after #88: JWT from Keycloak, 403 outside the grant
#
# Needs Node 20+ and npm; the only dependency is the TypeScript compiler (dev-time). Everything the
# program reads comes from lib/kit.sh, cistern.env and identities.env, exported here.
set -euo pipefail
APP_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=../lib/kit.sh
. "${APP_DIR}/../lib/kit.sh"

readonly NODE_MIN_MAJOR=20
readonly NODE_MODULES="${APP_DIR}/node_modules"
readonly DIST_MAIN="${APP_DIR}/dist/main.js"

command -v node >/dev/null || die "node not found — the sample app needs Node ${NODE_MIN_MAJOR}+ (https://nodejs.org)"
node_major="$(node -p 'process.versions.node.split(".")[0]')"
[ "$node_major" -ge "$NODE_MIN_MAJOR" ] || die "Node ${NODE_MIN_MAJOR}+ required, found $(node --version)"

# lib/kit.sh exports the env files; these are computed there and must travel too.
export KIT_MODE CISTERN_BASE KEYCLOAK_BASE GRANTS_DIR

cd "$APP_DIR"
if [ ! -d "$NODE_MODULES" ]; then
  say "First run: installing the TypeScript compiler (npm ci)"
  npm ci --silent --no-audit --no-fund
fi
npm run --silent build
exec node "$DIST_MAIN" "$@"
