package io.github.hinn1411.docviewer.search.model;

import java.time.Instant;
import java.util.List;

/** One row in {@code source_snapshot}: a source's last good answer for a VIN. */
public record Snapshot(Vin vin, String source, List<Document> documents, Instant fetchedAt) {}
