---
name: frame-task
description: Agree a task's requirements and tests with the user before any code exists, and save them as a brief in docs/plans/briefs/.
argument-hint: <task-id, e.g. T07>
disable-model-invocation: true
---

# Frame task $ARGUMENTS

Goal: an agreed brief for $ARGUMENTS that `/implement-task` can turn into failing tests. Write no code and no tests here.

1. **Locate.** Find `**$ARGUMENTS**` and its `### $ARGUMENTS:` section in `docs/plans/06-tasks.md`. If it doesn't exist, say so and stop. If `docs/plans/briefs/$ARGUMENTS.md` already exists, ask whether to keep it or refresh it, and wait.
2. **Gather.** Read the task section, the design doc sections and plan files it cites (`docs/system-design-document.md`, `docs/plans/0*.md`), and the code it touches. Check that the tasks it depends on are ticked in `06-tasks.md`.
3. **Explain.** Tell the user in 2–3 plain sentences what the task delivers and why it matters. Then ask them to predict the acceptance criteria and tests, or to reply `skip`. **End your turn and wait.**
4. **Brief.** Fill [`brief-template.md`](brief-template.md), 30 lines at most:
   - "Done when" items are Given/When/Then and each one is testable.
   - Every "Done when" item has at least one named test (`Class.behaviourName`).
   - Under "Prediction vs brief", list where the user's prediction differed and why.
5. **Decide.** Ask at most 4 open questions (AskUserQuestion, recommended option first). Don't ask anything the design doc or plans already answer; cite them in the Decisions table instead.
6. **Save.** Write `docs/plans/briefs/$ARGUMENTS.md` with status `agreed` and today's date. Show it, then print the next step: `/implement-task $ARGUMENTS`.
