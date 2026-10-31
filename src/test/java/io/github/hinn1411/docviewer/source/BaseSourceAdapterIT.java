package io.github.hinn1411.docviewer.source;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.serviceUnavailable;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import io.github.hinn1411.docviewer.search.model.FailureReason;
import io.github.hinn1411.docviewer.search.model.Vin;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The failure tests every source adapter must pass. {@code fetch} is called through the Spring
 * proxy, so {@code @Retryable} applies. A subclass supplies the context and its mock source.
 */
public abstract class BaseSourceAdapterIT {

  // Outside the demo-VIN table, so each test's own stub is the only one that matches.
  protected static final Vin VIN = Vin.parse("WVWZZZ1JZXW000099");

  @Autowired protected DocumentSource source;

  protected abstract WireMockExtension api();

  /** The source's request path for {@link #VIN}. */
  protected abstract String path();

  /** A valid response with no documents. */
  protected abstract String emptyBody();

  @Test
  void throwsErrorOn404WithoutRetry() {
    stub(aResponse().withStatus(404));

    assertFails(FailureReason.ERROR, 1);
  }

  @Test
  void neverPutsVinInTheException() {
    // Spring's I/O error message contains the request URL, and so the VIN.
    stub(aResponse().withFault(Fault.EMPTY_RESPONSE));

    SourceException failure = fetchFailure();

    assertThat(failure.getCause()).isNull();
    assertThat(failure.getMessage()).doesNotContain(VIN.value());
  }

  @Test
  void retriesOnceOn503() {
    api()
        .stubFor(
            get(urlPathEqualTo(path()))
                .inScenario("retry")
                .whenScenarioStateIs(Scenario.STARTED)
                .willSetStateTo("recovered")
                .willReturn(serviceUnavailable()));
    api()
        .stubFor(
            get(urlPathEqualTo(path()))
                .inScenario("retry")
                .whenScenarioStateIs("recovered")
                .willReturn(okJson(emptyBody())));

    assertThat(source.fetch(VIN)).isEmpty();
    api().verify(2, getRequestedFor(urlPathEqualTo(path())));
  }

  @Test
  void throwsErrorAfterSecond503() {
    stub(serviceUnavailable());

    assertFails(FailureReason.ERROR, 2);
  }

  @Test
  void throwsTimeoutAndDoesNotRetrySlowRead() {
    stub(okJson(emptyBody()).withFixedDelay(2_000));

    assertFails(FailureReason.TIMEOUT, 1);
  }

  @Test
  void throwsTimeoutWhenBodyStallsAfterHeaders() {
    // Two chunks over 4 s: the first arrives at once, then nothing for longer than the read
    // timeout.
    stub(okJson(emptyBody()).withChunkedDribbleDelay(2, 4_000));

    assertFails(FailureReason.TIMEOUT, 1);
  }

  protected void stub(ResponseDefinitionBuilder response) {
    api().stubFor(get(urlPathEqualTo(path())).willReturn(response));
  }

  protected void assertFails(FailureReason reason, int requests) {
    assertThat(fetchFailure().reason()).isEqualTo(reason);
    api().verify(requests, getRequestedFor(urlPathEqualTo(path())));
  }

  /** A JSON string value, or the JSON {@code null}. */
  protected static String json(String value) {
    return value == null ? "null" : "\"" + value + "\"";
  }

  private SourceException fetchFailure() {
    SourceException failure = catchThrowableOfType(SourceException.class, () -> source.fetch(VIN));
    assertThat(failure).isNotNull();
    return failure;
  }
}
