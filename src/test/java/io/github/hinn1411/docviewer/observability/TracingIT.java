package io.github.hinn1411.docviewer.observability;

import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import io.github.hinn1411.docviewer.TestcontainersConfiguration;
import io.github.hinn1411.docviewer.domain.Vin;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.data.SpanData;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.tracing.test.autoconfigure.AutoConfigureTracing;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

/** T14: one trace per search, read from an in-memory exporter instead of Jaeger. */
@SpringBootTest(
    webEnvironment = WebEnvironment.RANDOM_PORT,
    properties = {
      "management.tracing.export.otlp.enabled=false", // spans go to memory, never to port 4318
      "management.opentelemetry.tracing.export.schedule-delay=50ms"
    })
@Import({TestcontainersConfiguration.class, TracingIT.SpanCapture.class})
@AutoConfigureTracing
class TracingIT {

  private static final String VIN_COMPLETE = "WVWZZZ1JZXW000001";
  private static final String VIN_SERVICE_SLOW = "WVWZZZ1JZXW000002";
  private static final AttributeKey<String> SOURCE = AttributeKey.stringKey("source");
  private static final AttributeKey<String> RESULT = AttributeKey.stringKey("result");
  private static final AttributeKey<String> DOCUMENT_COUNT =
      AttributeKey.stringKey("document_count");
  private static final AttributeKey<String> VIN = AttributeKey.stringKey("vin");

  @TestConfiguration
  static class SpanCapture {
    @Bean
    InMemorySpanExporter inMemorySpanExporter() {
      return InMemorySpanExporter.create();
    }
  }

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

  @Autowired InMemorySpanExporter exporter;
  @Autowired JdbcClient jdbcClient;
  @Autowired CircuitBreakerRegistry breakers;

  @BeforeEach
  void reset() {
    exporter.reset();
    breakers.getAllCircuitBreakers().forEach(CircuitBreaker::reset);
  }

  @Test
  void sourceSpansShareTraceAndOverlap() {
    String traceId = search(VIN_COMPLETE);

    List<SpanData> spans = spansOf(traceId);
    SpanData request = only(spans, SpanKind.SERVER);
    SpanData sales = named(spans, "sales.fetch");
    SpanData service = named(spans, "service.fetch");

    assertThat(sales.getParentSpanId()).isEqualTo(request.getSpanId());
    assertThat(service.getParentSpanId()).isEqualTo(request.getSpanId());
    // Each started before the other ended, so they ran in parallel
    assertThat(sales.getStartEpochNanos()).isLessThan(service.getEndEpochNanos());
    assertThat(service.getStartEpochNanos()).isLessThan(sales.getEndEpochNanos());
    for (SpanData source : List.of(sales, service)) {
      assertThat(spans)
          .anySatisfy(
              call -> {
                assertThat(call.getKind()).isEqualTo(SpanKind.CLIENT);
                assertThat(call.getParentSpanId()).isEqualTo(source.getSpanId());
              });
    }
  }

  @Test
  void requestIdEqualsTraceId() {
    String requestId = search(VIN_COMPLETE);

    assertThat(only(spansOf(requestId), SpanKind.SERVER).getTraceId()).isEqualTo(requestId);
    assertThat(
            jdbcClient
                .sql("SELECT count(*) FROM document_access_audit WHERE trace_id = ?")
                .param(requestId)
                .query(Integer.class)
                .single())
        .isEqualTo(1);
  }

  @Test
  void propagatesTraceparentToSources() {
    String traceId = search(VIN_COMPLETE);

    String traceparent = "00-" + traceId + "-[0-9a-f]{16}-[0-9a-f]{2}";
    salesApi.verify(
        getRequestedFor(urlPathEqualTo("/sales/v1/deals"))
            .withHeader("traceparent", matching(traceparent)));
    serviceApi.verify(
        getRequestedFor(urlPathEqualTo("/service/api/vehicles/" + VIN_COMPLETE + "/records"))
            .withHeader("traceparent", matching(traceparent)));
  }

  /**
   * Service times out, so its HTTP client span carries an error, which Spring's message says has
   * the URL.
   */
  @Test
  void spansContainOnlyMaskedVin() {
    String traceId = search(VIN_SERVICE_SLOW);

    List<SpanData> spans = spansOf(traceId);
    SpanData service = named(spans, "service.fetch");
    assertThat(service.getAttributes().get(SOURCE)).isEqualTo("SERVICE");
    assertThat(service.getAttributes().get(RESULT)).isEqualTo("timeout");
    assertThat(service.getAttributes().get(DOCUMENT_COUNT)).isEqualTo("0");
    assertThat(service.getAttributes().get(VIN)).isEqualTo(Vin.parse(VIN_SERVICE_SLOW).masked());
    assertThat(named(spans, "sales.fetch").getAttributes().get(RESULT)).isEqualTo("ok");
    named(spans, "fallback.read");
    named(spans, "audit.save");

    for (SpanData span : spans) {
      assertThat(span.getName()).doesNotContain(VIN_SERVICE_SLOW);
      assertThat(span.getStatus().getDescription()).doesNotContain(VIN_SERVICE_SLOW);
      assertThat(span.getAttributes().asMap().keySet())
          .extracting(AttributeKey::getKey)
          .doesNotContain("http.url");
      assertThat(span.getAttributes().asMap().values())
          .allSatisfy(value -> assertThat(value.toString()).doesNotContain(VIN_SERVICE_SLOW));
      assertThat(span.getEvents())
          .allSatisfy(
              event ->
                  assertThat(event.getAttributes().asMap().values())
                      .allSatisfy(
                          value -> assertThat(value.toString()).doesNotContain(VIN_SERVICE_SLOW)));
    }
  }

  /** Returns the {@code X-Request-Id}, which should be the trace ID. */
  private String search(String vin) {
    return RestClient.create("http://localhost:" + port)
        .get()
        .uri("/api/v1/vehicles/" + vin + "/documents")
        .header("X-User-Id", "user-1")
        .header("X-Dealership-Id", "dealer-1")
        .retrieve()
        .onStatus(status -> true, (request, errorResponse) -> {})
        .toBodilessEntity()
        .getHeaders()
        .getFirst("X-Request-Id");
  }

  /** Waits for the request span, which ends last, then returns every span in its trace. */
  private List<SpanData> spansOf(String traceId) {
    await()
        .atMost(Duration.ofSeconds(5))
        .until(() -> spansIn(traceId).stream().anyMatch(span -> span.getKind() == SpanKind.SERVER));
    return spansIn(traceId);
  }

  private List<SpanData> spansIn(String traceId) {
    return exporter.getFinishedSpanItems().stream()
        .filter(span -> span.getTraceId().equals(traceId))
        .toList();
  }

  private static SpanData only(List<SpanData> spans, SpanKind kind) {
    return assertThat(spans).filteredOn(span -> span.getKind() == kind).singleElement().actual();
  }

  private static SpanData named(List<SpanData> spans, String name) {
    return assertThat(spans)
        .filteredOn(span -> span.getName().equals(name))
        .as("span %s", name)
        .singleElement()
        .actual();
  }
}
