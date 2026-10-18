package ca.arashkeshtgar.integrationhub;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.MeterRegistry;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.RabbitMQContainer;

/**
 * RabbitMQ is down while a call is relayed, then comes up: the sender is not affected and no event
 * is lost.
 */
// The publisher of a cached context would keep taking rows from the shared test database.
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class EventsOutboxTest extends RelayTestBase {

  static final int BROKER_PORT;
  static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:3.13-management-alpine");

  static {
    try (ServerSocket socket = new ServerSocket(0)) {
      BROKER_PORT = socket.getLocalPort();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
    RABBIT.setPortBindings(List.of(BROKER_PORT + ":5672"));
  }

  @DynamicPropertySource
  static void events(DynamicPropertyRegistry registry) {
    registry.add("spring.rabbitmq.host", () -> "localhost");
    registry.add("spring.rabbitmq.port", () -> BROKER_PORT);
    registry.add("hub.events.enabled", () -> "true");
    registry.add("hub.events.pseudonym-secret", () -> EventsTest.SECRET);
    registry.add("hub.events.publish-interval", () -> "200ms");
    registry.add("hub.events.max-backoff", () -> "2s");
  }

  @Autowired RabbitTemplate rabbit;
  @Autowired MeterRegistry meters;

  @Test
  void eventsWaitInTheOutboxWhileTheBrokerIsDownAndArePublishedWhenItIsBack() throws Exception {
    jdbc.update("delete from event_outbox");
    LABFLOW.stubFor(post("/fhir").willReturn(aResponse().withStatus(200).withBody("{}")));

    var response =
        postBundle(
            "labflow",
            ContractFlowsTest.ordersToken("labflow"),
            Files.readAllBytes(Path.of("src/test/resources/contracts/order-bundle.json")));

    // The sender got its answer although nothing could be published.
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from event_outbox where published_at is null", Integer.class))
        .isEqualTo(4);
    Awaitility.await()
        .atMost(Duration.ofSeconds(20))
        .untilAsserted(
            () -> {
              var row =
                  jdbc.queryForMap("select max(attempts) a, max(last_error) e from event_outbox");
              assertThat(((Number) row.get("a")).intValue()).isGreaterThanOrEqualTo(2);
              assertThat(row.get("e")).isNotNull();
            });
    assertThat(meters.get("hub.events.outbox.pending").gauge().value()).isEqualTo(4.0);
    assertThat(meters.get("hub.events.outbox.oldest.age.seconds").gauge().value())
        .isGreaterThan(0.0);

    RABBIT.start();

    Awaitility.await()
        .atMost(Duration.ofSeconds(90))
        .until(
            () ->
                jdbc.queryForObject(
                        "select count(*) from event_outbox where published_at is null",
                        Integer.class)
                    == 0);
    int received = 0;
    for (int i = 0; i < 20 && received < 4; i++) {
      Message m = rabbit.receive(EventsTest.QUEUE, 1000);
      if (m != null) {
        received++;
      }
    }
    assertThat(received).isEqualTo(4);
    assertThat(meters.get("hub.events.outbox.pending").gauge().value()).isZero();
  }
}
