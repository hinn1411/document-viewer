---
name: implement-task
description: Implement an agreed task brief test-first - red tests, user review, then green code, review and close-out.
argument-hint: <task-id, e.g. T07>
disable-model-invocation: true
---

# Implement task $ARGUMENTS

Follow these steps in order. Never skip the stop in step 3.

1. **Understand.** Read `docs/plans/briefs/$ARGUMENTS.md`. If it's missing or not `agreed`, suggest `/frame-task $ARGUMENTS` and stop. Check that the tasks it depends on are ticked in `docs/plans/06-tasks.md`.
2. **Branch.** `git switch main`, then `git switch -c feat/<task-id lower-case>-<short-slug>`.
3. **Red.**
   - Write only the tests listed in the brief, plus the smallest stubs needed to compile them.
   - Run them (`./mvnw test` or `./mvnw verify`) and show that they fail for the right reason: an assertion or a missing behaviour, not an unrelated compile error. Check that the test count isn't 0.
   - **Stop. Show the tests and the failure output, and ask the user to review them. End your turn.**
   - Only when the user replies "approved", commit the tests as `test($ARGUMENTS): <summary>`. If they ask for changes, change the tests and stop again.
4. **Green.**
   - Write the simplest code that passes. Look up library APIs with context7.
   - Run `./mvnw spotless:apply verify` until it passes.
   - **Don't edit files from the red commit.** If a test seems wrong, stop and explain why; change it only with the user's agreement, and note the change in the brief.
5. **Refactor.** Tidy up only while the tests stay green.
6. **Review.** Run the `code-reviewer` agent with the task id. Fix every `blocking` finding and rerun `./mvnw verify`. Mention the suggestions you didn't take.
7. **Close out.**
   - Tick $ARGUMENTS in `docs/plans/06-tasks.md`, update the phase status in `docs/plans/README.md`, and set the brief's status to `done`.
   - If the AI made a mistake that was caught (a wrong API, a false green, a test that would have been weakened), add a row to `docs/ai-log.md`.
   - Commit `feat($ARGUMENTS): <summary>`.
   - Summarise what changed. Don't push or open a PR unless the user asks.
