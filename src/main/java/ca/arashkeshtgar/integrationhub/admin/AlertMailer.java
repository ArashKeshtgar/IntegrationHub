package ca.arashkeshtgar.integrationhub.admin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/**
 * Alerts by e-mail ({@code hub.alerts.mail-to}, comma separated; the server is Spring's {@code
 * spring.mail.*}). The body is what the webhook carries: kind, subject, state, when and the fixed
 * detail phrase. Never message content, so a mailbox needs no more care than the alert log.
 */
@Component
public class AlertMailer {

  private static final Logger log = LoggerFactory.getLogger(AlertMailer.class);

  private final AlertProperties properties;
  private final ObjectProvider<JavaMailSender> sender;

  public AlertMailer(AlertProperties properties, ObjectProvider<JavaMailSender> sender) {
    this.properties = properties;
    this.sender = sender;
  }

  /** True when the server took it; false (and a log line without addresses) when not. */
  public boolean send(String state, String kind, String subject, String detail, String since) {
    JavaMailSender mail = sender.getIfAvailable();
    if (mail == null) {
      log.warn("alert mail not sent: hub.alerts.mail-to is set but spring.mail.host is not");
      return false;
    }
    try {
      SimpleMailMessage message = new SimpleMailMessage();
      message.setFrom(properties.mailFrom());
      message.setTo(properties.mailTo().split("\\s*,\\s*"));
      message.setSubject("[IntegrationHub] " + state + ": " + kind + " " + subject);
      message.setText(
          "Alert "
              + state
              + "\nKind: "
              + kind
              + "\nSubject: "
              + subject
              + "\nSince: "
              + since
              + "\nDetail: "
              + detail
              + "\n");
      mail.send(message);
      return true;
    } catch (RuntimeException e) {
      log.warn("alert mail failed: {}; will try again", e.getClass().getSimpleName());
      return false;
    }
  }
}
