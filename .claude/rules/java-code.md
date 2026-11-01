---
paths:
  - "src/main/java/**/*.java"
---

# Java code rules

Applies to all production code.

## Package layout (`io.github.hinn1411.docviewer`)
- `api/`: controller; `dto/`; `error/` (`ProblemHandler`); `filter/` (`ResponseHeadersFilter`)
- `domain/`: records, enums and `ResultMerger`; `error/` (domain exceptions)
- `search/`: `DocumentSearchService`; `support/audit/` (`AccessAuditor`); `support/fallback/` (`SnapshotFallback`)
- `source/`: the `DocumentSource` interface; `adapter/` (`BaseSourceAdapter`, `sales/`, `service/`); `error/` (`SourceException`, `RetryOnTransientError`)
- `persistence/`: audit and snapshot repositories
- `job/`: scheduled jobs (`SnapshotCleanupJob`)
- `observability/`: `TraceIds`
- `config/`: `properties/`, `client/`, `execution/` (executor, scheduling), `time/` (`Clock`)

## Dependencies
- They run one way: `api → search → source, persistence → domain`, and `job → persistence → domain`. `config/` and `observability/` are used across the others.
- `domain/` imports no other package of ours.
- Folders holding exceptions are called `error/`, never `failure/`.

## Rules
- Each adapter keeps its source's response classes to itself; only `Document` leaves it.
- The controller returns DTOs, never domain records.
- Time always comes from an injected `Clock`. Measure latency with `System.nanoTime()`.

## Privacy
- Never put a full VIN, document title, URL or token in logs, metrics, span attributes or exception messages.
- Mask VINs as `WVW**********0001` (first 3 and last 4 characters kept).
