package io.github.hinn1411.docviewer.observability;

/**
 * Marks a span as failed with only the original exception's class as its message, since messages
 * can hold the VIN. It has no cause or stack trace. In Jaeger the exception type is this class, and
 * the original class is the message.
 */
public final class ClassOnlyError extends RuntimeException {

  public ClassOnlyError(Throwable original) {
    super(original.getClass().getName(), null, false, false);
  }
}
