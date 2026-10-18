package ca.arashkeshtgar.integrationhub.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Search of the message log. Metadata only: there are no bodies to return. Guarded by {@link
 * AdminAccessFilter}.
 */
@RestController
public class MessagesController {

  static final int MAX_LIMIT = 200;

  public record Identifier(String system, String value) {}

  public record MessageView(
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
      JsonNode resourceSummary,
      Integer upstreamStatus,
      Integer responseStatus,
      Integer latencyMs,
      String error,
      List<Identifier> identifiers) {}

  private static final String COLUMNS =
      "m.id, m.received_at, m.route, m.sender_client_id, m.clinic_slug, m.method, m.path,"
          + " m.content_type, m.request_size, m.request_sha256, m.bundle_id,"
          + " m.resource_summary::text, m.upstream_status, m.response_status, m.latency_ms, m.error";

  private final JdbcTemplate jdbc;
  private final ObjectMapper json;

  public MessagesController(JdbcTemplate jdbc, ObjectMapper json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  /**
   * {@code status} is what the sender was told. {@code identifier} is a value, or {@code
   * system|value}. {@code from} is inclusive and {@code to} exclusive (ISO-8601 instants), newest
   * first.
   */
  @GetMapping("/api/messages")
  public Map<String, Object> search(
      @RequestParam(required = false) String route,
      @RequestParam(required = false) String clinic,
      @RequestParam(required = false) Integer status,
      @RequestParam(required = false) String identifier,
      @RequestParam(required = false) Instant from,
      @RequestParam(required = false) Instant to,
      @RequestParam(defaultValue = "50") int limit,
      @RequestParam(defaultValue = "0") int offset) {
    if (limit < 1 || offset < 0) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "limit must be 1 or more, offset 0 or more");
    }
    int capped = Math.min(limit, MAX_LIMIT);
    StringBuilder sql = new StringBuilder("select " + COLUMNS + " from message m where 1 = 1");
    List<Object> params = new ArrayList<>();
    if (route != null) {
      sql.append(" and m.route = ?");
      params.add(route);
    }
    if (clinic != null) {
      sql.append(" and m.clinic_slug = ?");
      params.add(clinic);
    }
    if (status != null) {
      sql.append(" and m.response_status = ?");
      params.add(status);
    }
    if (from != null) {
      sql.append(" and m.received_at >= ?");
      params.add(java.sql.Timestamp.from(from));
    }
    if (to != null) {
      sql.append(" and m.received_at < ?");
      params.add(java.sql.Timestamp.from(to));
    }
    if (identifier != null && !identifier.isBlank()) {
      int bar = identifier.lastIndexOf('|');
      sql.append(" and exists (select 1 from message_identifier i where i.message_id = m.id");
      if (bar > 0) {
        sql.append(" and i.system = ? and i.value = ?");
        params.add(identifier.substring(0, bar));
        params.add(identifier.substring(bar + 1));
      } else {
        sql.append(" and i.value = ?");
        params.add(identifier);
      }
      sql.append(")");
    }
    sql.append(" order by m.received_at desc, m.id limit ? offset ?");
    params.add(capped);
    params.add(offset);

    List<MessageView> items =
        withIdentifiers(jdbc.query(sql.toString(), this::row, params.toArray()));
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("items", items);
    body.put("limit", capped);
    body.put("offset", offset);
    return body;
  }

  @GetMapping("/api/messages/{id}")
  public MessageView one(@PathVariable UUID id) {
    List<MessageView> found =
        withIdentifiers(
            jdbc.query("select " + COLUMNS + " from message m where m.id = ?", this::row, id));
    if (found.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
    return found.get(0);
  }

  private MessageView row(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
    String summary = rs.getString(12);
    JsonNode node = null;
    try {
      node = summary == null ? null : json.readTree(summary);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      // stored by this application as JSON; an unreadable value is shown as absent
    }
    return new MessageView(
        rs.getObject(1, UUID.class),
        rs.getTimestamp(2).toInstant(),
        rs.getString(3),
        rs.getString(4),
        rs.getString(5),
        rs.getString(6),
        rs.getString(7),
        rs.getString(8),
        (Long) rs.getObject(9),
        rs.getString(10),
        rs.getString(11),
        node,
        (Integer) rs.getObject(13),
        (Integer) rs.getObject(14),
        (Integer) rs.getObject(15),
        rs.getString(16),
        List.of());
  }

  private List<MessageView> withIdentifiers(List<MessageView> messages) {
    if (messages.isEmpty()) {
      return messages;
    }
    String marks = String.join(",", messages.stream().map(m -> "?").toList());
    Map<UUID, List<Identifier>> byMessage = new LinkedHashMap<>();
    jdbc.query(
        "select message_id, system, value from message_identifier where message_id in ("
            + marks
            + ") order by system, value",
        rs -> {
          byMessage
              .computeIfAbsent(rs.getObject(1, UUID.class), k -> new ArrayList<>())
              .add(new Identifier(rs.getString(2), rs.getString(3)));
        },
        messages.stream().map(MessageView::id).toArray());
    return messages.stream()
        .map(
            m ->
                new MessageView(
                    m.id(),
                    m.receivedAt(),
                    m.route(),
                    m.senderClientId(),
                    m.clinicSlug(),
                    m.method(),
                    m.path(),
                    m.contentType(),
                    m.requestSize(),
                    m.requestSha256(),
                    m.bundleId(),
                    m.resourceSummary(),
                    m.upstreamStatus(),
                    m.responseStatus(),
                    m.latencyMs(),
                    m.error(),
                    byMessage.getOrDefault(m.id(), List.of())))
        .toList();
  }
}
