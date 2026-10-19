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
    @DefaultValue Outbound outbound) {

  /** {@code port} 0 picks a free port (tests). No TLS: see docs/architecture.md. */
  public record Mllp(
      @DefaultValue("2575") int port,
      @DefaultValue("0.0.0.0") String bindAddress,
      @DefaultValue("1048576") @Positive int maxMessageBytes,
      @DefaultValue("30s") Duration readTimeout) {}

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
      @DefaultValue("10s") Duration timeout) {}

  public Hl7Properties {
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
