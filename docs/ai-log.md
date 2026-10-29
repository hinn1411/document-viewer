# AI log

One row per AI mistake that was caught. This is the raw material for the README's AI Collaboration Narrative and the 1–2 minute AI story in the video.

| Date | Task | What the AI did | How it was caught | Fix |
|---|---|---|---|---|
| 2026-10-29 | T01 | Pinned google-java-format 1.37.0 in the Spotless plugin, which made `spotless:apply` fail with `InvocationTargetException` | Running the formatter; gjf on its own worked, so the pin was the cause | Removed the pin and let Spotless pick its default gjf version |
| 2026-10-29 | T01 | Treated the first `./mvnw verify` as a red run, but it exited 0 with **0 tests**, because failsafe wasn't configured to run `*IT` | Reading the test count in the output, not just the exit code | Added `maven-failsafe-plugin`; `implement-task` now checks that the test count isn't 0 |
| 2026-10-29 | T03 | Planned to prove the Claude review (Done-when #3) on the T03 PR itself, but `claude-code-action`'s OIDC token exchange requires the workflow file to match `main`, so the review job fails on the PR that adds it | `code-reviewer` agent, before the green commit | #3 is proved on the first PR after merge; noted in the brief and `03-ci-pipeline.md` |
| 2026-10-29 | T03 | Wrote the comment-deletion step without `pipefail`, so a failed `gh api` listing would pass silently and leave old comments in place | `code-reviewer` agent | Added `shell: bash` (GitHub then runs it with `-eo pipefail`) |
| 2026-10-29 | T03 | Predicted (with `code-reviewer`) that `Claude review` would **fail** on the T03 PR; it passed, because `claude-code-action` skips with only a warning when the workflow file differs from `main`: a false green with no review | Reading the job log after CI went green, instead of trusting the check | Corrected the `ci.yml` comment and `03-ci-pipeline.md`: a green review with no comments can be a skip |
