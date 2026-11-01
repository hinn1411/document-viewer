# 01. Project setup

Covers tasks **T01** (scaffold) and **T04** (Docker Compose and mocks). See [`06-tasks.md`](06-tasks.md) for their acceptance criteria.

## Project

| Setting | Value |
|---|---|
| Build | Maven with the wrapper (`./mvnw`), the same as devcool-core |
| Java | 21 (LTS). Virtual threads switched on with `spring.threads.virtual.enabled=true` |
| Spring Boot | **4.1.2** (Spring Framework 7), the start.spring.io default at scaffold time. Its built-in structured JSON logging (T15) and `@Retryable` mean fewer libraries. Look up Boot 4 APIs through context7, because AI training data is mostly Boot 3 |
| Group / artifact | `io.github.hinn1411` / `document-viewer` |
| Base package | `io.github.hinn1411.docviewer` |

## Dependencies

| Area | Dependency | Why |
|---|---|---|
These are the Boot 4 starters that start.spring.io generates. Boot 4 splits the starters by module, and each one has a matching `*-test` starter.

| Area | Dependency | Why |
|---|---|---|
| Web | `spring-boot-starter-webmvc` | REST endpoint and `RestClient` for calling the sources |
| Web | `spring-boot-starter-validation` | Validating request inputs |
| Ops | `spring-boot-starter-actuator` | Health checks and the metrics endpoint |
| Data | `spring-boot-starter-jdbc`, `org.postgresql:postgresql` | `JdbcClient` with plain SQL for the two tables. No JPA: there's nothing to map |
| Data | `spring-boot-starter-flyway`, `flyway-database-postgresql` | Versioned schema migrations |
| Resilience | none extra | Spring Framework 7's `@Retryable` + `@EnableResilientMethods`: one retry per source call |
| Resilience | `resilience4j-circuitbreaker` (core library, T12b) | One breaker per source ([ADR 0001](../adr/0001-circuit-breaker-per-source.md)). Spring Framework 7 has no breaker. No annotation, because an annotation behind a proxy failed silently in T07 |
| API docs | `springdoc-openapi-starter-webmvc-ui` (3.x, for Boot 4) | OpenAPI spec and Swagger UI, which stand in for the client |
| Metrics | `micrometer-registry-prometheus` | `/actuator/prometheus` (T13) |
| Tracing | `spring-boot-starter-opentelemetry` (from **T01**, export switched off until T14) | Every request gets a trace ID from day 1, which is used as the request ID and in the audit row. T14 switches on the OTLP export to Jaeger |
| Logs | none extra | Spring Boot's built-in structured JSON logging (T15) |
| Test | `spring-boot-starter-*-test` for each starter above | JUnit 5, AssertJ, Mockito and the test slices |
| Test | `spring-boot-testcontainers`, `testcontainers-postgresql`, `testcontainers-junit-jupiter` | A real PostgreSQL in tests, never H2 |
| Test | `org.wiremock:wiremock-standalone` (added in T04) | Fake Sales and Service APIs with delays and errors |
| Test | `org.awaitility:awaitility` (added when first needed) | Polling without `Thread.sleep`. T11 closes the executor instead, which waits for its background saves |

**Maven plugins:**
- `spotless-maven-plugin` with google-java-format. `spotless:check` runs in CI.
- `maven-failsafe-plugin` so `*IT.java` tests run in `verify`.

## Package layout

Package by feature, mirroring the components in design doc §3. Dependencies run one way, and `domain/` imports no other package of ours. `config/` and `observability/` are used across the others.

```text
api → search → source, persistence → domain
job → persistence → domain
```

