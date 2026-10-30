package io.github.hinn1411.docviewer.search.model;

import java.util.List;

/** Stub for the T06 red step: partial and outcome aren't derived yet. */
public record DocumentSearchResult(Vin vin, List<Document> documents, List<SourceOutcome> sources) {

  public boolean partial() {
    return false;
  }

  public SearchOutcome outcome() {
    return null;
  }
}
