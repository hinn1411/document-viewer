# 06. Tasks

Each task is one pull request. Run it with `/frame-task Txx`, then `/implement-task Txx` (see the [TDD loop](README.md#the-ai-native-tdd-loop)). The **Tests first** lists are the starting point for the brief: `/frame-task` turns them into the agreed list.

## Checklist

**P0 Foundation**
- [x] **T01** Scaffold the Spring Boot project
- [x] **T02** Claude Code harness
- [x] **T03** CI pipeline
- [x] **T04** Docker Compose and mock source systems

**P1 Domain**
- [x] **T05** VIN value object
- [x] **T06** Result merger

**P2 Source systems**
- [x] **T07** Sales System adapter
- [x] **T08** Service System adapter
- [x] **T09** Parallel calls with a deadline

**P3 Persistence**
- [x] **T10** Access audit (fails closed)
- [ ] **T11** Fallback snapshots

**P4 API**
- [ ] **T12** REST endpoint, errors and OpenAPI

**P5 Observability**
- [ ] **T13** Metrics, dashboard and alert rules
- [ ] **T14** Tracing
- [ ] **T15** Structured logs

**P6 Delivery**
- [ ] **T16** README, demo and AI narrative

**P7 CD and AWS (optional)**
- [ ] **T17** Production Docker image
- [ ] **T18** Terraform walking skeleton
- [ ] **T19** Deploy workflow

---

## P0 Foundation

### T01: Scaffold the Spring Boot project
Serves NFR4 (stateless) · est 2 h · depends on nothing · details in [`01-project-setup.md`](01-project-setup.md)

**Done when**
1. Given a fresh clone, when I run `./mvnw verify`, then it passes, with a Testcontainers PostgreSQL started and Flyway applied.
2. Given the app is running, when I call `GET /actuator/health`, then it returns 200 `UP`.
3. Given a badly formatted Java file, when I run `./mvnw spotless:check`, then it fails.
4. A `Tracer` bean exists and creates 32-hex trace IDs (tracing is on, with no exporter yet), so T10 and T12 can use them as the request ID.

**Tests first:** `ApplicationIT.contextLoadsWithMigrations`, `ApplicationIT.healthIsUp`, `ApplicationIT.tracerCreatesTraceIds`

**Files:** `pom.xml`, `mvnw*`, `src/main/resources/application.yml`, `src/main/resources/db/migration/` (empty for now), the package folders

### T02: Claude Code harness
Serves the AI-native TDD loop · est 2 h · depends on T01 · details in [`02-claude-code-harness.md`](02-claude-code-harness.md)

**Done when**
1. Given the harness files, when I start Claude Code in the repo, then `CLAUDE.md` loads and `/frame-task` and `/implement-task` are listed.
2. Given `{"tool_input":{"command":"git push --force"}}` piped to `guard-bash.sh`, then it exits 2. A push to `main` also exits 2, and `git status` exits 0.
3. Given an edit to a badly formatted Java file, then `format-java.sh` leaves it formatted.

**Tests first:** the three shell checks above, kept as `scripts/test-hooks.sh`

### T03: CI pipeline
Serves every requirement (the gate) · est 1 h · depends on T01 · details in [`03-ci-pipeline.md`](03-ci-pipeline.md)

**Done when**
1. A PR runs the `Format, build and test` check, and it passes.
2. A formatting error fails it at "Format check".
3. A green non-draft PR gets a Claude review that replaces its earlier comments.
4. `main` is protected by that check.

The exact Given/When/Then and tests are in [`briefs/T03.md`](briefs/T03.md): `scripts/test-ci.sh` plus live PR runs.

### T04: Docker Compose and mock source systems
Serves FR2, A4 · est 2 h · depends on T01 · details in [`01-project-setup.md`](01-project-setup.md#docker-compose-t04)

**Done when**
1. Given `docker compose up`, then PostgreSQL, `sales-api` (8081) and `service-api` (8082) are healthy.
2. Given each demo VIN, when I call the mock directly with cURL, then it returns the shape, delay or error in the demo-VIN table.
3. The same stub files in `mocks/sales/mappings/` and `mocks/service/mappings/` are used by Compose **and** by the integration tests. There's one set of stubs.

**Tests first:** `MockStubsIT.salesStubReturnsNestedDeals`, `MockStubsIT.serviceStubDelaysFor000002`

---

## P1 Domain

### T05: VIN value object
Serves FR1, A2, A10 · est 1 h · depends on T01

**Done when**
1. Given `" wvwzzz1jzxw000001 "`, when it's parsed, then the value is `WVWZZZ1JZXW000001`.
2. Given 16 or 18 characters, or a VIN containing I, O or Q, then `InvalidVinException` is thrown.
3. Given a valid VIN, then `masked()` returns `WVW**********0001`.

**Tests first:** `VinTest.normalisesCaseAndWhitespace`, `rejectsWrongLength`, `rejectsLettersIOQ`, `masksAllButFirst3AndLast4`

**Files:** `search/model/Vin.java`, `InvalidVinException.java`

### T06: Result merger
Serves FR3, A6, A7 · est 2 h · depends on T05 · see invariants 2–7 in [`05-domain-model.md`](05-domain-model.md#invariants-each-one-is-a-unit-test)

**Done when**
1. Given documents from two sources, then the merged list is newest first, with undated documents last and a stable tie-break.
2. Given the same `sourceDocumentId` twice from one source, then it appears once.
3. Given outcomes `OK` + `STALE`, then `partial` is true and `outcome` is `PARTIAL`. All `OK` gives `COMPLETE`. No `OK` or `STALE` gives `FAILED`.
4. Every document keeps its `source`.

**Tests first:** `ResultMergerTest.sortsNewestFirstUndatedLast`, `breaksTiesBySourceThenSourceDocumentId`, `dedupesWithinSourceOnly`, `derivesOutcomeAndPartialFromStatuses`, `keepsSourceStaleFlagAndEveryOutcome`

**Files:** `search/ResultMerger.java`, `search/model/*`

---

## P2 Source systems

### T07: Sales System adapter
Serves FR2, A3, A4, NFR5 · est 3 h · depends on T04, T05

**Done when**
1. Given the nested deals response, then each document becomes a `Document` with `id` `SALES:INV-1001`, a mapped `type` (`INV` → `SALES_INVOICE`, unknown → `OTHER`) and `createdOn` `14/03/2025` read as the start of that day in the configured dealership time zone, converted to UTC.
2. Given a document whose link isn't an `https` URL, then that item is dropped and the others are kept.
3. Given a response that can't be parsed, then `fetch` throws `SourceException` with reason `ERROR`.
4. Given 503 followed by 200, then the call succeeds after **one** retry. Given 503 twice, then it throws with reason `ERROR`, and WireMock saw exactly 2 requests.
5. Given a read longer than 1.5 s, then it throws with reason **`TIMEOUT`** and **isn't retried** (WireMock saw 1 request).

**Tests first:** `SalesSystemAdapterIT.mapsNestedDealsToDocuments`, `mapsUnknownTypeToOther`, `dropsNonHttpsLinks`, `throwsErrorOnUnreadableBody`, `retriesOnceOn503`, `throwsTimeoutAndDoesNotRetrySlowRead`, plus `RetryOnTransientErrorTest` (true for 502/503/504 and refused connections, false for timeouts)

**Decide in `/frame-task`:** does a 404 mean "no documents" or an error?

**From the T06 review:** `ResultMerger`'s tie-break needs a non-null `sourceDocumentId`. The brief should cover dropping an item with no ID (design doc §4.2).

**Design note:** the retry is Spring Framework 7's `@Retryable(includes = SourceException.class, predicate = RetryOnTransientError.class, maxRetries = 1, delay = 100)` on the adapter **class** (not `fetch`, where the JDK proxy misses it), with `@EnableResilientMethods` on a config class (see [`01-project-setup.md`](01-project-setup.md#configuration-applicationyml)). It works through a proxy, so it applies only when another bean calls `fetch`, as `DocumentSearchService` does. The adapter builds its own client with `SourceClientsConfig.restClient(...)`, so T08 needs no new bean, only its `application.yml` entry.

**Files:** `source/DocumentSource.java`, `source/SourceException.java`, `source/RetryOnTransientError.java`, `source/sales/*`, `config/SourceClientsConfig.java`

### T08: Service System adapter
Serves FR2, A4, NFR5 · est 2 h · depends on T07 (same pattern)

**Done when**
1. Given the flat records response, then `recordId` 778 becomes `SERVICE:778`, `completedAt` epoch seconds becomes a UTC `Instant`, and `category` maps as in the type table.
2. Criteria 2–5 from T07 hold for this adapter too.
3. **No other class changed to add it** (NFR5). The reviewer checks this.

**Tests first:** `ServiceSystemAdapterIT.mapsFlatRecordsToDocuments`, `convertsEpochSecondsToUtc`, plus the T07 failure tests for this source

**Files:** `source/service/*`

### T09: Parallel calls with a deadline
Serves FR2, NFR1, NFR2 · est 4 h · depends on T06, T07, T08

**Done when**
1. Given two sources that each take 1 s, then the search finishes in **under 1.5 s**. This proves the calls run in parallel.
2. Given the Service source takes 5 s, then the search returns within **2.5 s**. Sales is `OK` and Service is `UNAVAILABLE` with reason `TIMEOUT`.
3. Given one source throws, then the other source's documents are still returned. One failure never fails the whole search.
4. Given any mix of results, then every source ends as exactly one `SourceOutcome`, never an exception.

**Tests first:** `DocumentSearchServiceTest.callsSourcesInParallel`, `timesOutSlowSourceAt2s`, `oneFailureDoesNotHideOtherSource`, `everySourceEndsWithAResult` (unit tests with fake `DocumentSource`s and a fixed `Clock`). Then `DocumentSearchIT.serviceTimeoutGivesPartialResult` against WireMock.

**Design notes:**
- Use one task per source on a virtual-thread executor with `completeOnTimeout`, and turn each `SourceException` into `SourceOutcome.unavailable` with its reason.
- The adapter's read timeout (1.5 s) normally reports `TIMEOUT` first. The 2 s deadline is the backstop for anything slower, such as a fake source in the unit tests.
- Measure latency with `System.nanoTime()`.
- Timing assertions use the margins above: sequential calls would take at least 2 s.

**Files:** `search/DocumentSearchService.java`, `config/ExecutorConfig.java`

---

## P3 Persistence

### T10: Access audit (fails closed)
Serves NFR3, A10 · est 3 h · depends on T09 · details in [`04-data-model.md`](04-data-model.md)

**Done when**
1. Given any search that reached the sources, then exactly one audit row is written, with the caller, VIN, trace ID, outcome, per-source status and document count. That includes `FAILED` searches.
2. Given the audit insert fails, then the search throws `AuditUnavailableException` and **no documents are returned**.
3. Given an invalid VIN, then no audit row is written.

**Tests first:** `AuditRepositoryIT.savesOneRowPerSearch`, `DocumentSearchServiceTest.failsClosedWhenAuditFails`, `DocumentSearchServiceTest.auditsFailedSearches`

**Files:** `V1__init.sql` (both tables, as in `04-data-model.md`), `persistence/AuditRepository.java`, `search/AccessAuditor.java`

### T11: Fallback snapshots
Serves NFR2, A8 · est 3 h · depends on T10

**Done when**
1. Given a successful source call, then its documents are saved as a snapshot **in the background**, after the response.
2. Given Service fails and a 3-hour-old snapshot exists, then Service is `STALE`, its documents have `stale: true`, and `dataAsOf` is the snapshot time.
3. Given only a 25-hour-old snapshot, then Service is `UNAVAILABLE`.
4. Given saving the snapshot fails, then the response is unaffected and the error is logged.
5. Given an older save finishes after a newer one, then the newer copy is kept.
6. The cleanup job deletes snapshots older than 24 h.

**Tests first:** `SnapshotRepositoryIT.newerCopyWins`, `ignoresCopiesOlderThan24h`, `DocumentSearchServiceTest.servesStaleCopyWhenSourceFails`, `unavailableWhenCopyTooOld`, `snapshotSaveFailureDoesNotAffectResponse`, `SnapshotCleanupJobIT.deletesExpiredSnapshots`

**Files:** `persistence/SnapshotRepository.java` (the table is already in `V1__init.sql`, from T10), `persistence/SnapshotCleanupJob.java`

---

## P4 API

### T12: REST endpoint, errors and OpenAPI
Serves FR1, FR3, A1, A11 · est 3 h · depends on T11

**Done when**
1. Given demo VIN `…0001` and both identity headers, when I call `GET /api/v1/vehicles/{vin}/documents`, then it returns 200 with:
   - `vin`, `partial`
   - `documents[]`: `id`, `source`, `type`, `title`, `documentDate`, `url`, `stale`
   - `sources[]`: `source`, `status`, `reason`, `documentCount`, `latencyMs`, `dataAsOf`
2. Every demo VIN in [`01-project-setup.md`](01-project-setup.md#demo-vins-wiremock-stubs) gives the result in its table, end to end.
3. Errors:
   - an invalid VIN returns 400
   - a missing identity header returns 401
   - both sources down with no snapshot returns 503 with `sources[]`
   - an audit failure returns 503

   Each error is `application/problem+json`.
4. Every response has `X-Request-Id` and `Cache-Control: no-store`.
5. `/v3/api-docs` and `/swagger-ui.html` describe the endpoint, the error responses and the example.

**Tests first:** `DocumentControllerIT`, one test per demo VIN plus `returns400ForInvalidVin` (which also checks that no audit row is written, from T10), `returns401WithoutCaller`, `returns503WhenAuditFails`, `everyResponseHasRequestId`. These run against the full app with Testcontainers PostgreSQL and WireMock.

**Files:** `api/*`, `api/dto/*`

---

## P5 Observability

T13 (metrics, dashboard, alert rules), T14 (tracing) and T15 (structured logs) are specified in [`07-observability.md`](07-observability.md), with their acceptance criteria and tests. Est 2 h each. They depend on T12.

---

## P6 Delivery

### T16: README, demo and AI narrative
Serves the brief's deliverables · est 4 h · depends on T12 (and T13–T15 if they're done)

**Done when**
1. Given a fresh clone on a clean machine with Docker and Java 21, when I follow only the README, then I can build, run the tests and call the API.
2. The README has:
   - Build, Run, Test
   - API: a link to Swagger UI, and a cURL command per demo VIN
   - Design: a link to the design doc
   - **AI Collaboration Narrative:** strategy, how I verified the output, and examples from `docs/ai-log.md`
3. `docs/demo.md` scripts the video demo:
   1. `docker compose up`
   2. the happy path
   3. Service slow → partial
   4. Service down → retry and fallback
   5. both down → 503
   6. the trace in Jaeger and the Grafana dashboard
4. The red/green commit pairs are visible in `git log`.

**Design note:** `…0003` and `…0004` return 503 on every call, so the stack can never save a snapshot for them. The fallback demo step needs a seeded snapshot row or a temporary stub through WireMock's admin API.

---

## P7 CD and AWS (optional)

T17 (Docker image), T18 (Terraform) and T19 (deploy workflow) are outlined in [`08-cd-aws.md`](08-cd-aws.md). Only start them if P0–P6 are done and there's at least a day left.
