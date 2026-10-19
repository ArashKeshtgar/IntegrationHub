package ca.arashkeshtgar.integrationhub.admin;

import ca.arashkeshtgar.integrationhub.hl7.Hl7Properties;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Opens and resolves alerts and tells the log (and the webhook, when set). Two kinds:
 *
 * <ul>
 *   <li>{@code route-down}: a route meets the rule in {@link AlertProperties}
 *   <li>{@code hl7-dead-letters}: HL7 messages given up or refused and not replayed (inbound,
 *       outbound); resolved when replayed or removed by the retention period
 * </ul>
 *
 * Everything written here is a fixed phrase with counts and a route or queue name, never content.
 */
@Component
@ConditionalOnProperty(name = "hub.alerts.enabled", havingValue = "true", matchIfMissing = true)
public class AlertMonitor {

  private static final Logger log = LoggerFactory.getLogger(AlertMonitor.class);

  static final String ROUTE_DOWN = "route-down";
  static final String DEAD_LETTERS = "hl7-dead-letters";

  private final AlertProperties properties;
  private final RouteHealth routes;
  private final Alerts alerts;
  private final Hl7Properties hl7;
  private final JdbcTemplate jdbc;
  private final ObjectMapper json;
  private final MeterRegistry meters;
  private final HttpClient http = HttpClient.newHttpClient();
  private final ScheduledExecutorService scheduler =
      Executors.newSingleThreadScheduledExecutor(
          r -> {
            Thread t = new Thread(r, "alert-monitor");
            t.setDaemon(true);
            return t;
          });

  public AlertMonitor(
      AlertProperties properties,
      RouteHealth routes,
      Alerts alerts,
      Hl7Properties hl7,
      JdbcTemplate jdbc,
      ObjectMapper json,
      MeterRegistry meters) {
    this.properties = properties;
    this.routes = routes;
    this.alerts = alerts;
    this.hl7 = hl7;
    this.jdbc = jdbc;
    this.json = json;
    this.meters = meters;
  }

  @PostConstruct
  void start() {
    long every = properties.interval().toMillis();
    scheduler.scheduleWithFixedDelay(this::safely, every, every, TimeUnit.MILLISECONDS);
    Gauge.builder(
            "hub.alerts.open",
            () -> {
              try {
                return alerts.openCount();
              } catch (RuntimeException e) {
                return Double.NaN;
              }
            })
        .register(meters);
  }

  @PreDestroy
  void stop() {
    scheduler.shutdownNow();
  }

  private void safely() {
    try {
      runOnce();
    } catch (RuntimeException e) {
      log.error("alert round failed: {}", e.getClass().getSimpleName());
    }
  }

  /** One round; public so tests can run it without waiting. */
  public void runOnce() {
    List<String> down = routes.failing();
    for (String route : routes.names()) {
      if (down.contains(route)) {
        alerts.open(
            ROUTE_DOWN,
            route,
            "At least "
                + properties.routeDownMinFailures()
                + " calls failed and none was answered by the upstream in the last "
                + properties.routeDownAfter().toMinutes()
                + " minutes.");
      } else {
        alerts.resolve(ROUTE_DOWN, route);
      }
    }
    if (hl7.enabled()) {
      deadLetters("inbound", "hl7_inbound", "status in ('FAILED', 'REJECTED')");
      deadLetters("outbound", "hl7_outbound", "status = 'FAILED'");
    }
    for (String[] open : alerts.openKeys()) {
      if (ROUTE_DOWN.equals(open[0]) && !routes.names().contains(open[1])) {
        alerts.resolve(open[0], open[1]); // the route was removed from the settings
      }
    }
    notifyPending();
  }

  private void deadLetters(String queue, String table, String where) {
    Long n = jdbc.queryForObject("select count(*) from " + table + " where " + where, Long.class);
    long count = n == null ? 0 : n;
    if (count > 0) {
      alerts.open(
          DEAD_LETTERS,
          queue,
          count
              + " HL7 "
              + queue
              + " message(s) were given up or refused and have not been replayed.");
    } else {
      alerts.resolve(DEAD_LETTERS, queue);
    }
  }

  private void notifyPending() {
    // Opened and not told yet.
    List<Map<String, Object>> opened =
        jdbc.queryForList(
            "select id, kind, subject, opened_at, detail from alert where notified_at is null and resolved_at is null");
    for (Map<String, Object> row : opened) {
      log.error("alert opened: {} {} - {}", row.get("kind"), row.get("subject"), row.get("detail"));
      if (tell(row, "opened", null)) {
        jdbc.update("update alert set notified_at = now() where id = ?", row.get("id"));
      }
    }
    // Resolved after we told about the opening, not told yet.
    List<Map<String, Object>> resolved =
        jdbc.queryForList(
            "select id, kind, subject, opened_at, resolved_at, detail from alert"
                + " where resolved_at is not null and notified_at is not null and resolved_notified_at is null");
    for (Map<String, Object> row : resolved) {
      log.info("alert resolved: {} {}", row.get("kind"), row.get("subject"));
      if (tell(row, "resolved", row.get("resolved_at"))) {
        jdbc.update("update alert set resolved_notified_at = now() where id = ?", row.get("id"));
      }
    }
    // Opened and resolved between two rounds: nothing was told, nothing to tell.
    jdbc.update(
        "update alert set notified_at = now(), resolved_notified_at = now()"
            + " where resolved_at is not null and notified_at is null");
  }

  /** True when there is nothing to send to or the webhook took it. */
  private boolean tell(Map<String, Object> row, String state, Object resolvedAt) {
    if (!properties.hasWebhook()) {
      return true;
    }
    try {
      Map<String, Object> body = new LinkedHashMap<>();
      body.put("hub", "integrationhub");
      body.put("id", String.valueOf(row.get("id")));
      body.put("kind", row.get("kind"));
      body.put("subject", row.get("subject"));
      body.put("state", state);
      body.put("openedAt", instant(row.get("opened_at")));
      if (resolvedAt != null) {
        body.put("resolvedAt", instant(resolvedAt));
      }
      body.put("detail", row.get("detail"));
      HttpRequest request =
          HttpRequest.newBuilder(URI.create(properties.webhookUrl()))
              .timeout(properties.webhookTimeout())
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
              .build();
      HttpResponse<Void> response = http.send(request, HttpResponse.BodyHandlers.discarding());
      boolean ok = response.statusCode() >= 200 && response.statusCode() < 300;
      if (!ok) {
        log.warn("alert webhook answered {}; will try again", response.statusCode());
      }
      return ok;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    } catch (Exception e) {
      log.warn("alert webhook failed: {}; will try again", e.getClass().getSimpleName());
      return false;
    }
  }

  private static String instant(Object timestamp) {
    return timestamp instanceof java.sql.Timestamp t
        ? t.toInstant().toString()
        : Instant.now().toString();
  }
}
