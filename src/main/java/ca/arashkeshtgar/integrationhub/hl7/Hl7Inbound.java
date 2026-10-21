package ca.arashkeshtgar.integrationhub.hl7;

import ca.arashkeshtgar.integrationhub.events.EventFactory;
import ca.arashkeshtgar.integrationhub.relay.BodySummary;
import ca.arashkeshtgar.integrationhub.relay.HubProperties.BodyFormat;
import ca.arashkeshtgar.integrationhub.relay.MessageLog;
import ca.uhn.fhir.context.FhirContext;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * One ORU^R01 from the lab: parse, map to FHIR, keep (encrypted) in {@code hl7_inbound}, try
 * ClinicHub at once, answer. The ACK is the lab's only signal, so it says where the result is:
 *
 * <ul>
 *   <li>AA: ClinicHub has it (or the message holds nothing to forward, e.g. a preliminary result)
 *   <li>AE: the hub has it and will keep trying; the lab must not resend (if it does, the repeat is
 *       recognised by sender and message control id and not stored twice)
 *   <li>AR: refused for good: not parseable, not an ORU^R01, no usable order number, a value the
 *       hub cannot map, sender not allowed, or ClinicHub said no
 * </ul>
 *
 * Nothing of the message is logged or put in an ACK text: only fixed reasons.
 */
@Component
@ConditionalOnProperty(name = "hub.hl7.enabled", havingValue = "true")
public class Hl7Inbound {

  private static final Logger log = LoggerFactory.getLogger(Hl7Inbound.class);
  private static final String ROUTE = "hl7";

  /** A row being worked on is not picked up by the retry worker for this long. */
  static final Duration LEASE = Duration.ofMinutes(2);

  private final Hl7Properties properties;
  private final JdbcTemplate jdbc;
  private final PayloadCipher cipher;
  private final ClinicHubClient clinichub;
  private final MessageLog messages;
  private final EventFactory events;
  private final MeterRegistry meters;
  private final OruToFhir mapper;

  public Hl7Inbound(
      Hl7Properties properties,
      JdbcTemplate jdbc,
      PayloadCipher cipher,
      ClinicHubClient clinichub,
      MessageLog messages,
      EventFactory events,
      MeterRegistry meters,
      LabCodes codes) {
    this.properties = properties;
    this.jdbc = jdbc;
    this.cipher = cipher;
    this.clinichub = clinichub;
    this.messages = messages;
    this.events = events;
    this.meters = meters;
    this.mapper = new OruToFhir(properties.zoneId(), codes::forLabCode);
  }

  /** Handles one frame and returns the ACK to write back. Never throws. */
  public byte[] handle(byte[] raw) {
    long started = System.nanoTime();
    Instant receivedAt = Instant.now();
    String sha = sha256(raw);
    Hl7Message message = null;
    try {
      message = Hl7Message.parse(decode(raw));
      Oru oru = mapper.map(message);
      if (!properties.allowedSenders().isEmpty()
          && !properties.allowedSenders().contains(oru.sender())) {
        throw new Hl7Exception("sender not accepted");
      }
      if (!oru.hasForwardable()) {
        count("accepted-not-forwarded");
        record(
            oru.sender(),
            oru.controlId(),
            receivedAt,
            sha,
            raw.length,
            null,
            null,
            200,
            "not-forwarded",
            started);
        return ack(message, Hl7Ack.ACCEPT, "accepted, nothing to forward");
      }
      byte[] bundle =
          FhirContext.forR4Cached()
              .newJsonParser()
              .encodeResourceToString(oru.bundle())
              .getBytes(StandardCharsets.UTF_8);
      return store(message, oru, bundle, raw.length, sha, receivedAt, started);
    } catch (Hl7Exception e) {
      count("rejected");
      log.warn("hl7 message refused: {}", e.getMessage());
      record(
          "unknown",
          null,
          receivedAt,
          sha,
          raw.length,
          null,
          null,
          422,
          "refused: " + e.getMessage(),
          started);
      return ack(message, Hl7Ack.REJECT, e.getMessage());
    } catch (RuntimeException e) {
      count("error");
      log.error("hl7 message not handled: {}", e.getClass().getSimpleName());
      return ack(message, Hl7Ack.ERROR, "hub error, try again later");
    }
  }

