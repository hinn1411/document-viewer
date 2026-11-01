package io.github.hinn1411.docviewer.domain;

import static java.util.Comparator.comparing;
import static java.util.Comparator.nullsLast;

import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Merges each source's documents into one list, in the same order every time (A6, A7). */
@Component
public class ResultMerger {

  private static final Comparator<Document> NEWEST_FIRST =
      comparing(Document::documentDate, nullsLast(Comparator.<Instant>reverseOrder()))
          .thenComparing(Document::source)
          .thenComparing(Document::sourceDocumentId);

  public DocumentSearchResult merge(Vin vin, List<SourceOutcome> outcomes) {
    // The id includes the source, so duplicates are only removed within a source
    Map<String, Document> byId = new LinkedHashMap<>();
    outcomes.stream()
        .flatMap(outcome -> outcome.documents().stream())
        .forEach(document -> byId.putIfAbsent(document.id(), document));

    return new DocumentSearchResult(
        vin, byId.values().stream().sorted(NEWEST_FIRST).toList(), outcomes);
  }
}
