package io.github.hinn1411.docviewer.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.assertj.core.api.Assertions.entry;
import static org.assertj.core.groups.Tuple.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.hinn1411.docviewer.config.properties.DocViewerProperties;
import io.github.hinn1411.docviewer.domain.AccessAuditRecord;
import io.github.hinn1411.docviewer.domain.Caller;
import io.github.hinn1411.docviewer.domain.Document;
import io.github.hinn1411.docviewer.domain.DocumentSearchResult;
import io.github.hinn1411.docviewer.domain.DocumentType;
import io.github.hinn1411.docviewer.domain.FailureReason;
import io.github.hinn1411.docviewer.domain.ResultMerger;
import io.github.hinn1411.docviewer.domain.SearchOutcome;
import io.github.hinn1411.docviewer.domain.Snapshot;
import io.github.hinn1411.docviewer.domain.SourceOutcome;
import io.github.hinn1411.docviewer.domain.SourceStatus;
import io.github.hinn1411.docviewer.domain.Vin;
import io.github.hinn1411.docviewer.domain.error.AuditUnavailableException;
import io.github.hinn1411.docviewer.persistence.AuditRepository;
import io.github.hinn1411.docviewer.persistence.SnapshotRepository;
import io.github.hinn1411.docviewer.search.support.audit.AccessAuditor;
import io.github.hinn1411.docviewer.search.support.fallback.SnapshotFallback;
import io.github.hinn1411.docviewer.source.DocumentSource;
import io.github.hinn1411.docviewer.source.error.SourceException;
import io.micrometer.tracing.Tracer;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;

/**
 * T09: sources are called at once, each with a deadline, and each ends as one outcome. T10: audit.
 * T11: fallback copies.
 */
class DocumentSearchServiceTest {

  private static final Vin VIN = Vin.parse("WVWZZZ1JZXW000001");
  private static final Duration DEADLINE = Duration.ofSeconds(2);
  private static final Duration MAX_AGE = Duration.ofHours(24);
  private static final Caller CALLER = new Caller("user-1", "dealer-1");
  private static final Instant NOW = Instant.parse("2026-11-01T10:00:00Z");
  private static final Clock FIXED_CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

  private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
  private final CountDownLatch release = new CountDownLatch(1);
  private Clock clock = FIXED_CLOCK;
  private final AuditRepository auditRepository = mock(AuditRepository.class);
  private final SnapshotRepository snapshotRepository = mock(SnapshotRepository.class);
  private final Tracer tracer = mock(Tracer.class, RETURNS_DEEP_STUBS);

  @AfterEach
  void endFakeSources() {
    release.countDown();
    executor.close();
  }

  @Test
  void callsSourcesInParallel() {
    long start = System.nanoTime();

    DocumentSearchResult result =
        search(slow("SALES", Duration.ofSeconds(1)), slow("SERVICE", Duration.ofSeconds(1)));

    assertThat(elapsedSince(start)).isLessThan(Duration.ofMillis(1_500));
    assertThat(result.outcome()).isEqualTo(SearchOutcome.COMPLETE);
    assertThat(result.documents()).extracting(Document::source).contains("SALES", "SERVICE");
    assertThat(result.sources())
        .extracting(SourceOutcome::latency)
        .allSatisfy(latency -> assertThat(latency).isGreaterThanOrEqualTo(Duration.ofSeconds(1)));
  }

  @Test
  void timesOutSlowSourceAt2s() {
    long start = System.nanoTime();

    DocumentSearchResult result =
        search(answers("SALES", document("SALES")), slow("SERVICE", Duration.ofSeconds(5)));

    assertThat(elapsedSince(start)).isBetween(DEADLINE, Duration.ofMillis(2_500));
    SourceOutcome service = result.sources().getLast();
    assertThat(result.sources().getFirst().status()).isEqualTo(SourceStatus.OK);
    assertThat(service.status()).isEqualTo(SourceStatus.UNAVAILABLE);
    assertThat(service.reason()).isEqualTo(FailureReason.TIMEOUT);
    assertThat(service.latency()).isGreaterThanOrEqualTo(DEADLINE);
    assertThat(result.documents()).extracting(Document::source).containsExactly("SALES");
  }

  @Test
  void oneFailureDoesNotHideOtherSource() {
    DocumentSearchResult result =
        search(
            answers("SALES", document("SALES")),
            fails("SERVICE", new SourceException(FailureReason.ERROR, "failed")));

    assertThat(result.documents()).extracting(Document::source).containsExactly("SALES");
    assertThat(result.outcome()).isEqualTo(SearchOutcome.PARTIAL);
    assertThat(result.sources().getLast().reason()).isEqualTo(FailureReason.ERROR);
  }

  @Test
  void everySourceEndsWithAResult() {
    DocumentSearchResult result =
        search(
            answers("OK", document("OK")),
            fails("TIMED-OUT", new SourceException(FailureReason.TIMEOUT, "failed")),
            fails("BROKEN", new IllegalStateException("a bug in an adapter")),
            slow("SLOW", Duration.ofSeconds(5)));

    assertThat(result.sources())
        .extracting(SourceOutcome::source)
        .containsExactly("OK", "TIMED-OUT", "BROKEN", "SLOW");
    assertThat(result.sources())
        .extracting(SourceOutcome::status)
        .containsExactly(
            SourceStatus.OK,
            SourceStatus.UNAVAILABLE,
            SourceStatus.UNAVAILABLE,
            SourceStatus.UNAVAILABLE);
    assertThat(result.sources())
        .extracting(SourceOutcome::reason)
        .containsExactly(null, FailureReason.TIMEOUT, FailureReason.ERROR, FailureReason.TIMEOUT);
    assertThat(result.sources()).extracting(SourceOutcome::latency).doesNotContainNull();
  }

