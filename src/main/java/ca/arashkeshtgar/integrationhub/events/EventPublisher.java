package ca.arashkeshtgar.integrationhub.events;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Sends what is in {@code event_outbox} to RabbitMQ, oldest first, with publisher confirms. A
 * failure (broker down, no confirm) leaves the rows in place and tries again later with a growing
 * pause. Delivery is at least once: a consumer must ignore an event id it has seen.
 */
@Component
@ConditionalOnProperty(name = "hub.events.enabled", havingValue = "true")
class EventPublisher {

  private static final Logger log = LoggerFactory.getLogger(EventPublisher.class);
  private static final long CONFIRM_TIMEOUT_MS = 10_000;

  private record Row(UUID id, String type, String payload, int attempts) {}

  private final EventsProperties properties;
  private final JdbcTemplate jdbc;
  private final TransactionTemplate transaction;
  private final RabbitTemplate rabbit;
  private final MeterRegistry meters;
  private final ScheduledExecutorService scheduler =
      Executors.newSingleThreadScheduledExecutor(
          r -> {
            Thread t = new Thread(r, "event-publisher");
            t.setDaemon(true);
            return t;
          });

  EventPublisher(
      EventsProperties properties,
      JdbcTemplate jdbc,
      TransactionTemplate transaction,
      RabbitTemplate rabbit,
      MeterRegistry meters) {
    this.properties = properties;
    this.jdbc = jdbc;
    this.transaction = transaction;
    this.rabbit = rabbit;
    this.meters = meters;
  }

  @PostConstruct
  void start() {
    long every = properties.publishInterval().toMillis();
    scheduler.scheduleWithFixedDelay(this::safely, every, every, TimeUnit.MILLISECONDS);
    scheduler.scheduleWithFixedDelay(this::purge, 1, 60, TimeUnit.MINUTES);
  }

  @PreDestroy
  void stop() {
    scheduler.shutdownNow();
  }

  private void safely() {
    try {
      publishDue();
    } catch (RuntimeException e) {
      log.error("event publishing failed: {}", e.getClass().getSimpleName());
    }
  }

  /** One round; public to the package so tests can run it without waiting. */
  void publishDue() {
    transaction.executeWithoutResult(
        status -> {
          List<Row> rows =
              jdbc.query(
                  """
                  select id, event_type, payload::text, attempts from event_outbox
                   where published_at is null and next_attempt_at <= now()
                   order by created_at limit ? for update skip locked
                  """,
                  (rs, i) ->
                      new Row(
                          rs.getObject(1, UUID.class),
                          rs.getString(2),
                          rs.getString(3),
                          rs.getInt(4)),
                  properties.batchSize());
          if (rows.isEmpty()) {
            return;
          }
          try {
            rabbit.invoke(
                ops -> {
                  for (Row row : rows) {
                    ops.send(properties.exchange(), row.type(), message(row));
                  }
                  ops.waitForConfirmsOrDie(CONFIRM_TIMEOUT_MS);
                  return null;
                });
            jdbc.batchUpdate(
                "update event_outbox set published_at = now(), attempts = attempts + 1, last_error = null where id = ?",
                rows.stream().map(r -> new Object[] {r.id()}).toList());
            meters.counter("hub.events.published", "result", "ok").increment(rows.size());
          } catch (RuntimeException e) {
            String reason = e.getClass().getSimpleName();
            for (Row row : rows) {
              jdbc.update(
                  "update event_outbox set attempts = attempts + 1, last_error = ?,"
                      + " next_attempt_at = now() + make_interval(secs => ?) where id = ?",
                  reason,
                  backoff(row.attempts()).toSeconds(),
                  row.id());
            }
            meters.counter("hub.events.published", "result", "failed").increment(rows.size());
            log.warn("{} events not published ({}), will retry", rows.size(), reason);
          }
        });
  }

  Duration backoff(int attempts) {
    long seconds = 1L << Math.min(attempts + 1, 20);
    return Duration.ofSeconds(Math.min(seconds, Math.max(1, properties.maxBackoff().toSeconds())));
  }

  private void purge() {
    try {
      jdbc.update(
          "delete from event_outbox where published_at < now() - make_interval(days => ?)",
          properties.retentionDays());
      jdbc.update(
          "delete from order_link where created_at < now() - make_interval(days => ?)",
          properties.orderLinkRetentionDays());
    } catch (RuntimeException e) {
      log.warn("event outbox purge failed: {}", e.getClass().getSimpleName());
    }
  }

  private static Message message(Row row) {
    return MessageBuilder.withBody(row.payload().getBytes(StandardCharsets.UTF_8))
        .setContentType("application/json")
        .setContentEncoding("UTF-8")
        .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
        .setMessageId(row.id().toString())
        .setType(row.type())
        .setTimestamp(new Date())
        .setHeader("schemaVersion", ClinicalEvent.SCHEMA_VERSION)
        .build();
  }
}
