package io.github.hinn1411.docviewer.search;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import io.github.hinn1411.docviewer.config.ClockConfig;
import io.github.hinn1411.docviewer.config.ExecutorConfig;
import io.github.hinn1411.docviewer.config.SourceClientsConfig;
import io.github.hinn1411.docviewer.persistence.SnapshotRepository;
import io.github.hinn1411.docviewer.search.model.Caller;
import io.github.hinn1411.docviewer.search.model.Document;
import io.github.hinn1411.docviewer.search.model.DocumentSearchResult;
import io.github.hinn1411.docviewer.search.model.FailureReason;
import io.github.hinn1411.docviewer.search.model.SearchOutcome;
import io.github.hinn1411.docviewer.search.model.SourceOutcome;
import io.github.hinn1411.docviewer.search.model.SourceStatus;
import io.github.hinn1411.docviewer.search.model.Vin;
import io.github.hinn1411.docviewer.source.adapter.sales.SalesSystemAdapter;
import io.github.hinn1411.docviewer.source.adapter.service.ServiceSystemAdapter;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** T09: the real adapters against the demo stubs, where the Service mock answers after 5 s. */
@SpringBootTest(
    classes = {
      SourceClientsConfig.class,
      ExecutorConfig.class,
      SalesSystemAdapter.class,
      ServiceSystemAdapter.class,
      ResultMerger.class,
      AccessAuditor.class,
      ClockConfig.class,
      SnapshotFallback.class,
      DocumentSearchService.class
    },
    webEnvironment = WebEnvironment.NONE)
class DocumentSearchIT {

  private static final Vin VIN_SERVICE_SLOW = Vin.parse("WVWZZZ1JZXW000002");

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

  // The audit has its own tests, and this test needs no database.
  @MockitoBean AccessAuditor auditor;

  @MockitoBean SnapshotRepository snapshotRepository;

  @Autowired DocumentSearchService service;

  @Test
  void serviceTimeoutGivesPartialResult() {
    long start = System.nanoTime();

    DocumentSearchResult result =
        service.search(VIN_SERVICE_SLOW, new Caller("user-1", "dealer-1"));

    assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(2_500));
    assertThat(result.outcome()).isEqualTo(SearchOutcome.PARTIAL);
    assertThat(outcomeOf(result, "SALES").status()).isEqualTo(SourceStatus.OK);
    SourceOutcome serviceOutcome = outcomeOf(result, "SERVICE");
    assertThat(serviceOutcome.status()).isEqualTo(SourceStatus.UNAVAILABLE);
    assertThat(serviceOutcome.reason()).isEqualTo(FailureReason.TIMEOUT);
    assertThat(result.documents()).isNotEmpty().extracting(Document::source).containsOnly("SALES");
  }

  private static SourceOutcome outcomeOf(DocumentSearchResult result, String source) {
    return result.sources().stream()
        .filter(outcome -> outcome.source().equals(source))
        .findFirst()
        .orElseThrow();
  }
}
