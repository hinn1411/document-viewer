package io.github.hinn1411.docviewer.search;

import io.github.hinn1411.docviewer.config.DocViewerProperties;
import io.github.hinn1411.docviewer.search.model.Caller;
import io.github.hinn1411.docviewer.search.model.DocumentSearchResult;
import io.github.hinn1411.docviewer.search.model.FailureReason;
import io.github.hinn1411.docviewer.search.model.SourceOutcome;
import io.github.hinn1411.docviewer.search.model.Vin;
import io.github.hinn1411.docviewer.source.DocumentSource;
import io.github.hinn1411.docviewer.source.failure.SourceException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Service;

/**
 * Calls every source at once and merges the results. Each source has a deadline, and each one ends
 * as an outcome, so one slow or broken source can't fail the search. Only a failed audit can.
 */
@Service
public class DocumentSearchService {

  private final List<DocumentSource> sources;
  private final ResultMerger merger;
  private final ExecutorService sourceCallExecutor;
  private final Duration deadline;
  private final AccessAuditor auditor;

  public DocumentSearchService(
      List<DocumentSource> sources,
      ResultMerger merger,
      ExecutorService sourceCallExecutor,
      DocViewerProperties properties,
      AccessAuditor auditor,
      SnapshotFallback fallback) {
    this.sources = sources;
    this.merger = merger;
    this.sourceCallExecutor = sourceCallExecutor;
    this.deadline = properties.sourceDeadline();
    this.auditor = auditor;
  }

  public DocumentSearchResult search(Vin vin, Caller caller) {
    // Start every call before waiting for any of them, so they run in parallel.
    List<CompletableFuture<SourceOutcome>> calls =
        sources.stream().map(source -> callWithDeadline(source, vin)).toList();
    List<SourceOutcome> outcomes = calls.stream().map(CompletableFuture::join).toList();
    DocumentSearchResult result = merger.merge(vin, outcomes);
    auditor.record(caller, result);
    return result;
  }

  /**
   * Past the deadline the search stops waiting. A stalled call then ends at the read timeout, and
   * one still trickling data runs on until the source finishes or the connection drops.
   */
  private CompletableFuture<SourceOutcome> callWithDeadline(DocumentSource source, Vin vin) {
    SourceOutcome timedOut =
        SourceOutcome.unavailable(source.name(), FailureReason.TIMEOUT, deadline);
    return CompletableFuture.supplyAsync(() -> call(source, vin), sourceCallExecutor)
        .completeOnTimeout(timedOut, deadline.toMillis(), TimeUnit.MILLISECONDS);
  }

  private static SourceOutcome call(DocumentSource source, Vin vin) {
    long start = System.nanoTime();
    try {
      return SourceOutcome.ok(source.name(), source.fetch(vin), elapsedSince(start));
    } catch (SourceException failure) {
      return SourceOutcome.unavailable(source.name(), failure.reason(), elapsedSince(start));
    } catch (RuntimeException unexpected) {
      return SourceOutcome.unavailable(source.name(), FailureReason.ERROR, elapsedSince(start));
    }
  }

  private static Duration elapsedSince(long startNanos) {
    return Duration.ofNanos(System.nanoTime() - startNanos);
  }
}
