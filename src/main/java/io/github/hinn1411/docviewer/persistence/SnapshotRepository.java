package io.github.hinn1411.docviewer.persistence;

import io.github.hinn1411.docviewer.domain.Document;
import io.github.hinn1411.docviewer.domain.Snapshot;
import io.github.hinn1411.docviewer.domain.Vin;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** The last good answer per VIN and source, in {@code source_snapshot}. */
@Repository
public class SnapshotRepository {

  private static final Logger log = LoggerFactory.getLogger(SnapshotRepository.class);

  private final JdbcClient jdbcClient;

  public SnapshotRepository(JdbcClient jdbcClient) {
    this.jdbcClient = jdbcClient;
  }

  /** An upsert that keeps the newer copy, so saves finishing out of order need no locking. */
  public void save(Snapshot snapshot) {
    int updated =
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
    // 0 means a newer copy was already there, so it stayed.
    log.atDebug()
        .setMessage("Saved snapshot")
        .addKeyValue("source", snapshot.source())
        .addKeyValue("vin", snapshot.vin().masked())
        .addKeyValue("updated", updated)
        .log();
  }

  public Optional<Snapshot> findNewerThan(Vin vin, String source, Instant cutoff) {
    Optional<Snapshot> snapshot =
        jdbcClient
            .sql(
                """
            SELECT documents, fetched_at FROM source_snapshot
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
    log.atDebug()
        .setMessage("Read snapshot")
        .addKeyValue("source", source)
        .addKeyValue("vin", vin.masked())
        .addKeyValue("found", snapshot.isPresent())
        .log();
    return snapshot;
  }

  public int deleteOlderThan(Instant cutoff) {
    int deleted =
        jdbcClient
            .sql("DELETE FROM source_snapshot WHERE fetched_at < :cutoff")
            .param("cutoff", utc(cutoff))
            .update();
    log.atDebug().setMessage("Deleted snapshots").addKeyValue("deleted", deleted).log();
    return deleted;
  }

  private static List<Document> readDocuments(String json) {
    return JsonMapper.shared().readValue(json, new TypeReference<List<Document>>() {});
  }

  private static OffsetDateTime utc(Instant instant) {
    return instant.atOffset(ZoneOffset.UTC);
  }
}
