# 03. CI pipeline

Covers task **T03** (brief: [`briefs/T03.md`](briefs/T03.md)). It's based on devcool-core's `ci.yml`, `ci-backend` action and `claude.yml`, cut down to the steps chosen for this project:

| Step | Kept? | Why |
|---|---|---|
| Format check (Spotless) | Yes | Cheap, and keeps AI-written code consistent |
| Build + unit + integration tests | Yes | The main gate. Testcontainers and WireMock run on GitHub's Ubuntu runners |
| Claude PR review | Yes | A second reviewer on every green PR, and evidence of directing AI |
| Path filters, coverage floor, SpotBugs, Sonar, Docker scan, actionlint | No | One small service doesn't need them. The Docker build arrives with the optional CD (T17) |

## `.github/workflows/ci.yml`

The workflow file is the source of truth: [`.github/workflows/ci.yml`](../../.github/workflows/ci.yml). `scripts/test-ci.sh` checks its shape. It runs on every PR (`opened`, `synchronize`, `reopened`, `ready_for_review`) and on pushes to `main`. A new push to a PR cancels that PR's running build and review.

| Job | Runs when | Steps |
|---|---|---|
| `Format, build and test` (required check) | always | checkout@v7 → setup-java@v6 (Temurin 21, Maven cache) → **Format check** (`./mvnw -B spotless:check`) → `./mvnw -B verify` (unit + `*IT` with Testcontainers and WireMock) → upload surefire/failsafe reports (upload-artifact@v7) |
| `Claude review` (advisory) | `needs: build`, non-draft PR only | delete Claude's earlier inline comments (`claude[bot]`, via `GITHUB_TOKEN`) → `anthropics/claude-code-action@v1` |

The review replaces the label opt-in that devcool-core uses. Each green push gets a fresh review, and only Claude's own earlier comments are deleted. The prompt checks the diff against `CLAUDE.md`, the design doc and, when the PR names a task, its brief's "Done when" tests. Claude can only read the diff (`gh pr diff`, `gh pr view`) and post inline comments (`mcp__github_inline_comment__create_inline_comment`). It never edits files or pushes.

Things to know about the review job:
- `claude-code-action` gets its GitHub token by an OIDC exchange that requires the PR's workflow file to match `main`. A PR that changes `ci.yml` (including T03 itself) gets a failed `Claude review` until it merges. That's why it isn't a required check.
- Old comments are deleted before the new review runs. If the review then fails or is cancelled, the PR has no Claude comments, so check the job's status before reading "no comments" as "no issues".
- PRs from forks are skipped: they get no secrets and a read-only token.

Action versions are checked against each action's releases when the file changes.

## One-time setup (I do these)

1. **Claude app and secret:** in Claude Code, run `/install-github-app` for `hinn1411/document-viewer`. It installs the Claude GitHub app and adds `CLAUDE_CODE_OAUTH_TOKEN`. Skip its offer to add workflow files.
2. **Branch protection on `main`,** after the first green CI run so the check name exists:
   - require a pull request before merging (0 approvals: a solo repo)
   - require the `Format, build and test` check to pass
   - block force pushes, and apply the rules to admins too

   Set it in the repo's Settings → Branches, or with `gh api`.

## Definition of done for T03

See the "Done when" list in [`briefs/T03.md`](briefs/T03.md).
