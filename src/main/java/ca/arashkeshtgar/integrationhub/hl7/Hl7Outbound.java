package ca.arashkeshtgar.integrationhub.hl7;

import ca.arashkeshtgar.integrationhub.events.OrderLinks;
import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.parser.LenientErrorHandler;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import org.hl7.fhir.r4.model.Bundle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Puts the OLIS copy of a relayed result into {@code hl7_outbound}. Called inside the transaction
 * that writes the message log row, so a result is logged with its copy or neither. Never throws
 * into the relay: a body that cannot be converted is counted and skipped.
 */
@Component
@ConditionalOnProperty(name = "hub.hl7.enabled", havingValue = "true")
public class Hl7Outbound {

  private static final Logger log = LoggerFactory.getLogger(Hl7Outbound.class);

  private final Hl7Properties properties;
  private final JdbcTemplate jdbc;
  private final PayloadCipher cipher;
  private final OrderLinks links;
  private final MeterRegistry meters;

  public Hl7Outbound(
      Hl7Properties properties,
      JdbcTemplate jdbc,
      PayloadCipher cipher,
      OrderLinks links,
      MeterRegistry meters) {
    this.properties = properties;
    this.jdbc = jdbc;
    this.cipher = cipher;
    this.links = links;
    this.meters = meters;
  }

  public boolean enabled() {
    return properties.sendsOut();
  }

  /** Converts the Bundle in {@code body} and queues one ORU^R01 per report; returns how many. */
  public int enqueue(UUID messageId, byte[] body) {
    if (!enabled()) {
      return 0;
    }
    Bundle bundle;
    try {
      var parser = FhirContext.forR4Cached().newJsonParser();
      parser.setParserErrorHandler(new LenientErrorHandler().setErrorOnInvalidValue(false));
      var resource = parser.parseResource(new String(body, StandardCharsets.UTF_8));
      if (!(resource instanceof Bundle b)) {
        return 0;
      }
      bundle = b;
    } catch (RuntimeException e) {
      meters.counter("hub.hl7.outbound", "result", "unparsed").increment();
      return 0;
    }
    FhirToOru.Result result;
    try {
      result =
          new FhirToOru(
                  properties.outbound(),
                  (clinic, order) ->
                      links.find(clinic, order).map(OrderLinks.Link::patientPseudonym))
              .convert(bundle, messageId.toString(), Instant.now());
    } catch (RuntimeException e) {
      // The copy must never cost the result its log row.
      meters.counter("hub.hl7.outbound", "result", "unconverted").increment();
      log.error("hl7 copy not made: {}", e.getClass().getSimpleName());
      return 0;
    }
    for (FhirToOru.Message message : result.messages()) {
      UUID id = UUID.randomUUID();
      jdbc.update(
          "insert into hl7_outbound (id, message_id, status, payload) values (?, ?, 'PENDING', ?)",
          id,
          messageId,
          cipher.encrypt(message.text().getBytes(StandardCharsets.ISO_8859_1), id.toString()));
    }
    if (result.skipped() > 0) {
      meters.counter("hub.hl7.outbound", "result", "skipped").increment(result.skipped());
      log.warn(
          "{} reports not exported to the OLIS copy (not expressible in ORU^R01)",
          result.skipped());
    }
    if (!result.messages().isEmpty()) {
      meters.counter("hub.hl7.outbound", "result", "queued").increment(result.messages().size());
    }
    return result.messages().size();
  }
}
