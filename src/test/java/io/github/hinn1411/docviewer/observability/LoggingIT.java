package io.github.hinn1411.docviewer.observability;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import io.github.hinn1411.docviewer.TestcontainersConfiguration;
import io.github.hinn1411.docviewer.persistence.AuditRepository;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** T15: what the whole app writes to the console, against a real PostgreSQL and the demo stubs. */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class LoggingIT {

  private static final String VIN_COMPLETE = "WVWZZZ1JZXW000001";
  private static final String VIN_SERVICE_SLOW = "WVWZZZ1JZXW000002";
  private static final String VIN_SERVICE_500 = "WVWZZZ1JZXW000007";
  private static final List<String> DEMO_VINS =
      List.of(
          VIN_COMPLETE,
          VIN_SERVICE_SLOW,
          "WVWZZZ1JZXW000003",
          "WVWZZZ1JZXW000004",
          "WVWZZZ1JZXW000005");

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

  @Autowired CircuitBreakerRegistry breakers;

  @MockitoSpyBean AuditRepository auditRepository;

  @BeforeEach
  void closeBreakers() {
    breakers.getAllCircuitBreakers().forEach(CircuitBreaker::reset);
  }

  @Test
  void writesOneJsonSummaryPerRequest(CapturedOutput output) {
    ResponseEntity<String> response = search(VIN_SERVICE_SLOW);

    List<JsonNode> summaries = summariesIn(output);
    assertThat(summaries).hasSize(1);
    JsonNode summary = summaries.getFirst();
    assertThat(summary.get("level").asString()).isEqualTo("INFO");
    assertThat(summary.get("userId").asString()).isEqualTo("user-1");
    assertThat(summary.get("dealershipId").asString()).isEqualTo("dealer-1");
    assertThat(summary.get("vin").asString()).isEqualTo("WVW**********0002");
    assertThat(summary.get("httpStatus").asInt()).isEqualTo(200);
    assertThat(summary.get("outcome").asString()).isEqualTo("PARTIAL");
    assertThat(summary.get("durationMs").isNumber()).isTrue();
    assertThat(summary.get("traceId").asString())
        .isEqualTo(response.getHeaders().getFirst("X-Request-Id"));
    assertThat(summary.get("sources"))
        .hasSize(2)
        .anySatisfy(
            source -> {
              assertThat(source.get("source").asString()).isEqualTo("SERVICE");
              assertThat(source.get("status").asString()).isEqualTo("UNAVAILABLE");
              assertThat(source.get("reason").asString()).isEqualTo("TIMEOUT");
              assertThat(source.get("latencyMs").isNumber()).isTrue();
              assertThat(source.get("documentCount").asInt()).isZero();
            });
  }

  @Test
  void neverLogsFullVin(CapturedOutput output) {
    DEMO_VINS.forEach(this::search);

    assertThat(summariesIn(output)).hasSize(DEMO_VINS.size());
    assertThat(output.toString())
        .contains("WVW**********0001")
        .doesNotContain(DEMO_VINS.toArray(String[]::new))
        .doesNotContain(".example.com");
  }

  @Test
  void logsSourceFailureAtWarn(CapturedOutput output) {
    serviceApi.stubFor(
        WireMock.get(urlPathEqualTo("/service/api/vehicles/" + VIN_SERVICE_500 + "/records"))
            .willReturn(aResponse().withStatus(500).withBody("secret-response-body")));

    search(VIN_SERVICE_500);

    List<JsonNode> warnings =
        jsonLinesIn(output).stream()
            .filter(line -> line.get("level").asString().equals("WARN"))
            .filter(line -> line.path("source").asString("").equals("SERVICE"))
            .toList();
    assertThat(warnings).hasSize(1);
    assertThat(warnings.getFirst().get("reason").asString()).isEqualTo("ERROR");
    assertThat(output.toString()).doesNotContain("secret-response-body");
  }

  @Test
  void logsAuditFailureWithClassOnly(CapturedOutput output) {
    // PostgreSQL's error detail can contain the whole row, including the VIN.
    doThrow(new DataAccessResourceFailureException("Failing row contains " + VIN_COMPLETE))
        .when(auditRepository)
        .save(any());

    search(VIN_COMPLETE);

    List<JsonNode> errors =
        jsonLinesIn(output).stream()
            .filter(line -> line.get("level").asString().equals("ERROR"))
            .toList();
    assertThat(errors).hasSize(1);
    assertThat(errors.getFirst().get("failure").asString())
        .endsWith("DataAccessResourceFailureException");
    assertThat(summariesIn(output)).isEmpty();
    assertThat(output.toString()).doesNotContain(VIN_COMPLETE, "Failing row");
  }

  @Test
  void logsRejectedRequestWithoutVin(CapturedOutput output) {
    search("NOT-A-VIN");

    List<JsonNode> rejected =
        jsonLinesIn(output).stream()
            .filter(line -> line.get("message").asString().equals("Request rejected"))
            .toList();
    assertThat(rejected).hasSize(1);
    assertThat(rejected.getFirst().get("type").asString()).isEqualTo("invalid-vin");
    assertThat(rejected.getFirst().get("httpStatus").asInt()).isEqualTo(400);
    assertThat(output.toString()).doesNotContain("NOT-A-VIN");
  }

  private ResponseEntity<String> search(String vin) {
    return RestClient.create("http://localhost:" + port)
        .get()
        .uri("/api/v1/vehicles/" + vin + "/documents")
        .header("X-User-Id", "user-1")
        .header("X-Dealership-Id", "dealer-1")
        .retrieve()
        .onStatus(status -> true, (request, errorResponse) -> {})
        .toEntity(String.class);
  }

  private static List<JsonNode> summariesIn(CapturedOutput output) {
    return jsonLinesIn(output).stream()
        .filter(line -> line.get("message").asString().equals("documents.search completed"))
        .toList();
  }

  private static List<JsonNode> jsonLinesIn(CapturedOutput output) {
    return output
        .getOut()
        .lines()
        .filter(line -> line.startsWith("{"))
        .map(JsonMapper.shared()::readTree)
        .toList();
  }
}
