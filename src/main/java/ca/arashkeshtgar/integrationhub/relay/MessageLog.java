package ca.arashkeshtgar.integrationhub.relay;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Writes one row per call to {@code message}, plus the order identifiers found in it. */
@Component
public class MessageLog {

  private static final Logger log = LoggerFactory.getLogger(MessageLog.class);

  /** Metadata only. There is deliberately no field for a body. */
  public record Entry(
      UUID id,
      String route,
      String senderClientId,
      String clinicSlug,
      String method,
      String path,
      String contentType,
      Long requestSize,
      String requestSha256,
      String bundleId,
      BundleSummary summary,
      Integer upstreamStatus,
      int responseStatus,
      long latencyMs,
      String error) {}

  private final JdbcTemplate jdbc;
  private final ObjectMapper json;

  public MessageLog(JdbcTemplate jdbc, ObjectMapper json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  /**
   * A failing log must not stop a result from reaching ClinicHub, so errors are reported, not
   * thrown.
   */
  public void write(Entry entry) {
    try {
      insert(entry);
    } catch (RuntimeException | JsonProcessingException e) {
      log.error(
          "message log write failed for route {}: {}", entry.route(), e.getClass().getSimpleName());
    }
  }

  @Transactional
  void insert(Entry e) throws JsonProcessingException {
    String summary = e.summary() == null ? null : json.writeValueAsString(e.summary().toJson());
    jdbc.update(
        """
        insert into message (id, route, direction, sender_client_id, clinic_slug, method, path,
                             content_type, request_size, request_sha256, bundle_id, resource_summary,
                             upstream_status, response_status, latency_ms, error)
        values (?, ?, 'inbound', ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?)
        """,
        e.id(),
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
      for (BundleSummary.Identifier id : e.summary().identifiers()) {
        rows.add(new Object[] {e.id(), id.system(), id.value()});
      }
      jdbc.batchUpdate(
          "insert into message_identifier (message_id, system, value) values (?, ?, ?)", rows);
    }
  }

  private static String cut(String value, int max) {
    return value == null || value.length() <= max ? value : value.substring(0, max);
  }
}
