package io.github.hinn1411.docviewer.observability;

/**
 * A span error naming only the original exception's class, as messages can hold the VIN. Jaeger
 * shows this class as the type.
 */
public final class ClassOnlyError extends RuntimeException {

  public ClassOnlyError(Throwable original) {
    super(original.getClass().getName(), null, false, false);
  }
}
