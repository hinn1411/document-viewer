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

## Package layout (`io.github.hinn1411.docviewer`)
- `api/`: controller, caller filter, problem handler, `dto/`
- `search/`: `DocumentSearchService`, `ResultMerger`, `model/` (Vin, Document, SourceResult, …)
- `source/`: the `DocumentSource` interface, `SourceException`, `RetryOnTransientError`, `sales/`, `service/`
- `persistence/`: audit and snapshot repositories, snapshot cleanup job
- `config/`: `RestClient` beans, executor, `Clock`, properties

Rules:
- Each adapter keeps its source's response classes to itself; only `Document` leaves it.
- The controller returns DTOs, never domain records.
- Time always comes from an injected `Clock`.

## Tests
- Unit tests: JUnit 5 + AssertJ, no Spring context.
- Integration tests end in `IT` and use Testcontainers PostgreSQL and WireMock. Never H2.
- Name tests for the behaviour, e.g. `returnsPartialWhenServiceTimesOut`.
- Tests use a fixed `Clock`. Waiting uses Awaitility. Never `Thread.sleep`.
- Measure latency with `System.nanoTime()`, never the `Clock` (a fixed `Clock` always reports 0 ms).
- Timing tests assert a margin (e.g. "under 1.5 s"), never an exact time.

## TDD rules
- Tests come first: `/frame-task Txx`, then `/implement-task Txx`.
- The tests in a task's red commit (`test(Txx): …`) are the spec. Don't change or weaken them without asking.
- Look up library APIs with context7 instead of guessing (Spring Boot 4 changed many of them).

## Never
- Log full VINs, document titles, URLs or tokens (mask VINs as `WVW**********0001`).
- Return domain records from the controller.
- Call the sources one after the other.
- Wait on a source without a deadline.
- Push to `main`, force push, or run `terraform apply`/`destroy` (the `guard-bash` hook blocks these).

## Definition of Done
See [`docs/plans/README.md`](docs/plans/README.md#definition-of-done-every-task).
