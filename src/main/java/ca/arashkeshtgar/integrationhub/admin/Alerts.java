package ca.arashkeshtgar.integrationhub.admin;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** The {@code alert} table. */
@Component
public class Alerts {

  public record Alert(
      UUID id,
      String kind,
      String subject,
      Instant openedAt,
      Instant resolvedAt,
      String detail,
      boolean notified) {}

  private final JdbcTemplate jdbc;

  public Alerts(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public List<Alert> list(boolean openOnly, int limit) {
    return jdbc.query(
        "select id, kind, subject, opened_at, resolved_at, detail, notified_at is not null from alert"
            + (openOnly ? " where resolved_at is null" : "")
            + " order by opened_at desc limit ?",
        (rs, i) ->
            new Alert(
                rs.getObject(1, UUID.class),
                rs.getString(2),
                rs.getString(3),
                rs.getTimestamp(4).toInstant(),
                rs.getTimestamp(5) == null ? null : rs.getTimestamp(5).toInstant(),
                rs.getString(6),
                rs.getBoolean(7)),
        limit);
  }

  public long openCount() {
    Long n =
        jdbc.queryForObject("select count(*) from alert where resolved_at is null", Long.class);
    return n == null ? 0 : n;
  }

  /** True when this call opened it (false: it was already open; the detail is refreshed). */
  public boolean open(String kind, String subject, String detail) {
    int refreshed =
        jdbc.update(
            "update alert set detail = ? where kind = ? and subject = ? and resolved_at is null",
            detail,
            kind,
            subject);
    if (refreshed > 0) {
      return false;
    }
    return jdbc.update(
            "insert into alert (id, kind, subject, detail) values (?, ?, ?, ?)"
                + " on conflict do nothing",
            UUID.randomUUID(),
            kind,
            subject,
            detail)
        > 0;
  }

  public boolean resolve(String kind, String subject) {
    return jdbc.update(
            "update alert set resolved_at = now() where kind = ? and subject = ? and resolved_at is null",
            kind,
            subject)
        > 0;
  }

  /** Kind and subject of every open alert. */
  public List<String[]> openKeys() {
    return jdbc.query(
        "select kind, subject from alert where resolved_at is null",
        (rs, i) -> new String[] {rs.getString(1), rs.getString(2)});
  }
}
