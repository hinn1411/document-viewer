package io.github.hinn1411.docviewer.config.client;

import io.github.hinn1411.docviewer.config.properties.CircuitBreakerProperties;
import io.github.hinn1411.docviewer.source.error.SourceException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig.SlidingWindowType;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** One breaker per source, created from its name on first use, with these shared settings. */
@Configuration
@EnableConfigurationProperties(CircuitBreakerProperties.class)
public class SourceCircuitBreakerConfig {

  private static final Logger log = LoggerFactory.getLogger(SourceCircuitBreakerConfig.class);

  @Bean
  CircuitBreakerRegistry circuitBreakers(CircuitBreakerProperties settings) {
    CircuitBreakerRegistry registry = CircuitBreakerRegistry.of(breakerConfig(settings));
    registry.getEventPublisher().onEntryAdded(added -> logStateChanges(added.getAddedEntry()));
    return registry;
  }

  private static CircuitBreakerConfig breakerConfig(CircuitBreakerProperties settings) {
    return CircuitBreakerConfig.custom()
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
        .build();
  }

  private static void logStateChanges(CircuitBreaker breaker) {
    breaker
        .getEventPublisher()
        .onStateTransition(
            change ->
                log.warn(
                    "Circuit breaker changed state: source={}, from={}, to={}",
                    change.getCircuitBreakerName(),
                    change.getStateTransition().getFromState(),
                    change.getStateTransition().getToState()));
  }
}
