package io.github.hinn1411.docviewer.observability;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import io.github.hinn1411.docviewer.TestcontainersConfiguration;
import io.github.hinn1411.docviewer.domain.Document;
import io.github.hinn1411.docviewer.domain.DocumentType;
import io.github.hinn1411.docviewer.domain.Snapshot;
import io.github.hinn1411.docviewer.domain.Vin;
import io.github.hinn1411.docviewer.persistence.AuditRepository;
import io.github.hinn1411.docviewer.persistence.SnapshotRepository;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.web.client.RestClient;

/** T13: the whole app's metrics, read from the registry and from a real Prometheus scrape. */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@AutoConfigureMetrics
class MetricsIT {

  private static final String VIN_COMPLETE = "WVWZZZ1JZXW000001";
  private static final String VIN_SERVICE_SLOW = "WVWZZZ1JZXW000002";
  private static final String VIN_BOTH_DOWN = "WVWZZZ1JZXW000004";
  private static final String SOURCE_CALLS = "docviewer.source.calls";
  private static final String REQUESTS = "docviewer.requests";

  @RegisterExtension
  static WireMockExtension salesApi =
      WireMockExtension.newInstance()
          .options(wireMockConfig().dynamicPort().usingFilesUnderDirectory("mocks/sales"))
          .build();

  @RegisterExtension
  static WireMockExtension serviceApi =
      WireMockExtension.newInstance()
          .options(wireMockConfig().dynamicPort().usingFilesUnderDirectory("mocks/service"))
          .build();

  @DynamicPropertySource
  static void baseUrls(DynamicPropertyRegistry registry) {
    registry.add("docviewer.sources.sales.base-url", salesApi::baseUrl);
    registry.add("docviewer.sources.service.base-url", serviceApi::baseUrl);
  }

  @LocalServerPort int port;

  @Autowired MeterRegistry registry;
  @Autowired SnapshotRepository snapshotRepository;
  @Autowired CircuitBreakerRegistry breakers;

  @MockitoSpyBean AuditRepository auditRepository;

  @BeforeEach
  void closeBreakers() {
    breakers.getAllCircuitBreakers().forEach(CircuitBreaker::reset);
  }

  @Test
  void countsPartialSearchWhenSourceTimesOut() {
    double timeoutsBefore = count(SOURCE_CALLS, "source", "SERVICE", "result", "timeout");
    double partialBefore = count(REQUESTS, "outcome", "partial", "status", "200");

    search(VIN_SERVICE_SLOW);

    assertThat(count(SOURCE_CALLS, "source", "SERVICE", "result", "timeout"))
        .isEqualTo(timeoutsBefore + 1);
    assertThat(count(REQUESTS, "outcome", "partial", "status", "200")).isEqualTo(partialBefore + 1);
  }

  @Test
  void exposesAllSixMetrics() {
    saveServiceCopy(VIN_BOTH_DOWN);
    search(VIN_BOTH_DOWN);

    assertThat(scrape())
        .contains(
            "docviewer_requests_total",
            "docviewer_request_duration_seconds",
            "docviewer_source_calls_total",
            "docviewer_source_duration_seconds",
            "docviewer_fallback_served_total",
            "docviewer_audit_failures_total",
            "http_client_requests_seconds",
            "le=\"0.25\"",
            "le=\"2.5\"")
        .doesNotContain(VIN_BOTH_DOWN);
  }

  @Test
  void countsFailedAuditAsFailedRequest() {
    double failuresBefore = count("docviewer.audit.failures");
    double requestsBefore = count(REQUESTS, "outcome", "failed", "status", "503");
    doThrow(new DataAccessResourceFailureException("database down"))
        .when(auditRepository)
        .save(any());

    search(VIN_COMPLETE);

    assertThat(count("docviewer.audit.failures")).isEqualTo(failuresBefore + 1);
    assertThat(count(REQUESTS, "outcome", "failed", "status", "503")).isEqualTo(requestsBefore + 1);
  }

  @Test
  void countsOpenBreakerAsCircuitOpen() {
    double before = count(SOURCE_CALLS, "source", "SERVICE", "result", "circuit_open");
    breakers.circuitBreaker("SERVICE").transitionToOpenState();

    search(VIN_COMPLETE);

    assertThat(count(SOURCE_CALLS, "source", "SERVICE", "result", "circuit_open"))
        .isEqualTo(before + 1);
  }

  private double count(String name, String... tags) {
    Counter counter = registry.find(name).tags(tags).counter();
    return counter == null ? 0 : counter.count();
  }

  private void saveServiceCopy(String vin) {
    Document copy =
        new Document(
            "SERVICE",
            "1",
            DocumentType.JOB_CARD,
            "Saved job card",
            null,
            URI.create("https://service.example.com/files/1.pdf"),
            false);
    snapshotRepository.save(new Snapshot(Vin.parse(vin), "SERVICE", List.of(copy), Instant.now()));
  }

  private void search(String vin) {
    RestClient.create("http://localhost:" + port)
        .get()
        .uri("/api/v1/vehicles/" + vin + "/documents")
        .header("X-User-Id", "user-1")
        .header("X-Dealership-Id", "dealer-1")
        .retrieve()
        .onStatus(status -> true, (request, errorResponse) -> {})
        .toBodilessEntity();
  }

  private String scrape() {
    return RestClient.create("http://localhost:" + port)
        .get()
        .uri("/actuator/prometheus")
        .retrieve()
        .body(String.class);
  }
}
