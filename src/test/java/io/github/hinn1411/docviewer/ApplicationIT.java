package io.github.hinn1411.docviewer;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.client.RestClient;

/** T01: the scaffold starts against a real PostgreSQL, reports healthy and creates trace IDs. */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class ApplicationIT {

  @LocalServerPort int port;

  @Autowired JdbcClient jdbcClient;

  @Autowired Flyway flyway;

  @Autowired Tracer tracer;

  @Test
  void contextLoadsWithMigrations() {
    // Flyway ran at startup against PostgreSQL (no migrations yet: the first one arrives in T10).
    assertThat(flyway.info().pending()).isEmpty();
    String version = jdbcClient.sql("select version()").query(String.class).single();
    assertThat(version).startsWith("PostgreSQL 16");
  }

  @Test
  void healthIsUp() {
    ResponseEntity<String> response =
        RestClient.create("http://localhost:" + port)
            .get()
            .uri("/actuator/health")
            .retrieve()
            .toEntity(String.class);

    assertThat(response.getStatusCode().value()).isEqualTo(200);
    assertThat(response.getBody()).contains("\"status\":\"UP\"");
  }

  @Test
  void tracerCreatesTraceIds() {
    // T10 and T12 use the trace ID as the request ID, so tracing must exist from T01.
    Span span = tracer.nextSpan().name("t01-check").start();
    try {
      assertThat(span.context().traceId()).matches("[0-9a-f]{32}");
    } finally {
      span.end();
    }
  }
}
