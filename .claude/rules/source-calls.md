---
paths:
  - "src/main/java/**/source/**"
  - "src/main/java/**/search/**"
  - "src/main/java/**/config/**"
---

# Source call rules

Applies to the adapters, the search service and their configuration. The values come from the design doc (§0 NFR1, §1, §4.2).

- Call the sources in parallel, never one after the other.
- Never wait on a source without a deadline: **2 s per source**.
- HTTP client timeouts: **300 ms connect, 1.5 s read**. A read timeout is a `TIMEOUT`, not an `ERROR`.
- **One retry** (`@Retryable`, 100 ms delay) on 502/503/504 or a refused connection. Never retry a timeout.
- One source failing becomes a status (`STALE` from the 24 h fallback copy, else `UNAVAILABLE`), never an exception for the whole request.
- A new source is one new class extending `BaseSourceAdapter`; the search service doesn't change.
