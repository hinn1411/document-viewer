package io.github.hinn1411.docviewer.api.logging;

import io.github.hinn1411.docviewer.domain.Caller;
import io.github.hinn1411.docviewer.domain.DocumentSearchResult;
import io.github.hinn1411.docviewer.domain.SourceOutcome;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Writes the one summary line for a search. It holds counts and statuses, never document data. */
public final class SearchSummaryLog {

  private static final Logger log = LoggerFactory.getLogger(SearchSummaryLog.class);

  private SearchSummaryLog() {}

  public static void write(
      Caller caller, DocumentSearchResult result, int httpStatus, long durationMs) {
    log.atInfo()
        .setMessage("documents.search completed")
        .addKeyValue("userId", caller.userId())
        .addKeyValue("dealershipId", caller.dealershipId())
        .addKeyValue("vin", result.vin().masked())
        .addKeyValue("httpStatus", httpStatus)
        .addKeyValue("outcome", result.outcome())
        .addKeyValue("durationMs", durationMs)
        .addKeyValue("sources", sourcesOf(result))
        .log();
  }

  /** Maps, because the JSON log format writes a record as its {@code toString}. */
  private static List<Map<String, Object>> sourcesOf(DocumentSearchResult result) {
    return result.sources().stream().map(SearchSummaryLog::sourceOf).toList();
  }

  private static Map<String, Object> sourceOf(SourceOutcome outcome) {
    // Not Map.of: a source that answered has no reason, and Map.of rejects null.
    Map<String, Object> source = new LinkedHashMap<>();
    source.put("source", outcome.source());
    source.put("status", outcome.status());
    source.put("reason", outcome.reason());
    source.put("latencyMs", outcome.latency().toMillis());
    source.put("documentCount", outcome.documents().size());
    return source;
  }
}
