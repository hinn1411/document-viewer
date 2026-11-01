package io.github.hinn1411.docviewer.domain;

/** The overall result of a search, written to the audit row and mapped to the HTTP status. */
public enum SearchOutcome {
  COMPLETE,
  PARTIAL,
  FAILED
}
