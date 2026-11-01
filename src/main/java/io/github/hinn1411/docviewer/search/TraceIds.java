package io.github.hinn1411.docviewer.search;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;

/** The ID shared by the audit row and {@code X-Request-Id}, so the two can't differ. */
public final class TraceIds {

  private static final String NO_TRACE = "no-trace";

  private TraceIds() {}

  public static String current(Tracer tracer) {
    Span span = tracer.currentSpan();
    return span == null ? NO_TRACE : span.context().traceId();
  }
}
