package io.github.hinn1411.docviewer.source.sales;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.serviceUnavailable;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.github.tomakehurst.wiremock.client.MappingBuilder;
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
 * T07: maps the nested Sales response and handles its failures. {@code fetch} is called through the
 * Spring proxy, so {@code @Retryable} applies. No database is needed.
 */
@SpringBootTest(
    classes = {SourceClientsConfig.class, SalesSystemAdapter.class},
    webEnvironment = WebEnvironment.NONE)
class SalesSystemAdapterIT {

  private static final String DEALS_PATH = "/sales/v1/deals";
  private static final String LINK = "https://sales.example.com/docs/a";
  private static final String DATE = "14/03/2025";
  // Outside the demo-VIN table, so each test's own stub is the only one that matches.
  private static final Vin VIN = Vin.parse("WVWZZZ1JZXW000099");

  @RegisterExtension
  static WireMockExtension salesApi =
      WireMockExtension.newInstance()
          .options(wireMockConfig().dynamicPort().usingFilesUnderDirectory("mocks/sales"))
          .build();

  @DynamicPropertySource
  static void salesBaseUrl(DynamicPropertyRegistry registry) {
    registry.add("docviewer.sources.sales.base-url", salesApi::baseUrl);
  }

  @Autowired DocumentSource source;

  @Test
  void mapsNestedDealsToDocuments() {
    List<Document> documents = source.fetch(Vin.parse("WVWZZZ1JZXW000001"));

    assertThat(source.name()).isEqualTo("SALES");
    assertThat(documents)
        .containsExactly(
            new Document(
                "SALES",
                "INV-1001",
                DocumentType.SALES_INVOICE,
                "Sales invoice",
                Instant.parse("2025-03-14T00:00:00Z"),
                URI.create("https://sales.example.com/docs/INV-1001"),
                false),
            new Document(
                "SALES",
                "ORD-1001",
                DocumentType.ORDER_FORM,
                "Order form",
                Instant.parse("2025-03-10T00:00:00Z"),
                URI.create("https://sales.example.com/docs/ORD-1001"),
                false));
    assertThat(documents.getFirst().id()).isEqualTo("SALES:INV-1001");
  }

  @Test
  void mapsUnknownTypeToOther() {
    stubDocuments(item("A", "NEW_CODE", DATE, LINK), item("B", null, DATE, LINK));

    assertThat(source.fetch(VIN))
        .extracting(Document::type)
        .containsExactly(DocumentType.OTHER, DocumentType.OTHER);
  }

  @Test
  void convertsCreatedOnFromDealershipZoneToUtc() {
    // London is on UTC+1 in June. A March date couldn't tell the zone apart from UTC.
    stubDocuments(item("A", "INV", "14/06/2025", LINK));

    assertThat(source.fetch(VIN).getFirst().documentDate())
        .isEqualTo(Instant.parse("2025-06-13T23:00:00Z"));
  }

  @Test
  void returnsEmptyListForNoDeals() {
    assertThat(source.fetch(Vin.parse("WVWZZZ1JZXW000005"))).isEmpty();
  }

  @Test
  void ignoresDealWithoutDocuments() {
    stub(okJson("{\"deals\": [{}]}"));

    assertThat(source.fetch(VIN)).isEmpty();
  }

  @Test
  void dropsBadItems() {
    stubDocuments(
        item(null, "INV", DATE, LINK),
        item(" ", "INV", DATE, LINK),
        item("HTTP", "INV", DATE, "http://sales.example.com/docs/a"),
        item("NO-LINK", "INV", DATE, null),
        item("BAD-LINK", "INV", DATE, "https://not a link"),
        item("IMPOSSIBLE", "INV", "31/02/2025", LINK),
        item("WRONG-FORMAT", "INV", "2025-03-14", LINK),
        item("GOOD", "INV", DATE, LINK));

    assertThat(source.fetch(VIN)).extracting(Document::id).containsExactly("SALES:GOOD");
  }

  @Test
  void keepsItemWithoutDateAsUndated() {
    stubDocuments(item("A", "INV", null, LINK));

    assertThat(source.fetch(VIN).getFirst().documentDate()).isNull();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "not json",
        "{}",
        "{\"deals\": null}",
        "{\"deals\": [null]}",
        "{\"deals\": [{\"documents\": [null]}]}",
        "{\"deals\": [{\"documents\": [{\"docRef\": {}}]}]}"
      })
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
    salesApi.stubFor(
        dealsRequest()
            .inScenario("retry")
            .whenScenarioStateIs(Scenario.STARTED)
            .willSetStateTo("recovered")
            .willReturn(serviceUnavailable()));
    salesApi.stubFor(
        dealsRequest()
            .inScenario("retry")
            .whenScenarioStateIs("recovered")
            .willReturn(okJson("{\"deals\": []}")));

    assertThat(source.fetch(VIN)).isEmpty();
    salesApi.verify(2, getRequestedFor(urlPathEqualTo(DEALS_PATH)));
  }

  @Test
  void throwsErrorAfterSecond503() {
    stub(serviceUnavailable());

    assertFails(FailureReason.ERROR, 2);
  }

  @Test
  void throwsTimeoutAndDoesNotRetrySlowRead() {
    stub(okJson("{\"deals\": []}").withFixedDelay(2_000));

    assertFails(FailureReason.TIMEOUT, 1);
  }

  @Test
  void throwsTimeoutWhenBodyStallsAfterHeaders() {
    // Two chunks over 4 s: the first arrives at once, then nothing for longer than the read
    // timeout.
    stub(okJson("{\"deals\": []}").withChunkedDribbleDelay(2, 4_000));

    assertFails(FailureReason.TIMEOUT, 1);
  }

  private void assertFails(FailureReason reason, int requests) {
    assertThat(fetchFailure().reason()).isEqualTo(reason);
    salesApi.verify(requests, getRequestedFor(urlPathEqualTo(DEALS_PATH)));
  }

  private SourceException fetchFailure() {
    SourceException failure = catchThrowableOfType(SourceException.class, () -> source.fetch(VIN));
    assertThat(failure).isNotNull();
    return failure;
  }

  private static MappingBuilder dealsRequest() {
    return get(urlPathEqualTo(DEALS_PATH)).withQueryParam("vin", equalTo(VIN.value()));
  }

  private static void stub(ResponseDefinitionBuilder response) {
    salesApi.stubFor(dealsRequest().willReturn(response));
  }

  private static void stubDocuments(String... items) {
    stub(okJson("{\"deals\": [{\"documents\": [" + String.join(",", items) + "]}]}"));
  }

  private static String item(String docRef, String docType, String createdOn, String link) {
    return "{\"docRef\": %s, \"docType\": %s, \"name\": \"Title\", \"createdOn\": %s, \"link\": %s}"
        .formatted(json(docRef), json(docType), json(createdOn), json(link));
  }

  private static String json(String value) {
    return value == null ? "null" : "\"" + value + "\"";
  }
}
