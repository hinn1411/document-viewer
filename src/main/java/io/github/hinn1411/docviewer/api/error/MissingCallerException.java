package io.github.hinn1411.docviewer.api.error;

/** A gateway identity header is missing or blank (A1). */
public class MissingCallerException extends RuntimeException {

  public MissingCallerException() {
    super("X-User-Id and X-Dealership-Id are required");
  }
}
