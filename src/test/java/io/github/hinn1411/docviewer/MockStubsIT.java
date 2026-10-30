package io.github.hinn1411.docviewer;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.jayway.jsonpath.JsonPath;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * T04: the stub files Compose mounts return what the demo-VIN table in 01-project-setup.md says.
 * Requests carry no auth header: the sources are internal and need none.
 */
class MockStubsIT {

  private static final String VIN_OK = "WVWZZZ1JZXW000001";
  private static final String VIN_SERVICE_SLOW = "WVWZZZ1JZXW000002";
  private static final String VIN_SERVICE_DOWN = "WVWZZZ1JZXW000003";
  private static final String VIN_BOTH_DOWN = "WVWZZZ1JZXW000004";
  private static final String VIN_NO_DOCUMENTS = "WVWZZZ1JZXW000005";

  @RegisterExtension
  static WireMockExtension sales =
      WireMockExtension.newInstance()
          .options(wireMockConfig().dynamicPort().usingFilesUnderDirectory("mocks/sales"))
          .build();

  @RegisterExtension
  static WireMockExtension service =
      WireMockExtension.newInstance()
          .options(wireMockConfig().dynamicPort().usingFilesUnderDirectory("mocks/service"))
          .build();

  private final HttpClient http = HttpClient.newHttpClient();

  @Test
  void salesStubReturnsNestedDeals() throws Exception {
    HttpResponse<String> response = callSales(VIN_OK);

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.headers().firstValue("Content-Type")).contains("application/json");
    String body = response.body();
    assertThat(JsonPath.<String>read(body, "$.vin")).isEqualTo(VIN_OK);
    assertThat(JsonPath.<String>read(body, "$.deals[0].dealNumber")).isNotBlank();
    assertThat(JsonPath.<String>read(body, "$.deals[0].documents[0].docRef")).isNotBlank();
    assertThat(JsonPath.<String>read(body, "$.deals[0].documents[0].docType")).isNotBlank();
    assertThat(JsonPath.<String>read(body, "$.deals[0].documents[0].name")).isNotBlank();
    assertThat(JsonPath.<String>read(body, "$.deals[0].documents[0].createdOn"))
        .matches("\\d{2}/\\d{2}/\\d{4}");
    assertThat(JsonPath.<String>read(body, "$.deals[0].documents[0].link")).startsWith("https://");
  }

  @Test
  void serviceStubReturnsFlatRecords() throws Exception {
    HttpResponse<String> response = callService(VIN_OK);

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.headers().firstValue("Content-Type")).contains("application/json");
    String body = response.body();
    assertThat(JsonPath.<Integer>read(body, "$[0].recordId")).isPositive();
    assertThat(JsonPath.<String>read(body, "$[0].category")).isNotBlank();
    assertThat(JsonPath.<String>read(body, "$[0].description")).isNotBlank();
    assertThat(JsonPath.<Number>read(body, "$[0].completedAt").longValue()).isPositive();
    assertThat(JsonPath.<String>read(body, "$[0].fileUrl")).startsWith("https://");
  }

  @Test
  void serviceStubDelaysFor000002() throws Exception {
    long start = System.nanoTime();
    HttpResponse<String> response = callService(VIN_SERVICE_SLOW);
    Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(JsonPath.<List<?>>read(response.body(), "$")).isNotEmpty();
    assertThat(elapsed).isGreaterThan(Duration.ofMillis(4500));
  }

  @Test
  void salesStubIsOkFor000002And000003() throws Exception {
    for (String vin : List.of(VIN_SERVICE_SLOW, VIN_SERVICE_DOWN)) {
      HttpResponse<String> response = callSales(vin);

      assertThat(response.statusCode()).as(vin).isEqualTo(200);
      assertThat(JsonPath.<String>read(response.body(), "$.vin")).isEqualTo(vin);
      assertThat(JsonPath.<List<?>>read(response.body(), "$.deals[0].documents"))
          .as(vin)
          .isNotEmpty();
    }
  }

  @Test
  void serviceStubReturns503EveryCallFor000003() throws Exception {
    assertThat(callService(VIN_SERVICE_DOWN).statusCode()).isEqualTo(503);
    assertThat(callService(VIN_SERVICE_DOWN).statusCode()).isEqualTo(503);
    assertThat(callService(VIN_SERVICE_DOWN).statusCode()).isEqualTo(503);
  }

  @Test
  void bothStubsReturn503For000004() throws Exception {
    assertThat(callSales(VIN_BOTH_DOWN).statusCode()).isEqualTo(503);
    assertThat(callService(VIN_BOTH_DOWN).statusCode()).isEqualTo(503);
  }

  @Test
  void bothStubsReturnNoDocumentsFor000005() throws Exception {
    HttpResponse<String> salesResponse = callSales(VIN_NO_DOCUMENTS);
    HttpResponse<String> serviceResponse = callService(VIN_NO_DOCUMENTS);

    assertThat(salesResponse.statusCode()).isEqualTo(200);
    assertThat(JsonPath.<String>read(salesResponse.body(), "$.vin")).isEqualTo(VIN_NO_DOCUMENTS);
    assertThat(JsonPath.<List<?>>read(salesResponse.body(), "$.deals")).isEmpty();
    assertThat(serviceResponse.statusCode()).isEqualTo(200);
    assertThat(JsonPath.<List<?>>read(serviceResponse.body(), "$")).isEmpty();
  }

  private HttpResponse<String> callSales(String vin) throws IOException, InterruptedException {
    return get(sales.baseUrl() + "/sales/v1/deals?vin=" + vin);
  }

  private HttpResponse<String> callService(String vin) throws IOException, InterruptedException {
    return get(service.baseUrl() + "/service/api/vehicles/" + vin + "/records");
  }

  private HttpResponse<String> get(String url) throws IOException, InterruptedException {
    HttpRequest request = HttpRequest.newBuilder(URI.create(url)).GET().build();
    return http.send(request, HttpResponse.BodyHandlers.ofString());
  }
}
