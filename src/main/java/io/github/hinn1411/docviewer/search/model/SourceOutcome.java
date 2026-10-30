package io.github.hinn1411.docviewer.search.model;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/** Stub for the T06 red step: no validation yet. */
public record SourceOutcome(
    String source,
    SourceStatus status,
    FailureReason reason,
    List<Document> documents,
    Duration latency,
    Instant dataAsOf) {}
