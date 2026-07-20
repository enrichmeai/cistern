#!/usr/bin/env bash
# Turn a conformance-harness run into a Markdown summary (stdout).
#
#   ./summarize.sh <harness-log> <reports-dir> [harness-exit-code] [extra-log...]
#
# CI (T3.1) redirects this into $GITHUB_STEP_SUMMARY; run it locally against a
# saved log to see exactly what CI will say.
#
# Honesty rules baked in here, because this text is the project's public
# conformance claim:
#   * Pass/fail counts are printed ONLY when the harness actually emitted a
#     results report. Pre-T5.4 it dies during setup and writes nothing at all —
#     printing "0 failed" from that would read as green when nothing ran.
#   * When no results report exists, the run is reported as fully UNTESTED,
#     with the harness's own error as the stated reason.
#   * A count this script could not parse is reported as unknown, never as 0.
#   * A harness that could not WRITE its report is called out. It logs the failure
#     and exits 0 regardless, so an unwritten report is otherwise indistinguishable
#     from a clean run that simply had nothing to say.
set -euo pipefail

log="${1:?usage: summarize.sh <harness-log> <reports-dir> [exit-code] [extra-log...]}"
reports="${2:?usage: summarize.sh <harness-log> <reports-dir> [exit-code] [extra-log...]}"
exit_code="${3:-}"
shift 3 2>/dev/null || shift $#
extra_logs=("$@")

# Pull a single capture group out of the log; empty if the marker is absent.
extract() { sed -n -E "s/.*$1.*/\2/p" "$log" | head -1; }

harness_version=$(extract '(Conformance Test Harness: )([0-9][^ ]*)')
suite_version=$(extract '(Test suite version: )(.*)$')
discovered=$(extract '(TEST CASES FOUND: )([0-9]+)')

# The harness writes results as report.* (HTML/Turtle); coverage.html comes from
# --coverage mode and is NOT a results report.
results_report=$(find "$reports" -maxdepth 1 -type f -name 'report*' 2>/dev/null | head -1 || true)
coverage_report=$(find "$reports" -maxdepth 1 -type f -name 'coverage*' 2>/dev/null | head -1 || true)

echo "## Conformance test harness"
echo

echo "| Field | Value |"
echo "|---|---|"
echo "| Harness version | ${harness_version:-unknown} |"
echo "| Test suite version | ${suite_version:-unknown} |"
echo "| Test cases discovered | ${discovered:-unknown} |"

if [ -n "$results_report" ]; then
  # Results exist (T5.4 onwards). Report what the harness itself stated; if the
  # counts cannot be parsed, say so rather than inventing zeros.
  passed=$(extract '(featuresPassed[^0-9]*)([0-9]+)')
  failed=$(extract '(featuresFailed[^0-9]*)([0-9]+)')
  echo "| Passed | ${passed:-see report artifact} |"
  echo "| Failed | ${failed:-see report artifact} |"
  echo "| Harness exit code | ${exit_code:-unknown} |"
  echo
  echo "Results report emitted — download the \`cth-reports\` artifact for the full detail."
else
  echo "| Passed | 0 |"
  echo "| Failed | 0 |"
  echo "| Untested | ${discovered:-unknown} |"
  echo "| Harness exit code | ${exit_code:-unknown} |"
  echo
  echo "**No results report was emitted — nothing was tested.** The harness authenticates"
  echo "alice and bob (\`registerClients\`) and prepares a test container before running any"
  echo "feature; it has no unauthenticated run mode. Until **T5.4** provisions those accounts"
  echo "the run stops during setup, which is the recorded baseline in \`cth/BASELINE.md\`."
  echo

  reason=$(sed -n -E 's/.*(TestHarnessInitializationException: .*)$/\1/p' "$log" | head -1)
  if [ -n "$reason" ]; then
    echo "Harness error:"
    echo
    echo '```'
    echo "$reason"
    echo '```'
    echo
  fi
fi

if [ -n "$coverage_report" ]; then
  echo "Coverage report generated ($(basename "$coverage_report")) — in the artifact."
fi

# The harness exits 0 even when it could not write its output, so an unwritten
# report looks exactly like a clean run unless we say so.
# ${arr[@]+"${arr[@]}"} — plain "${arr[@]}" on an empty array is an unbound-variable
# error under `set -u` in bash 3.2, which is what macOS ships.
write_failure=$(grep -h -m1 'Failed to write reports' "$log" ${extra_logs[@]+"${extra_logs[@]}"} 2>/dev/null || true)
if [ -n "$write_failure" ]; then
  echo
  echo "> **The harness could not write its reports** (it logs this and exits 0 anyway)."
  echo "> The reports directory must be writable by uid 185, the image's user:"
  echo "> \`chmod 777 cth/reports\`. Reported output above may be incomplete."
fi

echo
echo "_Report-only: this job never fails the build (T3.1). The ratchet gate lands in T3.2._"
