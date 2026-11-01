package io.github.hinn1411.docviewer.persistence;

import org.springframework.stereotype.Component;

/** Deletes expired fallback copies. Stub for T11's red step. */
@Component
public class SnapshotCleanupJob {

  public void deleteExpiredSnapshots() {
    throw new UnsupportedOperationException("T11 green step");
  }
}
