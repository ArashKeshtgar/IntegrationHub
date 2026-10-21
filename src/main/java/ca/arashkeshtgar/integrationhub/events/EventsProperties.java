package ca.arashkeshtgar.integrationhub.events;

import jakarta.validation.constraints.Positive;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Settings under {@code hub.events}. Off by default; when on, a secret of at least 32 characters is
 * required (the patient pseudonym is an HMAC with it) and RabbitMQ must be reachable, though a
 * broker that is down only delays events.
 *
 * <p>{@code mrnSystem} is the identifier system of the clinic's MRN on the Patient in an order;
 * {@code {clinic}} stands for the clinic slug.
 */
@Validated
@ConfigurationProperties("hub.events")
public record EventsProperties(
    @DefaultValue("false") boolean enabled,
    String pseudonymSecret,
    @DefaultValue("urn:clinichub:{clinic}:mrn") String mrnSystem,
    @DefaultValue("clinical.events") String exchange,
    @DefaultValue("analytics.clinical-events") String queue,
    @DefaultValue("2s") Duration publishInterval,
    @DefaultValue("5m") Duration maxBackoff,
    @DefaultValue("50") @Positive int batchSize,
    @DefaultValue("7") @Positive int retentionDays,
    @DefaultValue("365") @Positive int orderLinkRetentionDays) {

  public EventsProperties {
    if (enabled && (pseudonymSecret == null || pseudonymSecret.length() < 32)) {
      throw new IllegalArgumentException(
          "hub.events.pseudonym-secret must be at least 32 characters when hub.events.enabled=true");
    }
  }
}
