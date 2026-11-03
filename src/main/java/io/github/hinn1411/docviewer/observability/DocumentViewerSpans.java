package io.github.hinn1411.docviewer.observability;

import io.github.hinn1411.docviewer.domain.SourceOutcome;
import io.github.hinn1411.docviewer.domain.Vin;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import java.util.Locale;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * The spans from design doc §6.2, below the request span Spring creates. They carry the masked VIN
 * only, and a failure's class only, because exception messages can hold the VIN.
 */
@Component
public class DocumentViewerSpans {

  private final Tracer tracer;

  public DocumentViewerSpans(Tracer tracer) {
    this.tracer = tracer;
  }

  /** Call on the request thread, so the request span is the parent. */
  public Span startSourceCall(String source, Vin vin) {
    return tracer
        .nextSpan()
        .name(source.toLowerCase(Locale.ROOT) + ".fetch")
        .tag("source", source)
        .tag("vin", vin.masked())
        .start();
  }

  /**
   * Runs a source call on its own thread with its span current, so the HTTP client span, the {@code
   * traceparent} header and the thread's log lines all join the request's trace.
   */
  public <T> T inSourceCall(Span span, Supplier<T> call) {
    try (Tracer.SpanInScope scope = tracer.withSpan(span)) {
      return call.get();
    }
  }

  /** Ends with what the search got, so a deadline timeout ends the span at the deadline. */
  public void endSourceCall(Span span, SourceOutcome outcome) {
    span.tag("result", CallResults.of(outcome.reason()))
        .tag("document_count", String.valueOf(outcome.documents().size()))
        .end();
  }

  /** Runs the read of a failed source's fallback copy in a {@code fallback.read} span. */
  public <T> T readFallback(String source, Vin vin, Supplier<T> read) {
    Span span =
        tracer.nextSpan().name("fallback.read").tag("source", source).tag("vin", vin.masked());
    return inStep(span, read);
  }

  /** Runs the audit insert in an {@code audit.save} span. */
  public void saveAudit(Vin vin, Runnable save) {
    Span span = tracer.nextSpan().name("audit.save").tag("vin", vin.masked());
    inStep(
        span,
        () -> {
          save.run();
          return null;
        });
  }

  /** A failed step shows as an error in Jaeger, with the exception's class only. */
  private <T> T inStep(Span span, Supplier<T> step) {
    span.start();
    try (Tracer.SpanInScope scope = tracer.withSpan(span)) {
      return step.get();
    } catch (RuntimeException failure) {
      span.error(new ClassOnlyError(failure));
      throw failure;
    } finally {
      span.end();
    }
  }
}
