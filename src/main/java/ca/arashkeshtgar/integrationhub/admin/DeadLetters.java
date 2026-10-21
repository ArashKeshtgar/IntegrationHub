package ca.arashkeshtgar.integrationhub.admin;

import ca.arashkeshtgar.integrationhub.hl7.Hl7Properties;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * HL7 messages the hub gave up on (FAILED) or ClinicHub refused (REJECTED), and their replay. A row
 * can be replayed while it still has its encrypted payload (see {@code
 * hub.hl7.replay-window-days}); replaying puts it back in the queue and the retry worker takes it
 * within one interval. Nothing of the message is shown here: the fields are queue metadata.
 */
@Component
public class DeadLetters {

  private static final Logger log = LoggerFactory.getLogger(DeadLetters.class);

  public record Letter(
      UUID id,
      String queue,
      String status,
      String sender,
      String controlId,
      Instant receivedAt,
      Instant failedAt,
      int attempts,
      Integer lastStatus,
      String lastError,
      boolean replayable,
      int replayCount,
      Instant lastReplayedAt,
      String lastReplayedBy) {}

  private final JdbcTemplate jdbc;
  private final Hl7Properties hl7;
  private final MeterRegistry meters;

  public DeadLetters(JdbcTemplate jdbc, Hl7Properties hl7, MeterRegistry meters) {
    this.jdbc = jdbc;
    this.hl7 = hl7;
    this.meters = meters;
  }

  public List<Letter> list(int limit) {
    List<Letter> all = new ArrayList<>();
    all.addAll(
        jdbc.query(
            """
            select id, 'inbound', status, sender, control_id, received_at, failed_at, attempts,
                   last_status, last_error, payload is not null, replay_count, last_replayed_at, last_replayed_by
              from hl7_inbound where status in ('FAILED', 'REJECTED')
             order by coalesce(failed_at, received_at) desc limit ?
            """,
            this::row,
            limit));
    all.addAll(
        jdbc.query(
            """
            select id, 'outbound', status, null, null, created_at, failed_at, attempts,
                   null, last_error, payload is not null, replay_count, last_replayed_at, last_replayed_by
              from hl7_outbound where status = 'FAILED'
             order by coalesce(failed_at, created_at) desc limit ?
            """,
            this::row,
            limit));
    all.sort(
        Comparator.comparing((Letter l) -> l.failedAt() == null ? l.receivedAt() : l.failedAt())
            .reversed());
    return all.size() > limit ? all.subList(0, limit) : all;
  }

  /**
   * Puts the message back in its queue. Throws 404 when there is no such row, 409 when it cannot be
   * replayed.
   */
  public void replay(String queue, UUID id, String by) {
    String table =
        switch (queue) {
          case "inbound" -> "hl7_inbound";
          case "outbound" -> "hl7_outbound";
          default -> throw new ResponseStatusException(HttpStatus.NOT_FOUND, "no such queue");
        };
    if (!hl7.enabled()) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "HL7 is switched off on this hub.");
    }
    String statuses = "inbound".equals(queue) ? "('FAILED', 'REJECTED')" : "('FAILED')";
    int changed =
        jdbc.update(
            "update "
                + table
                + " set status = 'PENDING', attempts = 0, next_attempt_at = now(), failed_at = null,"
                + " last_error = null, replay_count = replay_count + 1, last_replayed_at = now(),"
                + " last_replayed_by = ? where id = ? and status in "
                + statuses
                + " and payload is not null",
            by,
            id);
    if (changed == 1) {
      meters.counter("hub.replays", "queue", queue).increment();
      log.info("hl7 {} message {} replayed by {}", queue, id, by);
      return;
    }
    var found =
        jdbc.query(
            "select status, payload is not null from " + table + " where id = ?",
            (rs, i) -> new Object[] {rs.getString(1), rs.getBoolean(2)},
            id);
    if (found.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "no such message");
    }
    boolean hasPayload = (boolean) found.get(0)[1];
    boolean failedState =
        !"PENDING".equals(found.get(0)[0])
            && !"DELIVERED".equals(found.get(0)[0])
            && !"SENT".equals(found.get(0)[0]);
    throw new ResponseStatusException(
        HttpStatus.CONFLICT,
        failedState && !hasPayload
            ? "The message is no longer kept (the replay window has passed)."
            : "Only a message that was given up or refused can be replayed.");
  }

  private Letter row(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
    return new Letter(
        rs.getObject(1, UUID.class),
        rs.getString(2),
        rs.getString(3),
        rs.getString(4),
        rs.getString(5),
        rs.getTimestamp(6).toInstant(),
        rs.getTimestamp(7) == null ? null : rs.getTimestamp(7).toInstant(),
        rs.getInt(8),
        (Integer) rs.getObject(9),
        rs.getString(10),
        rs.getBoolean(11),
        rs.getInt(12),
        rs.getTimestamp(13) == null ? null : rs.getTimestamp(13).toInstant(),
        rs.getString(14));
  }
}
