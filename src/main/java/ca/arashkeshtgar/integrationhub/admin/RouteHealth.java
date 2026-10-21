package ca.arashkeshtgar.integrationhub.admin;

import ca.arashkeshtgar.integrationhub.hl7.Hl7Properties;
import ca.arashkeshtgar.integrationhub.relay.HubProperties;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * How each route is doing, read from the message log (no probe is sent to the upstream: a route
 * with no traffic is "idle", not "ok"). A call failed when the hub answered 5xx; it succeeded when
 * the upstream answered below 500.
 */
@Component
public class RouteHealth {

  public record Route(
      String route,
      String status,
      long calls15m,
      long failures15m,
      long calls24h,
      long failures24h,
      Integer p95Ms,
      Instant lastSuccessAt,
      Instant lastFailureAt) {}

  private final JdbcTemplate jdbc;
  private final HubProperties hub;
  private final Hl7Properties hl7;
  private final AlertProperties alerts;

  public RouteHealth(
      JdbcTemplate jdbc, HubProperties hub, Hl7Properties hl7, AlertProperties alerts) {
    this.jdbc = jdbc;
    this.hub = hub;
    this.hl7 = hl7;
    this.alerts = alerts;
  }

  /** The routes in the settings, plus {@code hl7} when the listener is on. */
  public List<String> names() {
    TreeSet<String> names = new TreeSet<>(hub.routes().keySet());
    if (hl7.enabled()) {
      names.add("hl7");
    }
    return new ArrayList<>(names);
  }

  /** Routes that meet the "down" rule right now. */
  public List<String> failing() {
    List<String> down = new ArrayList<>();
    jdbc.query(
        """
        select route,
               count(*) filter (where response_status >= 500),
               count(*) filter (where upstream_status is not null and upstream_status < 500)
          from message
         where received_at > now() - make_interval(secs => ?)
         group by route
        """,
        rs -> {
          if (rs.getLong(2) >= alerts.routeDownMinFailures() && rs.getLong(3) == 0) {
            down.add(rs.getString(1));
          }
        },
        (double) alerts.routeDownAfter().toSeconds());
    return down;
  }

  public List<Route> all() {
    Map<String, Object[]> stats = new HashMap<>();
    jdbc.query(
        """
        select route,
               count(*) filter (where received_at > now() - interval '15 minutes'),
               count(*) filter (where received_at > now() - interval '15 minutes' and response_status >= 500),
               count(*),
               count(*) filter (where response_status >= 500),
               percentile_cont(0.95) within group (order by latency_ms)
                 filter (where received_at > now() - interval '15 minutes'),
               max(received_at) filter (where upstream_status is not null and upstream_status < 500),
               max(received_at) filter (where response_status >= 500)
          from message
         where received_at > now() - interval '24 hours'
         group by route
        """,
        rs -> {
          Double p95 = (Double) rs.getObject(6);
          stats.put(
              rs.getString(1),
              new Object[] {
                rs.getLong(2),
                rs.getLong(3),
                rs.getLong(4),
                rs.getLong(5),
                p95 == null ? null : (int) Math.round(p95),
                rs.getTimestamp(7) == null ? null : rs.getTimestamp(7).toInstant(),
                rs.getTimestamp(8) == null ? null : rs.getTimestamp(8).toInstant()
              });
        });
    List<String> down = failing();
    List<Route> routes = new ArrayList<>();
    for (String name : names()) {
      Object[] s = stats.getOrDefault(name, new Object[] {0L, 0L, 0L, 0L, null, null, null});
      long calls15 = (long) s[0];
      long fail15 = (long) s[1];
      String status =
          down.contains(name) ? "down" : fail15 > 0 ? "degraded" : calls15 > 0 ? "ok" : "idle";
      routes.add(
          new Route(
              name,
              status,
              calls15,
              fail15,
              (long) s[2],
              (long) s[3],
              (Integer) s[4],
              (Instant) s[5],
              (Instant) s[6]));
    }
    return routes;
  }
}
