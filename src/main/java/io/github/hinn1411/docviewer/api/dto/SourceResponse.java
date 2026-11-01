package io.github.hinn1411.docviewer.api.dto;

import io.github.hinn1411.docviewer.search.model.FailureReason;
import io.github.hinn1411.docviewer.search.model.SourceOutcome;
import io.github.hinn1411.docviewer.search.model.SourceStatus;
import java.time.Instant;

/** How one source ended up. {@code reason} and {@code dataAsOf} are null when they don't apply. */
public record SourceResponse(
    String source,
    SourceStatus status,
    FailureReason reason,
    int documentCount,
    long latencyMs,
    Instant dataAsOf) {

  public static SourceResponse from(SourceOutcome outcome) {
    return new SourceResponse(
        outcome.source(),
        outcome.status(),
        outcome.reason(),
        outcome.documents().size(),
        outcome.latency().toMillis(),
        outcome.dataAsOf());
  }
}
