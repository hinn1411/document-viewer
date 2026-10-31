package io.github.hinn1411.docviewer.source.service;

import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import io.github.hinn1411.docviewer.config.SourceClientsConfig;
import io.github.hinn1411.docviewer.search.model.Document;
import io.github.hinn1411.docviewer.search.model.DocumentType;
import io.github.hinn1411.docviewer.search.model.FailureReason;
import io.github.hinn1411.docviewer.search.model.Vin;
import io.github.hinn1411.docviewer.source.BaseSourceAdapterIT;
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

/** T08: maps the flat Service response. The shared failure tests are in the base class. */
@SpringBootTest(
    classes = {SourceClientsConfig.class, ServiceSystemAdapter.class},
    webEnvironment = WebEnvironment.NONE)
class ServiceSystemAdapterIT extends BaseSourceAdapterIT {

  private static final String LINK = "https://service.example.com/files/1.pdf";
  private static final Long DATE = 1790757900L;

  @RegisterExtension
  static WireMockExtension serviceApi =
      WireMockExtension.newInstance()
          .options(wireMockConfig().dynamicPort().usingFilesUnderDirectory("mocks/service"))
          .build();

  @DynamicPropertySource
  static void serviceBaseUrl(DynamicPropertyRegistry registry) {
    registry.add("docviewer.sources.service.base-url", serviceApi::baseUrl);
  }

  @Override
  protected WireMockExtension api() {
    return serviceApi;
  }

  @Override
  protected String path() {
    return "/service/api/vehicles/" + VIN.value() + "/records";
  }

  @Override
  protected String emptyBody() {
    return "[]";
  }

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

  private void stubRecords(String... records) {
    stub(okJson("[" + String.join(",", records) + "]"));
  }

  private static String record(Long recordId, String category, Long completedAt, String fileUrl) {
    return "{\"recordId\": %s, \"category\": %s, \"description\": \"Title\", \"completedAt\": %s, \"fileUrl\": %s}"
        .formatted(recordId, json(category), completedAt, json(fileUrl));
  }
}
