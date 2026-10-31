package io.github.hinn1411.docviewer.source;

import java.lang.reflect.Method;
import org.springframework.resilience.retry.MethodRetryPredicate;

/** Decides which failures get the one retry. Stub for T07's red step. */
public class RetryOnTransientError implements MethodRetryPredicate {

  @Override
  public boolean shouldRetry(Method method, Throwable failure) {
    throw new UnsupportedOperationException("T07 green step");
  }
}
