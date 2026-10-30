package io.github.hinn1411.docviewer.search.model;

/** How a source ended up in a search. {@link #STALE} means the fallback copy was used. */
public enum SourceStatus {
  OK,
  STALE,
  UNAVAILABLE
}
