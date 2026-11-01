package io.github.hinn1411.docviewer.api;

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
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** T12: the whole app over HTTP, against a real PostgreSQL and the demo stubs from 01. */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class DocumentControllerIT {

  private static final String VIN_COMPLETE = "WVWZZZ1JZXW000001";
  private static final String VIN_BOTH_DOWN = "WVWZZZ1JZXW000004";
  private static final String USER_ID = "X-User-Id";
  private static final String DEALERSHIP_ID = "X-Dealership-Id";
  private static final String[] CALLER = {USER_ID, "user-1", DEALERSHIP_ID, "dealer-1"};

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

  @Autowired JdbcClient jdbcClient;
  @Autowired SnapshotRepository snapshotRepository;

  @MockitoSpyBean AuditRepository auditRepository;

  @BeforeEach
  void clearTables() {
    jdbcClient.sql("DELETE FROM source_snapshot").update();
    jdbcClient.sql("DELETE FROM document_access_audit").update();
  }

  @Test
  void returnsCompleteResultForVin1() {
    ResponseEntity<String> response = get(documentsOf(VIN_COMPLETE), CALLER);

    assertThat(response.getStatusCode().value()).isEqualTo(200);
    JsonNode body = json(response);
    assertThat(body.get("vin").asString()).isEqualTo(VIN_COMPLETE);
    assertThat(body.get("partial").asBoolean()).isFalse();
    assertThat(body.get("documents")).hasSize(4);

    JsonNode newest = body.get("documents").get(0);
    assertThat(newest.get("id").asString()).isEqualTo("SERVICE:779");
    assertThat(newest.get("source").asString()).isEqualTo("SERVICE");
    assertThat(newest.get("type").asString()).isEqualTo("MOT_CERTIFICATE");
    assertThat(newest.get("title").asString()).isEqualTo("MOT certificate");
    assertThat(newest.get("documentDate").asString()).isEqualTo("2026-09-30T09:45:00Z");
    assertThat(newest.get("url").asString()).isEqualTo("https://service.example.com/files/779.pdf");
    assertThat(newest.get("stale").asBoolean()).isFalse();

    JsonNode service = sourceIn(body, "SERVICE");
    assertThat(service.get("status").asString()).isEqualTo("OK");
    assertThat(service.get("reason").isNull()).isTrue();
    assertThat(service.get("documentCount").asInt()).isEqualTo(2);
    assertThat(service.get("latencyMs").isNumber()).isTrue();
    assertThat(service.get("dataAsOf").isNull()).isTrue();

    assertThat(auditCallerOf(VIN_COMPLETE)).containsExactly("user-1 dealer-1 COMPLETE");
  }

  @ParameterizedTest
  @CsvSource(
      nullValues = "none",
      value = {
        "WVWZZZ1JZXW000002, true,  UNAVAILABLE, TIMEOUT",
        "WVWZZZ1JZXW000003, true,  UNAVAILABLE, ERROR",
        "WVWZZZ1JZXW000005, false, OK,          none"
      })
  void demoVinsGiveExpectedResult(
      String vin, boolean partial, String serviceStatus, String serviceReason) {
    ResponseEntity<String> response = get(documentsOf(vin), CALLER);

    assertThat(response.getStatusCode().value()).isEqualTo(200);
    JsonNode body = json(response);
    assertThat(body.get("partial").asBoolean()).isEqualTo(partial);
    JsonNode service = sourceIn(body, "SERVICE");
    assertThat(service.get("status").asString()).isEqualTo(serviceStatus);
    assertThat(service.get("reason").asString(null)).isEqualTo(serviceReason);
    assertThat(sourceIn(body, "SALES").get("status").asString()).isEqualTo("OK");
    assertThat(body.get("documents").isEmpty()).isEqualTo(!partial);
  }

  @Test
  void returnsStaleCopyForVin4WhenCopyExists() {
    Instant savedAt = Instant.now().minus(Duration.ofHours(3)).truncatedTo(ChronoUnit.SECONDS);
    Document copy =
        new Document(
            "SERVICE",
            "1",
            DocumentType.JOB_CARD,
            "Saved job card",
            null,
            URI.create("https://service.example.com/files/1.pdf"),
            false);
    snapshotRepository.save(
        new Snapshot(Vin.parse(VIN_BOTH_DOWN), "SERVICE", List.of(copy), savedAt));

    ResponseEntity<String> response = get(documentsOf(VIN_BOTH_DOWN), CALLER);

    assertThat(response.getStatusCode().value()).isEqualTo(200);
    JsonNode body = json(response);
    assertThat(body.get("partial").asBoolean()).isTrue();
    JsonNode service = sourceIn(body, "SERVICE");
    assertThat(service.get("status").asString()).isEqualTo("STALE");
    assertThat(service.get("reason").asString()).isEqualTo("ERROR");
    assertThat(Instant.parse(service.get("dataAsOf").asString())).isEqualTo(savedAt);
    assertThat(body.get("documents")).hasSize(1);
    assertThat(body.get("documents").get(0).get("stale").asBoolean()).isTrue();
  }

  @Test
  void returns503WithSourcesForVin4() {
    ResponseEntity<String> response = get(documentsOf(VIN_BOTH_DOWN), CALLER);

    assertThat(response.getStatusCode().value()).isEqualTo(503);
    JsonNode body = json(response);
    assertThat(body.get("type").asString()).isEqualTo("sources-unavailable");
    assertThat(sourceIn(body, "SALES").get("status").asString()).isEqualTo("UNAVAILABLE");
    assertThat(sourceIn(body, "SERVICE").get("status").asString()).isEqualTo("UNAVAILABLE");
    assertThat(auditCallerOf(VIN_BOTH_DOWN)).containsExactly("user-1 dealer-1 FAILED");
  }

  @Test
  void returns400ForInvalidVin() {
    ResponseEntity<String> response = get(documentsOf("NOT-A-VIN"), CALLER);

    assertThat(response.getStatusCode().value()).isEqualTo(400);
    assertThat(json(response).get("type").asString()).isEqualTo("invalid-vin");
    assertThat(auditRowCount()).isZero();
  }

  @Test
  void returns401WithoutCaller() {
    ResponseEntity<String> missing = get(documentsOf(VIN_COMPLETE), USER_ID, "user-1");
    ResponseEntity<String> blank =
        get(documentsOf(VIN_COMPLETE), USER_ID, "user-1", DEALERSHIP_ID, " ");

    assertThat(missing.getStatusCode().value()).isEqualTo(401);
    assertThat(json(missing).get("type").asString()).isEqualTo("missing-caller");
    assertThat(blank.getStatusCode().value()).isEqualTo(401);
  }

  @Test
  void returns503WhenAuditFails() {
    doThrow(new DataAccessResourceFailureException("database down"))
        .when(auditRepository)
        .save(any());

    ResponseEntity<String> response = get(documentsOf(VIN_COMPLETE), CALLER);

    assertThat(response.getStatusCode().value()).isEqualTo(503);
    JsonNode body = json(response);
    assertThat(body.get("type").asString()).isEqualTo("audit-unavailable");
    assertThat(body.has("documents")).isFalse();
  }

  @Test
  void everyResponseHasRequestId() {
    ResponseEntity<String> ok = get(documentsOf(VIN_COMPLETE), CALLER);
    assertThat(ok.getStatusCode().value()).isEqualTo(200);
    assertHasRequestHeaders(ok);

    ResponseEntity<String> badRequest = get(documentsOf("NOT-A-VIN"), CALLER);
    ResponseEntity<String> unauthorized = get(documentsOf(VIN_COMPLETE));
    ResponseEntity<String> unknownPath = get("/api/v1/nothing-here", CALLER);

    assertThat(badRequest.getStatusCode().value()).isEqualTo(400);
    assertThat(unauthorized.getStatusCode().value()).isEqualTo(401);
    assertThat(unknownPath.getStatusCode().value()).isEqualTo(404);
    for (ResponseEntity<String> error : List.of(badRequest, unauthorized, unknownPath)) {
      assertHasRequestHeaders(error);
      assertThat(error.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
      assertThat(json(error).get("requestId").asString())
          .isEqualTo(error.getHeaders().getFirst("X-Request-Id"));
    }
  }

  @Test
  void apiDocsDescribeTheEndpoint() {
    ResponseEntity<String> response = get("/v3/api-docs");

    assertThat(response.getStatusCode().value()).isEqualTo(200);
    JsonNode operation =
        json(response).get("paths").get("/api/v1/vehicles/{vin}/documents").get("get");
    List<String> parameters = new ArrayList<>();
    operation
        .get("parameters")
        .forEach(parameter -> parameters.add(parameter.get("name").asString()));
    assertThat(parameters).containsExactlyInAnyOrder("vin", USER_ID, DEALERSHIP_ID);
    assertThat(response.getBody()).contains(VIN_COMPLETE);
    List<String> statuses = new ArrayList<>(operation.get("responses").propertyNames());
    assertThat(statuses).contains("200", "400", "401", "503");
  }

  private static String documentsOf(String vin) {
    return "/api/v1/vehicles/" + vin + "/documents";
  }

  private ResponseEntity<String> get(String path, String... headerNamesAndValues) {
    return RestClient.create("http://localhost:" + port)
        .get()
        .uri(path)
        .headers(headers -> addAll(headers, headerNamesAndValues))
        .retrieve()
        .onStatus(status -> true, (request, errorResponse) -> {})
        .toEntity(String.class);
  }

  private static void addAll(HttpHeaders headers, String[] namesAndValues) {
    for (int i = 0; i < namesAndValues.length; i += 2) {
      headers.add(namesAndValues[i], namesAndValues[i + 1]);
    }
  }

  private static JsonNode json(ResponseEntity<String> response) {
    return JsonMapper.shared().readTree(response.getBody());
  }

  private static JsonNode sourceIn(JsonNode body, String source) {
    for (JsonNode entry : body.get("sources")) {
      if (entry.get("source").asString().equals(source)) {
        return entry;
      }
    }
    throw new AssertionError("No entry for source " + source);
  }

  private static void assertHasRequestHeaders(ResponseEntity<String> response) {
    assertThat(response.getHeaders().getFirst("X-Request-Id")).matches("[0-9a-f]{32}");
    assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
  }

  private List<String> auditCallerOf(String vin) {
    return jdbcClient
        .sql(
            """
            SELECT user_id || ' ' || dealership_id || ' ' || outcome
            FROM document_access_audit WHERE vin = :vin
            """)
        .param("vin", vin)
        .query(String.class)
        .list();
  }

  private long auditRowCount() {
    return jdbcClient.sql("SELECT count(*) FROM document_access_audit").query(Long.class).single();
  }
}
