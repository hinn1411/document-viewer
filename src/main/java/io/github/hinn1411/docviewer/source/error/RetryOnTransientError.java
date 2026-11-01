package io.github.hinn1411.docviewer.source.error;

import java.lang.reflect.Method;
import org.springframework.resilience.retry.MethodRetryPredicate;

/** Gives {@code @Retryable} the decision {@link SourceException#from} already made. */
public class RetryOnTransientError implements MethodRetryPredicate {

  @Override
  public boolean shouldRetry(Method method, Throwable failure) {
    return failure instanceof SourceException source && source.retryable();
  }
}
