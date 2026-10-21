package ca.arashkeshtgar.integrationhub.admin;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Counts of what is waiting in the hub's queues (no content). */
@Component
public class Queues {

  private final JdbcTemplate jdbc;

  public Queues(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public Map<String, Object> counts() {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("hl7InboundPending", count("hl7_inbound", "status = 'PENDING'"));
    out.put("hl7InboundFailed", count("hl7_inbound", "status = 'FAILED'"));
    out.put("hl7InboundRejected", count("hl7_inbound", "status = 'REJECTED'"));
    out.put("hl7OutboundPending", count("hl7_outbound", "status = 'PENDING'"));
    out.put("hl7OutboundFailed", count("hl7_outbound", "status = 'FAILED'"));
    out.put("eventsWaiting", count("event_outbox", "published_at is null"));
    return out;
  }

  private long count(String table, String where) {
    Long n = jdbc.queryForObject("select count(*) from " + table + " where " + where, Long.class);
    return n == null ? 0 : n;
  }
}
