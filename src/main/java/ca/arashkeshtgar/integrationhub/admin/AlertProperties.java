package ca.arashkeshtgar.integrationhub.admin;

import jakarta.validation.constraints.Positive;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Alerts (phase 6). A route is "down" when, in the last {@code routeDownAfter}, at least {@code
 * routeDownMinFailures} calls failed with a 5xx and not one was answered by the upstream with
 * anything below 500. An alert is a row in {@code alert} and a log line; when {@code webhookUrl} is
 * set the hub also POSTs a small JSON document (kind, subject, state, times, a fixed phrase), never
 * message content. When {@code mailTo} is set (and {@code spring.mail.host}) the same facts go by
 * e-mail, see {@link AlertMailer}.
 */
@Validated
@ConfigurationProperties("hub.alerts")
public record AlertProperties(
    @DefaultValue("true") boolean enabled,
    @DefaultValue("30s") Duration interval,
    @DefaultValue("5m") Duration routeDownAfter,
    @DefaultValue("3") @Positive int routeDownMinFailures,
    @DefaultValue("") String webhookUrl,
    @DefaultValue("5s") Duration webhookTimeout,
    @DefaultValue("") String mailTo,
    @DefaultValue("integrationhub@localhost") String mailFrom) {

  public boolean hasMail() {
    return mailTo != null && !mailTo.isBlank();
  }

  public boolean hasWebhook() {
    return webhookUrl != null && !webhookUrl.isBlank();
  }
}
