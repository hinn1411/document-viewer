package io.github.hinn1411.docviewer.source;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.hinn1411.docviewer.search.model.FailureReason;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * T07: only short-lived failures are retried. A timeout never is: it adds load to a slow source.
 */
class RetryOnTransientErrorTest {

  @ParameterizedTest
  @CsvSource({"502, true", "503, true", "504, true", "500, false", "404, false"})
  void retriesOnlyTransientStatuses(int status, boolean retried) {
    RestClientException failure =
        new RestClientResponseException(
            "failed",
            HttpStatusCode.valueOf(status),
            "failed",
            HttpHeaders.EMPTY,
            new byte[0],
            null);

    assertThat(retried(failure)).isEqualTo(retried);
  }

  @Test
  void retriesRefusedConnection() {
    assertThat(retried(new ResourceAccessException("refused", new ConnectException()))).isTrue();
  }

  @Test
  void doesNotRetryTimeout() {
    RestClientException failure = new ResourceAccessException("slow", new SocketTimeoutException());

    assertThat(retried(failure)).isFalse();
    assertThat(SourceException.from(failure).reason()).isEqualTo(FailureReason.TIMEOUT);
  }

  private static boolean retried(RestClientException failure) {
    return new RetryOnTransientError().shouldRetry(null, SourceException.from(failure));
  }
}
