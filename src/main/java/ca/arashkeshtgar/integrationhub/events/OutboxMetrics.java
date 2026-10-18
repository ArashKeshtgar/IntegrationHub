package ca.arashkeshtgar.integrationhub.events;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** How far behind the event feed is: rows waiting and the age of the oldest. */
@Component
class OutboxMetrics {

  OutboxMetrics(MeterRegistry registry, JdbcTemplate jdbc) {
    Gauge.builder(
            "hub.events.outbox.pending",
            () ->
                jdbc.queryForObject(
                    "select count(*) from event_outbox where published_at is null", Double.class))
        .description("Events written but not yet published to RabbitMQ")
        .register(registry);
    Gauge.builder(
            "hub.events.outbox.oldest.age.seconds",
            () ->
                jdbc.queryForObject(
                    "select coalesce(extract(epoch from now() - min(created_at)), 0)"
                        + " from event_outbox where published_at is null",
                    Double.class))
        .description("Age of the oldest unpublished event")
        .baseUnit("seconds")
        .register(registry);
  }
}
