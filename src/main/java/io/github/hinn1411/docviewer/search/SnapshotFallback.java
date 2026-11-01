package io.github.hinn1411.docviewer.search;

import io.github.hinn1411.docviewer.config.DocViewerProperties;
import io.github.hinn1411.docviewer.persistence.SnapshotRepository;
import io.github.hinn1411.docviewer.search.model.Snapshot;
import io.github.hinn1411.docviewer.search.model.SourceOutcome;
import io.github.hinn1411.docviewer.search.model.SourceStatus;
import io.github.hinn1411.docviewer.search.model.Vin;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Keeps each source's last good answer, and serves it when that source fails. Neither job can fail
 * a search. Logs carry only the exception's class, because PostgreSQL's messages can hold the VIN.
 */
@Component
public class SnapshotFallback {

  private static final Logger log = LoggerFactory.getLogger(SnapshotFallback.class);

  private final Clock clock;
  private final SnapshotRepository snapshotRepository;
  private final ExecutorService executor;
  private final Duration maxAge;

  public SnapshotFallback(
      Clock clock,
      SnapshotRepository snapshotRepository,
      ExecutorService sourceCallExecutor,
      DocViewerProperties properties) {
    this.clock = clock;
    this.snapshotRepository = snapshotRepository;
    this.executor = sourceCallExecutor;
    this.maxAge = properties.fallbackMaxAge();
  }

  /** Turns a failed outcome into a stale one if a recent copy exists. Others pass through. */
  public SourceOutcome applyTo(Vin vin, SourceOutcome outcome) {
    if (outcome.status() != SourceStatus.UNAVAILABLE) {
      return outcome;
    }
    return findCopy(vin, outcome.source())
        .map(
            copy ->
                SourceOutcome.stale(
                    outcome.source(),
                    outcome.reason(),
                    copy.documents(),
                    outcome.latency(),
                    copy.fetchedAt()))
        .orElse(outcome);
  }

  /**
   * Saves each live answer as the next copy, without making the user wait for it. Copies are dated
   * when their search started, so a slow search that saves last can't replace a newer answer.
   */
  public void saveInBackground(Vin vin, List<SourceOutcome> outcomes, Instant fetchedAt) {
    outcomes.stream()
        .filter(outcome -> outcome.status() == SourceStatus.OK)
        .forEach(
            outcome ->
                submitSave(new Snapshot(vin, outcome.source(), outcome.documents(), fetchedAt)));
  }

  private Optional<Snapshot> findCopy(Vin vin, String source) {
    try {
      return snapshotRepository.findNewerThan(vin, source, clock.instant().minus(maxAge));
    } catch (RuntimeException failure) {
      log.warn("Reading a fallback copy failed: source={}, failure={}", source, name(failure));
      return Optional.empty();
    }
  }

  private void submitSave(Snapshot snapshot) {
    try {
      executor.execute(() -> save(snapshot));
    } catch (RejectedExecutionException shuttingDown) {
      logSaveFailure(snapshot, shuttingDown);
    }
  }

  private void save(Snapshot snapshot) {
    try {
      snapshotRepository.save(snapshot);
    } catch (RuntimeException failure) {
      logSaveFailure(snapshot, failure);
    }
  }

  private static void logSaveFailure(Snapshot snapshot, RuntimeException failure) {
    log.warn(
        "Saving a fallback copy failed: source={}, failure={}", snapshot.source(), name(failure));
  }

  private static String name(RuntimeException failure) {
    return failure.getClass().getName();
  }
}
