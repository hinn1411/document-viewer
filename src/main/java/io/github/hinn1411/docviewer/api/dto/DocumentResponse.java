package io.github.hinn1411.docviewer.api.dto;

import io.github.hinn1411.docviewer.domain.Document;
import io.github.hinn1411.docviewer.domain.DocumentType;
import java.net.URI;
import java.time.Instant;

/** One document in the response. {@code documentDate} is null when the source gave no date. */
public record DocumentResponse(
    String id,
    String source,
    DocumentType type,
    String title,
    Instant documentDate,
    URI url,
    boolean stale) {

  static DocumentResponse from(Document document) {
    return new DocumentResponse(
        document.id(),
        document.source(),
        document.type(),
        document.title(),
        document.documentDate(),
        document.url(),
        document.stale());
  }
}
