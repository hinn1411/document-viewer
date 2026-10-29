# 03. CI pipeline

Covers task **T03**. It's based on devcool-core's `ci.yml`, `ci-backend` action and `claude.yml`, cut down to the steps chosen for this project:

| Step | Kept? | Why |
|---|---|---|
| Format check (Spotless) | Yes | Cheap, and keeps AI-written code consistent |
| Build + unit + integration tests | Yes | The main gate. Testcontainers and WireMock run on GitHub's Ubuntu runners |
| Claude PR review | Yes | A second reviewer on demand, and evidence of directing AI |
| Path filters, coverage floor, SpotBugs, Sonar, Docker scan, actionlint | No | One small service doesn't need them. The Docker build arrives with the optional CD (T17) |

## `.github/workflows/ci.yml`

```yaml
name: ci

on:
  pull_request:
  push:
    branches: [main]

permissions:
  contents: read

concurrency:
  group: ci-${{ github.ref }}
  cancel-in-progress: ${{ github.event_name == 'pull_request' }}

jobs:
  build:
    name: Format, build and test
    runs-on: ubuntu-latest
    timeout-minutes: 20
    steps:
      - name: Checkout
        uses: actions/checkout@v4

      - name: Set up JDK 21
        uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: "21"
          cache: maven

      - name: Format check
        run: ./mvnw -B spotless:check

      # Unit tests (surefire) and *IT tests (failsafe) with Testcontainers PostgreSQL and WireMock
      - name: Build, unit and integration tests
        run: ./mvnw -B verify

      - name: Upload test reports
        if: always()
        uses: actions/upload-artifact@v4
        with:
          name: test-reports
          path: |
            target/surefire-reports/
            target/failsafe-reports/
```

## `.github/workflows/claude.yml`

Adding the `claude-review` label to a PR runs a review. It's opt-in to save usage, the same as in devcool-core.

```yaml
name: claude-review

on:
  pull_request:
    types: [labeled]

jobs:
  review:
    if: github.event.label.name == 'claude-review' && !github.event.pull_request.draft
    runs-on: ubuntu-latest
    permissions:
      contents: read
      pull-requests: write
      id-token: write
    steps:
      - uses: actions/checkout@v4
        with:
          fetch-depth: 1

      - uses: anthropics/claude-code-action@v1
        with:
          claude_code_oauth_token: ${{ secrets.CLAUDE_CODE_OAUTH_TOKEN }}
          claude_args: |
            --allowedTools "mcp__github_inline_comment__create_inline_comment,Bash(gh pr diff:*),Bash(gh pr view:*)"
          prompt: |
            REPO: ${{ github.repository }}
            PR NUMBER: ${{ github.event.pull_request.number }}

            Review this pull request. Don't edit files or push.
            1. Read the diff with `gh pr diff`.
            2. Check it against CLAUDE.md and docs/system-design-document.md
               (parallel source calls with a 2 s deadline, one retry, fallback, audit, HTTP codes in §4.2,
               no full VINs or document content in logs).
            3. If the PR names a task (Txx), read docs/plans/briefs/Txx.md and check that each
               "Done when" item has a test and that the tests weren't weakened.
            4. Post each concrete issue as an inline comment with a suggested fix. No summary comment.
               Skip formatting: Spotless enforces it.
```

The pinned version tag (`@v1`) is checked against the action's releases when the file is written.

## One-time setup (I do these)

1. **Claude secret:** in Claude Code, run `/install-github-app` for `hinn1411/document-viewer`. It installs the GitHub app and adds `CLAUDE_CODE_OAUTH_TOKEN`.
2. **Label:** `gh label create claude-review --color 7057ff --description "Ask Claude to review this PR"`.
3. **Branch protection on `main`,** after the first green CI run so the check name exists:
   - require a pull request before merging
   - require the `Format, build and test` check to pass
   - block force pushes

   Set it in the repo's Settings → Branches, or with `gh api`.

## Definition of done for T03

- A PR shows a green `Format, build and test` check.
- A PR with a badly formatted file fails at "Format check".
- Labelling a PR `claude-review` produces inline comments.
