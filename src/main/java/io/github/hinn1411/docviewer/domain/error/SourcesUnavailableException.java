package io.github.hinn1411.docviewer.domain.error;

import io.github.hinn1411.docviewer.domain.SourceOutcome;
import java.util.List;

/** No source answered and no fallback copy exists, so there is nothing to return. */
public class SourcesUnavailableException extends RuntimeException {

  private final transient List<SourceOutcome> sources;

  public SourcesUnavailableException(List<SourceOutcome> sources) {
    super("No source could provide documents");
    this.sources = sources;
  }

  public List<SourceOutcome> sources() {
    return sources;
  }
}