```text
io.github.hinn1411.docviewer
├── DocumentViewerApplication.java
├── api/                     API layer
│   ├── DocumentController.java      GET /api/v1/vehicles/{vin}/documents, reads X-User-Id / X-Dealership-Id
│   ├── dto/                         response records (never return domain objects)
│   ├── error/
│   │   └── ProblemHandler.java      400 / 401 / 503 / 500 as problem details
│   └── filter/
│       └── ResponseHeadersFilter.java   X-Request-Id and Cache-Control on every response
├── domain/                  Records, enums and rules with no external dependencies
│   ├── Vin, Caller, Document, SourceOutcome, … (see 05-domain-model.md)
│   ├── ResultMerger.java            dedupe, sort (pure function)
│   └── error/
│       ├── InvalidVinException.java
│       ├── MissingCallerException.java
│       ├── SourcesUnavailableException.java
│       └── AuditUnavailableException.java
├── search/                  Document search service
│   ├── DocumentSearchService.java   parallel calls, fallback, audit
│   └── support/
│       ├── audit/AccessAuditor.java     builds the audit row and fails closed
│       └── fallback/SnapshotFallback.java   saves and serves the 24 h fallback copies
├── source/                  Source system adapters
│   ├── DocumentSource.java          the one interface: name() and fetch(vin)
│   ├── adapter/
│   │   ├── BaseSourceAdapter.java   what adapters share: the call, retry, failures, dropping bad items
│   │   ├── sales/SalesSystemAdapter.java   its response shape is records inside it
│   │   └── service/ServiceSystemAdapter.java
│   └── error/
│       ├── SourceException.java     carries a FailureReason (TIMEOUT or ERROR) and whether to retry
│       └── RetryOnTransientError.java   gives @Retryable that decision
├── persistence/             Repositories
│   ├── AuditRepository.java
│   └── SnapshotRepository.java
├── job/
│   └── SnapshotCleanupJob.java      deletes snapshots older than 24 h
├── observability/
│   └── TraceIds.java                the ID shared by the audit row and X-Request-Id
└── config/
    ├── properties/DocViewerProperties.java, CircuitBreakerProperties.java
    ├── client/SourceClientsConfig.java     RestClient factory, @Retryable on
    │   client/SourceCircuitBreakerConfig.java   one breaker per source
    ├── execution/ExecutorConfig.java, SchedulingConfig.java
    └── time/ClockConfig.java
```

Tests use the same packages as the classes they test.

Rules (also in `.claude/rules/java-code.md`):
- Each adapter keeps its source's response classes to itself, and only `Document` leaves it.
- The controller returns DTOs, never domain records.
- Time always comes from an injected `Clock`, so tests can control it.
- Folders holding exceptions are called `error/`. `SourceException` stays in `source/error/`, because it depends on Spring's HTTP client.

## Configuration (`application.yml`)

The values come from the design doc (§0 NFR1, §1 A8, §4).

```yaml
spring:
  threads:
    virtual:
      enabled: true
  datasource:
    url: jdbc:postgresql://localhost:5432/docviewer
    username: docviewer
    password: docviewer
    hikari:
      connection-timeout: 1000   # ms. Only limits waiting for a connection
      data-source-properties:
        socketTimeout: 2         # seconds. Applies to every query. A slow future migration needs its own Flyway connection settings

docviewer:
  source-deadline: 2s          # per source, then TIMEOUT
  fallback-max-age: 24h        # older snapshots are not served, and are deleted
  dealership-zone: Europe/London   # for date-only values such as the Sales createdOn
  circuit-breaker:             # per source and per instance. Starting values, to tune from T13's metrics
    window: 60s                # time-based, so old failures expire at low traffic
    minimum-calls: 10
    failure-rate: 50           # percent
    slow-call: 1500ms
    slow-call-rate: 50         # percent
    open-for: 30s
    half-open-calls: 3
    half-open-max-wait: 60s    # then back to open, even if trial calls succeeded
  sources:
    sales:
      base-url: http://localhost:8081
      connect-timeout: 300ms
      read-timeout: 1500ms
    service:
      base-url: http://localhost:8082
      connect-timeout: 300ms
      read-timeout: 1500ms
```

The retry is set on the `BaseSourceAdapter` **class**, so every adapter gets it, and `@EnableResilientMethods` is on a config class. It can't go on `fetch`: with a JDK proxy, Spring's interceptor checks only the interface method and the target class, so it would be missed and nothing would be retried.

