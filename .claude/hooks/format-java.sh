#!/usr/bin/env bash
# PostToolUse hook on Edit/Write: formats the edited Java file with Spotless so CI's format check passes.
# Never blocks: always exits 0.
set -u

file="$(jq -r '.tool_input.file_path // ""')"
ROOT="${CLAUDE_PROJECT_DIR:-$(git rev-parse --show-toplevel 2>/dev/null || pwd)}"

case "$file" in
  *.java) ;;
  *) exit 0 ;;
esac
[ -f "$file" ] || exit 0

# spotlessFiles takes a regex, so escape the path
pattern="$(printf '%s' "$file" | sed 's/[][\.*^$+?(){}|]/\\&/g')"
"$ROOT/mvnw" -q -f "$ROOT/pom.xml" spotless:apply -DspotlessFiles="$pattern" >/dev/null 2>&1 \
  || echo "format-java.sh: spotless:apply failed for $file" >&2

exit 0
