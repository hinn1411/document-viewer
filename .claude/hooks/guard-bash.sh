#!/usr/bin/env bash
# PreToolUse hook on Bash: blocks commands that rewrite history, skip the PR flow or destroy things.
# Exit 2 = blocked; the stderr message goes back to Claude.
set -u
set -f # never expand globs in the checked command (e.g. rm -rf *)

cmd="$(jq -r '.tool_input.command // ""')"
ROOT="${CLAUDE_PROJECT_DIR:-$(git rev-parse --show-toplevel 2>/dev/null || pwd)}"

block() {
  echo "Blocked by guard-bash.sh: $1. If you really mean it, run it yourself with: ! $cmd" >&2
  exit 2
}

has() { printf '%s' "$cmd" | grep -Eq "$1"; }

S='[[:space:]]'
if has "git${S}+push"; then
  has "${S}(-f|--force[^[:space:]]*)(${S}|$)" && block "force push"
  has "(${S}|:)(refs/heads/)?main(${S}|$)" && block "push to main (open a PR instead)"
  # A bare 'git push' or 'git push origin' while on main also pushes main.
  branch="$(git -C "$ROOT" branch --show-current 2>/dev/null)"
  if [ "$branch" = "main" ]; then
    args="$(printf '%s' "$cmd" | sed -E 's/.*git[[:space:]]+push//' | tr ' ' '\n' | grep -Ev '^(-|$)' | wc -l)"
    [ "$args" -le 1 ] && block "push from main (open a PR instead)"
  fi
fi

has "git${S}+reset${S}.*--hard" && block "git reset --hard"
has "terraform${S}+(apply|destroy)" && block "terraform apply/destroy (run infrastructure changes yourself)"

# rm -r / -rf is only allowed on target/
while read -r segment; do
  [ -z "$segment" ] && continue
  printf '%s' "$segment" | grep -Eq "^rm${S}+(.*${S})?-[a-zA-Z]*[rR]" || continue
  for arg in ${segment#rm}; do
    case "$arg" in
      -*) ;;
      target | target/* | ./target | ./target/*) ;;
      *) block "rm -r outside target/ ($arg)" ;;
    esac
  done
done < <(printf '%s' "$cmd" | grep -Eo "(^|[;&|(]${S}*)rm${S}[^;&|)]*" | sed -E 's/^[;&|([:space:]]*//')

exit 0
