package io.github.hinn1411.docviewer.domain;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** One row in {@code document_access_audit}: who viewed which vehicle, when, with what result. */
public record AccessAuditRecord(
    UUID id,
    Instant occurredAt,
    Caller caller,
    Vin vin,
    String traceId,
    SearchOutcome outcome,
    Map<String, SourceStatus> sourceStatuses,
    int documentCount) {}
