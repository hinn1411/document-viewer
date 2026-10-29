#!/usr/bin/env bash
# T02: checks that the Claude Code hooks block what they should and format Java files.
# Run from anywhere: bash scripts/test-hooks.sh
set -u

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
GUARD="$ROOT/.claude/hooks/guard-bash.sh"
FORMAT="$ROOT/.claude/hooks/format-java.sh"
export CLAUDE_PROJECT_DIR="$ROOT"
failures=0

# check <name> <expected-exit> <hook> <json>
check() {
  local name="$1" expected="$2" hook="$3" json="$4" actual
  printf '%s' "$json" | "$hook" >/dev/null 2>&1
  actual=$?
  if [ "$actual" -eq "$expected" ]; then
    echo "PASS  $name"
  else
    echo "FAIL  $name (expected exit $expected, got $actual)"
    failures=$((failures + 1))
  fi
}

bash_json() { jq -cn --arg c "$1" '{tool_input: {command: $c}}'; }
file_json() { jq -cn --arg f "$1" '{tool_input: {file_path: $f}}'; }

echo "guard-bash.sh"
for cmd in \
  "git push --force" \
  "git push -f origin x" \
  "git push origin main" \
  "git push origin HEAD:main" \
  "git reset --hard" \
  "rm -rf src" \
  "terraform apply" \
  "terraform destroy"; do
  check "blocks: $cmd" 2 "$GUARD" "$(bash_json "$cmd")"
done
for cmd in \
  "git status" \
  "git push origin feat/t02-harness" \
  "rm -rf target" \
  "./mvnw verify"; do
  check "allows: $cmd" 0 "$GUARD" "$(bash_json "$cmd")"
done

echo "format-java.sh"
PROBE="$ROOT/src/test/java/io/github/hinn1411/docviewer/HookProbe.java"
NOTE="$ROOT/target/hook-probe.txt"
trap 'rm -f "$PROBE" "$NOTE"' EXIT

printf 'package io.github.hinn1411.docviewer;\nclass HookProbe{void   badlyFormatted( ){int x=1;}}\n' >"$PROBE"
check "exits 0 on a Java file" 0 "$FORMAT" "$(file_json "$PROBE")"
if "$ROOT/mvnw" -q -f "$ROOT/pom.xml" spotless:check >/dev/null 2>&1; then
  echo "PASS  leaves the Java file formatted"
else
  echo "FAIL  leaves the Java file formatted (spotless:check still fails)"
  failures=$((failures + 1))
fi

mkdir -p "$ROOT/target"
printf 'not   java\n' >"$NOTE"
check "exits 0 on a non-Java file" 0 "$FORMAT" "$(file_json "$NOTE")"
if [ "$(cat "$NOTE")" = "not   java" ]; then
  echo "PASS  leaves a non-Java file unchanged"
else
  echo "FAIL  leaves a non-Java file unchanged"
  failures=$((failures + 1))
fi

echo
if [ "$failures" -eq 0 ]; then
  echo "All hook checks passed"
else
  echo "$failures hook check(s) failed"
  exit 1
fi
