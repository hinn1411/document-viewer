#!/usr/bin/env bash
# T03: checks the shape of the CI workflow (the live PR runs prove the rest, see docs/plans/briefs/T03.md).
# Run from anywhere: bash scripts/test-ci.sh   (needs python3 with PyYAML)
set -u

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
export CI_YML="${CI_YML:-$ROOT/.github/workflows/ci.yml}"
failures=0

# check <name> <python>: the python runs with the workflow loaded as `w` (plus `on`, `build`, `review`)
# and fails the check by raising (usually an assert with a message).
check() {
  local name="$1" body="$2" out
  if out="$(python3 - "$body" 2>&1 <<'PY'
import os, sys, yaml

path = os.environ["CI_YML"]
assert os.path.isfile(path), f"{path} is missing"
with open(path) as f:
    w = yaml.safe_load(f)
on = w.get("on", w.get(True))  # PyYAML reads a bare `on:` key as True
jobs = w.get("jobs", {})
build, review = jobs.get("build", {}), jobs.get("review", {})

def step_index(steps, predicate, what):
    for i, s in enumerate(steps):
        if predicate(s):
            return i
    raise AssertionError(f"no step {what}")

exec(sys.argv[1])
PY
)"; then
    echo "PASS  $name"
  else
    echo "FAIL  $name ($(printf '%s' "$out" | tail -n 1))"
    failures=$((failures + 1))
  fi
}

echo "ci.yml"
check "jobNameIsRequiredCheck" '
name = build.get("name")
assert name == "Format, build and test", f"build job name is {name!r}"
'

check "runsOnPullRequestAndPushToMain" '
assert "pull_request" in on, "no pull_request trigger"
assert on.get("push", {}).get("branches") == ["main"], "push is not limited to main"
assert w.get("permissions", {}).get("contents") == "read", "top-level permissions are not contents: read"
'

check "formatCheckRunsBeforeVerify" '
steps = build.get("steps", [])
fmt = step_index(steps, lambda s: s.get("name") == "Format check" and "spotless:check" in s.get("run", ""), "\"Format check\" running spotless:check")
verify = step_index(steps, lambda s: "./mvnw -B verify" in s.get("run", ""), "running ./mvnw -B verify")
assert fmt < verify, "Format check runs after verify"
'

check "claudeReviewNeedsGreenBuild" '
needs = review.get("needs", [])
assert "build" in ([needs] if isinstance(needs, str) else needs), "review job does not need build"
cond = review.get("if", "")
assert "pull_request" in cond and "draft" in cond, f"review if is {cond!r}"
assert "ready_for_review" in (on.get("pull_request") or {}).get("types", []), "a PR leaving draft does not trigger CI"
'

check "oldClaudeCommentsDeletedFirst" '
steps = review.get("steps", [])
delete = step_index(steps, lambda s: "-X DELETE" in s.get("run", "") and "claude[bot]" in s.get("run", ""), "deleting claude[bot] comments")
action = step_index(steps, lambda s: s.get("uses", "").startswith("anthropics/claude-code-action@"), "using claude-code-action")
assert delete < action, "old comments are deleted after the review"
'

check "claudeReviewCannotWrite" '
import re, shlex
steps = review.get("steps", [])
action = steps[step_index(steps, lambda s: s.get("uses", "").startswith("anthropics/claude-code-action@"), "using claude-code-action")]
args = action.get("with", {}).get("claude_args", "")
m = re.search(r"--allowedTools\s+(\"[^\"]*\"|\S+)", args)
assert m, "no --allowedTools in claude_args"
tools = set(shlex.split(m.group(1))[0].split(","))
expected = {"mcp__github_inline_comment__create_inline_comment", "Bash(gh pr diff:*)", "Bash(gh pr view:*)"}
assert tools == expected, f"allowedTools is {sorted(tools)}"
assert review.get("permissions", {}).get("contents") == "read", "review job can write repo contents"
'

echo
if [ "$failures" -eq 0 ]; then
  echo "All CI checks passed"
else
  echo "$failures CI check(s) failed"
  exit 1
fi
