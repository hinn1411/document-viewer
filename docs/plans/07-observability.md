# 07. Observability

This builds what design doc §6 describes: watch the service the way users experience it, and watch each source system separately. It covers tasks **T13–T15**, each about 2 h, all depending on T12.

## Local stack (added to Docker Compose)

| Service | Image | Port | Purpose |
|---|---|---|---|
| `prometheus` | `prom/prometheus` | 9090 | Scrapes `/actuator/prometheus` every 15 s and loads `observability/alerts.yml` |
| `grafana` | `grafana/grafana` | 3000 | Prometheus data source and the dashboard, both provisioned from `observability/grafana/` |
| `jaeger` | `jaegertracing/all-in-one` | 16686 (UI), 4318 (OTLP) | Receives traces directly over OTLP, so no separate collector is needed locally |

## T13: Metrics, dashboard and alert rules

Serves NFR1, NFR2, NFR6.

**The six metrics** (from design doc §6.2):

| Metric | Type | Labels | Recorded in |
|---|---|---|---|
| `docviewer_requests_total` | Counter | `outcome`, `status` | the controller, once per request |
| `docviewer_request_duration_seconds` | Histogram | — | the controller |
| `docviewer_source_calls_total` | Counter | `source`, `result` (`ok`, `timeout`, `error`) | `DocumentSearchService`, once per source call |
| `docviewer_source_duration_seconds` | Histogram | `source` | `DocumentSearchService` |
| `docviewer_fallback_served_total` | Counter | `source` | when a `STALE` copy is used |
| `docviewer_audit_failures_total` | Counter | — | when the audit insert fails |

- All six are kept in one `DocumentViewerMetrics` class, so the names live in one place.
- The histograms publish buckets around the targets: 0.25, 0.5, 1, 2 and 2.5 s.
- Spring Boot's own HTTP metrics are kept. The `RestClient`s must come from Spring's auto-configured `RestClient.Builder` to get them.

**Alert rules** (`observability/alerts.yml`), matching design doc §6.1:

| Alert | Rule (summary) | Severity |
|---|---|---|
| `LatencyTargetMissed` | p95 of `docviewer_request_duration_seconds` > 1 s for 10 min | urgent |
| `AvailabilityTargetMissed` | the 5xx share of `docviewer_requests_total` (`status=~"5.."`) > 0.1% over 30 min. This includes audit-failure 503s, not only `outcome="failed"` | urgent |
| `AuditWriteFailing` | `increase(docviewer_audit_failures_total[5m]) > 0` | urgent |
| `SourceDegraded` | per `source`: timeouts + errors > 50% of calls for 5 min | warning |

**Dashboard** (`observability/grafana/dashboards/document-viewer.json`):
- request rate, partial rate and p95 latency
- one row per source system: calls by result, p95 latency, fallback copies served
- audit failures

**Done when**
1. Given a search where Service times out, then `docviewer_source_calls_total{source="SERVICE",result="timeout"}` goes up by 1, and `docviewer_requests_total{outcome="partial"}` goes up by 1.
2. `/actuator/prometheus` exposes all six metrics.
3. `docker compose up` shows the dashboard with data after a few demo requests.
4. `promtool check rules observability/alerts.yml` passes.

**Tests first:** `MetricsIT.countsTimeoutPerSource`, `MetricsIT.countsPartialRequests`, `MetricsIT.exposesAllSixMetrics`, all using `MeterRegistry` and `/actuator/prometheus`

## T14: Tracing

Serves NFR6.

- Micrometer Tracing with the OpenTelemetry bridge is already on from **T01**, so trace IDs exist from day 1, and T10 and T12 already use them. T14 adds the OTLP export to Jaeger, the custom spans and propagation.
- **One trace per request.** Its trace ID is returned as `X-Request-Id`, and the same ID is written to the audit row and the logs.
- **Spans:** the request, then `sales.fetch` and `service.fetch` (in parallel), `fallback.read` (only when a source failed) and `audit.save`.
- **Span attributes:** `source`, `result`, `document_count` and the masked VIN. Never the full VIN, titles or URLs.
- **Context across threads:** the virtual-thread executor is wrapped with Micrometer's context-propagation support, so the source spans are children of the request span. Without the wrapper they start new traces, which is a common AI mistake. The exact API is checked through context7 when this is built.
- **To the sources:** `RestClient` from the auto-configured builder sends the W3C `traceparent` header.
- **Sampling:** 100% locally. The design's production policy (keep every failed or slow trace plus about 10% of the rest) belongs in a collector's tail-sampling config, which is out of scope for local runs.

**Done when**
1. Given one search, then both source spans share the request's trace ID, and their time ranges overlap. That shows they ran in parallel.
2. Given a search, then the `X-Request-Id` header equals the trace ID, and the audit row's `trace_id` matches.
3. Given a call to WireMock, then the request carries a `traceparent` header with the same trace ID.
4. No span attribute contains the full VIN.

**Tests first:** `TracingIT.sourceSpansShareTraceAndOverlap`, `requestIdEqualsTraceId`, `propagatesTraceparentToSources`, `spansContainOnlyMaskedVin`. These use an in-memory span exporter.

## T15: Structured logs

Serves NFR3, NFR6.

- Spring Boot's built-in structured logging writes JSON to the console, and the trace and span IDs are added automatically.
- **One summary line per request** at `INFO`, with `message` `documents.search completed`. The fields are listed in design doc §6.2:
  - `userId`, `dealershipId`, the masked `vin`
  - `httpStatus`, `outcome`, `durationMs`
  - `sources[]`: status, reason, latency and count for each source
- Adapter failures are logged at `WARN`, with the source and the reason, but not the response body.
- A failed audit save and an unexpected exception from a source are logged at `ERROR` with the exception's class name only. Their messages can contain the VIN (T09, T10).
- **Never logged:** the full VIN, document titles, URLs or tokens.

**Done when**
1. Given a partial search, then exactly one `INFO` summary line is written, it's valid JSON, and it contains the fields above.
2. Given any search, then no log line contains the full VIN.
3. Given a source error, then a `WARN` line names the source and the reason, and contains no response body.

**Tests first:** `LoggingIT.writesOneJsonSummaryPerRequest`, `neverLogsFullVin`, `logsSourceFailureAtWarn`, all using Spring's `OutputCaptureExtension`
