package io.github.hinn1411.docviewer.persistence;

import io.github.hinn1411.docviewer.search.model.AccessAuditRecord;
import org.springframework.stereotype.Repository;

/** Saves audit rows. Stub for T10's red step. */
@Repository
public class AuditRepository {

  public void save(AccessAuditRecord record) {
    throw new UnsupportedOperationException("T10 green step");
  }
}
