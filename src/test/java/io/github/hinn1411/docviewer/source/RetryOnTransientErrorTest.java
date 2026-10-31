package io.github.hinn1411.docviewer.source;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.hinn1411.docviewer.search.model.Vin;
import java.lang.reflect.Method;
import java.net.ConnectException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;

/**
 * T07: only short-lived failures are retried. A timeout never is: it adds load to a slow source.
 */
class RetryOnTransientErrorTest {

  private final RetryOnTransientError predicate = new RetryOnTransientError();

  @ParameterizedTest
  @MethodSource
  void retriesTransientFailures(RestClientException failure) throws Exception {
    assertThat(predicate.shouldRetry(fetchMethod(), SourceException.from(failure))).isTrue();
  }

  static Stream<RestClientException> retriesTransientFailures() {
    return Stream.of(
        serverError(HttpStatus.BAD_GATEWAY),
        serverError(HttpStatus.SERVICE_UNAVAILABLE),
        serverError(HttpStatus.GATEWAY_TIMEOUT),
        new ResourceAccessException("refused", new ConnectException()));
  }

  @ParameterizedTest
  @MethodSource
  void doesNotRetryOtherFailures(RestClientException failure) throws Exception {
    assertThat(predicate.shouldRetry(fetchMethod(), SourceException.from(failure))).isFalse();
  }

  static Stream<RestClientException> doesNotRetryOtherFailures() {
    return Stream.of(
        serverError(HttpStatus.INTERNAL_SERVER_ERROR),
        HttpClientErrorException.create(
            HttpStatus.NOT_FOUND, "Not Found", HttpHeaders.EMPTY, new byte[0], null),
        new ResourceAccessException("slow read", new HttpTimeoutException("timed out")),
        new ResourceAccessException("slow connect", new HttpConnectTimeoutException("timed out")),
        new RestClientException("unreadable body"));
  }

  private static RestClientException serverError(HttpStatus status) {
    return HttpServerErrorException.create(
        status, status.getReasonPhrase(), HttpHeaders.EMPTY, new byte[0], null);
  }

  private static Method fetchMethod() throws NoSuchMethodException {
    return DocumentSource.class.getMethod("fetch", Vin.class);
  }
}
