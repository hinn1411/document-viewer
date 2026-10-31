package io.github.hinn1411.docviewer.search;

import io.github.hinn1411.docviewer.persistence.AuditRepository;
import io.micrometer.tracing.Tracer;
import java.time.Clock;
import org.springframework.stereotype.Component;

/** Records each search. Stub for T10's red step. */
@Component
public class AccessAuditor {

  public AccessAuditor(Clock clock, Tracer tracer, AuditRepository auditRepository) {}
}
