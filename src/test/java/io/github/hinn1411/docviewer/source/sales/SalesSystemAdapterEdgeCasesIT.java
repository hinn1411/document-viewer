package io.github.hinn1411.docviewer.source.sales;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import io.github.hinn1411.docviewer.config.SourceClientsConfig;
import io.github.hinn1411.docviewer.search.model.Document;
import io.github.hinn1411.docviewer.search.model.FailureReason;
import io.github.hinn1411.docviewer.search.model.Vin;
import io.github.hinn1411.docviewer.source.DocumentSource;
import io.github.hinn1411.docviewer.source.SourceException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** T07: cases the code review found after the red commit. See the brief. */
@SpringBootTest(
    classes = {SourceClientsConfig.class, SalesSystemAdapter.class},
    webEnvironment = WebEnvironment.NONE)
class SalesSystemAdapterEdgeCasesIT {

  private static final String DEALS_PATH = "/sales/v1/deals";
  private static final Vin VIN = Vin.parse("WVWZZZ1JZXW000099");

  @RegisterExtension
  static WireMockExtension salesApi =
      WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

  @DynamicPropertySource
  static void salesBaseUrl(DynamicPropertyRegistry registry) {
    registry.add("docviewer.sources.sales.base-url", salesApi::baseUrl);
  }

  @Autowired DocumentSource source;

  @Test
  void throwsTimeoutWhenBodyStallsAfterHeaders() {
    // Two chunks over 4 s: the first arrives at once, then nothing for longer than the read
    // timeout.
    stub(okJson("{\"deals\": []}").withChunkedDribbleDelay(2, 4_000));

    SourceException failure = catchThrowableOfType(SourceException.class, () -> source.fetch(VIN));

    assertThat(failure.reason()).isEqualTo(FailureReason.TIMEOUT);
    salesApi.verify(1, getRequestedFor(urlPathEqualTo(DEALS_PATH)));
  }

  @Test
  void dropsNullEntriesBlankIdsAndMalformedLinks() {
    stub(
        okJson(
            """
            {"deals": [
              null,
              {"documents": [
                null,
                {"docRef": " ", "docType": "INV", "name": "Blank ID", "createdOn": "14/03/2025",
                 "link": "https://sales.example.com/docs/blank"},
                {"docRef": "BAD-LINK", "docType": "INV", "name": "Bad link",
                 "createdOn": "14/03/2025", "link": "https://not a link"},
                {"docRef": "GOOD", "docType": "INV", "name": "Good", "createdOn": "14/03/2025",
                 "link": "https://sales.example.com/docs/GOOD"}
              ]}
            ]}
            """));

    assertThat(source.fetch(VIN)).extracting(Document::id).containsExactly("SALES:GOOD");
  }

  @Test
  void wrongTypedFieldFailsTheWholeResponse() {
    // An accepted limit: dropping only that item would need node-by-node parsing.
    stub(okJson("{\"deals\": [{\"documents\": [{\"docRef\": {}}]}]}"));

    SourceException failure = catchThrowableOfType(SourceException.class, () -> source.fetch(VIN));

    assertThat(failure.reason()).isEqualTo(FailureReason.ERROR);
  }

  private static void stub(ResponseDefinitionBuilder response) {
    salesApi.stubFor(get(urlPathEqualTo(DEALS_PATH)).willReturn(response));
  }
}
