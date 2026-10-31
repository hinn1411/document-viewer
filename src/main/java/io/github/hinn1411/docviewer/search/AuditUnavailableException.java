package io.github.hinn1411.docviewer.search;

/**
 * The access couldn't be recorded, so no documents may be returned. It keeps no cause, because
 * PostgreSQL's error detail can contain the whole row, including the VIN.
 */
public class AuditUnavailableException extends RuntimeException {

  public AuditUnavailableException() {
    super("The access audit could not be saved");
  }
}
