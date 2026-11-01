# ADR 0001: One circuit breaker per source, not one per endpoint

**Status:** Accepted · **Date:** 2026-11-01 · **Task:** T12b

## Context
`GET /api/v1/vehicles/{vin}/documents` calls the Sales and Service systems in parallel. A failed source becomes a status for that source, using a fallback copy under 24 h old when one exists, so the endpoint returns 200 unless no source can provide anything or the audit fails.

While a source is stalled, every request waits for its 1.5 s read timeout or 2 s deadline before falling back, and keeps sending load to a source already in trouble. I wanted a circuit breaker to stop that. The question was where to put it.

## Options
1. **One breaker in front of the endpoint.**
2. **One breaker in front of each source.**

## Decision
Option 2: one breaker per source, wrapping each source call inside the search service.

| Situation | Breaker on the endpoint | Breaker per source |
|---|---|---|
| Service stalled, Sales fine | Sees 200s, so never opens, and every request keeps waiting | Service is skipped, and Sales still answers |
| Both down, copies exist | Sees 200s, so never opens | Both skipped, and copies are served |
| Both down, only some VINs have copies | If it opens, it also rejects VINs a copy could answer | Each VIN gets its copy, or a 503 |
| Database down, sources fine | Opens and blames the endpoint | Stays closed, and the audit returns its 503 |
| A request while open | Rejected with no audit row | Audited as usual |

Three reasons decided it:
- **The failures are per source.** The endpoint's status hides a single source's failure, so an endpoint breaker can't see the main case. Opening it on partial results would block the healthy source too, against NFR2.
- **Fallback copies must stay reachable.** A breaker above the search rejects requests that a copy could answer.
- **Every access must be audited (NFR3).** A breaker above the audit step would need its own audit path.

## Consequences
**Better**
- A stalled source costs almost no time once its breaker is open, and stops receiving calls (NFR1).
- The state names the source that failed (NFR6).
- A third source gets its own breaker from its name, with no change to the search service (NFR5).

**Worse**
- A new library, and more state to understand and tune.
- The API's `reason` field gains `CIRCUIT_OPEN`.
- Work other than the source call still happens when a breaker is open, including the fallback read and the audit write. So it doesn't shed load if this service itself is overloaded. That belongs at the gateway.
- Timeouts and 503s can also be specific to one VIN, and they count toward the whole source. A client repeatedly asking for one failing VIN can open the breaker for every VIN. The demo stubs fail by VIN, so about ten mostly failing calls in 60 s would do this in a demo.
- Each instance tracks failures on its own. `minimum-calls` applies per instance, so more instances means slower opening. I chose this over shared state in Redis, which would add a network call and a new failure to every source call.

## What counts as a failure
Timeouts, 502/503/504 and refused connections count as failures. The breaker sits outside the retry, so a retried call counts once.

Everything else counts as a **success** in the breaker's window, because the library treats any exception it isn't told to record that way. That's intended for a 4xx, where the source answered and the problem may be one VIN. It has two costs:
- A plain 500, a reset connection or a DNS failure never opens the breaker, even if every call fails that way. The source keeps receiving load, and requests still fail fast and fall back.
- Unreadable responses and adapter bugs also count as successes, so they can dilute the failure rate or close a half-open breaker.

## When to revisit
- Traffic is too low per instance for the breaker ever to open.
- Many services call these sources, so a service mesh or gateway should own this.
- The business rejects serving stale documents for some types (A8), which would reduce the value of skipping to a copy.

The time savings above come from the configured timeouts and haven't been measured under load. The settings are starting values, to tune from T13's metrics.
