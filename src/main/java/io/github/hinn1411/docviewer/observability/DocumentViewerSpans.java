package io.github.hinn1411.docviewer.observability;

import io.github.hinn1411.docviewer.domain.FailureReason;
import io.github.hinn1411.docviewer.domain.SourceOutcome;
import io.github.hinn1411.docviewer.domain.Vin;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import java.util.Locale;
import java.util.concurrent.CompletionException;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/** The spans from design doc §6.2, with the masked VIN and a failure's class only. */
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

  /** Makes the span current on the call's thread, so the call joins the trace. */
  public <T> T inSourceCall(Span span, Supplier<T> call) {
    try (Tracer.SpanInScope scope = tracer.withSpan(span)) {
      return call.get();
    }
  }

  /**
   * Ends with the outcome, so a timeout ends at the deadline. With no outcome (rejected, or an
   * {@code Error}), it ends as an error.
   */
  public void endSourceCall(Span span, SourceOutcome outcome, Throwable failure) {
    if (failure == null) {
      span.tag("result", CallResults.of(outcome.reason()))
          .tag("document_count", String.valueOf(outcome.documents().size()));
    } else {
      Throwable cause =
          failure instanceof CompletionException && failure.getCause() != null
              ? failure.getCause()
              : failure;
      span.tag("result", CallResults.of(FailureReason.ERROR)).error(new ClassOnlyError(cause));
    }
    span.end();
  }

  /** The fallback read, in a {@code fallback.read} span. */
  public <T> T readFallback(String source, Vin vin, Supplier<T> read) {
    Span span =
        tracer.nextSpan().name("fallback.read").tag("source", source).tag("vin", vin.masked());
    return inStep(span, read);
  }

  /** The audit insert, in an {@code audit.save} span. */
  public void saveAudit(Vin vin, Runnable save) {
    Span span = tracer.nextSpan().name("audit.save").tag("vin", vin.masked());
    inStep(
        span,
        () -> {
          save.run();
          return null;
        });
  }

  /** A failure shows as an error, with its class only. */
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
