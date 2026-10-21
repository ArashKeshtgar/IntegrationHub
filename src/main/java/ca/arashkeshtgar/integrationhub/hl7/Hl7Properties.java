package ca.arashkeshtgar.integrationhub.hl7;

import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import java.time.Duration;
import java.time.ZoneId;
import java.util.Base64;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Settings under {@code hub.hl7}. Off by default. When on, the queue key (32 bytes, base64) and the
 * ClinicHub client credentials are required, so a hub that is misconfigured fails at start, not on
 * the first result.
 */
@Validated
@ConfigurationProperties("hub.hl7")
public record Hl7Properties(
    @DefaultValue("false") boolean enabled,
    @DefaultValue Mllp mllp,
    String queueKey,
    @DefaultValue("America/Toronto") String zone,
    @DefaultValue List<String> allowedSenders,
    @DefaultValue Retry retry,
    @DefaultValue("14") @Positive int retentionDays,
    @DefaultValue("7") @PositiveOrZero int replayWindowDays,
    @DefaultValue ClinicHub clinichub,
    @DefaultValue Outbound outbound,
    @DefaultValue Lab lab,
    @DefaultValue Tls tls) {

  /**
   * {@code port} 0 picks a free port (tests). {@code tls}: the listener speaks MLLP over TLS with
   * the key store's certificate; {@code client-auth} none, want or need (the lab's certificate,
   * checked against the trust store).
   */
  public record Mllp(
      @DefaultValue("2575") int port,
      @DefaultValue("0.0.0.0") String bindAddress,
      @DefaultValue("1048576") @Positive int maxMessageBytes,
      @DefaultValue("30s") Duration readTimeout,
      @DefaultValue("false") boolean tls,
      @DefaultValue("none") String clientAuth) {}

  /**
   * PKCS12 stores for MLLP over TLS ({@link MllpSockets}): the hub's own key and certificate, and
   * the certificates it trusts (empty: the JVM's trusted roots).
   */
  public record Tls(
      String keyStore, String keyStorePassword, String trustStore, String trustStorePassword) {}

  public record Retry(
      @DefaultValue("10s") Duration initialBackoff,
      @DefaultValue("30m") Duration maxBackoff,
      @DefaultValue("12") @Positive int maxAttempts,
      @DefaultValue("15s") Duration interval,
      @DefaultValue("20") @Positive int batchSize) {}

  /** The client-credentials call that sends results to ClinicHub as {@code integrationhub}. */
  public record ClinicHub(
      String tokenUrl,
      String fhirUrl,
      @DefaultValue("integrationhub") String clientId,
      String clientSecret,
      @DefaultValue("results") String scope,
      @DefaultValue("10s") Duration timeout) {}

  /** The OLIS-style receiver. {@code host} empty = nothing is sent. */
  public record Outbound(
      @DefaultValue("false") boolean enabled,
      String host,
      @DefaultValue("2576") int port,
      @DefaultValue("INTEGRATIONHUB") String sendingApplication,
      @DefaultValue("HUB") String sendingFacility,
      @DefaultValue("OLIS") String receivingApplication,
      @DefaultValue("OLIS") String receivingFacility,
      @DefaultValue("10s") Duration timeout,
      @DefaultValue("false") boolean tls) {}

  /**
   * The external laboratory that takes orders as ORM^O01 over MLLP (the route with {@code
   * hl7-order: true}). {@code host} empty = the route answers 503. Needs no queue: ClinicHub's
   * outbox retries.
   */
  public record Lab(
      String host,
      @DefaultValue("2577") int port,
      @DefaultValue("INTEGRATIONHUB") String sendingApplication,
      @DefaultValue("HUB") String sendingFacility,
      @DefaultValue("EXTLAB") String receivingApplication,
      @DefaultValue("LAB1") String receivingFacility,
      @DefaultValue("10s") Duration timeout,
      @DefaultValue("false") boolean tls) {

    public boolean configured() {
      return host != null && !host.isBlank();
    }
  }

  public Hl7Properties {
    require(
        List.of("none", "want", "need").contains(mllp.clientAuth()),
        "hub.hl7.mllp.client-auth is none, want or need");
    if (mllp.tls() || !"none".equals(mllp.clientAuth())) {
      require(
          tls.keyStore() != null && !tls.keyStore().isBlank(),
          "hub.hl7.tls.key-store is required for hub.hl7.mllp.tls");
    }
    if (enabled) {
      require(
          queueKey != null && decodeKey(queueKey) != null,
          "hub.hl7.queue-key must be 32 bytes, base64");
      require(
          clinichub.tokenUrl() != null && !clinichub.tokenUrl().isBlank(),
          "hub.hl7.clinichub.token-url is required");
      require(
          clinichub.fhirUrl() != null && !clinichub.fhirUrl().isBlank(),
          "hub.hl7.clinichub.fhir-url is required");
      require(
          clinichub.clientSecret() != null && !clinichub.clientSecret().isBlank(),
          "hub.hl7.clinichub.client-secret is required");
      ZoneId.of(zone);
      if (outbound.enabled()) {
        require(
            outbound.host() != null && !outbound.host().isBlank(),
            "hub.hl7.outbound.host is required");
      }
    }
  }

  public ZoneId zoneId() {
    return ZoneId.of(zone);
  }

  public boolean sendsOut() {
    return enabled && outbound.enabled();
  }

  public static byte[] decodeKey(String base64) {
    try {
      byte[] key = Base64.getDecoder().decode(base64.trim());
      return key.length == 32 ? key : null;
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  private static void require(boolean ok, String message) {
    if (!ok) {
      throw new IllegalArgumentException(message);
    }
  }
}
