---
paths:
  - "src/main/java/**/*.java"
---

# Java code rules

Applies to all production code.

## Package layout (`io.github.hinn1411.docviewer`)
- `api/`: controller, caller filter, problem handler, `dto/`
- `search/`: `DocumentSearchService`, `ResultMerger`, `model/` (Vin, Document, SourceOutcome, …)
- `source/`: the `DocumentSource` interface, `SourceException`, `RetryOnTransientError`, `sales/`, `service/`
- `persistence/`: audit and snapshot repositories, snapshot cleanup job
- `config/`: `RestClient` beans, executor, `Clock`, properties

## Rules
- Each adapter keeps its source's response classes to itself; only `Document` leaves it.
- The controller returns DTOs, never domain records.
- Time always comes from an injected `Clock`. Measure latency with `System.nanoTime()`.

## Privacy
- Never put a full VIN, document title, URL or token in logs, metrics, span attributes or exception messages.
- Mask VINs as `WVW**********0001` (first 3 and last 4 characters kept).
