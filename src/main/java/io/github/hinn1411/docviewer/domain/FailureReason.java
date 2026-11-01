package io.github.hinn1411.docviewer.domain;

/** Why a live source call failed. */
public enum FailureReason {
  TIMEOUT,
  ERROR,
  CIRCUIT_OPEN
}
