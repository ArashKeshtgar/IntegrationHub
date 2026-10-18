package ca.arashkeshtgar.integrationhub.relay;

import ca.arashkeshtgar.integrationhub.events.EventFactory;
import ca.arashkeshtgar.integrationhub.events.EventOutbox;
import ca.arashkeshtgar.integrationhub.events.OrderLinks;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Writes one row per call to {@code message}, plus the order identifiers found in it and the events
 * it caused, all in one transaction: a call is logged with its events or not at all.
 */
@Component
public class MessageLog {

  private static final Logger log = LoggerFactory.getLogger(MessageLog.class);

  /** Metadata only. There is deliberately no field for a body. */
  public record Entry(
      UUID id,
      Instant receivedAt,
      String route,
      String senderClientId,
      String clinicSlug,
      String method,
      String path,
      String contentType,
      Long requestSize,
      String requestSha256,
      String bundleId,
      BodySummary summary,
      Integer upstreamStatus,
      int responseStatus,
      long latencyMs,
      String error) {}

  private final JdbcTemplate jdbc;
  private final ObjectMapper json;
  private final TransactionTemplate transaction;
  private final EventOutbox outbox;
  private final OrderLinks links;

  public MessageLog(
      JdbcTemplate jdbc,
      ObjectMapper json,
      TransactionTemplate transaction,
      EventOutbox outbox,
      OrderLinks links) {
    this.jdbc = jdbc;
    this.json = json;
    this.transaction = transaction;
    this.outbox = outbox;
    this.links = links;
  }

  public void write(Entry entry) {
    write(entry, EventFactory.Result.NONE);
  }

  /**
   * A failing log must not stop a result from reaching ClinicHub, so errors are reported, not
   * thrown. When it fails nothing is kept: neither the row nor its events.
   */
  public void write(Entry entry, EventFactory.Result events) {
    write(entry, events, null);
  }

  /**
   * The same, and {@code alsoInTransaction} runs in the same transaction after the row is written
   * (the HL7 copy of a result): it commits or rolls back with the log row.
   */
  public void write(Entry entry, EventFactory.Result events, Runnable alsoInTransaction) {
    try {
      transaction.executeWithoutResult(
          status -> {
            try {
              insert(entry, events);
              if (alsoInTransaction != null) {
                alsoInTransaction.run();
              }
            } catch (JsonProcessingException e) {
              throw new IllegalStateException(e);
            }
          });
    } catch (RuntimeException e) {
      log.error(
          "message log write failed for route {}: {}", entry.route(), e.getClass().getSimpleName());
    }
  }

  private void insert(Entry e, EventFactory.Result events) throws JsonProcessingException {
    String summary = e.summary() == null ? null : json.writeValueAsString(e.summary().toJson());
    jdbc.update(
        """
        insert into message (id, received_at, route, direction, sender_client_id, clinic_slug, method, path,
                             content_type, request_size, request_sha256, bundle_id, resource_summary,
                             upstream_status, response_status, latency_ms, error)
        values (?, ?, ?, 'inbound', ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?)
        """,
        e.id(),
        Timestamp.from(e.receivedAt()),
        cut(e.route(), 60),
        cut(e.senderClientId(), 100),
        cut(e.clinicSlug(), 60),
        cut(e.method(), 10),
        e.path(),
        cut(e.contentType(), 200),
        e.requestSize(),
        e.requestSha256(),
        cut(e.bundleId(), 100),
        summary,
        e.upstreamStatus(),
        e.responseStatus(),
        (int) Math.min(e.latencyMs(), Integer.MAX_VALUE),
        e.error());
    if (e.summary() != null && !e.summary().identifiers().isEmpty()) {
      List<Object[]> rows = new ArrayList<>();
      for (BodySummary.Identifier id : e.summary().identifiers()) {
        rows.add(new Object[] {e.id(), id.system(), id.value()});
      }
      jdbc.batchUpdate(
          "insert into message_identifier (message_id, system, value) values (?, ?, ?)", rows);
    }
    for (OrderLinks.Link link : events.links()) {
      links.upsert(link);
    }
    for (var event : events.events()) {
      outbox.add(e.id(), event);
    }
  }

  private static String cut(String value, int max) {
    return value == null || value.length() <= max ? value : value.substring(0, max);
  }
}
