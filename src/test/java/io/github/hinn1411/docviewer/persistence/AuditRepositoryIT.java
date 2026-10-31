package io.github.hinn1411.docviewer.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import io.github.hinn1411.docviewer.TestcontainersConfiguration;
import io.github.hinn1411.docviewer.search.model.AccessAuditRecord;
import io.github.hinn1411.docviewer.search.model.Caller;
import io.github.hinn1411.docviewer.search.model.SearchOutcome;
import io.github.hinn1411.docviewer.search.model.SourceStatus;
import io.github.hinn1411.docviewer.search.model.Vin;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/** T10: an audit record becomes one row in a real PostgreSQL, and a database wait stays short. */
@SpringBootTest(webEnvironment = WebEnvironment.NONE)
@Import(TestcontainersConfiguration.class)
class AuditRepositoryIT {

  private static final Instant OCCURRED_AT = Instant.parse("2026-11-01T10:00:00Z");

  @Autowired AuditRepository repository;
  @Autowired JdbcClient jdbcClient;
  @Autowired DataSource dataSource;

  @Test
  void savesOneRowPerSearch() {
    UUID id = UUID.randomUUID();

    repository.save(
        new AccessAuditRecord(
            id,
            OCCURRED_AT,
            new Caller("user-1", "dealer-1"),
            Vin.parse("WVWZZZ1JZXW000001"),
            "trace-1",
            SearchOutcome.PARTIAL,
            Map.of("SALES", SourceStatus.OK, "SERVICE", SourceStatus.UNAVAILABLE),
            3));

    Map<String, Object> row =
        jdbcClient
            .sql(
                """
                SELECT user_id, dealership_id, vin, trace_id, outcome, document_count,
                       source_statuses ->> 'SALES' AS sales, source_statuses ->> 'SERVICE' AS service
                FROM document_access_audit WHERE id = :id
                """)
            .param("id", id)
            .query()
            .singleRow();
    assertThat(row)
        .containsEntry("user_id", "user-1")
        .containsEntry("dealership_id", "dealer-1")
        .containsEntry("vin", "WVWZZZ1JZXW000001")
        .containsEntry("trace_id", "trace-1")
        .containsEntry("outcome", "PARTIAL")
        .containsEntry("document_count", 3)
        .containsEntry("sales", "OK")
        .containsEntry("service", "UNAVAILABLE");
    assertThat(
            jdbcClient
                .sql("SELECT occurred_at FROM document_access_audit WHERE id = :id")
                .param("id", id)
                .query(OffsetDateTime.class)
                .single())
        .isEqualTo(OCCURRED_AT.atOffset(ZoneOffset.UTC));
  }

  @Test
  void waitsAtMostOneSecondForAConnection() throws SQLException {
    assertThat(dataSource.unwrap(HikariDataSource.class).getConnectionTimeout()).isEqualTo(1_000);
  }
}
