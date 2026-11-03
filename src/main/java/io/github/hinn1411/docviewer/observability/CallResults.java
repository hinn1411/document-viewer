package io.github.hinn1411.docviewer.observability;

import io.github.hinn1411.docviewer.domain.FailureReason;
import java.util.Locale;

/** The {@code result} of a source call, shared by metrics and spans so the two always agree. */
final class CallResults {

  private CallResults() {}

  /** {@code reason} is null for a call that succeeded. */
  static String of(FailureReason reason) {
    return reason == null ? "ok" : reason.name().toLowerCase(Locale.ROOT);
  }
}
