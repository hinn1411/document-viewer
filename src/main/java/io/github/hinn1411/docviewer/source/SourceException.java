package io.github.hinn1411.docviewer.source;

import io.github.hinn1411.docviewer.search.model.FailureReason;
import org.springframework.web.client.RestClientException;

/** A failed source call. Stub for T07's red step. */
public class SourceException extends RuntimeException {

  public static SourceException from(RestClientException failure) {
    throw new UnsupportedOperationException("T07 green step");
  }

  public FailureReason reason() {
    throw new UnsupportedOperationException("T07 green step");
  }
}
