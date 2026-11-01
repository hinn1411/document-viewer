package io.github.hinn1411.docviewer.persistence;

import io.github.hinn1411.docviewer.domain.AccessAuditRecord;
import java.time.ZoneOffset;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

/** Saves rows to {@code document_access_audit}, which is insert-only. */
@Repository
public class AuditRepository {

  private final JdbcClient jdbcClient;

  public AuditRepository(JdbcClient jdbcClient) {
    this.jdbcClient = jdbcClient;
  }

  public void save(AccessAuditRecord record) {
    jdbcClient
        .sql(
            """
            INSERT INTO document_access_audit
                (id, occurred_at, user_id, dealership_id, vin, trace_id, outcome,
                 source_statuses, document_count)
            VALUES
                (:id, :occurredAt, :userId, :dealershipId, :vin, :traceId, :outcome,
                 CAST(:sourceStatuses AS jsonb), :documentCount)
            """)
        .param("id", record.id())
        .param("occurredAt", record.occurredAt().atOffset(ZoneOffset.UTC))
        .param("userId", record.caller().userId())
        .param("dealershipId", record.caller().dealershipId())
        .param("vin", record.vin().value())
        .param("traceId", record.traceId())
        .param("outcome", record.outcome().name())
        .param("sourceStatuses", JsonMapper.shared().writeValueAsString(record.sourceStatuses()))
        .param("documentCount", record.documentCount())
        .update();
  }
}
