package io.github.hinn1411.docviewer.search.model;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * What one source contributed to a search, after the fallback was applied. Invalid combinations of
 * status, reason, documents and data age are rejected, so they can't exist.
 */
public record SourceOutcome(
    String source,
    SourceStatus status,
    FailureReason reason,
    List<Document> documents,
    Duration latency,
    Instant dataAsOf) {

  public SourceOutcome {
    documents = List.copyOf(documents);
    boolean hasStale = documents.stream().anyMatch(Document::stale);
    boolean allStale = documents.stream().allMatch(Document::stale);
    boolean valid =
        switch (status) {
          case OK -> reason == null && dataAsOf == null && !hasStale;
          case STALE -> reason != null && dataAsOf != null && allStale;
          case UNAVAILABLE -> reason != null && documents.isEmpty();
        };
    if (!valid) {
      throw new IllegalArgumentException("Invalid " + status + " outcome for source " + source);
    }
  }
}
