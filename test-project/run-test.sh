#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

JAR="${1:-$(ls "$SCRIPT_DIR"/../target/maven-sane-out-slf4j-*.jar 2>/dev/null | grep -v original | head -1)}"

if [ -z "$JAR" ] || [ ! -f "$JAR" ]; then
  echo "FAIL: agent JAR not found" >&2
  exit 1
fi

JAR="$(cd "$(dirname "$JAR")" && pwd)/$(basename "$JAR")"
echo "Using agent JAR: $JAR"

cd "$SCRIPT_DIR"

stdout=$(mktemp)
stderr=$(mktemp)
trap 'rm -f "$stdout" "$stderr"' EXIT

set +e
MAVEN_OPTS="-javaagent:$JAR" mvn -B compile >"$stdout" 2>"$stderr"
rc=$?
set -e

failed=0

if [ "$rc" -eq 0 ]; then
  echo "FAIL: expected non-zero exit code, got 0"
  failed=1
fi

if ! grep -q '\[ERROR\]' "$stderr"; then
  echo "FAIL: stderr does not contain [ERROR]"
  echo "--- stderr ---"
  cat "$stderr"
  failed=1
fi

if grep -q '\[ERROR\]' "$stdout"; then
  echo "FAIL: stdout still contains [ERROR]"
  echo "--- stdout ---"
  cat "$stdout"
  failed=1
fi

if [ "$failed" -ne 0 ]; then
  exit 1
fi

echo "PASS: [ERROR] lines routed to stderr"

# --- Quiet mode test ---
stdout_q=$(mktemp)
stderr_q=$(mktemp)
trap 'rm -f "$stdout" "$stderr" "$stdout_q" "$stderr_q"' EXIT

set +e
MAVEN_SANE_OUT_QUIET=5 MAVEN_OPTS="-javaagent:$JAR" mvn -B compile >"$stdout_q" 2>"$stderr_q"
set -e

if [ -s "$stdout_q" ]; then
  echo "FAIL: quiet mode stdout should be empty, but got:"
  cat "$stdout_q"
  failed=1
fi

if ! grep -q '\[ERROR\]' "$stderr_q"; then
  echo "FAIL: quiet mode stderr does not contain [ERROR]"
  echo "--- stderr ---"
  cat "$stderr_q"
  failed=1
fi

if [ "$failed" -ne 0 ]; then
  exit 1
fi

echo "PASS: quiet mode suppresses stdout, errors on stderr"

# --- Quiet mode via system property test ---
stdout_sp=$(mktemp)
stderr_sp=$(mktemp)
trap 'rm -f "$stdout" "$stderr" "$stdout_q" "$stderr_q" "$stdout_sp" "$stderr_sp"' EXIT

set +e
MAVEN_OPTS="-javaagent:$JAR" mvn -Dsane.quiet=5 -Dstyle.color=always compile >"$stdout_sp" 2>"$stderr_sp"
set -e

if [ -s "$stdout_sp" ]; then
  echo "FAIL: quiet mode (sysprop) stdout should be empty, but got:"
  cat "$stdout_sp"
  failed=1
fi

if ! grep -q 'ERROR' "$stderr_sp"; then
  echo "FAIL: quiet mode (sysprop) stderr does not contain ERROR"
  echo "--- stderr ---"
  cat "$stderr_sp"
  failed=1
fi

if [ "$failed" -ne 0 ]; then
  exit 1
fi

echo "PASS: quiet mode via -Dsane.quiet=5 with colors"
