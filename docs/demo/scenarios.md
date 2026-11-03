# Demo scenarios

How to run the service locally and check its main behaviours by hand. You send requests from Swagger, watch the results in Grafana, and each request's trace in Jaeger. Prometheus is only for a few checks Grafana can't show.

Run the scenarios in order, because the counts add up as you go.

## 1. Set up: containers and server
You need Docker and JDK 21. Run these commands from the repository root.

1. Start from clean containers. This deletes the local audit rows and fallback copies.
   ```bash
   docker compose down -v
   ```
2. Start PostgreSQL, the two mock sources, Prometheus, Grafana and Jaeger. Without Jaeger, the server logs trace export warnings.
   ```bash
   docker compose up -d --wait
   ```
3. Start the server on port 8080: run `DocumentViewerApplication` in IntelliJ, or use the command below.
   ```bash
   ./mvnw spring-boot:run
   ```

## 2. Start everything
| Tool | Link | Used for |
|---|---|---|
| Swagger | http://localhost:8080/swagger-ui.html | Sending searches |
| Grafana | http://localhost:3000/d/document-viewer | Watching the results; no login needed |
| Prometheus | http://localhost:9090/query | Exact counts |
| Prometheus | http://localhost:9090/targets | Checking that `document-viewer` is `UP` |
| Prometheus | http://localhost:9090/alerts | Checking which alerts are firing |
| Jaeger | http://localhost:16686 | One request's trace |

**In Swagger:** expand `GET /api/v1/vehicles/{vin}/documents`, click **Try it out**, and enter `user-1` as `X-User-Id` and `dealer-1` as `X-Dealership-Id`.

**In Grafana:** the dashboard refreshes every 15 s and shows the last 30 minutes. Its graphs show rates, so look at which lines rise rather than their values. A single event may not draw a line, because a rate needs a counter to rise between two scrapes. That's why the scenarios repeat their requests.

**In Jaeger:** copy `x-request-id` from the response headers in Swagger and paste it into **Lookup by Trace ID**, or pick Service `document-viewer` and click **Find Traces**. Click a span to see its tags.

## 3. Request and source counts
Send the five demo VINs, wait about 20 s, then send them again.

| VIN | What the mocks do | Status | `partial` | Grafana: where it shows |
|---|---|---|---|---|
| `WVWZZZ1JZXW000001` | Both sources answer | 200 | `false` | **Searches per second, by outcome**: `complete` |
| `WVWZZZ1JZXW000002` | Service is too slow, so the search takes about 1.5 s | 200 | `true` | **Share of searches with partial results**; **Service calls per second, by result**: `timeout`; **Search response time, p95** rises |
| `WVWZZZ1JZXW000003` | Service returns 503 | 200 | `true` | **Share of searches with partial results**; **Service calls per second, by result**: `error` |
| `WVWZZZ1JZXW000004` | Both sources return 503 | 503 | not in the response | **Searches per second, by outcome**: `failed`; **Sales calls per second, by result**: `error` |
| `WVWZZZ1JZXW000005` | Both sources answer with no documents | 200 | `false` | **Searches per second, by outcome**: `complete` |

These two requests are rejected before any search runs, so Grafana shows nothing for them:

| Request | Status |
|---|---|
| `WVWZZZ1JZXW000001` with both headers blank | 401 |
| VIN `BAD` with both headers filled in | 400 |

**Traces (Jaeger):** open each search's trace.

| VIN | What the trace shows |
|---|---|
| `WVWZZZ1JZXW000001` | `sales.fetch` and `service.fetch` overlap, so they ran in parallel. Each has an `http get` child, then comes `audit.save`. Tags: `result=ok`, `document_count=2`, masked `vin` |
| `WVWZZZ1JZXW000002` | `service.fetch` takes about 1.5 s with `result=timeout`, and its `http get` is red. Then `fallback.read` (`source=SERVICE`) and `audit.save` |
| `WVWZZZ1JZXW000003` | `service.fetch` has `result=error` and two red `http get` children: the 503 and its retry. Then `fallback.read` |
| `WVWZZZ1JZXW000004` | Both fetches have `result=error`, each with two red `http get`. Then two `fallback.read` (`SALES` and `SERVICE`) and `audit.save`. The request span has `status=503` |
| `WVWZZZ1JZXW000005` | Both fetches have `result=ok` and `document_count=0` |
| The 401 and 400 | Only the request span, because no search ran |

**Check on any trace:**
- The VIN only shows masked, such as `WVW**********0001`, and there is no `http.url` tag.
- A red span's error names only an exception class, such as `ResourceAccessException`, never a URL.
- In IntelliJ's console, the `traceId` on the `documents.search completed` line equals `x-request-id`.

