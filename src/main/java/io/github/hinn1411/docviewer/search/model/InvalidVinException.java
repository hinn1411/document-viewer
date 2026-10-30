package io.github.hinn1411.docviewer.search.model;

/** The input isn't a valid VIN. The message is fixed, so the input never reaches logs. */
public class InvalidVinException extends RuntimeException {

  public InvalidVinException() {
    super("VIN must be 17 characters from A-Z and 0-9, excluding I, O and Q");
  }
}
