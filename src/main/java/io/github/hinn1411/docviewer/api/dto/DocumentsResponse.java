package io.github.hinn1411.docviewer.api.dto;

import io.github.hinn1411.docviewer.search.model.DocumentSearchResult;
import java.util.List;

/** The body of a successful search. */
public record DocumentsResponse(
    String vin, boolean partial, List<DocumentResponse> documents, List<SourceResponse> sources) {

  public static DocumentsResponse from(DocumentSearchResult result) {
    return new DocumentsResponse(
        result.vin().value(),
        result.partial(),
        result.documents().stream().map(DocumentResponse::from).toList(),
        result.sources().stream().map(SourceResponse::from).toList());
  }
}
