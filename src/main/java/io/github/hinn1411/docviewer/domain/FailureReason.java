package io.github.hinn1411.docviewer.domain;

/** Why a source gave no live answer. {@link #CIRCUIT_OPEN} means no call was made. */
public enum FailureReason {
  TIMEOUT,
  ERROR,
  CIRCUIT_OPEN
}
