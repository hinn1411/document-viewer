package io.github.hinn1411.docviewer.api;

/** A gateway identity header is missing or blank (A1). */
class MissingCallerException extends RuntimeException {

  MissingCallerException() {
    super("X-User-Id and X-Dealership-Id are required");
  }
}
