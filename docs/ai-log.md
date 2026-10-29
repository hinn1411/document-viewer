# AI log

One row per AI mistake that was caught. This is the raw material for the README's AI Collaboration Narrative and the 1–2 minute AI story in the video.

| Date | Task | What the AI did | How it was caught | Fix |
|---|---|---|---|---|
| 2026-10-29 | T01 | Pinned google-java-format 1.37.0 in the Spotless plugin, which made `spotless:apply` fail with `InvocationTargetException` | Running the formatter; gjf on its own worked, so the pin was the cause | Removed the pin and let Spotless pick its default gjf version |
| 2026-10-29 | T01 | Treated the first `./mvnw verify` as a red run, but it exited 0 with **0 tests**, because failsafe wasn't configured to run `*IT` | Reading the test count in the output, not just the exit code | Added `maven-failsafe-plugin`; `implement-task` now checks that the test count isn't 0 |
