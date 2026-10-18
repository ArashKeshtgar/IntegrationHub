package ca.arashkeshtgar.integrationhub.hl7;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * AES-256-GCM for the queued messages. Layout: 12-byte IV, then ciphertext and tag. The row id is
 * authenticated, so a payload copied into another row does not decrypt.
 */
@Component
@ConditionalOnProperty(name = "hub.hl7.enabled", havingValue = "true")
public class PayloadCipher {

  private static final int IV_BYTES = 12;
  private static final int TAG_BITS = 128;

  private final SecretKeySpec key;
  private final SecureRandom random = new SecureRandom();

  public PayloadCipher(Hl7Properties properties) {
    this.key = new SecretKeySpec(Hl7Properties.decodeKey(properties.queueKey()), "AES");
  }

  public byte[] encrypt(byte[] plain, String rowId) {
    try {
      byte[] iv = new byte[IV_BYTES];
      random.nextBytes(iv);
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
      cipher.updateAAD(rowId.getBytes(StandardCharsets.UTF_8));
      byte[] sealed = cipher.doFinal(plain);
      return ByteBuffer.allocate(iv.length + sealed.length).put(iv).put(sealed).array();
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("encryption failed", e);
    }
  }

  public byte[] decrypt(byte[] payload, String rowId) {
    try {
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, payload, 0, IV_BYTES));
      cipher.updateAAD(rowId.getBytes(StandardCharsets.UTF_8));
      return cipher.doFinal(payload, IV_BYTES, payload.length - IV_BYTES);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("decryption failed", e);
    }
  }
}
