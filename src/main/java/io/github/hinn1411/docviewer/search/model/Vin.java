package io.github.hinn1411.docviewer.search.model;

/** Stub for the T05 red step: no validation, normalising or masking yet. */
public record Vin(String value) {

  public static Vin parse(String raw) {
    return new Vin(raw);
  }

  public String masked() {
    return value;
  }
}
