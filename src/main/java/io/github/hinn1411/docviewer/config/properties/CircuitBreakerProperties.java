package io.github.hinn1411.docviewer.config.properties;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** The {@code docviewer.circuit-breaker.*} settings, shared by every source's breaker. */
@ConfigurationProperties("docviewer.circuit-breaker")
public record CircuitBreakerProperties(
    Duration window,
    int minimumCalls,
    float failureRate,
    Duration slowCall,
    float slowCallRate,
    Duration openFor,
    int halfOpenCalls,
    Duration halfOpenMaxWait) {}
