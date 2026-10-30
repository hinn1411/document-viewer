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
    Document duplicate =
        new Document(
            "SALES:1",
            "SALES",
            "1",
            DocumentType.OTHER,
            "X",
            "A later copy",
            NEW,
            URI.create("https://example.com/copy"),
            false);

    DocumentSearchResult result =
        merge(ok("SALES", first, duplicate), ok("SERVICE", document("SERVICE", "1", NEW)));

    assertThat(ids(result)).containsExactly("SALES:1", "SERVICE:1");
    assertThat(result.documents().getFirst().title()).isEqualTo(first.title());
  }

  @Test
  void completeWhenAllSourcesOk() {
    DocumentSearchResult result = merge(ok("SALES"), ok("SERVICE"));

    assertThat(result.outcome()).isEqualTo(SearchOutcome.COMPLETE);
    assertThat(result.partial()).isFalse();
  }

  @Test
  void partialWhenAnySourceNotOk() {
    for (DocumentSearchResult result :
        List.of(merge(ok("SALES"), stale("SERVICE")), merge(ok("SALES"), unavailable("SERVICE")))) {
      assertThat(result.outcome()).isEqualTo(SearchOutcome.PARTIAL);
      assertThat(result.partial()).isTrue();
    }
  }

  @Test
  void partialWhenOnlyStaleData() {
    for (DocumentSearchResult result :
        List.of(
            merge(stale("SALES"), stale("SERVICE")),
            merge(stale("SALES"), unavailable("SERVICE")))) {
      assertThat(result.outcome()).isEqualTo(SearchOutcome.PARTIAL);
      assertThat(result.partial()).isTrue();
    }
  }

  @Test
  void failedWhenNoSourceHasData() {
    DocumentSearchResult result = merge(unavailable("SALES"), unavailable("SERVICE"));

    assertThat(result.outcome()).isEqualTo(SearchOutcome.FAILED);
    assertThat(result.partial()).isTrue();
    assertThat(result.documents()).isEmpty();
  }

  @Test
  void failedWhenNoSources() {
    DocumentSearchResult result = merge();

    assertThat(result.outcome()).isEqualTo(SearchOutcome.FAILED);
    assertThat(result.partial()).isTrue();
    assertThat(result.documents()).isEmpty();
  }

  @Test
  void keepsSourceOnEveryDocument() {
    DocumentSearchResult result =
        merge(
            ok("SALES", document("SALES", "1", NEW)), ok("SERVICE", document("SERVICE", "2", OLD)));

    assertThat(result.documents()).extracting(Document::source).containsExactly("SALES", "SERVICE");
  }

  @Test
  void keepsStaleFlagAndEverySourceOutcome() {
    SourceOutcome sales = ok("SALES", document("SALES", "1", NEW));
    SourceOutcome service = stale("SERVICE", staleDocument("SERVICE", "2", OLD));
    SourceOutcome third = unavailable("THIRD");

    DocumentSearchResult result = merge(sales, service, third);

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
    return new SourceOutcome(source, SourceStatus.OK, null, List.of(documents), LATENCY, null);
  }

  private static SourceOutcome stale(String source, Document... documents) {
    return new SourceOutcome(
        source,
        SourceStatus.STALE,
        FailureReason.TIMEOUT,
        List.of(documents),
        LATENCY,
        SNAPSHOT_TIME);
  }

  private static SourceOutcome unavailable(String source) {
    return new SourceOutcome(
        source, SourceStatus.UNAVAILABLE, FailureReason.ERROR, List.of(), LATENCY, null);
  }

  private static Document document(String source, String id, Instant date) {
    return document(source, id, date, false);
  }

  private static Document staleDocument(String source, String id, Instant date) {
    return document(source, id, date, true);
  }

  private static Document document(String source, String id, Instant date, boolean stale) {
    return new Document(
        source + ":" + id,
        source,
        id,
        DocumentType.OTHER,
        "X",
        "Title " + id,
        date,
        URI.create("https://example.com/" + id),
        stale);
  }
}
