package ca.arashkeshtgar.integrationhub.hl7;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Retries what ClinicHub did not take, on the schedule in {@code hub.hl7.retry}, and purges old
 * rows.
 */
@Component
@ConditionalOnProperty(name = "hub.hl7.enabled", havingValue = "true")
public class Hl7InboundWorker {

  private static final Logger log = LoggerFactory.getLogger(Hl7InboundWorker.class);

  private final Hl7Properties properties;
  private final Hl7Inbound inbound;
  private final JdbcTemplate jdbc;
  private final MeterRegistry meters;
  private final ScheduledExecutorService scheduler =
      Executors.newSingleThreadScheduledExecutor(
          r -> {
            Thread t = new Thread(r, "hl7-inbound-worker");
            t.setDaemon(true);
            return t;
          });

  public Hl7InboundWorker(
      Hl7Properties properties, Hl7Inbound inbound, JdbcTemplate jdbc, MeterRegistry meters) {
    this.properties = properties;
    this.inbound = inbound;
    this.jdbc = jdbc;
    this.meters = meters;
  }

  @PostConstruct
  void start() {
    long every = properties.retry().interval().toMillis();
    scheduler.scheduleWithFixedDelay(this::safely, every, every, TimeUnit.MILLISECONDS);
    scheduler.scheduleWithFixedDelay(this::purge, 1, 60, TimeUnit.MINUTES);
    Gauge.builder("hub.hl7.inbound.pending", () -> count("PENDING")).register(meters);
    Gauge.builder("hub.hl7.inbound.failed", () -> count("FAILED")).register(meters);
  }

  @PreDestroy
  void stop() {
    scheduler.shutdownNow();
  }

  private double count(String status) {
    try {
      Long n =
          jdbc.queryForObject(
              "select count(*) from hl7_inbound where status = ?", Long.class, status);
      return n == null ? 0 : n;
    } catch (RuntimeException e) {
      return Double.NaN;
    }
  }

  private void safely() {
    try {
      runOnce();
    } catch (RuntimeException e) {
      log.error("hl7 retry round failed: {}", e.getClass().getSimpleName());
    }
  }

  /** One round; package-visible so tests can run it without waiting. */
  public int runOnce() {
    int n = 0;
    for (UUID id : inbound.claimDue()) {
      try {
        inbound.retry(id);
        n++;
      } catch (RuntimeException e) {
        log.error("hl7 retry failed: {}", e.getClass().getSimpleName());
      }
    }
    return n;
  }

  void purge() {
    try {
      int n =
          jdbc.update(
              "delete from hl7_inbound where status <> 'PENDING' and received_at < now() - make_interval(days => ?)",
              properties.retentionDays());
      n +=
          jdbc.update(
              "delete from hl7_outbound where status <> 'PENDING' and created_at < now() - make_interval(days => ?)",
              properties.retentionDays());
      if (n > 0) {
        log.info("purged {} old hl7 queue rows", n);
      }
    } catch (RuntimeException e) {
      log.error("hl7 purge failed: {}", e.getClass().getSimpleName());
    }
  }
}
