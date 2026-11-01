package io.github.hinn1411.docviewer.persistence;

import io.github.hinn1411.docviewer.search.model.Document;
import io.github.hinn1411.docviewer.search.model.Snapshot;
import io.github.hinn1411.docviewer.search.model.Vin;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** The last good answer per VIN and source, in {@code source_snapshot}. */
@Repository
public class SnapshotRepository {

  private final JdbcClient jdbcClient;

  public SnapshotRepository(JdbcClient jdbcClient) {
    this.jdbcClient = jdbcClient;
  }

  /** An upsert that keeps the newer copy, so saves finishing out of order need no locking. */
  public void save(Snapshot snapshot) {
    jdbcClient
        .sql(
            """
            INSERT INTO source_snapshot (vin, source, documents, fetched_at)
            VALUES (:vin, :source, CAST(:documents AS jsonb), :fetchedAt)
            ON CONFLICT (vin, source) DO UPDATE
                SET documents = EXCLUDED.documents,
                    fetched_at = EXCLUDED.fetched_at
                WHERE source_snapshot.fetched_at < EXCLUDED.fetched_at
            """)
        .param("vin", snapshot.vin().value())
        .param("source", snapshot.source())
        .param("documents", JsonMapper.shared().writeValueAsString(snapshot.documents()))
        .param("fetchedAt", utc(snapshot.fetchedAt()))
        .update();
  }

  public Optional<Snapshot> findNewerThan(Vin vin, String source, Instant cutoff) {
    return jdbcClient
        .sql(
            """
            SELECT CAST(documents AS text) AS documents, fetched_at FROM source_snapshot
            WHERE vin = :vin AND source = :source AND fetched_at > :cutoff
            """)
        .param("vin", vin.value())
        .param("source", source)
        .param("cutoff", utc(cutoff))
        .query(
            (row, rowNumber) ->
                new Snapshot(
                    vin,
                    source,
                    readDocuments(row.getString("documents")),
                    row.getObject("fetched_at", OffsetDateTime.class).toInstant()))
        .optional();
  }

  public int deleteOlderThan(Instant cutoff) {
    return jdbcClient
        .sql("DELETE FROM source_snapshot WHERE fetched_at < :cutoff")
        .param("cutoff", utc(cutoff))
        .update();
  }

  private static List<Document> readDocuments(String json) {
    return JsonMapper.shared().readValue(json, new TypeReference<List<Document>>() {});
  }

  private static OffsetDateTime utc(Instant instant) {
    return instant.atOffset(ZoneOffset.UTC);
  }
}
