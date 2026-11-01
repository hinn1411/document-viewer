package io.github.hinn1411.docviewer.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/** What one source contributed to a search. Create one with the factory for its status. */
public record SourceOutcome(
    String source,
    SourceStatus status,
    FailureReason reason,
    List<Document> documents,
    Duration latency,
    Instant dataAsOf) {

  /** The live call succeeded. */
  public static SourceOutcome ok(String source, List<Document> documents, Duration latency) {
    return new SourceOutcome(source, SourceStatus.OK, null, documents, latency, null);
  }

  /** The live call failed, so the fallback copy from {@code dataAsOf} is served. */
  public static SourceOutcome stale(
      String source,
      FailureReason reason,
      List<Document> documents,
      Duration latency,
      Instant dataAsOf) {
    List<Document> staleDocuments = documents.stream().map(Document::asStale).toList();
    return new SourceOutcome(source, SourceStatus.STALE, reason, staleDocuments, latency, dataAsOf);
  }

  /** The live call failed and there's no usable fallback copy. */
  public static SourceOutcome unavailable(String source, FailureReason reason, Duration latency) {
    return new SourceOutcome(source, SourceStatus.UNAVAILABLE, reason, List.of(), latency, null);
  }
}
