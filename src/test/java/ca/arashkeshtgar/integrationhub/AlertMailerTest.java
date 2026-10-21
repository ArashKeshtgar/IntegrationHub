package ca.arashkeshtgar.integrationhub;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ca.arashkeshtgar.integrationhub.admin.AlertMailer;
import ca.arashkeshtgar.integrationhub.admin.AlertProperties;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

class AlertMailerTest {

  private static AlertProperties props(String to) {
    return new AlertProperties(
        true,
        Duration.ofSeconds(30),
        Duration.ofMinutes(5),
        3,
        "",
        Duration.ofSeconds(5),
        to,
        "hub@clinic.test");
  }

  @SuppressWarnings("unchecked")
  private static ObjectProvider<JavaMailSender> provider(JavaMailSender mail) {
    ObjectProvider<JavaMailSender> p = mock(ObjectProvider.class);
    when(p.getIfAvailable()).thenReturn(mail);
    return p;
  }

  @Test
  void anAlertGoesToEveryAddressWithFactsOnly() {
    JavaMailSender mail = mock(JavaMailSender.class);
    boolean ok =
        new AlertMailer(props("a@clinic.test, b@clinic.test"), provider(mail))
            .send(
                "opened",
                "route-down",
                "labflow",
                "no answer from upstream",
                "2026-10-21T03:00:00Z");
    assertThat(ok).isTrue();
    ArgumentCaptor<SimpleMailMessage> sent = ArgumentCaptor.forClass(SimpleMailMessage.class);
    verify(mail).send(sent.capture());
    assertThat(sent.getValue().getTo()).containsExactly("a@clinic.test", "b@clinic.test");
    assertThat(sent.getValue().getFrom()).isEqualTo("hub@clinic.test");
    assertThat(sent.getValue().getSubject())
        .isEqualTo("[IntegrationHub] opened: route-down labflow");
    assertThat(sent.getValue().getText()).contains("no answer from upstream");
  }

  @Test
  void aServerThatRefusesOrIsNotConfiguredIsReportedNotThrown() {
    JavaMailSender down = mock(JavaMailSender.class);
    doThrow(new MailSendException("smtp down")).when(down).send(any(SimpleMailMessage.class));
    assertThat(
            new AlertMailer(props("a@clinic.test"), provider(down)).send("o", "k", "s", "d", "t"))
        .isFalse();
    assertThat(
            new AlertMailer(props("a@clinic.test"), provider(null)).send("o", "k", "s", "d", "t"))
        .isFalse();
  }
}
