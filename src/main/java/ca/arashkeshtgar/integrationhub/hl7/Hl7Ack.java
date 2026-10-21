package ca.arashkeshtgar.integrationhub.hl7;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

/**
 * The ACK for an ORU^R01. {@code AA}: the result is with ClinicHub (or needs no forwarding). {@code
 * AE}: the hub has it and will retry, the sender need not resend (if it does, it is recognised).
 * {@code AR}: refused, resending the same message will not help; the text says why, in a fixed
 * vocabulary that never repeats the message.
 */
public final class Hl7Ack {

  public static final String ACCEPT = "AA";
  public static final String ERROR = "AE";
  public static final String REJECT = "AR";

  private Hl7Ack() {}

  /**
   * {@code incoming} may be null when the message could not be parsed; the ACK then addresses no
   * one.
   */
  public static String build(Hl7Message incoming, String code, String text) {
    String app = "";
    String facility = "";
    String control = "";
    if (incoming != null) {
      var msh = incoming.first("MSH").orElse(null);
      if (msh != null) {
        app = Hl7Message.escapeStandard(msh.component(3, 1));
        facility = Hl7Message.escapeStandard(msh.component(4, 1));
        control = Hl7Message.escapeStandard(msh.component(10, 1));
      }
    }
    String id = UUID.randomUUID().toString().replace("-", "").substring(0, 20);
    String safeText = Hl7Message.escapeStandard(text.length() > 80 ? text.substring(0, 80) : text);
    return "MSH|^~\\&|INTEGRATIONHUB|HUB|"
        + app
        + "|"
        + facility
        + "|"
        + Hl7Time.fromInstant(Instant.now())
        + "||ACK^R01|"
        + id
        + "|P|2.5\rMSA|"
        + code
        + "|"
        + control
        + "|"
        + safeText
        + "\r";
  }

  public static byte[] bytes(String ack) {
    return ack.getBytes(StandardCharsets.ISO_8859_1);
  }
}
