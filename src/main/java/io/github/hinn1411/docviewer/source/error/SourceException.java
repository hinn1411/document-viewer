package io.github.hinn1411.docviewer.source.error;

import io.github.hinn1411.docviewer.domain.FailureReason;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.Set;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * A failed source call. It keeps no cause, because Spring's I/O error messages contain the request
 * URL, and so the full VIN.
 */
public class SourceException extends RuntimeException {

  private static final Set<Integer> TRANSIENT_STATUSES = Set.of(502, 503, 504);

  private final FailureReason reason;
  private final boolean retryable;

  public SourceException(FailureReason reason, String message) {
    this(reason, false, message);
  }

  private SourceException(FailureReason reason, boolean retryable, String message) {
    super(message);
    this.reason = reason;
    this.retryable = retryable;
  }

  /** Classifies a failed HTTP call. Timeouts are never retried: that adds load to a slow source. */
  public static SourceException from(RestClientException failure) {
    FailureReason reason =
        failure.contains(SocketTimeoutException.class)
            ? FailureReason.TIMEOUT
            : FailureReason.ERROR;
    boolean retryable =
        failure.contains(ConnectException.class)
            || failure instanceof RestClientResponseException response
                && TRANSIENT_STATUSES.contains(response.getStatusCode().value());
    return new SourceException(
        reason, retryable, "Source call failed: " + failure.getClass().getSimpleName());
  }

  public FailureReason reason() {
    return reason;
  }

  public boolean retryable() {
    return retryable;
  }
}