**Exact counts (Prometheus):** paste this into the [query page](http://localhost:9090/query) and open the **Table** tab. Results can lag by up to 15 s.
```text
{__name__=~"docviewer_(requests|source_calls|audit_failures)_total"}
```

Expected after the two rounds. The query also lists results that haven't happened yet, such as `circuit_open`, at 0.

| Metric | Labels | Count |
|---|---|---|
| `docviewer_requests_total` | `outcome="complete"`, `status="200"` | 4 |
| `docviewer_requests_total` | `outcome="partial"`, `status="200"` | 4 |
| `docviewer_requests_total` | `outcome="failed"`, `status="503"` | 2 |
| `docviewer_source_calls_total` | `source="SALES"`, `result="ok"` | 8 |
| `docviewer_source_calls_total` | `source="SALES"`, `result="error"` | 2 |
| `docviewer_source_calls_total` | `source="SERVICE"`, `result="ok"` | 4 |
| `docviewer_source_calls_total` | `source="SERVICE"`, `result="timeout"` | 2 |
| `docviewer_source_calls_total` | `source="SERVICE"`, `result="error"` | 4 |
| `docviewer_audit_failures_total` | none | 0 |

## 4. Failed audit
A search that can't be audited must not return documents, even when both sources are healthy. The Jaeger step here wasn't checked live yet.

1. Stop the database.
   ```bash
   docker compose stop postgres
   ```
2. In Swagger, search for `WVWZZZ1JZXW000001`, wait about 20 s, then search again. Both return **503**, with no documents.
3. In Grafana, check that **Failed audit saves, last 5 minutes** rises and **Searches per second, by outcome** shows `failed`.
4. In Jaeger, open a 503's trace. Both fetches have `result=ok`, then `audit.save` is red, with only an exception class as its error.
5. **Alert (Prometheus):** after about 30 s, the [alerts page](http://localhost:9090/alerts) shows `AuditWriteFailing` and `AvailabilityTargetMissed` firing.
6. Start the database again.
   ```bash
   docker compose up -d --wait postgres
   ```
7. In Swagger, search for `WVWZZZ1JZXW000001` again. It returns **200** with `partial: false`, and this search saves the fallback copies for the next scenario.

The alerts stay firing after the database returns, until their time windows pass: 5 minutes for `AuditWriteFailing` and 30 minutes for `AvailabilityTargetMissed`. `LatencyTargetMissed` and `SourceDegraded` may show as pending, because they must stay true for 10 and 5 minutes before they fire.

## 5. Fallback copy and open breaker
When a source fails, the service serves its saved copy. After enough failures, the breaker opens and the service stops calling that source for 30 s. The Jaeger steps here weren't checked live yet.

### Fallback copy
1. Wait a few seconds after the last search, then stop the Service mock.
   ```bash
   docker compose stop service-api
   ```
2. In Swagger, search for `WVWZZZ1JZXW000001`, wait about 20 s, then search again. Each returns **200** with `partial: true`, and the `SERVICE` entry in `sources` shows:
   ```json
   { "source": "SERVICE", "status": "STALE", "reason": "ERROR", "documentCount": 2, "dataAsOf": "<time of the saved copy>" }
   ```
3. In Grafana, check that **Service fallback copies served per second** rises.
4. In Jaeger, `service.fetch` has `result=error`, with two red `http get` (the refused connection is retried once), then `fallback.read` with `source=SERVICE`.

### Open breaker
The breaker needs at least 10 calls in 60 s, with at least half failing. To save clicks, stay on the Swagger page, press `F12`, open **Console**, and run this loop. Chrome may ask you to type `allow pasting` first.
```js
for (let i = 0; i < 15; i++) {
  const r = await fetch('/api/v1/vehicles/WVWZZZ1JZXW000001/documents',
    { headers: { 'X-User-Id': 'user-1', 'X-Dealership-Id': 'dealer-1' } });
  console.log(i + 1, r.status);
}
```

1. Check that the Console prints 15 lines ending in `200`. Users still get a result while the source is down.
2. In Swagger, search for `WVWZZZ1JZXW000001` straight away. The `SERVICE` entry now has `"reason": "CIRCUIT_OPEN"`, which means no call was made. If it still shows `ERROR`, more than 30 s have passed, so run the loop again and search straight away.
3. In Grafana, check that the `circuit_open` line in **Service calls per second, by result** rises above 0 within about 30 s. The graph is a 5-minute average, so expect small values, around 0.02 req/s.
4. In Jaeger, open the search from step 2. `service.fetch` has `result=circuit_open`, takes about 0 ms and has no `http get` child, because no call was made. `fallback.read` still runs.

### Recovery
1. Start the Service mock again.
   ```bash
   docker compose start service-api
   ```
2. Wait about 35 s, so the breaker's 30 s open period has passed.
3. Run the Console loop again, changing `15` to `5`.
4. In Swagger, search for `WVWZZZ1JZXW000001`. It returns **200** with `partial: false`, and `SERVICE` has `"status": "OK"`.
5. In Grafana, check that **Service calls per second, by result** shows `ok` rising. The `circuit_open` line fades over the next 5 minutes, because the graph averages over that window.
6. In Jaeger, open the search from step 4. `service.fetch` is back to `result=ok`, with one `http get` child.

## 6. Clean up
Stop the server, then remove the containers and their data.
```bash
docker compose down -v
```
