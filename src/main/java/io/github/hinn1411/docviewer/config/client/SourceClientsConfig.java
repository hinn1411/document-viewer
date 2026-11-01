package io.github.hinn1411.docviewer.config.client;

import io.github.hinn1411.docviewer.config.properties.DocViewerProperties;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.resilience.annotation.EnableResilientMethods;
import org.springframework.web.client.RestClient;

/** Turns on {@code @Retryable} and builds each source's HTTP client from its settings. */
@Configuration
@EnableResilientMethods
@EnableConfigurationProperties(DocViewerProperties.class)
public class SourceClientsConfig {

  @Bean
  CircuitBreakerRegistry circuitBreakers() {
    return CircuitBreakerRegistry.ofDefaults();
  }

  /**
   * Uses socket timeouts so that a stall in the headers or the body throws {@code
   * SocketTimeoutException}. Spring's JDK client reports a stalled body only as "closed". The read
   * timeout applies per read, so the search service's deadline limits the total time.
   */
  public static RestClient restClient(DocViewerProperties.SourceClient source) {
    SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
    requestFactory.setConnectTimeout(source.connectTimeout());
    requestFactory.setReadTimeout(source.readTimeout());
    return RestClient.builder().baseUrl(source.baseUrl()).requestFactory(requestFactory).build();
  }
}
