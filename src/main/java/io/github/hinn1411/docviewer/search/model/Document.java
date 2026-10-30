package io.github.hinn1411.docviewer.search.model;

import java.net.URI;
import java.time.Instant;

/** The common document format every adapter produces. {@code documentDate} may be null. */
public record Document(
    String source,
    String sourceDocumentId,
    DocumentType type,
    String title,
    Instant documentDate,
    URI url,
    boolean stale) {

  public String id() {
    return source + ":" + sourceDocumentId;
  }

  public Document asStale() {
    return new Document(source, sourceDocumentId, type, title, documentDate, url, true);
  }
}
