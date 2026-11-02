package io.github.hinn1411.docviewer.observability;

import io.github.hinn1411.docviewer.domain.FailureReason;
import io.github.hinn1411.docviewer.domain.SourceOutcome;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.Locale;
import org.springframework.stereotype.Component;

/** The six metrics from design doc §6.2, so their names and labels live in one place. */
@Component
public class DocumentViewerMetrics {

  /** Around the 1 s target and the 2 s source deadline. */
  private static final Duration[] BUCKETS = {
    Duration.ofMillis(250),
    Duration.ofMillis(500),
    Duration.ofSeconds(1),
    Duration.ofSeconds(2),
    Duration.ofMillis(2_500)
  };

  private final MeterRegistry registry;
  private final Timer requestDuration;
  private final Counter auditFailures;

  public DocumentViewerMetrics(MeterRegistry registry) {
    this.registry = registry;
    this.requestDuration =
        Timer.builder("docviewer.request.duration")
            .serviceLevelObjectives(BUCKETS)
            .register(registry);
    // Registered now, so the alert's increase() has a 0 to start from.
    this.auditFailures = registry.counter("docviewer.audit.failures");
  }

  /** Records one search. {@code outcome} is complete, partial or failed. */
  public void recordRequest(String outcome, int httpStatus, Duration duration) {
    registry
        .counter("docviewer.requests", "outcome", outcome, "status", String.valueOf(httpStatus))
        .increment();
    requestDuration.record(duration);
  }

  /**
   * Creates a source's call counters at 0. Prometheus' rate() ignores the jump from no series to a
   * value, so without this a short burst of a new result would show as 0 in Grafana.
   */
  public void registerSource(String source) {
    sourceCalls(source, null);
    for (FailureReason reason : FailureReason.values()) {
      sourceCalls(source, reason);
    }
  }

  /** Records one source's live result. An open breaker made no call, so it has no duration. */
  public void recordSourceCall(SourceOutcome outcome) {
    sourceCalls(outcome.source(), outcome.reason()).increment();
    if (outcome.reason() != FailureReason.CIRCUIT_OPEN) {
      Timer.builder("docviewer.source.duration")
          .tag("source", outcome.source())
          .serviceLevelObjectives(BUCKETS)
          .register(registry)
          .record(outcome.latency());
    }
  }

  public void recordFallbackServed(String source) {
    registry.counter("docviewer.fallback.served", "source", source).increment();
  }

  public void recordAuditFailure() {
    auditFailures.increment();
  }

  /** {@code reason} is null for a call that succeeded. */
  private Counter sourceCalls(String source, FailureReason reason) {
    String result = reason == null ? "ok" : reason.name().toLowerCase(Locale.ROOT);
    return registry.counter("docviewer.source.calls", "source", source, "result", result);
  }
}
