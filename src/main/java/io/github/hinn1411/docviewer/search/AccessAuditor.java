package io.github.hinn1411.docviewer.search;

import io.github.hinn1411.docviewer.persistence.AuditRepository;
import io.github.hinn1411.docviewer.search.model.AccessAuditRecord;
import io.github.hinn1411.docviewer.search.model.Caller;
import io.github.hinn1411.docviewer.search.model.DocumentSearchResult;
import io.github.hinn1411.docviewer.search.model.SourceOutcome;
import io.github.hinn1411.docviewer.search.model.SourceStatus;
import io.micrometer.tracing.Tracer;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Records who viewed which vehicle's documents. If it can't, the search must not return them. */
@Component
public class AccessAuditor {

  private final Clock clock;
  private final Tracer tracer;
  private final AuditRepository auditRepository;

  public AccessAuditor(Clock clock, Tracer tracer, AuditRepository auditRepository) {
    this.clock = clock;
    this.tracer = tracer;
    this.auditRepository = auditRepository;
  }

  public void record(Caller caller, DocumentSearchResult result) {
    AccessAuditRecord record =
        new AccessAuditRecord(
            UUID.randomUUID(),
            clock.instant(),
            caller,
            result.vin(),
            TraceIds.current(tracer),
            result.outcome(),
            statusesOf(result),
            result.documents().size());
    try {
      auditRepository.save(record);
    } catch (RuntimeException failure) {
      throw new AuditUnavailableException();
    }
  }

  private static Map<String, SourceStatus> statusesOf(DocumentSearchResult result) {
    Map<String, SourceStatus> statuses = new LinkedHashMap<>();
    for (SourceOutcome outcome : result.sources()) {
      statuses.put(outcome.source(), outcome.status());
    }
    return statuses;
  }
}
