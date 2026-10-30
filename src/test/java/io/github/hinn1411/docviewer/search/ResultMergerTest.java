package io.github.hinn1411.docviewer.search;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.hinn1411.docviewer.search.model.Document;
import io.github.hinn1411.docviewer.search.model.DocumentSearchResult;
import io.github.hinn1411.docviewer.search.model.DocumentType;
import io.github.hinn1411.docviewer.search.model.FailureReason;
import io.github.hinn1411.docviewer.search.model.SearchOutcome;
import io.github.hinn1411.docviewer.search.model.SourceOutcome;
import io.github.hinn1411.docviewer.search.model.SourceStatus;
import io.github.hinn1411.docviewer.search.model.Vin;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** T06: one list in a fixed order, and the search outcome derived from each source's status. */
class ResultMergerTest {

  private static final Vin VIN = Vin.parse("WVWZZZ1JZXW000001");
  private static final Duration LATENCY = Duration.ofMillis(10);
  private static final Instant OLD = Instant.parse("2024-01-01T00:00:00Z");
  private static final Instant MIDDLE = Instant.parse("2024-06-01T00:00:00Z");
  private static final Instant NEW = Instant.parse("2025-01-01T00:00:00Z");
  private static final Instant SNAPSHOT_TIME = Instant.parse("2025-02-01T00:00:00Z");

  private final ResultMerger merger = new ResultMerger();

  @Test
  void sortsNewestFirstUndatedLast() {
    DocumentSearchResult result =
        merge(
            ok("SALES", document("SALES", "old", OLD), document("SALES", "undated", null)),
            ok("SERVICE", document("SERVICE", "new", NEW), document("SERVICE", "middle", MIDDLE)));

    assertThat(ids(result))
        .containsExactly("SERVICE:new", "SERVICE:middle", "SALES:old", "SALES:undated");
  }

  @Test
  void breaksTiesBySourceThenSourceDocumentId() {
    SourceOutcome sales =
        ok(
            "SALES",
            document("SALES", "b", NEW),
            document("SALES", "a", NEW),
            document("SALES", "y", null),
            document("SALES", "x", null));
    SourceOutcome service =
        ok("SERVICE", document("SERVICE", "a", NEW), document("SERVICE", "x", null));
    List<String> expected =
        List.of("SALES:a", "SALES:b", "SERVICE:a", "SALES:x", "SALES:y", "SERVICE:x");

    assertThat(ids(merge(sales, service))).isEqualTo(expected);
    assertThat(ids(merge(service, sales))).isEqualTo(expected);
  }

  @Test
  void dedupesWithinSourceOnly() {
    Document first = document("SALES", "1", NEW);
    Document laterCopy =
        new Document("SALES", "1", DocumentType.OTHER, "A later copy", NEW, first.url(), false);

    DocumentSearchResult result =
        merge(ok("SALES", first, laterCopy), ok("SERVICE", document("SERVICE", "1", NEW)));

    assertThat(ids(result)).containsExactly("SALES:1", "SERVICE:1");
    assertThat(result.documents().getFirst().title()).isEqualTo(first.title());
  }

  @ParameterizedTest
  @CsvSource({
    "OK,          OK,          COMPLETE, false",
    "OK,          STALE,       PARTIAL,  true",
    "OK,          UNAVAILABLE, PARTIAL,  true",
    "STALE,       STALE,       PARTIAL,  true",
    "STALE,       UNAVAILABLE, PARTIAL,  true",
    "UNAVAILABLE, UNAVAILABLE, FAILED,   true"
  })
  void derivesOutcomeAndPartialFromStatuses(
      SourceStatus sales, SourceStatus service, SearchOutcome outcome, boolean partial) {
    DocumentSearchResult result = merge(outcome("SALES", sales), outcome("SERVICE", service));

    assertThat(result.outcome()).isEqualTo(outcome);
    assertThat(result.partial()).isEqualTo(partial);
  }

  @Test
  void keepsSourceStaleFlagAndEveryOutcome() {
    SourceOutcome sales = ok("SALES", document("SALES", "1", NEW));
    SourceOutcome service =
        SourceOutcome.stale(
            "SERVICE",
            FailureReason.TIMEOUT,
            List.of(document("SERVICE", "2", OLD)),
            LATENCY,
            SNAPSHOT_TIME);
    SourceOutcome third = SourceOutcome.unavailable("THIRD", FailureReason.ERROR, LATENCY);

    DocumentSearchResult result = merge(sales, service, third);

    assertThat(result.documents()).extracting(Document::source).containsExactly("SALES", "SERVICE");
    assertThat(result.documents()).extracting(Document::stale).containsExactly(false, true);
    assertThat(result.sources()).containsExactly(sales, service, third);
  }

  private DocumentSearchResult merge(SourceOutcome... outcomes) {
    return merger.merge(VIN, List.of(outcomes));
  }

  private static List<String> ids(DocumentSearchResult result) {
    return result.documents().stream().map(Document::id).toList();
  }

  private static SourceOutcome ok(String source, Document... documents) {
    return SourceOutcome.ok(source, List.of(documents), LATENCY);
  }

  private static SourceOutcome outcome(String source, SourceStatus status) {
    return switch (status) {
      case OK -> SourceOutcome.ok(source, List.of(), LATENCY);
      case STALE ->
          SourceOutcome.stale(source, FailureReason.ERROR, List.of(), LATENCY, SNAPSHOT_TIME);
      case UNAVAILABLE -> SourceOutcome.unavailable(source, FailureReason.ERROR, LATENCY);
    };
  }

  private static Document document(String source, String id, Instant date) {
    return new Document(
        source,
        id,
        DocumentType.OTHER,
        "Title " + id,
        date,
        URI.create("https://example.com/" + id),
        false);
  }
}
