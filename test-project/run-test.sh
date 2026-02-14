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
