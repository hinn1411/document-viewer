package io.github.hinn1411.docviewer.source.adapter.sales;

import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import io.github.hinn1411.docviewer.config.client.SourceClientsConfig;
import io.github.hinn1411.docviewer.domain.Document;
import io.github.hinn1411.docviewer.domain.DocumentType;
import io.github.hinn1411.docviewer.domain.FailureReason;
import io.github.hinn1411.docviewer.domain.Vin;
import io.github.hinn1411.docviewer.source.adapter.BaseSourceAdapterIT;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** T07: maps the nested Sales response. The shared failure tests are in the base class. */
@SpringBootTest(
    classes = {SourceClientsConfig.class, SalesSystemAdapter.class},
    webEnvironment = WebEnvironment.NONE)
class SalesSystemAdapterIT extends BaseSourceAdapterIT {

  private static final String LINK = "https://sales.example.com/docs/a";
  private static final String DATE = "14/03/2025";

  @RegisterExtension
  static WireMockExtension salesApi =
      WireMockExtension.newInstance()
          .options(wireMockConfig().dynamicPort().usingFilesUnderDirectory("mocks/sales"))
          .build();

  @DynamicPropertySource
  static void salesBaseUrl(DynamicPropertyRegistry registry) {
    registry.add("docviewer.sources.sales.base-url", salesApi::baseUrl);
  }

  @Override
  protected WireMockExtension api() {
    return salesApi;
  }

  @Override
  protected String path() {
    return "/sales/v1/deals";
  }

  @Override
  protected String emptyBody() {
    return "{\"deals\": []}";
  }

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

  private void stubDocuments(String... items) {
    stub(okJson("{\"deals\": [{\"documents\": [" + String.join(",", items) + "]}]}"));
  }

  private static String item(String docRef, String docType, String createdOn, String link) {
    return "{\"docRef\": %s, \"docType\": %s, \"name\": \"Title\", \"createdOn\": %s, \"link\": %s}"
        .formatted(json(docRef), json(docType), json(createdOn), json(link));
  }
}
