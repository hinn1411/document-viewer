package io.github.hinn1411.docviewer.search.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/** T05: a Vin is always valid and normalised, and never exposes the full VIN by accident. */
class VinTest {

  private static final String VALID = "WVWZZZ1JZXW000001";

  @ParameterizedTest
  @ValueSource(strings = {" wvwzzz1jzxw000001 ", "\twvwzzz1jzxw000001\n", "WvWzZz1JzXw000001"})
  void normalisesCaseAndWhitespace(String raw) {
    assertThat(Vin.parse(raw).value()).isEqualTo(VALID);
  }

  @ParameterizedTest
  @ValueSource(strings = {"ABCDEFGHJKLMNPRST", "UVWXYZ01234567890"})
  void acceptsEveryAllowedLetterAndDigit(String allowed) {
    assertThat(Vin.parse(allowed).value()).isEqualTo(allowed);
  }

  @ParameterizedTest
  @ValueSource(strings = {"WVWZZZ1JZXW00000", "WVWZZZ1JZXW0000011", "W"})
  void rejectsWrongLength(String raw) {
    assertRejected(raw);
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {"   ", "\t\n"})
  void rejectsNullAndBlank(String raw) {
    assertRejected(raw);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "WVWZZZ1JZXW00000I",
        "WVWZZZ1JZXW00000O",
        "WVWZZZ1JZXW00000Q",
        "wvwzzz1jzxw00000i",
        "wvwzzz1jzxw00000o",
        "wvwzzz1jzxw00000q"
      })
  void rejectsLettersIOQ(String raw) {
    assertRejected(raw);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "WVWZZZ1JZXW00000-",
        "WVWZZZ1JZ W000001",
        "WVWZZZ1JZXW00000_",
        // Accented letter and a non-ASCII digit
        "WVWZZZ1JZXW00000É",
        "WVWZZZ1JZXW00000١",
        // Sharp s: 16 characters, but it upper-cases to "SS", which would make 17 allowed ones
        "WVWZZZ1JZXW0000ß"
      })
  void rejectsOtherCharacters(String raw) {
    assertRejected(raw);
  }

  @Test
  void masksAllButFirst3AndLast4() {
    assertThat(Vin.parse(VALID).masked()).isEqualTo("WVW**********0001");
  }

  @Test
  void toStringIsMasked() {
    assertThat(Vin.parse(VALID).toString()).isEqualTo("WVW**********0001");
  }

  @ParameterizedTest
  @ValueSource(strings = {"WVWZZZ1JZXW00000I", "WVWZZZ1JZXW0000011"})
  void rejectionMessageOmitsTheInput(String raw) {
    assertThatThrownBy(() -> Vin.parse(raw))
        .isInstanceOf(InvalidVinException.class)
        .message()
        .doesNotContain(raw);
  }

  @Test
  void constructorCannotBypassValidation() {
    assertThatThrownBy(() -> new Vin("not a vin")).isInstanceOf(InvalidVinException.class);
    assertThatThrownBy(() -> new Vin(null)).isInstanceOf(InvalidVinException.class);
    assertThat(new Vin(" wvwzzz1jzxw000001 ").value()).isEqualTo(VALID);
  }

  private static void assertRejected(String raw) {
    assertThatThrownBy(() -> Vin.parse(raw)).isInstanceOf(InvalidVinException.class);
  }
}
