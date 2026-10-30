package io.github.hinn1411.docviewer.search.model;

import java.util.Locale;
import java.util.regex.Pattern;

/** A full VIN, always valid and normalised (A2). {@link #toString()} is masked for safe logging. */
public record Vin(String value) {

  // Checked before upper-casing: "ß" would otherwise become "SS" and pass as two valid characters
  private static final Pattern FORMAT = Pattern.compile("[A-HJ-NPR-Za-hj-npr-z0-9]{17}");

  public Vin {
    String stripped = value == null ? "" : value.strip();
    if (!FORMAT.matcher(stripped).matches()) {
      throw new InvalidVinException();
    }
    value = stripped.toUpperCase(Locale.ROOT);
  }

  public static Vin parse(String raw) {
    return new Vin(raw);
  }

  /** The first 3 and last 4 characters, e.g. {@code WVW**********0001}. */
  public String masked() {
    return value.substring(0, 3) + "*".repeat(10) + value.substring(13);
  }

  @Override
  public String toString() {
    return masked();
  }
}