  private byte[] store(
      Hl7Message message,
      Oru oru,
      byte[] bundle,
      int size,
      String sha,
      Instant receivedAt,
      long started) {
    UUID id = UUID.randomUUID();
    int inserted =
        jdbc.update(
            """
            insert into hl7_inbound (id, received_at, sender, control_id, status, report_count, next_attempt_at, payload)
            values (?, ?, ?, ?, 'PENDING', ?, now() + make_interval(secs => ?), ?)
            on conflict (sender, control_id) do update
               set status = 'PENDING', attempts = 0, received_at = excluded.received_at,
                   next_attempt_at = excluded.next_attempt_at, payload = excluded.payload,
                   report_count = excluded.report_count, last_error = null, last_status = null, id = excluded.id
             where hl7_inbound.status in ('FAILED', 'REJECTED')
            """,
            id,
            java.sql.Timestamp.from(receivedAt),
            oru.sender(),
            oru.controlId(),
            oru.reportsSeen(),
            (double) LEASE.toSeconds(),
            cipher.encrypt(bundle, id.toString()));
    if (inserted == 0) {
      String status =
          jdbc.queryForObject(
              "select status from hl7_inbound where sender = ? and control_id = ?",
              String.class,
              oru.sender(),
              oru.controlId());
      count("duplicate");
      if ("DELIVERED".equals(status)) {
        return ack(message, Hl7Ack.ACCEPT, "already delivered");
      }
      return ack(message, Hl7Ack.ERROR, "already stored, will retry");
    }
    Outcome outcome =
        attempt(id, oru.sender(), oru.controlId(), bundle, receivedAt, sha, size, started, "MLLP");
    return switch (outcome) {
      case DELIVERED -> ack(message, Hl7Ack.ACCEPT, "delivered");
      case REJECTED -> ack(message, Hl7Ack.REJECT, "refused by clinichub");
      default -> ack(message, Hl7Ack.ERROR, "stored, will retry");
    };
  }

  /** What one attempt to hand a row to ClinicHub did to it. */
  enum Outcome {
    DELIVERED,
    RETRY,
    FAILED,
    REJECTED
  }

  /** One attempt for a row that is PENDING; used by the first try and by the retry worker. */
  Outcome attempt(
      UUID id,
      String sender,
      String controlId,
      byte[] bundle,
      Instant receivedAt,
      String sha,
      int size,
      long started,
      String method) {
    ClinicHubClient.Delivery delivery = clinichub.post(bundle);
    Outcome outcome;
    switch (delivery.outcome()) {
      case DELIVERED -> {
        jdbc.update(
            "update hl7_inbound set status = 'DELIVERED', delivered_at = now(), payload = null,"
                + " attempts = attempts + 1, last_status = ?, last_error = null where id = ?",
            delivery.status(),
            id);
        outcome = Outcome.DELIVERED;
      }
      case REJECTED -> {
        jdbc.update(
            "update hl7_inbound set status = 'REJECTED', payload = case when ? then payload end,"
                + " failed_at = now(), attempts = attempts + 1, last_status = ?, last_error = ? where id = ?",
            keepForReplay(),
            delivery.status(),
            delivery.note(),
            id);
        outcome = Outcome.REJECTED;
      }
      default -> {
        Integer attempts =
            jdbc.queryForObject("select attempts from hl7_inbound where id = ?", Integer.class, id);
        int done = (attempts == null ? 0 : attempts) + 1;
        if (done >= properties.retry().maxAttempts()) {
          jdbc.update(
              "update hl7_inbound set status = 'FAILED', payload = case when ? then payload end,"
                  + " failed_at = now(), attempts = ?, last_status = ?, last_error = ? where id = ?",
              keepForReplay(),
              done,
              delivery.status(),
              delivery.note(),
              id);
          outcome = Outcome.FAILED;
          log.error(
              "hl7 result from {} given up after {} attempts: {}", sender, done, delivery.note());
        } else {
          jdbc.update(
              "update hl7_inbound set attempts = ?, last_status = ?, last_error = ?,"
                  + " next_attempt_at = now() + make_interval(secs => ?) where id = ?",
              done,
              delivery.status(),
              delivery.note(),
              (double) backoff(done).toSeconds(),
              id);
          outcome = Outcome.RETRY;
        }
      }
    }
    count(outcome.name().toLowerCase());
    BodySummary summary = BodySummary.of(bundle);
    EventFactory.Result made = EventFactory.Result.NONE;
    if (outcome == Outcome.DELIVERED) {
      made = eventsFor(sender, receivedAt, sha, summary, delivery.status());
    }
    int responseStatus =
        switch (outcome) {
          case DELIVERED -> 200;
          case REJECTED, FAILED -> 422;
          case RETRY -> 503;
        };
    messages.write(
        new MessageLog.Entry(
            UUID.randomUUID(),
            receivedAt,
            ROUTE,
            sender,
            summary.clinicSlug(),
            method,
            "ORU^R01",
            "x-application/hl7-v2+er7",
            (long) size,
            sha,
            controlId,
            summary,
            delivery.status(),
            responseStatus,
            (System.nanoTime() - started) / 1_000_000,
            delivery.note()),
        made);
    return outcome;
  }

