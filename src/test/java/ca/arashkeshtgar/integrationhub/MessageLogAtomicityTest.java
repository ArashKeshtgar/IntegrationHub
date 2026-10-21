package ca.arashkeshtgar.integrationhub;

import static org.assertj.core.api.Assertions.assertThat;

import ca.arashkeshtgar.integrationhub.events.ClinicalEvent;
import ca.arashkeshtgar.integrationhub.events.EventFactory;
import ca.arashkeshtgar.integrationhub.relay.BodySummary;
import ca.arashkeshtgar.integrationhub.relay.MessageLog;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** A call and its events are written together or not at all. */
class MessageLogAtomicityTest extends PostgresTestBase {

  @Autowired MessageLog log;
  @Autowired JdbcTemplate jdbc;

  MessageLog.Entry entry(UUID id) {
    return new MessageLog.Entry(
        id,
        Instant.now(),
        "clinichub",
        "labflow",
        "harbourview",
        "POST",
        "/fhir",
        "application/fhir+json",
        10L,
        "0".repeat(64),
        "b1",
        BodySummary.unparsed()
            .withIdentifier(
                new BodySummary.Identifier("urn:clinichub:harbourview:service-request", "1")),
        200,
        200,
        5,
        null);
  }

  ClinicalEvent event(String type) {
    return new ClinicalEvent(
        1,
        UUID.randomUUID(),
        type,
        Instant.now(),
        "harbourview",
        "labflow",
        "service-request|1",
        null,
        null,
        null,
        null,
        null,
        null);
  }

  @Test
  void aCallAndItsEventsAreWrittenTogether() {
    UUID id = UUID.randomUUID();

    log.write(entry(id), new EventFactory.Result(List.of(event("order.placed")), List.of()));

    assertThat(jdbc.queryForObject("select count(*) from message where id = ?", Integer.class, id))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from message_identifier where message_id = ?", Integer.class, id))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from event_outbox where message_id = ?", Integer.class, id))
        .isEqualTo(1);
  }

  @Test
  void ifAnEventCannotBeStoredNothingOfTheCallIs() {
    UUID id = UUID.randomUUID();
    // event_type is varchar(60): this one fails after the message row and its identifiers went in.
    log.write(entry(id), new EventFactory.Result(List.of(event("x".repeat(80))), List.of()));

    assertThat(jdbc.queryForObject("select count(*) from message where id = ?", Integer.class, id))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from message_identifier where message_id = ?", Integer.class, id))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from event_outbox where message_id = ?", Integer.class, id))
        .isZero();
  }
}