  @Test
  void auditsOncePerSearch() {
    when(tracer.currentSpan().context().traceId()).thenReturn("trace-1");

    search(
        answers("SALES", document("SALES")),
        fails("SERVICE", new SourceException(FailureReason.ERROR, "failed")));

    AccessAuditRecord record = savedRecord();
    assertThat(record.id()).isNotNull();
    assertThat(record.occurredAt()).isEqualTo(NOW);
    assertThat(record.caller()).isEqualTo(CALLER);
    assertThat(record.vin()).isEqualTo(VIN);
    assertThat(record.traceId()).isEqualTo("trace-1");
    assertThat(record.outcome()).isEqualTo(SearchOutcome.PARTIAL);
    assertThat(record.sourceStatuses())
        .containsExactly(
            entry("SALES", SourceStatus.OK), entry("SERVICE", SourceStatus.UNAVAILABLE));
    assertThat(record.documentCount()).isEqualTo(1);
  }

  @Test
  void auditsFailedSearches() {
    DocumentSearchResult result =
        search(fails("SALES", new SourceException(FailureReason.ERROR, "failed")));

    assertThat(result.outcome()).isEqualTo(SearchOutcome.FAILED);
    assertThat(savedRecord().outcome()).isEqualTo(SearchOutcome.FAILED);
    assertThat(savedRecord().documentCount()).isZero();
  }

  @Test
  void failsClosedWhenAuditFails() {
    // PostgreSQL's error detail can contain the whole row, including the VIN.
    doThrow(new DataAccessResourceFailureException("Failing row contains " + VIN.value()))
        .when(auditRepository)
        .save(any());

    AuditUnavailableException failure =
        catchThrowableOfType(
            AuditUnavailableException.class, () -> search(answers("SALES", document("SALES"))));

    assertThat(failure).isNotNull();
    assertThat(failure.getCause()).isNull();
    assertThat(failure.getMessage()).doesNotContain(VIN.value());
  }

  @Test
  void writesNoTraceMarkerWithoutSpan() {
    when(tracer.currentSpan()).thenReturn(null);

    search(answers("SALES", document("SALES")));

    assertThat(savedRecord().traceId()).isEqualTo("no-trace");
  }

  @Test
  void servesStaleCopyWhenSourceFails() {
    Instant threeHoursAgo = NOW.minus(Duration.ofHours(3));
    when(snapshotRepository.findNewerThan(eq(VIN), eq("SERVICE"), any()))
        .thenReturn(
            Optional.of(new Snapshot(VIN, "SERVICE", List.of(document("SERVICE")), threeHoursAgo)));

    DocumentSearchResult result =
        search(
            answers("SALES", document("SALES")),
            fails("SERVICE", new SourceException(FailureReason.ERROR, "failed")));

    SourceOutcome service = result.sources().getLast();
    assertThat(service.status()).isEqualTo(SourceStatus.STALE);
    assertThat(service.reason()).isEqualTo(FailureReason.ERROR);
    assertThat(service.dataAsOf()).isEqualTo(threeHoursAgo);
    assertThat(result.documents())
        .extracting(Document::source, Document::stale)
        .containsExactlyInAnyOrder(tuple("SALES", false), tuple("SERVICE", true));
    assertThat(result.outcome()).isEqualTo(SearchOutcome.PARTIAL);
  }

  @Test
  void datesCopyWhenTheSearchStarted() {
    // A clock that moves on after the first reading, as time does during a search.
    clock = mock(Clock.class);
    when(clock.instant()).thenReturn(NOW, NOW.plusSeconds(5));

    search(answers("SALES", document("SALES")));
    executor.close();

    ArgumentCaptor<Snapshot> saved = ArgumentCaptor.forClass(Snapshot.class);
    verify(snapshotRepository).save(saved.capture());
    assertThat(saved.getValue().fetchedAt()).isEqualTo(NOW);
  }

  private AccessAuditRecord savedRecord() {
    ArgumentCaptor<AccessAuditRecord> record = ArgumentCaptor.forClass(AccessAuditRecord.class);
    verify(auditRepository).save(record.capture());
    return record.getValue();
  }

  private DocumentSearchResult search(DocumentSource... sources) {
    DocViewerProperties properties =
        new DocViewerProperties(DEADLINE, MAX_AGE, ZoneOffset.UTC, Map.of());
    AccessAuditor auditor = new AccessAuditor(clock, tracer, auditRepository);
    SnapshotFallback fallback =
        new SnapshotFallback(clock, snapshotRepository, executor, properties);
    return new DocumentSearchService(
            List.of(sources), new ResultMerger(), executor, properties, auditor, fallback, clock)
        .search(VIN, CALLER);
  }

  /** Blocks for the delay, or until the test ends, then answers with one document. */
  private DocumentSource slow(String name, Duration delay) {
    return source(
        name,
        () -> {
          try {
            release.await(delay.toMillis(), TimeUnit.MILLISECONDS);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          return List.of(document(name));
        });
  }

  private static DocumentSource answers(String name, Document document) {
    return source(name, () -> List.of(document));
  }

  private static DocumentSource fails(String name, RuntimeException failure) {
    return source(
        name,
        () -> {
          throw failure;
        });
  }

  private static DocumentSource source(String name, Supplier<List<Document>> fetch) {
    return new DocumentSource() {
      @Override
      public String name() {
        return name;
      }

      @Override
      public List<Document> fetch(Vin vin) {
        return fetch.get();
      }
    };
  }

  private static Document document(String source) {
    return new Document(
        source, "1", DocumentType.OTHER, "Title", null, URI.create("https://example.com/1"), false);
  }

  private static Duration elapsedSince(long startNanos) {
    return Duration.ofNanos(System.nanoTime() - startNanos);
  }
}
