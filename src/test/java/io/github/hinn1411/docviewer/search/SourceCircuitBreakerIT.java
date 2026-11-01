package io.github.hinn1411.docviewer.search;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.notFound;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.serverError;
import static com.github.tomakehurst.wiremock.client.WireMock.serviceUnavailable;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import io.github.hinn1411.docviewer.config.client.SourceClientsConfig;
import io.github.hinn1411.docviewer.config.execution.ExecutorConfig;
import io.github.hinn1411.docviewer.config.time.ClockConfig;
import io.github.hinn1411.docviewer.domain.Caller;
import io.github.hinn1411.docviewer.domain.DocumentSearchResult;
import io.github.hinn1411.docviewer.domain.FailureReason;
import io.github.hinn1411.docviewer.domain.ResultMerger;
import io.github.hinn1411.docviewer.domain.SourceOutcome;
import io.github.hinn1411.docviewer.domain.Vin;
import io.github.hinn1411.docviewer.persistence.SnapshotRepository;
import io.github.hinn1411.docviewer.search.support.audit.AccessAuditor;
import io.github.hinn1411.docviewer.search.support.fallback.SnapshotFallback;
import io.github.hinn1411.docviewer.source.adapter.sales.SalesSystemAdapter;
import io.github.hinn1411.docviewer.source.adapter.service.ServiceSystemAdapter;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** T12b: the Service breaker reacts to what the real adapter reports, with a low call minimum. */
@SpringBootTest(
    classes = {
      SourceClientsConfig.class,
      ExecutorConfig.class,
      SalesSystemAdapter.class,
      ServiceSystemAdapter.class,
      ResultMerger.class,
      ClockConfig.class,
      SnapshotFallback.class,
      DocumentSearchService.class
    },
    webEnvironment = WebEnvironment.NONE,
    properties = {
      "docviewer.circuit-breaker.minimum-calls=4",
      // Fast timeouts, under the slow-call limit, so only the timeout rule can open the breaker.
      "docviewer.sources.service.read-timeout=200ms"
    })
class SourceCircuitBreakerIT {

  private static final Vin VIN = Vin.parse("WVWZZZ1JZXW000099");
  private static final Caller CALLER = new Caller("user-1", "dealer-1");
  private static final String SERVICE_PATH = "/service/api/vehicles/" + VIN.value() + "/records";

  @RegisterExtension
  static WireMockExtension salesApi =
      WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

  @RegisterExtension
  static WireMockExtension serviceApi =
      WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

  @DynamicPropertySource
  static void baseUrls(DynamicPropertyRegistry registry) {
    registry.add("docviewer.sources.sales.base-url", salesApi::baseUrl);
    registry.add("docviewer.sources.service.base-url", serviceApi::baseUrl);
  }

  // The audit and the copies have their own tests, and this test needs no database.
  @MockitoBean AccessAuditor auditor;

  @MockitoBean SnapshotRepository snapshotRepository;

  @Autowired DocumentSearchService service;
  @Autowired CircuitBreakerRegistry breakers;

  @BeforeEach
  void salesAnswersAndBreakersAreClosed() {
    salesApi.stubFor(
        get(urlPathEqualTo("/sales/v1/deals"))
            .willReturn(okJson("{\"vin\":\"" + VIN.value() + "\",\"deals\":[]}")));
    breakers.getAllCircuitBreakers().forEach(CircuitBreaker::reset);
  }

  @Test
  void opensAfterRepeated503s() {
    serviceAnswers(serviceUnavailable());

    searchTimes(4);

    assertThat(serviceBreaker().getState()).isEqualTo(CircuitBreaker.State.OPEN);
    serviceApi.resetRequests();
    assertThat(serviceOutcome(service.search(VIN, CALLER)).reason())
        .isEqualTo(FailureReason.CIRCUIT_OPEN);
    serviceApi.verify(0, anyRequestedFor(anyUrl()));
  }

  static Stream<ResponseDefinitionBuilder> sourceFailures() {
    return Stream.of(
        serverError(),
        aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER),
        okJson("[]").withFixedDelay(1_000));
  }

  @ParameterizedTest
  @MethodSource("sourceFailures")
  void opensWhenSourceKeepsFailing(ResponseDefinitionBuilder failure) {
    serviceAnswers(failure);

    searchTimes(4);

    assertThat(serviceBreaker().getState()).isEqualTo(CircuitBreaker.State.OPEN);
  }

  @Test
  void doesNotOpenOnUnreadableResponses() {
    serviceAnswers(okJson("not json"));

    searchTimes(6);

    assertThat(serviceBreaker().getMetrics().getNumberOfFailedCalls()).isZero();
    assertThat(serviceBreaker().getState()).isEqualTo(CircuitBreaker.State.CLOSED);
  }

  @Test
  void doesNotOpenOn404s() {
    serviceAnswers(notFound());

    searchTimes(6);

    // The breaker saw every call, so it stayed closed because a 404 doesn't count as a failure.
    assertThat(serviceBreaker().getMetrics().getNumberOfBufferedCalls()).isEqualTo(6);
    assertThat(serviceBreaker().getMetrics().getNumberOfFailedCalls()).isZero();
    assertThat(serviceBreaker().getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    assertThat(serviceOutcome(service.search(VIN, CALLER)).reason()).isEqualTo(FailureReason.ERROR);
  }

  @Test
  void closesAfterSourceRecovers() {
    serviceBreaker().transitionToOpenState();
    serviceBreaker().transitionToHalfOpenState();
    serviceAnswers(okJson("[]"));

    searchTimes(3);

    assertThat(serviceBreaker().getState()).isEqualTo(CircuitBreaker.State.CLOSED);
  }

  private CircuitBreaker serviceBreaker() {
    return breakers.circuitBreaker("SERVICE");
  }

  private void serviceAnswers(ResponseDefinitionBuilder response) {
    serviceApi.stubFor(get(urlPathEqualTo(SERVICE_PATH)).willReturn(response));
  }

  private void searchTimes(int times) {
    for (int i = 0; i < times; i++) {
      service.search(VIN, CALLER);
    }
  }

  private static SourceOutcome serviceOutcome(DocumentSearchResult result) {
    return result.sources().stream()
        .filter(outcome -> outcome.source().equals("SERVICE"))
        .findFirst()
        .orElseThrow();
  }
}
