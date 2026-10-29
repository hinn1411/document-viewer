# CLAUDE.md

## What this is
A stateless backend that shows every document for a vehicle (by VIN) from two source systems, Sales and Service, in one list. It calls both sources in parallel, gives each one a deadline, serves a 24 h fallback copy when a source fails, and audits every access. **The source of truth is [`docs/system-design-document.md`](docs/system-design-document.md).** The task plan is in [`docs/plans/`](docs/plans/README.md).

Java 21 · Spring Boot 4.1 (Spring Framework 7) · PostgreSQL 16 · Flyway · `JdbcClient` · Spring `@Retryable`.

## Commands
- `./mvnw spotless:apply`: format (the `format-java` hook does this after each edit)
- `./mvnw test`: unit tests
- `./mvnw verify`: unit + integration tests (needs Docker for Testcontainers)
- `docker compose up`: PostgreSQL and the two mock sources (from T04)
- `bash scripts/test-hooks.sh`: checks the harness hooks

## Rules
Code rules live in `.claude/rules/` and load when you work on matching files:
- `java-code.md` (`src/main/java`): package layout, DTOs at the edge, `Clock`, VIN masking
- `source-calls.md` (`source/`, `search/`, `config/`): parallel calls, deadlines, timeouts, retry
- `testing.md` (`src/test`): test style, timing and the red-commit rule

## TDD workflow
- Tests come first: `/frame-task Txx`, then `/implement-task Txx`.
- The tests in a task's red commit are the spec.
- Look up library APIs with context7 instead of guessing (Spring Boot 4 changed many of them).

## Never
- Push to `main`, force push, or run `terraform apply`/`destroy` (the `guard-bash` hook blocks these).
- Read `.env` files.

## Definition of Done
See [`docs/plans/README.md`](docs/plans/README.md#definition-of-done-every-task).
