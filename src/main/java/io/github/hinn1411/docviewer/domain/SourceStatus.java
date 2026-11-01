package io.github.hinn1411.docviewer.domain;

/** How a source ended up in a search. {@link #STALE} means the fallback copy was used. */
public enum SourceStatus {
  OK,
  STALE,
  UNAVAILABLE
}
