package ca.arashkeshtgar.integrationhub.events;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/** Patient pseudonym = hex(HMAC-SHA256(secret, clinic + "|" + MRN)). Same patient, same value. */
@Component
public class Pseudonymizer {

  private final byte[] key;

  public Pseudonymizer(EventsProperties properties) {
    String secret = properties.pseudonymSecret();
    this.key = secret == null ? new byte[0] : secret.getBytes(StandardCharsets.UTF_8);
  }

  public String of(String clinicSlug, String mrn) {
    if (key.length == 0) {
      throw new IllegalStateException("no pseudonym secret configured");
    }
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(key, "HmacSHA256"));
      byte[] digest = mac.doFinal((clinicSlug + "|" + mrn).getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }
}
