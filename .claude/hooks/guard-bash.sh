#!/usr/bin/env bash
# PreToolUse hook on Bash: blocks commands that rewrite history, skip the PR flow or destroy things,
# and asks me first before destructive GitHub actions.
# Exit 2 = blocked; the stderr message goes back to Claude.
# An "ask" decision on stdout = I confirm in a prompt, which shows even in auto mode.
set -u
set -f # never expand globs in the checked command (e.g. rm -rf *)

cmd="$(jq -r '.tool_input.command // ""')"
ROOT="${CLAUDE_PROJECT_DIR:-$(git rev-parse --show-toplevel 2>/dev/null || pwd)}"
ask_reason=""

block() {
  echo "Blocked by guard-bash.sh: $1. If you really mean it, run it yourself with: ! $cmd" >&2
  exit 2
}

ask() {
  jq -n --arg r "guard-bash.sh: $1. This changes or deletes something on GitHub, so confirm it's wanted." \
    '{hookSpecificOutput: {hookEventName: "PreToolUse", permissionDecision: "ask", permissionDecisionReason: $r}}'
  exit 0
}

has() { printf '%s' "$cmd" | grep -Eq "$1"; }

S='[[:space:]]'
# Push rules look only at each 'git push ...' part, so 'git push origin feat/x && gh pr create --base main' is fine
while read -r push; do
  [ -z "$push" ] && continue
  in_push() { printf '%s' "$push" | grep -Eq "$1"; }
  in_push "${S}(-f|--force[^[:space:]]*)(${S}|$)" && block "force push"
  in_push "(${S}|:)(refs/heads/)?main(${S}|$)" && block "push to main (open a PR instead)"
  # A bare 'git push' or 'git push origin' while on main also pushes main.
  branch="$(git -C "$ROOT" branch --show-current 2>/dev/null)"
  if [ "$branch" = "main" ]; then
    args="$(printf '%s' "$push" | sed -E 's/^git[[:space:]]+push//' | tr ' ' '\n' | grep -Ev '^(-|$)' | wc -l)"
    [ "$args" -le 1 ] && block "push from main (open a PR instead)"
  fi
  in_push "${S}(-d|--delete)(${S}|$)|${S}:[^[:space:]]+" && ask_reason="deleting a remote branch"
done < <(printf '%s' "$cmd" | grep -Eo "git${S}+push[^;&|]*")

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

# Ask (after every block rule, so a block always wins): approval to open something is not approval to close it
[ -n "$ask_reason" ] && ask "$ask_reason"
has "gh${S}+(pr|issue)${S}+(close|merge|delete|lock)(${S}|$)" && ask "closing, merging or deleting a PR or issue"
has "--delete-branch" && ask "deleting a branch"
has "gh${S}+[a-z-]+${S}+delete(${S}|$)" && ask "a gh delete"
has "gh${S}+repo${S}+(archive|rename|edit)(${S}|$)" && ask "changing the repository"
if has "gh${S}+api${S}"; then
  printf '%s' "$cmd" | grep -Eiq "(-X|--method)(${S}+|=)?(POST|PUT|PATCH|DELETE)" && ask "a GitHub API write"
  if has "${S}(-f|-F|--field|--raw-field|--input)(${S}|=)" &&
    ! printf '%s' "$cmd" | grep -Eiq "(-X|--method)(${S}+|=)?GET"; then
    ask "a GitHub API write (fields make gh api send a POST)"
  fi
fi
has "git${S}+branch${S}+(.*${S})?-[a-zA-Z]*D(${S}|$)" && ask "force-deleting a local branch"

exit 0
