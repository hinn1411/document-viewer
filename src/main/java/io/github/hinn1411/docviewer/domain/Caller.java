package io.github.hinn1411.docviewer.domain;

import io.github.hinn1411.docviewer.domain.error.MissingCallerException;

/** Who is searching, from the gateway's identity headers (A1). Both values are always present. */
public record Caller(String userId, String dealershipId) {

  public Caller {
    if (userId == null || userId.isBlank() || dealershipId == null || dealershipId.isBlank()) {
      throw new MissingCallerException();
    }
  }
}
