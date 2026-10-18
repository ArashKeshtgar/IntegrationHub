package ca.arashkeshtgar.integrationhub.hl7;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** The synthetic sample messages under src/test/resources/hl7. */
final class Hl7Samples {
  private Hl7Samples() {}

  static String load(String name) {
    try (InputStream in = Hl7Samples.class.getResourceAsStream("/hl7/" + name)) {
      return new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }
}