  private EventFactory.Result eventsFor(
      String sender, Instant receivedAt, String sha, BodySummary summary, Integer status) {
    try {
      return events.create(
          new EventFactory.Evidence(
              ROUTE,
              "POST",
              "/fhir",
              sender,
              receivedAt,
              sha,
              summary,
              Optional.empty(),
              BodyFormat.FHIR,
              status));
    } catch (RuntimeException e) {
      log.error("making events failed for hl7: {}", e.getClass().getSimpleName());
      return EventFactory.Result.NONE;
    }
  }

  /**
   * A given-up or refused message keeps its (encrypted) payload for the replay window so an
   * operator can replay it; 0 days clears it at once.
   */
  private boolean keepForReplay() {
    return properties.replayWindowDays() > 0;
  }

  Duration backoff(int attemptsDone) {
    long seconds =
        properties.retry().initialBackoff().toSeconds()
            << Math.min(Math.max(attemptsDone - 1, 0), 20);
    return Duration.ofSeconds(
        Math.min(seconds, Math.max(1, properties.retry().maxBackoff().toSeconds())));
  }

  /** Rows the retry worker should try now; each is leased so another round does not take it too. */
  List<UUID> claimDue() {
    return jdbc.query(
        """
        update hl7_inbound set next_attempt_at = now() + make_interval(secs => ?)
         where id in (select id from hl7_inbound where status = 'PENDING' and next_attempt_at <= now()
                       order by next_attempt_at limit ? for update skip locked)
        returning id
        """,
        (rs, i) -> rs.getObject(1, UUID.class),
        (double) LEASE.toSeconds(),
        properties.retry().batchSize());
  }

  /** Retry one claimed row. */
  void retry(UUID id) {
    long started = System.nanoTime();
    var row =
        jdbc.query(
            "select sender, control_id, received_at, payload from hl7_inbound where id = ? and status = 'PENDING'",
            (rs, i) ->
                new Object[] {
                  rs.getString(1), rs.getString(2), rs.getTimestamp(3).toInstant(), rs.getBytes(4)
                },
            id);
    if (row.isEmpty() || row.get(0)[3] == null) {
      return;
    }
    Object[] r = row.get(0);
    byte[] bundle = cipher.decrypt((byte[]) r[3], id.toString());
    attempt(
        id,
        (String) r[0],
        (String) r[1],
        bundle,
        (Instant) r[2],
        sha256(bundle),
        bundle.length,
        started,
        "RETRY");
  }

  /**
   * Message text -> characters, by MSH-18 (default ISO-8859-1, which every byte value maps into).
   */
  static String decode(byte[] raw) {
    String latin = new String(raw, StandardCharsets.ISO_8859_1);
    int start = latin.indexOf("MSH");
    if (start >= 0) {
      int end = latin.indexOf('\r', start);
      String msh = end < 0 ? latin.substring(start) : latin.substring(start, end);
      String[] f = msh.split("\\|", -1);
      if (f.length > 17 && f[17].toUpperCase().contains("UTF-8")) {
        return new String(raw, StandardCharsets.UTF_8);
      }
    }
    return latin;
  }

  private byte[] ack(Hl7Message message, String code, String text) {
    return Hl7Ack.bytes(Hl7Ack.build(message, code, text));
  }

  private void record(
      String sender,
      String controlId,
      Instant receivedAt,
      String sha,
      int size,
      BodySummary summary,
      Integer upstream,
      int responseStatus,
      String error,
      long started) {
    messages.write(
        new MessageLog.Entry(
            UUID.randomUUID(),
            receivedAt,
            ROUTE,
            sender,
            null,
            "MLLP",
            "ORU^R01",
            "x-application/hl7-v2+er7",
            (long) size,
            sha,
            controlId,
            summary,
            upstream,
            responseStatus,
            (System.nanoTime() - started) / 1_000_000,
            error));
  }

  private void count(String result) {
    meters.counter("hub.hl7.inbound", "result", result).increment();
  }

  static String sha256(byte[] data) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
