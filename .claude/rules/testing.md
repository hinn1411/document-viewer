---
paths:
  - "src/test/**"
---

# Testing rules

Applies to all tests.

- Unit tests: JUnit 5 + AssertJ, no Spring context.
- Integration tests end in `IT` and use Testcontainers PostgreSQL and WireMock. Never H2.
- Name tests for the behaviour, e.g. `returnsPartialWhenServiceTimesOut`.
- Tests use a fixed `Clock`. Waiting uses Awaitility. Never `Thread.sleep`.
- Measure latency with `System.nanoTime()`, never the `Clock` (a fixed `Clock` always reports 0 ms).
- Timing tests assert a margin (e.g. "under 1.5 s"), never an exact time.
- The tests in a task's red commit (`test(Txx): …`) are the spec. Don't change or weaken them without asking.
