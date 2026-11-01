package io.github.hinn1411.docviewer.search.fallback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.hinn1411.docviewer.config.DocViewerProperties;
import io.github.hinn1411.docviewer.persistence.SnapshotRepository;
import io.github.hinn1411.docviewer.search.model.Document;
import io.github.hinn1411.docviewer.search.model.DocumentType;
import io.github.hinn1411.docviewer.search.model.FailureReason;
import io.github.hinn1411.docviewer.search.model.Snapshot;
import io.github.hinn1411.docviewer.search.model.SourceOutcome;
import io.github.hinn1411.docviewer.search.model.Vin;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataAccessResourceFailureException;

/** T11: a failed source falls back to a copy, and neither reading nor saving can fail a search. */
class SnapshotFallbackTest {

  private static final Vin VIN = Vin.parse("WVWZZZ1JZXW000001");
  private static final Instant NOW = Instant.parse("2026-11-01T10:00:00Z");
  private static final Duration MAX_AGE = Duration.ofHours(24);
  private static final Document DOCUMENT =
      new Document(
          "SALES",
          "1",
          DocumentType.OTHER,
          "Title",
          null,
          URI.create("https://example.com/1"),
          false);
  private static final SourceOutcome LIVE =
      SourceOutcome.ok("SALES", List.of(DOCUMENT), Duration.ZERO);
  private static final SourceOutcome FAILED =
      SourceOutcome.unavailable("SERVICE", FailureReason.TIMEOUT, Duration.ZERO);

  private final SnapshotRepository repository = mock(SnapshotRepository.class);
  private final ExecutorService executor = mock(ExecutorService.class);
  private final SnapshotFallback fallback =
      new SnapshotFallback(
          Clock.fixed(NOW, ZoneOffset.UTC),
          repository,
          executor,
          new DocViewerProperties(Duration.ofSeconds(2), MAX_AGE, ZoneOffset.UTC, Map.of()));

  @Test
  void savesLiveAnswersInBackground() {
    SourceOutcome empty = SourceOutcome.ok("EMPTY", List.of(), Duration.ZERO);

    fallback.saveInBackground(VIN, List.of(LIVE, empty, FAILED), NOW);

    verify(repository, never()).save(any());
    runBackgroundTasks();
    ArgumentCaptor<Snapshot> saved = ArgumentCaptor.forClass(Snapshot.class);
    verify(repository, times(2)).save(saved.capture());
    assertThat(saved.getAllValues())
        .containsExactly(
            new Snapshot(VIN, "SALES", List.of(DOCUMENT), NOW),
            new Snapshot(VIN, "EMPTY", List.of(), NOW));
  }

  @Test
  void unavailableWhenCopyTooOld() {
    assertThat(fallback.applyTo(VIN, FAILED)).isEqualTo(FAILED);

    verify(repository).findNewerThan(VIN, "SERVICE", NOW.minus(MAX_AGE));
  }

  @Test
  void unavailableWhenCopyCannotBeRead() {
    when(repository.findNewerThan(any(), any(), any()))
        .thenThrow(new DataAccessResourceFailureException("database down"));

    assertThat(fallback.applyTo(VIN, FAILED)).isEqualTo(FAILED);

    verify(repository).findNewerThan(any(), any(), any());
  }

  @Test
  @ExtendWith(OutputCaptureExtension.class)
  void snapshotSaveFailureIsOnlyLogged(CapturedOutput output) {
    // PostgreSQL's error detail can contain the whole row, including the VIN.
    doThrow(new DataAccessResourceFailureException("Failing row contains " + VIN.value()))
        .when(repository)
        .save(any());

    fallback.saveInBackground(VIN, List.of(LIVE), NOW);

    assertThatCode(this::runBackgroundTasks).doesNotThrowAnyException();
    assertThat(output)
        .contains("SALES", "DataAccessResourceFailureException")
        .doesNotContain(VIN.value());
  }

  @Test
  void rejectedBackgroundSaveIsOnlyLogged() {
    doThrow(new RejectedExecutionException("shutting down")).when(executor).execute(any());

    assertThatCode(() -> fallback.saveInBackground(VIN, List.of(LIVE), NOW))
        .doesNotThrowAnyException();
  }

  private void runBackgroundTasks() {
    ArgumentCaptor<Runnable> tasks = ArgumentCaptor.forClass(Runnable.class);
    verify(executor, atLeastOnce()).execute(tasks.capture());
    tasks.getAllValues().forEach(Runnable::run);
  }
}
