#!/usr/bin/env bash
# T02: checks that the Claude Code hooks block or ask about what they should and format Java files.
# Run from anywhere: bash scripts/test-hooks.sh
set -u

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
GUARD="$ROOT/.claude/hooks/guard-bash.sh"
FORMAT="$ROOT/.claude/hooks/format-java.sh"
export CLAUDE_PROJECT_DIR="$ROOT"
failures=0

# check <name> <expected-exit> <hook> <json>: an expected exit 0 also means no "ask" prompt
check() {
  local name="$1" expected="$2" hook="$3" json="$4" actual out
  out="$(printf '%s' "$json" | "$hook" 2>/dev/null)"
  actual=$?
  if [ "$actual" -ne "$expected" ]; then
    echo "FAIL  $name (expected exit $expected, got $actual)"
    failures=$((failures + 1))
  elif [ "$expected" -eq 0 ] && asked "$out"; then
    echo "FAIL  $name (expected no prompt, but the hook asked)"
    failures=$((failures + 1))
  else
    echo "PASS  $name"
  fi
}

# check_ask <name> <json>: the hook exits 0 and asks the user to confirm
check_ask() {
  local name="$1" json="$2" actual out
  out="$(printf '%s' "$json" | "$GUARD" 2>/dev/null)"
  actual=$?
  if [ "$actual" -eq 0 ] && asked "$out"; then
    echo "PASS  $name"
  else
    echo "FAIL  $name (expected an ask prompt, got exit $actual)"
    failures=$((failures + 1))
  fi
}

asked() { printf '%s' "$1" | jq -e '.hookSpecificOutput.permissionDecision == "ask"' >/dev/null 2>&1; }

bash_json() { jq -cn --arg c "$1" '{tool_input: {command: $c}}'; }
file_json() { jq -cn --arg f "$1" '{tool_input: {file_path: $f}}'; }

echo "guard-bash.sh"
for cmd in \
  "git push --force" \
  "git push -f origin x" \
  "git push origin main" \
  "git push origin HEAD:main" \
  "git push origin main && echo ok" \
  "git reset --hard" \
  "rm -rf src" \
  "terraform apply" \
  "terraform destroy"; do
  check "blocks: $cmd" 2 "$GUARD" "$(bash_json "$cmd")"
done
for cmd in \
  "git status" \
  "git push origin feat/t02-harness" \
  "git push origin feat/x && gh pr create --base main --title x" \
  "gh pr create --base main" \
  "gh pr view 3" \
  "gh pr edit 3 --body-file b.md" \
  "gh api repos/o/r/pulls/5/comments" \
  "gh api -X GET repos/o/r/pulls -f state=open" \
  "git branch -d feat/x" \
  "rm -rf target" \
  "./mvnw verify"; do
  check "allows: $cmd" 0 "$GUARD" "$(bash_json "$cmd")"
done
# Destructive GitHub actions need my confirmation, even in auto mode
for cmd in \
  "gh pr close 5 --delete-branch" \
  "gh pr merge 3 --merge" \
  "gh issue close 1" \
  "gh release delete v1" \
  "gh repo delete o/r" \
  "gh api -X DELETE repos/o/r/pulls/comments/1" \
  "gh api --method PUT repos/o/r/branches/main/protection --input p.json" \
  "gh api repos/o/r/issues/1/comments -f body=x" \
  "git push origin --delete feat/x" \
  "git push origin :feat/x" \
  "git branch -D feat/x"; do
  check_ask "asks: $cmd" "$(bash_json "$cmd")"
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
