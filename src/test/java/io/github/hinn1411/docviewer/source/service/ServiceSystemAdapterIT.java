package io.github.hinn1411.docviewer.source.service;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.serviceUnavailable;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import io.github.hinn1411.docviewer.config.SourceClientsConfig;
import io.github.hinn1411.docviewer.search.model.Document;
import io.github.hinn1411.docviewer.search.model.DocumentType;
import io.github.hinn1411.docviewer.search.model.FailureReason;
import io.github.hinn1411.docviewer.search.model.Vin;
import io.github.hinn1411.docviewer.source.DocumentSource;
import io.github.hinn1411.docviewer.source.SourceException;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * T08: maps the flat Service response and handles its failures. {@code fetch} is called through the
 * Spring proxy, so {@code @Retryable} applies. No database is needed.
 */
@SpringBootTest(
    classes = {SourceClientsConfig.class, ServiceSystemAdapter.class},
    webEnvironment = WebEnvironment.NONE)
class ServiceSystemAdapterIT {

  private static final String LINK = "https://service.example.com/files/1.pdf";
  private static final Long DATE = 1790757900L;
  // Outside the demo-VIN table, so each test's own stub is the only one that matches.
  private static final Vin VIN = Vin.parse("WVWZZZ1JZXW000099");
  private static final String RECORDS_PATH = "/service/api/vehicles/" + VIN.value() + "/records";

  @RegisterExtension
  static WireMockExtension serviceApi =
      WireMockExtension.newInstance()
          .options(wireMockConfig().dynamicPort().usingFilesUnderDirectory("mocks/service"))
          .build();

  @DynamicPropertySource
  static void serviceBaseUrl(DynamicPropertyRegistry registry) {
    registry.add("docviewer.sources.service.base-url", serviceApi::baseUrl);
  }

  @Autowired DocumentSource source;

  @Test
  void mapsFlatRecordsToDocuments() {
    List<Document> documents = source.fetch(Vin.parse("WVWZZZ1JZXW000001"));

    assertThat(source.name()).isEqualTo("SERVICE");
    assertThat(documents)
        .containsExactly(
            new Document(
                "SERVICE",
                "778",
                DocumentType.JOB_CARD,
                "60,000 km service",
                Instant.ofEpochSecond(1790757900),
                URI.create("https://service.example.com/files/778.pdf"),
                false),
            new Document(
                "SERVICE",
                "779",
                DocumentType.MOT_CERTIFICATE,
                "MOT certificate",
                Instant.ofEpochSecond(1790761500),
                URI.create("https://service.example.com/files/779.pdf"),
                false));
    assertThat(documents.getFirst().id()).isEqualTo("SERVICE:778");
  }

  @Test
  void convertsEpochSecondsToUtc() {
    stubRecords(record(1L, "MOT", DATE, LINK));

    assertThat(source.fetch(VIN).getFirst().documentDate())
        .isEqualTo(Instant.parse("2026-09-30T08:45:00Z"));
  }

  @Test
  void mapsUnknownCategoryToOther() {
    stubRecords(record(1L, "NEW_CODE", DATE, LINK), record(2L, null, DATE, LINK));

    assertThat(source.fetch(VIN))
        .extracting(Document::type)
        .containsExactly(DocumentType.OTHER, DocumentType.OTHER);
  }

  @Test
  void returnsEmptyListForNoRecords() {
    assertThat(source.fetch(Vin.parse("WVWZZZ1JZXW000005"))).isEmpty();
  }

  @Test
  void dropsBadRecords() {
    stubRecords(
        record(null, "MOT", DATE, LINK),
        record(2L, "MOT", DATE, "http://service.example.com/files/2.pdf"),
        record(3L, "MOT", DATE, null),
        record(4L, "MOT", DATE, "https://not a link"),
        record(5L, "MOT", Long.MAX_VALUE, LINK),
        record(6L, "MOT", DATE, LINK));

    assertThat(source.fetch(VIN)).extracting(Document::id).containsExactly("SERVICE:6");
  }

  @Test
  void keepsRecordWithoutDateAsUndated() {
    stubRecords(record(1L, "MOT", null, LINK));

    assertThat(source.fetch(VIN).getFirst().documentDate()).isNull();
  }

  @ParameterizedTest
  @ValueSource(strings = {"not json", "{}", "null", "[null]", "[{\"recordId\": \"abc\"}]"})
  void throwsErrorOnUnreadableBody(String body) {
    stub(okJson(body));

    assertFails(FailureReason.ERROR, 1);
  }

  @Test
  void throwsErrorOn404WithoutRetry() {
    stub(aResponse().withStatus(404));

    assertFails(FailureReason.ERROR, 1);
  }

  @Test
  void neverPutsVinInTheException() {
    // Spring's I/O error message contains the request URL, and so the VIN.
    stub(aResponse().withFault(Fault.EMPTY_RESPONSE));

    SourceException failure = fetchFailure();

    assertThat(failure.getCause()).isNull();
    assertThat(failure.getMessage()).doesNotContain(VIN.value());
  }

  @Test
  void retriesOnceOn503() {
    serviceApi.stubFor(
        get(urlPathEqualTo(RECORDS_PATH))
            .inScenario("retry")
            .whenScenarioStateIs(Scenario.STARTED)
            .willSetStateTo("recovered")
            .willReturn(serviceUnavailable()));
    serviceApi.stubFor(
        get(urlPathEqualTo(RECORDS_PATH))
            .inScenario("retry")
            .whenScenarioStateIs("recovered")
            .willReturn(okJson("[]")));

    assertThat(source.fetch(VIN)).isEmpty();
    serviceApi.verify(2, getRequestedFor(urlPathEqualTo(RECORDS_PATH)));
  }

  @Test
  void throwsErrorAfterSecond503() {
    stub(serviceUnavailable());

    assertFails(FailureReason.ERROR, 2);
  }

  @Test
  void throwsTimeoutAndDoesNotRetrySlowRead() {
    stub(okJson("[]").withFixedDelay(2_000));

    assertFails(FailureReason.TIMEOUT, 1);
  }

  private void assertFails(FailureReason reason, int requests) {
    assertThat(fetchFailure().reason()).isEqualTo(reason);
    serviceApi.verify(requests, getRequestedFor(urlPathEqualTo(RECORDS_PATH)));
  }

  private SourceException fetchFailure() {
    SourceException failure = catchThrowableOfType(SourceException.class, () -> source.fetch(VIN));
    assertThat(failure).isNotNull();
    return failure;
  }

  private static void stub(ResponseDefinitionBuilder response) {
    serviceApi.stubFor(get(urlPathEqualTo(RECORDS_PATH)).willReturn(response));
  }

  private static void stubRecords(String... records) {
    stub(okJson("[" + String.join(",", records) + "]"));
  }

  private static String record(Long recordId, String category, Long completedAt, String fileUrl) {
    return "{\"recordId\": %s, \"category\": %s, \"description\": \"Title\", \"completedAt\": %s, \"fileUrl\": %s}"
        .formatted(recordId, json(category), completedAt, json(fileUrl));
  }

  private static String json(String value) {
    return value == null ? "null" : "\"" + value + "\"";
  }
}
