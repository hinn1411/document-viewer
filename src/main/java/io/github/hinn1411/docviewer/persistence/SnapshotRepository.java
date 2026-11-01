package io.github.hinn1411.docviewer.persistence;

import io.github.hinn1411.docviewer.search.model.Snapshot;
import io.github.hinn1411.docviewer.search.model.Vin;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/** Saves and reads fallback copies. Stub for T11's red step. */
@Repository
public class SnapshotRepository {

  public void save(Snapshot snapshot) {
    throw new UnsupportedOperationException("T11 green step");
  }

  public Optional<Snapshot> findNewerThan(Vin vin, String source, Instant cutoff) {
    throw new UnsupportedOperationException("T11 green step");
  }

  public int deleteOlderThan(Instant cutoff) {
    throw new UnsupportedOperationException("T11 green step");
  }
}
