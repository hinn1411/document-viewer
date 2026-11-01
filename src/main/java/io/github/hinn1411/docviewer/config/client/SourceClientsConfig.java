package io.github.hinn1411.docviewer.config.client;

import io.github.hinn1411.docviewer.config.properties.CircuitBreakerProperties;
import io.github.hinn1411.docviewer.config.properties.DocViewerProperties;
import io.github.hinn1411.docviewer.source.error.SourceException;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig.SlidingWindowType;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.resilience.annotation.EnableResilientMethods;
import org.springframework.web.client.RestClient;

/**
 * Turns on {@code @Retryable}, builds each source's HTTP client from its settings and sets up the
 * circuit breakers.
 */
@Configuration
@EnableResilientMethods
@EnableConfigurationProperties({DocViewerProperties.class, CircuitBreakerProperties.class})
public class SourceClientsConfig {

  private static final Logger log = LoggerFactory.getLogger(SourceClientsConfig.class);

  /** One breaker per source, created from its name on first use, with these shared settings. */
  @Bean
  CircuitBreakerRegistry circuitBreakers(CircuitBreakerProperties settings) {
    CircuitBreakerRegistry registry =
        CircuitBreakerRegistry.of(
            CircuitBreakerConfig.custom()
                .slidingWindowType(SlidingWindowType.TIME_BASED)
                .slidingWindowSize((int) settings.window().toSeconds())
                .minimumNumberOfCalls(settings.minimumCalls())
                .failureRateThreshold(settings.failureRate())
                .slowCallDurationThreshold(settings.slowCall())
                .slowCallRateThreshold(settings.slowCallRate())
                .waitDurationInOpenState(settings.openFor())
                .permittedNumberOfCallsInHalfOpenState(settings.halfOpenCalls())
                .maxWaitDurationInHalfOpenState(settings.halfOpenMaxWait())
                .recordException(
                    failure ->
                        failure instanceof SourceException sourceFailure
                            && sourceFailure.countsAgainstSource())
                .build());
    registry
        .getEventPublisher()
        .onEntryAdded(
            added ->
                added
                    .getAddedEntry()
                    .getEventPublisher()
                    .onStateTransition(
                        change ->
                            log.warn(
                                "Circuit breaker changed state: source={}, from={}, to={}",
                                change.getCircuitBreakerName(),
                                change.getStateTransition().getFromState(),
                                change.getStateTransition().getToState())));
    return registry;
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
