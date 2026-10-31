package io.github.hinn1411.docviewer.search;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.hinn1411.docviewer.config.DocViewerProperties;
import io.github.hinn1411.docviewer.search.model.Document;
import io.github.hinn1411.docviewer.search.model.DocumentSearchResult;
import io.github.hinn1411.docviewer.search.model.DocumentType;
import io.github.hinn1411.docviewer.search.model.FailureReason;
import io.github.hinn1411.docviewer.search.model.SearchOutcome;
import io.github.hinn1411.docviewer.search.model.SourceOutcome;
import io.github.hinn1411.docviewer.search.model.SourceStatus;
import io.github.hinn1411.docviewer.search.model.Vin;
import io.github.hinn1411.docviewer.source.DocumentSource;
import io.github.hinn1411.docviewer.source.failure.SourceException;
import java.net.URI;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** T09: sources are called at once, each with a deadline, and each ends as one outcome. */
class DocumentSearchServiceTest {

  private static final Vin VIN = Vin.parse("WVWZZZ1JZXW000001");
  private static final Duration DEADLINE = Duration.ofSeconds(2);

  private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
  private final CountDownLatch release = new CountDownLatch(1);

  @AfterEach
  void endFakeSources() {
    release.countDown();
    executor.close();
  }

  @Test
  void callsSourcesInParallel() {
    long start = System.nanoTime();

    DocumentSearchResult result =
        search(slow("SALES", Duration.ofSeconds(1)), slow("SERVICE", Duration.ofSeconds(1)));

    assertThat(elapsedSince(start)).isLessThan(Duration.ofMillis(1_500));
    assertThat(result.outcome()).isEqualTo(SearchOutcome.COMPLETE);
    assertThat(result.documents()).extracting(Document::source).contains("SALES", "SERVICE");
    assertThat(result.sources())
        .extracting(SourceOutcome::latency)
        .allSatisfy(latency -> assertThat(latency).isGreaterThanOrEqualTo(Duration.ofSeconds(1)));
  }

  @Test
  void timesOutSlowSourceAt2s() {
    long start = System.nanoTime();

    DocumentSearchResult result =
        search(answers("SALES", document("SALES")), slow("SERVICE", Duration.ofSeconds(5)));

    assertThat(elapsedSince(start)).isBetween(DEADLINE, Duration.ofMillis(2_500));
    SourceOutcome service = result.sources().getLast();
    assertThat(result.sources().getFirst().status()).isEqualTo(SourceStatus.OK);
    assertThat(service.status()).isEqualTo(SourceStatus.UNAVAILABLE);
    assertThat(service.reason()).isEqualTo(FailureReason.TIMEOUT);
    assertThat(service.latency()).isGreaterThanOrEqualTo(DEADLINE);
    assertThat(result.documents()).extracting(Document::source).containsExactly("SALES");
  }

  @Test
  void oneFailureDoesNotHideOtherSource() {
    DocumentSearchResult result =
        search(
            answers("SALES", document("SALES")),
            fails("SERVICE", new SourceException(FailureReason.ERROR, "failed")));

    assertThat(result.documents()).extracting(Document::source).containsExactly("SALES");
    assertThat(result.outcome()).isEqualTo(SearchOutcome.PARTIAL);
    assertThat(result.sources().getLast().reason()).isEqualTo(FailureReason.ERROR);
  }

  @Test
  void everySourceEndsWithAResult() {
    DocumentSearchResult result =
        search(
            answers("OK", document("OK")),
            fails("TIMED-OUT", new SourceException(FailureReason.TIMEOUT, "failed")),
            fails("BROKEN", new IllegalStateException("a bug in an adapter")),
            slow("SLOW", Duration.ofSeconds(5)));

    assertThat(result.sources())
        .extracting(SourceOutcome::source)
        .containsExactly("OK", "TIMED-OUT", "BROKEN", "SLOW");
    assertThat(result.sources())
        .extracting(SourceOutcome::status)
        .containsExactly(
            SourceStatus.OK,
            SourceStatus.UNAVAILABLE,
            SourceStatus.UNAVAILABLE,
            SourceStatus.UNAVAILABLE);
    assertThat(result.sources())
        .extracting(SourceOutcome::reason)
        .containsExactly(null, FailureReason.TIMEOUT, FailureReason.ERROR, FailureReason.TIMEOUT);
    assertThat(result.sources()).extracting(SourceOutcome::latency).doesNotContainNull();
  }

  private DocumentSearchResult search(DocumentSource... sources) {
    DocViewerProperties properties = new DocViewerProperties(DEADLINE, ZoneOffset.UTC, Map.of());
    return new DocumentSearchService(List.of(sources), new ResultMerger(), executor, properties)
        .search(VIN);
  }

  /** Blocks for the delay, or until the test ends, then answers with one document. */
  private DocumentSource slow(String name, Duration delay) {
    return source(
        name,
        () -> {
          try {
            release.await(delay.toMillis(), TimeUnit.MILLISECONDS);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          return List.of(document(name));
        });
  }

  private static DocumentSource answers(String name, Document document) {
    return source(name, () -> List.of(document));
  }

  private static DocumentSource fails(String name, RuntimeException failure) {
    return source(
        name,
        () -> {
          throw failure;
        });
  }

  private static DocumentSource source(String name, Supplier<List<Document>> fetch) {
    return new DocumentSource() {
      @Override
      public String name() {
        return name;
      }

      @Override
      public List<Document> fetch(Vin vin) {
        return fetch.get();
      }
    };
  }

  private static Document document(String source) {
    return new Document(
        source, "1", DocumentType.OTHER, "Title", null, URI.create("https://example.com/1"), false);
  }

  private static Duration elapsedSince(long startNanos) {
    return Duration.ofNanos(System.nanoTime() - startNanos);
  }
}
