package io.github.hinn1411.docviewer.search.support.audit;

import io.github.hinn1411.docviewer.domain.AccessAuditRecord;
import io.github.hinn1411.docviewer.domain.Caller;
import io.github.hinn1411.docviewer.domain.DocumentSearchResult;
import io.github.hinn1411.docviewer.domain.SourceOutcome;
import io.github.hinn1411.docviewer.domain.SourceStatus;
import io.github.hinn1411.docviewer.domain.error.AuditUnavailableException;
import io.github.hinn1411.docviewer.observability.DocumentViewerMetrics;
import io.github.hinn1411.docviewer.observability.TraceIds;
import io.github.hinn1411.docviewer.persistence.AuditRepository;
import io.micrometer.tracing.Tracer;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Records who viewed which vehicle's documents. If it can't, the search must not return them. */
@Component
public class AccessAuditor {

  private static final Logger log = LoggerFactory.getLogger(AccessAuditor.class);

  private final Clock clock;
  private final Tracer tracer;
  private final AuditRepository auditRepository;
  private final DocumentViewerMetrics metrics;

  public AccessAuditor(
      Clock clock, Tracer tracer, AuditRepository auditRepository, DocumentViewerMetrics metrics) {
    this.clock = clock;
    this.tracer = tracer;
    this.auditRepository = auditRepository;
    this.metrics = metrics;
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
      metrics.recordAuditFailure();
      // The class only, because PostgreSQL's messages can hold the VIN.
      log.atError()
          .setMessage("Saving the audit record failed")
          .addKeyValue("failure", failure.getClass().getName())
          .log();
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
