# 02. Claude Code harness

Covers task **T02**. The harness is deliberately small. It exists to make the [TDD loop](README.md#the-ai-native-tdd-loop) repeatable for feature work, and to stop the agent doing anything I can't review. It has no pieces for CI/CD, observability or deployment: those tasks are run as normal prompts.

## Files

```text
document-viewer/
├── CLAUDE.md
├── .mcp.json
├── docs/ai-log.md
└── .claude/
    ├── settings.json
    ├── skills/
    │   ├── frame-task/SKILL.md
    │   ├── frame-task/brief-template.md
    │   └── implement-task/SKILL.md
    ├── agents/
    │   └── code-reviewer.md
    └── hooks/
        ├── format-java.sh
        └── guard-bash.sh
```

| Piece | Why it's there |
|---|---|
| `CLAUDE.md` | One place for the project's rules, so every session and every review agent starts from the same context |
| `frame-task` skill | Makes me agree the requirements and the tests before any code exists |
| `implement-task` skill | Enforces red → my review → green, and stops the agent changing agreed tests |
| `code-reviewer` agent | A second, read-only look at each diff, run in its own context |
| `format-java` hook | Formats each edited Java file, so CI's format check never fails on AI code |
| `guard-bash` hook | Blocks force pushes and direct pushes to `main` |
| `settings.json` | Pre-approves safe commands and asks before pushing |
| `.mcp.json` | context7, so the agent reads current Spring Boot 4 and Spring Framework 7 docs instead of guessing APIs |
| `docs/ai-log.md` | Evidence of what the AI got wrong and how it was caught |

## `CLAUDE.md` (outline)

1. **What this is:** one paragraph, plus a link to `docs/system-design-document.md` as the source of truth.
2. **Commands:**
   - `./mvnw spotless:apply`
   - `./mvnw test` (unit tests)
   - `./mvnw verify` (unit + integration tests)
   - `docker compose up`
3. **Package layout:** the tree from [`01-project-setup.md`](01-project-setup.md#package-layout) and its three rules.
4. **Tests:**
   - Unit tests: JUnit 5 + AssertJ, no Spring context.
   - Integration tests end in `IT` and use Testcontainers PostgreSQL and WireMock. Never H2.
   - Name tests for the behaviour, e.g. `returnsPartialWhenServiceTimesOut`.
   - Time comes from a fixed `Clock`. Waiting uses Awaitility. Never `Thread.sleep`.
   - Latency is measured with `System.nanoTime()`, never with the `Clock`, because a fixed `Clock` would always report 0 ms.
   - Timing tests assert a margin (for example "under 1.5 s"), never an exact time.
5. **TDD rules:**
   - Tests come first.
   - Tests from a task's red commit are the spec. Don't change them without asking.
6. **Never:**
   - log full VINs, document titles, URLs or tokens
   - return domain records from the controller
   - call the sources one after the other
   - wait on a source without a deadline
7. **Definition of Done:** a link to [`README.md`](README.md#definition-of-done-every-task).

## Skill: `frame-task`

`/frame-task T07`, run before `/implement-task`. Adapted from devcool-core and shortened. Both skills set `disable-model-invocation: true` in their frontmatter, so they only run when I type them.

1. **Locate:** find `**T07**` in `docs/plans/06-tasks.md`. If a brief already exists in `docs/plans/briefs/`, ask whether to keep or refresh it.
2. **Gather:** read the task, the design doc sections it cites, and the code it touches.
3. **Explain:** say what the task delivers in 2–3 plain sentences. Ask me to predict the acceptance criteria and tests, or reply `skip`. Then wait.
4. **Brief:** fill [`brief-template.md`](#brief-template), 30 lines at most. List where my prediction differed and why.
5. **Decide:** ask at most 4 open questions, with the recommended option first. Don't ask what the design doc already answers.
6. **Save:** write `docs/plans/briefs/T07.md` with status `agreed`, then print the next step, `/implement-task T07`.

### Brief template

```markdown
# T07: <title>
<phase> · est <h> · <draft|agreed|done> · <YYYY-MM-DD>

**Why:** <requirement ids> → <what breaks without it>

## Done when
1. Given … when … then …

## Tests (written first)
- `<TestClass>.<behaviourName>`: <what it proves>

## Decisions
| Question | Answer | Source |
|---|---|---|

## Out of scope
- …
```

## Skill: `implement-task`

`/implement-task T07`

1. **Understand:** read the brief. If there's none, suggest `/frame-task T07` and stop. Check that the tasks it depends on are ticked.
2. **Branch:** `feat/t07-<slug>` from `main`.
3. **Red:**
   - Write only the tests listed in the brief.
   - Run them and show that they fail for the right reason (not a compile error in unrelated code).
   - **Stop and ask me to review the tests.** The agent ends its turn here and waits for my reply.
   - When I reply "approved", commit `test(T07): <summary>` and continue.
4. **Green:**
   - Implement the simplest code that passes. Use context7 for library APIs.
   - Run `./mvnw spotless:apply verify`.
   - **Don't edit the files from the red commit.** If a test seems wrong, stop and explain.
5. **Refactor:** tidy up only while the tests stay green.
6. **Review:** run the `code-reviewer` agent and fix its blocking findings.
7. **Close out:**
   - Commit `feat(T07): <summary>`.
   - Tick the task in `06-tasks.md` and update the phase status in `README.md`.
   - If the AI made a mistake that was caught, add an entry to `docs/ai-log.md`.
   - Summarize what changed. Don't push unless I ask.

## Agent: `code-reviewer`

- **Tools:** Read, Grep, Glob and `Bash(git diff*)`, `Bash(git log*)`. It never edits.
- **Input:** the task id.
- **Checklist:**
  1. Every "Done when" item in the brief has a test.
  2. `git diff <red-commit>..HEAD -- src/test` shows no weakened or deleted assertions.
  3. The source calls really run in parallel and each has a deadline. One failure becomes a status, not an exception.
  4. The rules in `CLAUDE.md`: package layout, DTOs at the edge, `Clock`, no `Thread.sleep`.
  5. Values match the design doc: 2 s deadline, 1 retry, 24 h fallback, the HTTP codes in §4.2.
  6. No full VIN, title, URL or token in logs, metrics or span attributes.
- **Output:** findings tagged `blocking` or `suggestion`, each with `path:line`.

## Hooks

| Hook | Event | What it does |
|---|---|---|
| `format-java.sh` | PostToolUse on `Edit` / `Write` | If the file is `*.java`, runs `./mvnw -q spotless:apply -DspotlessFiles=<file>`. It never blocks |
| `guard-bash.sh` | PreToolUse on `Bash` | Exits with code 2 (blocked) for `git push --force` / `-f`, a push to `main`, `git reset --hard`, `rm -rf` outside `target/`, and `terraform apply` / `terraform destroy` (only needed if the optional T18 is done). The message says to run it myself with `!` if I really mean it |

Both are adapted from devcool-core's scripts, keeping only these rules.

## `settings.json`

```json
{
  "permissions": {
    "allow": [
      "Bash(./mvnw *)", "Bash(docker compose *)",
      "Bash(git status*)", "Bash(git diff*)", "Bash(git log*)", "Bash(git switch*)",
      "Bash(git add *)", "Bash(git commit *)",
      "Bash(gh pr view *)", "Bash(gh pr checks *)", "Bash(gh run *)"
    ],
    "ask": ["Bash(git push *)", "Bash(gh pr create *)", "Bash(gh pr merge *)"],
    "deny": ["Read(./**/.env)", "Read(./**/.env.*)"]
  },
  "hooks": {
    "PreToolUse": [{ "matcher": "Bash", "hooks": [{ "type": "command", "command": "\"$CLAUDE_PROJECT_DIR\"/.claude/hooks/guard-bash.sh" }] }],
    "PostToolUse": [{ "matcher": "Edit|Write", "hooks": [{ "type": "command", "command": "\"$CLAUDE_PROJECT_DIR\"/.claude/hooks/format-java.sh" }] }]
  }
}
```

## `.mcp.json`

```json
{
  "mcpServers": {
    "context7": { "type": "http", "url": "https://mcp.context7.com/mcp" }
  }
}
```

GitHub is reached through the `gh` CLI, which is already signed in, so there's no GitHub MCP server.

## `docs/ai-log.md`

One row per AI mistake that was caught. This is the raw material for the README's AI Collaboration Narrative and the 1–2 minute AI story in the video.

```markdown
| Date | Task | What the AI did | How it was caught | Fix |
|---|---|---|---|---|
```
