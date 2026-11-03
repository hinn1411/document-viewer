package io.github.hinn1411.docviewer.search;

import io.github.hinn1411.docviewer.config.properties.DocViewerProperties;
import io.github.hinn1411.docviewer.domain.Caller;
import io.github.hinn1411.docviewer.domain.Document;
import io.github.hinn1411.docviewer.domain.DocumentSearchResult;
import io.github.hinn1411.docviewer.domain.FailureReason;
import io.github.hinn1411.docviewer.domain.ResultMerger;
import io.github.hinn1411.docviewer.domain.SourceOutcome;
import io.github.hinn1411.docviewer.domain.SourceStatus;
import io.github.hinn1411.docviewer.domain.Vin;
import io.github.hinn1411.docviewer.observability.DocumentViewerMetrics;
import io.github.hinn1411.docviewer.observability.DocumentViewerSpans;
import io.github.hinn1411.docviewer.search.support.audit.AccessAuditor;
import io.github.hinn1411.docviewer.search.support.fallback.SnapshotFallback;
import io.github.hinn1411.docviewer.source.DocumentSource;
import io.github.hinn1411.docviewer.source.error.SourceException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.tracing.Span;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Calls every source at once and merges the results. Each source has a deadline and a circuit
 * breaker, and each one ends as an outcome, so one slow or broken source can't fail the search.
 * Only a failed audit can.
 */
@Service
public class DocumentSearchService {

  private static final Logger log = LoggerFactory.getLogger(DocumentSearchService.class);

  private final List<DocumentSource> sources;
  private final ResultMerger merger;
  private final ExecutorService sourceCallExecutor;
  private final Duration deadline;
  private final AccessAuditor auditor;
  private final SnapshotFallback fallback;
  private final Clock clock;
  private final CircuitBreakerRegistry breakers;
  private final DocumentViewerMetrics metrics;
  private final DocumentViewerSpans spans;

  public DocumentSearchService(
      List<DocumentSource> sources,
      ResultMerger merger,
      ExecutorService sourceCallExecutor,
      DocViewerProperties properties,
      AccessAuditor auditor,
      SnapshotFallback fallback,
      Clock clock,
      CircuitBreakerRegistry breakers,
      DocumentViewerMetrics metrics,
      DocumentViewerSpans spans) {
    this.sources = sources;
    this.merger = merger;
    this.sourceCallExecutor = sourceCallExecutor;
    this.deadline = properties.sourceDeadline();
    this.auditor = auditor;
    this.fallback = fallback;
    this.clock = clock;
    this.breakers = breakers;
    this.metrics = metrics;
    this.spans = spans;
    sources.forEach(source -> metrics.registerSource(source.name()));
  }

  public DocumentSearchResult search(Vin vin, Caller caller) {
    Instant startedAt = clock.instant();
    // Start every call before waiting for any of them, so they run in parallel.
    List<CompletableFuture<SourceOutcome>> calls =
        sources.stream().map(source -> callWithDeadline(source, vin)).toList();
    List<SourceOutcome> outcomes =
        calls.stream()
            .map(CompletableFuture::join)
            .map(
                outcome -> {
                  metrics.recordSourceCall(outcome);
                  logIfCallFailed(outcome);
                  return fallback.applyTo(vin, outcome);
                })
            .toList();
    DocumentSearchResult result = merger.merge(vin, outcomes);
    auditor.record(caller, result);
    fallback.saveInBackground(vin, outcomes, startedAt);
    return result;
  }

  /**
   * Past the deadline the search stops waiting. A stalled call then ends at the read timeout, and
   * one still trickling data runs on until the source finishes or the connection drops.
   */
  private CompletableFuture<SourceOutcome> callWithDeadline(DocumentSource source, Vin vin) {
    SourceOutcome timedOut =
        SourceOutcome.unavailable(source.name(), FailureReason.TIMEOUT, deadline);
    Span span = spans.startSourceCall(source.name(), vin);
    return CompletableFuture.supplyAsync(
            () -> spans.inSourceCall(span, () -> call(source, vin)), sourceCallExecutor)
        .completeOnTimeout(timedOut, deadline.toMillis(), TimeUnit.MILLISECONDS)
        .thenApply(
            outcome -> {
              spans.endSourceCall(span, outcome);
              return outcome;
            });
  }

  /** The breaker is outside the adapter's retry, so one search counts as one call. */
  private SourceOutcome call(DocumentSource source, Vin vin) {
    long start = System.nanoTime();
    try {
      List<Document> documents =
          breakers.circuitBreaker(source.name()).executeSupplier(() -> source.fetch(vin));
      return SourceOutcome.ok(source.name(), documents, elapsedSince(start));
    } catch (CallNotPermittedException open) {
      return SourceOutcome.unavailable(
          source.name(), FailureReason.CIRCUIT_OPEN, elapsedSince(start));
    } catch (SourceException failure) {
      return SourceOutcome.unavailable(source.name(), failure.reason(), elapsedSince(start));
    } catch (RuntimeException unexpected) {
      log.atError()
          .setMessage("Unexpected source failure")
          .addKeyValue("source", source.name())
          .addKeyValue("failure", unexpected.getClass().getName())
          .log();
      return SourceOutcome.unavailable(source.name(), FailureReason.ERROR, elapsedSince(start));
    }
  }

  /**
   * Logged here, after the join, because a call past the deadline never reaches {@link #call}. An
   * open breaker made no call: the summary line shows it, so an outage doesn't add a line per
   * request.
   */
  private static void logIfCallFailed(SourceOutcome outcome) {
    if (outcome.status() == SourceStatus.UNAVAILABLE
        && outcome.reason() != FailureReason.CIRCUIT_OPEN) {
      log.atWarn()
          .setMessage("Source call failed")
          .addKeyValue("source", outcome.source())
          .addKeyValue("reason", outcome.reason())
          .log();
    }
  }

  private static Duration elapsedSince(long startNanos) {
    return Duration.ofNanos(System.nanoTime() - startNanos);
  }
}
