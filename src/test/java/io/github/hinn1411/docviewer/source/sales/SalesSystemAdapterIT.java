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
import java.io.PrintWriter;
import java.io.StringWriter;
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
  private static final Vin VIN_OK = Vin.parse("WVWZZZ1JZXW000001");
  private static final Vin VIN_NO_DOCUMENTS = Vin.parse("WVWZZZ1JZXW000005");
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
    List<Document> documents = source.fetch(VIN_OK);

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
    stubDeals(
        """
        {"docRef": "A", "docType": "NEW_CODE", "name": "A", "createdOn": "14/03/2025",
         "link": "https://sales.example.com/docs/A"},
        {"docRef": "B", "name": "B", "createdOn": "14/03/2025",
         "link": "https://sales.example.com/docs/B"}
        """);

    assertThat(source.fetch(VIN))
        .extracting(Document::type)
        .containsExactly(DocumentType.OTHER, DocumentType.OTHER);
  }

  @Test
  void convertsCreatedOnFromDealershipZoneToUtc() {
    // London is on UTC+1 in June. A March date couldn't tell the zone apart from UTC.
    stubDeals(
        """
        {"docRef": "A", "docType": "INV", "name": "A", "createdOn": "14/06/2025",
         "link": "https://sales.example.com/docs/A"}
        """);

    assertThat(source.fetch(VIN).getFirst().documentDate())
        .isEqualTo(Instant.parse("2025-06-13T23:00:00Z"));
  }

  @Test
  void returnsEmptyListForNoDeals() {
    assertThat(source.fetch(VIN_NO_DOCUMENTS)).isEmpty();
  }

  @Test
  void dropsItemsWithoutIdOrHttpsLinkOrRealDate() {
    stubBody(
        """
        {"deals": [
          {"dealNumber": "D-1"},
          {"dealNumber": "D-2", "documents": [
            {"docType": "INV", "name": "No ID", "createdOn": "14/03/2025",
             "link": "https://sales.example.com/docs/no-id"},
            {"docRef": "HTTP", "docType": "INV", "name": "Not https", "createdOn": "14/03/2025",
             "link": "http://sales.example.com/docs/HTTP"},
            {"docRef": "NO-LINK", "docType": "INV", "name": "No link", "createdOn": "14/03/2025"},
            {"docRef": "IMPOSSIBLE", "docType": "INV", "name": "No such day",
             "createdOn": "31/02/2025", "link": "https://sales.example.com/docs/IMPOSSIBLE"},
            {"docRef": "WRONG-FORMAT", "docType": "INV", "name": "Wrong format",
             "createdOn": "2025-03-14", "link": "https://sales.example.com/docs/WRONG-FORMAT"},
            {"docRef": "GOOD", "docType": "INV", "name": "Good", "createdOn": "14/03/2025",
             "link": "https://sales.example.com/docs/GOOD"}
          ]}
        ]}
        """);

    assertThat(source.fetch(VIN)).extracting(Document::id).containsExactly("SALES:GOOD");
  }

  @Test
  void keepsItemWithoutDateAsUndated() {
    stubDeals(
        """
        {"docRef": "A", "docType": "INV", "name": "A",
         "link": "https://sales.example.com/docs/A"}
        """);

    List<Document> documents = source.fetch(VIN);

    assertThat(documents).extracting(Document::id).containsExactly("SALES:A");
    assertThat(documents.getFirst().documentDate()).isNull();
  }

  @ParameterizedTest
  @ValueSource(strings = {"not json", "{}", "{\"deals\": null}"})
  void throwsErrorOnUnreadableBody(String body) {
    stubBody(body);

    assertThat(fetchFailure().reason()).isEqualTo(FailureReason.ERROR);
    assertRequests(1);
  }

  @Test
  void throwsErrorOn404WithoutRetry() {
    stub(aResponse().withStatus(404));

    assertThat(fetchFailure().reason()).isEqualTo(FailureReason.ERROR);
    assertRequests(1);
  }

  @Test
  void neverPutsVinInTheException() {
    // An HTTP error, then an I/O error: Spring's I/O error message contains the request URL.
    stub(serviceUnavailable());
    assertThat(stackTraceOf(fetchFailure())).doesNotContain(VIN.value());

    stub(aResponse().withFault(Fault.EMPTY_RESPONSE));
    assertThat(stackTraceOf(fetchFailure())).doesNotContain(VIN.value());
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
    assertRequests(2);
  }

  @Test
  void throwsErrorAfterSecond503() {
    stub(serviceUnavailable());

    assertThat(fetchFailure().reason()).isEqualTo(FailureReason.ERROR);
    assertRequests(2);
  }

  @Test
  void throwsTimeoutAndDoesNotRetrySlowRead() {
    stub(okJson("{\"deals\": []}").withFixedDelay(2_000));

    assertThat(fetchFailure().reason()).isEqualTo(FailureReason.TIMEOUT);
    assertRequests(1);
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

  private static void stubBody(String body) {
    stub(okJson(body));
  }

  private static void stubDeals(String documents) {
    stubBody("{\"deals\": [{\"dealNumber\": \"D-1\", \"documents\": [" + documents + "]}]}");
  }

  private static void assertRequests(int count) {
    salesApi.verify(count, getRequestedFor(urlPathEqualTo(DEALS_PATH)));
  }

  /** Includes causes and suppressed exceptions, which is what a logged stack trace would show. */
  private static String stackTraceOf(Throwable failure) {
    StringWriter text = new StringWriter();
    failure.printStackTrace(new PrintWriter(text));
    return text.toString();
  }
}
