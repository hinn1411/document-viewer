package io.github.hinn1411.docviewer.source.error;

import io.github.hinn1411.docviewer.domain.FailureReason;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.Set;
import org.springframework.web.client.ResourceAccessException;
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
  private final boolean countsAgainstSource;

  public SourceException(FailureReason reason, String message) {
    this(reason, false, false, message);
  }

  private SourceException(
      FailureReason reason, boolean retryable, boolean countsAgainstSource, String message) {
    super(message);
    this.reason = reason;
    this.retryable = retryable;
    this.countsAgainstSource = countsAgainstSource;
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
    // Wider than the retry rule: a plain 500 isn't worth retrying, but the source is in trouble.
    boolean countsAgainstSource =
        reason == FailureReason.TIMEOUT
            || failure instanceof ResourceAccessException
            || failure instanceof RestClientResponseException response
                && response.getStatusCode().is5xxServerError();
    return new SourceException(
        reason,
        retryable,
        countsAgainstSource,
        "Source call failed: " + failure.getClass().getSimpleName());
  }

  /**
   * Whether the circuit breaker records this as a failure: any 5xx, timeout or I/O failure.
   * Anything else counts as a success in its window, such as a 4xx, where the source answered and
   * the problem may be one VIN.
   */
  public boolean countsAgainstSource() {
    return countsAgainstSource;
  }

  public FailureReason reason() {
    return reason;
  }

  public boolean retryable() {
    return retryable;
  }
}
