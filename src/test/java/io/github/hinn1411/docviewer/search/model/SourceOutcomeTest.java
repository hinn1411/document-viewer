package io.github.hinn1411.docviewer.search.model;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** T06: invariants 4 and 5 in 05-domain-model.md. An invalid SourceOutcome can't exist. */
class SourceOutcomeTest {

  private static final Instant AS_OF = Instant.parse("2025-02-01T00:00:00Z");
  private static final List<Document> NONE = List.of();

  @Test
  void okRejectsReasonOrDataAsOf() {
    assertValid(SourceStatus.OK, null, List.of(document(false)), null);
    assertRejected(SourceStatus.OK, FailureReason.ERROR, NONE, null);
    assertRejected(SourceStatus.OK, null, NONE, AS_OF);
  }

  @Test
  void staleRequiresReasonAndDataAsOf() {
    assertValid(SourceStatus.STALE, FailureReason.TIMEOUT, List.of(document(true)), AS_OF);
    assertRejected(SourceStatus.STALE, null, List.of(document(true)), AS_OF);
    assertRejected(SourceStatus.STALE, FailureReason.TIMEOUT, List.of(document(true)), null);
  }

  @Test
  void unavailableRejectsDocuments() {
    assertValid(SourceStatus.UNAVAILABLE, FailureReason.ERROR, NONE, null);
    assertRejected(SourceStatus.UNAVAILABLE, null, NONE, null);
    assertRejected(SourceStatus.UNAVAILABLE, FailureReason.ERROR, List.of(document(false)), null);
    assertRejected(SourceStatus.UNAVAILABLE, FailureReason.ERROR, List.of(document(true)), null);
  }

  @Test
  void staleDocumentsOnlyInStaleOutcomes() {
    assertRejected(SourceStatus.OK, null, List.of(document(true)), null);
    assertRejected(SourceStatus.STALE, FailureReason.ERROR, List.of(document(false)), AS_OF);
    assertRejected(
        SourceStatus.STALE, FailureReason.ERROR, List.of(document(true), document(false)), AS_OF);
  }

  private static void assertValid(
      SourceStatus status, FailureReason reason, List<Document> documents, Instant dataAsOf) {
    assertThatCode(() -> outcome(status, reason, documents, dataAsOf)).doesNotThrowAnyException();
  }

  private static void assertRejected(
      SourceStatus status, FailureReason reason, List<Document> documents, Instant dataAsOf) {
    assertThatThrownBy(() -> outcome(status, reason, documents, dataAsOf))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static SourceOutcome outcome(
      SourceStatus status, FailureReason reason, List<Document> documents, Instant dataAsOf) {
    return new SourceOutcome("SALES", status, reason, documents, Duration.ofMillis(10), dataAsOf);
  }

  private static Document document(boolean stale) {
    return new Document(
        "SALES:1",
        "SALES",
        "1",
        DocumentType.OTHER,
        "X",
        "Title",
        null,
        URI.create("https://example.com/1"),
        stale);
  }
}