```java
@Retryable(includes = SourceException.class, predicate = RetryOnTransientError.class,
           maxRetries = 1, delay = 100)   // 1 call + 1 retry, 100 ms apart
public abstract class BaseSourceAdapter<I> implements DocumentSource { ... }
```

**How failures are classified** (design doc §4.2):
- **Retry:** `SourceException.from` decides, and `RetryOnTransientError` (a `MethodRetryPredicate`) passes that on. Only HTTP 502, 503 and 504 and refused connections are retried. Timeouts (`SocketTimeoutException` anywhere in the cause chain) aren't, because retrying a slow source adds load to it. Reset connections aren't either: the JDK client has no specific exception for them.
- **No cause:** `SourceException` keeps no cause, because Spring's I/O error messages contain the URL, and so the full VIN.
- **Timeouts:** the adapter turns a connect or read timeout into `SourceException(TIMEOUT)`, and everything else into `SourceException(ERROR)`. The clients use `SimpleClientHttpRequestFactory`, because Spring's JDK client reports a stalled body only as `IOException: closed`. Its read timeout applies per read, so the service's 2 s deadline is the backstop for anything slower.

## Docker Compose (T04)

`docker compose up` starts everything the app needs locally. Observability services are added in T13 and T14.

| Service | Image | Port | Purpose |
|---|---|---|---|
| `postgres` | `postgres:16` | 5432 | Audit log and snapshots |
| `sales-api` | `wiremock/wiremock:3.13.2` | 8081 | Mock Sales System, stubs in `mocks/sales/mappings/*.json` |
| `service-api` | `wiremock/wiremock:3.13.2` | 8082 | Mock Service System, stubs in `mocks/service/mappings/*.json` |

Each `mocks/<source>/` folder is mounted as WireMock's root (`/home/wiremock`), so the `mappings/` (and optional `__files/`) layout works for the Docker image and the JUnit extension alike.

## Mock source contracts

The two mocks deliberately return different shapes (assumption A4), so each adapter does real mapping work.

**Sales System:** `GET /sales/v1/deals?vin={vin}`. Documents are nested under deals, and dates are `dd/MM/yyyy`.

```json
{
  "vin": "WVWZZZ1JZXW000001",
  "deals": [
    {
      "dealNumber": "D-20250314-07",
      "documents": [
        { "docRef": "INV-1001", "docType": "INV", "name": "Sales invoice", "createdOn": "14/03/2025", "link": "https://sales.example.com/docs/INV-1001" }
      ]
    }
  ]
}
```

**Service System:** `GET /service/api/vehicles/{vin}/records`. A flat list, with Unix epoch seconds and numeric IDs.

```json
[
  { "recordId": 778, "category": "JOB_CARD", "description": "60,000 km service", "completedAt": 1790757900, "fileUrl": "https://service.example.com/files/778.pdf" }
]
```

## Source type codes

The stubs use these codes. Any other code maps to `OTHER`.

| Source | Code | `DocumentType` |
|---|---|---|
| Sales | `INV` | `SALES_INVOICE` |
| Sales | `ORD` | `ORDER_FORM` |
| Service | `JOB_CARD` | `JOB_CARD` |
| Service | `MOT` | `MOT_CERTIFICATE` |

## Demo VINs (WireMock stubs)

The stubs make each failure path from design doc §4.2 reproducible in tests, the demo and the video.

| VIN | Sales | Service | Expected result |
|---|---|---|---|
| `WVWZZZ1JZXW000001` | OK | OK | 200, complete |
| `WVWZZZ1JZXW000002` | OK | 5 s delay | 200, partial, Service `TIMEOUT` |
| `WVWZZZ1JZXW000003` | OK | 503, twice | 200, partial, Service `ERROR` after one retry |
| `WVWZZZ1JZXW000004` | 503 | 503 | 503, unless a snapshot under 24 h old exists |
| `WVWZZZ1JZXW000005` | OK, no documents | OK, no documents | 200, empty list |
