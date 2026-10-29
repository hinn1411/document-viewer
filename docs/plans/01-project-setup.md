# 01. Project setup

Covers tasks **T01** (scaffold) and **T04** (Docker Compose and mocks). See [`06-tasks.md`](06-tasks.md) for their acceptance criteria.

## Project

| Setting | Value |
|---|---|
| Build | Maven with the wrapper (`./mvnw`), the same as devcool-core |
| Java | 21 (LTS). Virtual threads switched on with `spring.threads.virtual.enabled=true` |
| Spring Boot | The latest stable 3.x release, **at least 3.4** (needed for built-in structured JSON logging in T15). Look it up at scaffold time on start.spring.io or through context7, and don't guess |
| Group / artifact | `io.github.hinn1411` / `document-viewer` |
| Base package | `io.github.hinn1411.docviewer` |

## Dependencies

| Area | Dependency | Why |
|---|---|---|
| Web | `spring-boot-starter-web` | REST endpoint and `RestClient` for calling the sources |
| Web | `spring-boot-starter-validation` | Validating request inputs |
| Ops | `spring-boot-starter-actuator` | Health checks and the metrics endpoint |
| Data | `spring-boot-starter-jdbc`, `org.postgresql:postgresql` | `JdbcClient` with plain SQL for the two tables. No JPA: there's nothing to map |
| Data | `flyway-core`, `flyway-database-postgresql` | Versioned schema migrations |
| Resilience | `resilience4j-spring-boot3`, `spring-boot-starter-aop` | One retry per source call (`@Retry`) |
| API docs | `springdoc-openapi-starter-webmvc-ui` | OpenAPI spec and Swagger UI, which stand in for the client |
| Metrics | `micrometer-registry-prometheus` | `/actuator/prometheus` (T13) |
| Tracing | `micrometer-tracing-bridge-otel` (from **T01**), `opentelemetry-exporter-otlp` (from T14) | Every request gets a trace ID from day 1, which is used as the request ID and in the audit row. T14 adds the export to Jaeger |
| Logs | none extra | Spring Boot's built-in structured JSON logging (T15) |
| Test | `spring-boot-starter-test` | JUnit 5, AssertJ, Mockito |
| Test | `spring-boot-testcontainers`, `org.testcontainers:postgresql`, `org.testcontainers:junit-jupiter` | A real PostgreSQL in tests, never H2 |
| Test | `org.wiremock:wiremock-standalone` | Fake Sales and Service APIs with delays and errors |
| Test | `org.awaitility:awaitility` | Waiting for the background snapshot save without `Thread.sleep` |

**Maven plugins:**
- `spotless-maven-plugin` with google-java-format. `spotless:check` runs in CI.
- `maven-failsafe-plugin` so `*IT.java` tests run in `verify`.

## Package layout

Package by feature, mirroring the components in design doc §3. There are no extra layers: one API, one service, two adapters and two repositories.

```text
io.github.hinn1411.docviewer
├── DocumentViewerApplication.java
├── api/                     API layer
│   ├── DocumentController.java      GET /api/v1/vehicles/{vin}/documents
│   ├── CallerFilter.java            reads X-User-Id / X-Dealership-Id, returns 401
│   ├── ProblemHandler.java          400 / 401 / 503 as problem details
│   └── dto/                         response records (never return domain objects)
├── search/                  Document search service
│   ├── DocumentSearchService.java   parallel calls, fallback, audit
│   ├── ResultMerger.java            dedupe, sort, per-source status (pure function)
│   └── model/                       Vin, Document, SourceResult, … (see 05-domain-model.md)
├── source/                  Source system adapters
│   ├── DocumentSource.java          the one interface: name() and fetch(vin)
│   ├── SourceException.java         carries a FailureReason (TIMEOUT or ERROR)
│   ├── RetryOnTransientError.java   retry predicate: 5xx and connection errors, never timeouts
│   ├── sales/SalesSystemAdapter.java + SalesResponse.java (its own shape)
│   └── service/ServiceSystemAdapter.java + ServiceResponse.java
├── persistence/             Repository
│   ├── AuditRepository.java
│   ├── SnapshotRepository.java
│   └── SnapshotCleanupJob.java      deletes snapshots older than 24 h
└── config/                  RestClient beans, executor, Clock, properties
```

Rules (also in `CLAUDE.md`):
- Each adapter keeps its source's response classes to itself, and only `Document` leaves it.
- The controller returns DTOs, never domain records.
- Time always comes from an injected `Clock`, so tests can control it.

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

docviewer:
  source-deadline: 2s          # per source, then TIMEOUT
  fallback-max-age: 24h        # older snapshots are not served, and are deleted
  dealership-zone: Europe/London   # for date-only values such as the Sales createdOn
  sources:
    sales:
      base-url: http://localhost:8081
      connect-timeout: 300ms
      read-timeout: 1500ms
      token: dev-token         # stubbed OAuth 2.0 token, sent as a bearer token (A5)
    service:
      base-url: http://localhost:8082
      connect-timeout: 300ms
      read-timeout: 1500ms
      token: dev-token

resilience4j:
  retry:
    configs:
      default:
        max-attempts: 2        # 1 call + 1 retry
        wait-duration: 100ms
        retry-exception-predicate: io.github.hinn1411.docviewer.source.RetryOnTransientError
```

**How failures are classified** (design doc §4.2):
- **Retry:** `RetryOnTransientError` returns `true` only for HTTP 502, 503 and 504 and for refused or reset connections. It returns `false` for timeouts (`SocketTimeoutException` / `HttpTimeoutException` causes), because retrying a slow source adds load to it.
- **Timeouts:** the adapter turns a connect or read timeout into `SourceException(TIMEOUT)`, and everything else into `SourceException(ERROR)`. The service's 2 s deadline is the backstop for anything slower.
- **Token:** a real OAuth client-credentials flow is out of scope. The adapters send the configured token.

## Docker Compose (T04)

`docker compose up` starts everything the app needs locally. Observability services are added in T13 and T14.

| Service | Image | Port | Purpose |
|---|---|---|---|
| `postgres` | `postgres:16` | 5432 | Audit log and snapshots |
| `sales-api` | `wiremock/wiremock` | 8081 | Mock Sales System, stubs in `mocks/sales/mappings/*.json` |
| `service-api` | `wiremock/wiremock` | 8082 | Mock Service System, stubs in `mocks/service/mappings/*.json` |

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

## Demo VINs (WireMock stubs)

The stubs make each failure path from design doc §4.2 reproducible in tests, the demo and the video.

| VIN | Sales | Service | Expected result |
|---|---|---|---|
| `WVWZZZ1JZXW000001` | OK | OK | 200, complete |
| `WVWZZZ1JZXW000002` | OK | 5 s delay | 200, partial, Service `TIMEOUT` |
| `WVWZZZ1JZXW000003` | OK | 503, twice | 200, partial, Service `ERROR` after one retry |
| `WVWZZZ1JZXW000004` | 503 | 503 | 503, unless a snapshot under 24 h old exists |
| `WVWZZZ1JZXW000005` | OK, no documents | OK, no documents | 200, empty list |
