package io.github.hinn1411.docviewer.search;

import io.github.hinn1411.docviewer.config.DocViewerProperties;
import io.github.hinn1411.docviewer.persistence.SnapshotRepository;
import java.time.Clock;
import java.util.concurrent.ExecutorService;
import org.springframework.stereotype.Component;

/** Saves and serves fallback copies. Stub for T11's red step. */
@Component
public class SnapshotFallback {

  public SnapshotFallback(
      Clock clock,
      SnapshotRepository snapshotRepository,
      ExecutorService sourceCallExecutor,
      DocViewerProperties properties) {}
}
