package io.github.hinn1411.docviewer.search.model;

import java.net.URI;
import java.time.Instant;

/**
 * The common document format every adapter produces. {@code id} is {@code source:sourceDocumentId}
 * and {@code documentDate} is a UTC instant, or null if the source has none.
 */
public record Document(
    String id,
    String source,
    String sourceDocumentId,
    DocumentType type,
    String sourceType,
    String title,
    Instant documentDate,
    URI url,
    boolean stale) {}
