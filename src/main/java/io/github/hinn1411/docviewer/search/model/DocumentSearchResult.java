package io.github.hinn1411.docviewer.search.model;

import java.util.List;

/** The merged search result. {@link #partial()} and {@link #outcome()} are derived, not stored. */
public record DocumentSearchResult(Vin vin, List<Document> documents, List<SourceOutcome> sources) {

  public boolean partial() {
    return outcome() != SearchOutcome.COMPLETE;
  }

  public SearchOutcome outcome() {
    if (sources.stream().allMatch(source -> source.status() == SourceStatus.UNAVAILABLE)) {
      return SearchOutcome.FAILED;
    }
    return sources.stream().allMatch(source -> source.status() == SourceStatus.OK)
        ? SearchOutcome.COMPLETE
        : SearchOutcome.PARTIAL;
  }
}
