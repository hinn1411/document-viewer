package io.github.hinn1411.docviewer.persistence;

import io.github.hinn1411.docviewer.config.DocViewerProperties;
import java.time.Clock;
import java.time.Duration;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Deletes expired fallback copies every hour, so personal data isn't kept past the 24 h limit. Two
 * instances running it together is harmless: the delete is safe to repeat.
 */
@Component
public class SnapshotCleanupJob {

  private final SnapshotRepository snapshotRepository;
  private final Clock clock;
  private final Duration maxAge;

  public SnapshotCleanupJob(
      SnapshotRepository snapshotRepository, Clock clock, DocViewerProperties properties) {
    this.snapshotRepository = snapshotRepository;
    this.clock = clock;
    this.maxAge = properties.fallbackMaxAge();
  }

  @Scheduled(cron = "0 0 * * * *")
  public void deleteExpiredSnapshots() {
    snapshotRepository.deleteOlderThan(clock.instant().minus(maxAge));
  }
}
