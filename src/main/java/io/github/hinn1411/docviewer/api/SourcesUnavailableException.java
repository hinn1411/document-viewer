package io.github.hinn1411.docviewer.api;

import io.github.hinn1411.docviewer.search.model.SourceOutcome;
import java.util.List;

/** No source answered and no fallback copy exists, so there is nothing to return. */
class SourcesUnavailableException extends RuntimeException {

  private final transient List<SourceOutcome> sources;

  SourcesUnavailableException(List<SourceOutcome> sources) {
    super("No source could provide documents");
    this.sources = sources;
  }

  List<SourceOutcome> sources() {
    return sources;
  }
}
