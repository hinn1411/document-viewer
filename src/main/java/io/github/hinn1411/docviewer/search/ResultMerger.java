package io.github.hinn1411.docviewer.search;

import io.github.hinn1411.docviewer.search.model.DocumentSearchResult;
import io.github.hinn1411.docviewer.search.model.SourceOutcome;
import io.github.hinn1411.docviewer.search.model.Vin;
import java.util.List;

/** Stub for the T06 red step: no merging yet. */
public class ResultMerger {

  public DocumentSearchResult merge(Vin vin, List<SourceOutcome> outcomes) {
    return new DocumentSearchResult(vin, List.of(), outcomes);
  }
}
