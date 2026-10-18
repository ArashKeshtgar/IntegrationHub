package ca.arashkeshtgar.integrationhub;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.RabbitMQContainer;

/**
 * The event feed against a real RabbitMQ: what is published, to whom it can be traced, and what is
 * not in it.
 */
// The publisher of a cached context would keep taking rows from the shared test database.
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@ExtendWith(OutputCaptureExtension.class)
class EventsTest extends RelayTestBase {

  static final String SECRET = "test-secret-test-secret-test-secret-123";
  static final String QUEUE = "analytics.clinical-events";
  static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:3.13-management-alpine");

  static {
    RABBIT.start();
  }

  @DynamicPropertySource
  static void events(DynamicPropertyRegistry registry) {
    registry.add("spring.rabbitmq.host", RABBIT::getHost);
    registry.add("spring.rabbitmq.port", RABBIT::getAmqpPort);
    registry.add("hub.events.enabled", () -> "true");
    registry.add("hub.events.pseudonym-secret", () -> SECRET);
    registry.add("hub.events.publish-interval", () -> "200ms");
  }

  @Autowired RabbitTemplate rabbit;
  @Autowired ObjectMapper json;

  static byte[] read(String name) throws Exception {
    return Files.readAllBytes(Path.of("src/test/resources/contracts", name));
  }

  @BeforeEach
  void emptyTheFeed() {
    jdbc.update("delete from event_outbox");
    jdbc.update("delete from order_link");
    rabbit.execute(channel -> channel.queuePurge(QUEUE));
  }

  @AfterEach
  void waitUntilNothingIsPending() {
    Awaitility.await()
        .atMost(Duration.ofSeconds(20))
        .until(
            () ->
                jdbc.queryForObject(
                        "select count(*) from event_outbox where published_at is null",
                        Integer.class)
                    == 0);
  }

  /** Receives until `count` messages have arrived (or fails), plus a short wait for strays. */
  List<Message> receive(int count) {
    List<Message> got = new ArrayList<>();
    long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
    while (got.size() < count && System.nanoTime() < deadline) {
      Message m = rabbit.receive(QUEUE, 500);
      if (m != null) {
        got.add(m);
      }
    }
    assertThat(got).as("events received").hasSize(count);
    Message extra = rabbit.receive(QUEUE, 700);
    assertThat(extra).as("no more events than expected").isNull();
    return got;
  }

  JsonNode body(Message m) throws Exception {
    return json.readTree(m.getBody());
  }

  JsonNode only(List<Message> messages, String type, String orderId) throws Exception {
    List<JsonNode> found = new ArrayList<>();
    for (Message m : messages) {
      JsonNode n = body(m);
      if (type.equals(n.get("type").asText()) && orderId.equals(n.get("orderId").asText())) {
        found.add(n);
      }
    }
    assertThat(found).as(type + " " + orderId).hasSize(1);
    return found.get(0);
  }

  static String hmac(String clinic, String mrn) throws Exception {
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    return HexFormat.of()
        .formatHex(mac.doFinal((clinic + "|" + mrn).getBytes(StandardCharsets.UTF_8)));
  }

  void placeOrders(String app) throws Exception {
    var server = app.equals("labflow") ? LABFLOW : CLINICREPORT;
    server.stubFor(
        post("/fhir")
            .willReturn(aResponse().withStatus(200).withBody(read("order-response.json"))));
    var response = postBundle(app, ContractFlowsTest.ordersToken(app), read("order-bundle.json"));
    assertThat(response.statusCode()).isEqualTo(200);
  }

  // ---- orders ---------------------------------------------------------------------------------

  @Test
  void anAcceptedOrderMakesPlacedAndAcceptedEventsForEachTest(CapturedOutput output)
      throws Exception {
    placeOrders("labflow");

    List<Message> messages = receive(4);

    JsonNode placed = only(messages, "order.placed", "service-request|101");
    only(messages, "order.placed", "service-request|102");
    only(messages, "order.accepted", "service-request|101");
    JsonNode accepted = only(messages, "order.accepted", "service-request|102");

    assertThat(placed.get("schemaVersion").asInt()).isEqualTo(1);
    assertThat(placed.get("clinicSlug").asText()).isEqualTo("harbourview");
    assertThat(placed.get("application").asText()).isEqualTo("labflow");
    assertThat(placed.get("testCodeSystem").asText()).isEqualTo("http://loinc.org");
    assertThat(placed.get("testCode").asText()).isEqualTo("718-7");
    assertThat(placed.get("priority").asText()).isEqualTo("routine");
    assertThat(placed.get("status").asText()).isEqualTo("active");
    assertThat(placed.get("patientPseudonym").asText())
        .isEqualTo(hmac("harbourview", "MRN-MARKER-0001"));
    assertThat(placed.get("finance").isNull()).isTrue();
    assertThat(Instant.parse(placed.get("occurredAt").asText()))
        .isBefore(Instant.now().plusSeconds(1));
    assertThat(accepted.get("testCode").asText()).isEqualTo("2160-0");
    assertThat(accepted.get("priority").asText()).isEqualTo("urgent");
    assertThat(accepted.get("patientPseudonym").asText())
        .isEqualTo(placed.get("patientPseudonym").asText());
    assertThat(UUID.fromString(placed.get("eventId").asText()))
        .isEqualTo(
            UUID.nameUUIDFromBytes(
                "order.placed|harbourview|service-request|101|".getBytes(StandardCharsets.UTF_8)));

    var properties = messages.get(0).getMessageProperties();
    assertThat(properties.getReceivedExchange()).isEqualTo("clinical.events");
    assertThat(properties.getReceivedRoutingKey()).isEqualTo(properties.getType());
    assertThat(properties.getContentType()).isEqualTo("application/json");
    assertThat(properties.getReceivedDeliveryMode().name()).isEqualTo("PERSISTENT");
    assertThat(properties.getHeaders()).containsEntry("schemaVersion", 1);
    for (Message m : messages) {
      assertThat(m.getMessageProperties().getMessageId())
          .isEqualTo(body(m).get("eventId").asText());
    }

    // Nothing that names the patient or the doctor, anywhere the hub keeps or sends anything.
    String everywhere =
        messages.stream()
                .map(m -> new String(m.getBody(), StandardCharsets.UTF_8))
                .reduce("", String::concat)
            + String.join(
                "", jdbc.queryForList("select payload::text from event_outbox", String.class))
            + String.join(
                "",
                jdbc.queryForList("select row_to_json(l)::text from order_link l", String.class))
            + String.join(
                "", jdbc.queryForList("select row_to_json(m)::text from message m", String.class));
    for (String marker :
        new String[] {
          "Zzyzx",
          "Testpatient",
          "HC-MARKER",
          "MRN-MARKER",
          "1900-01-01",
          "CPSO",
          "MARKER-",
          "Synthetic"
        }) {
      assertThat(everywhere).as(marker).doesNotContain(marker);
      assertThat(output.getAll()).as(marker).doesNotContain(marker);
    }
    assertThat(jdbc.queryForObject("select count(*) from order_link", Integer.class)).isEqualTo(2);
  }

  @Test
  void theSameOrderSentAgainIsTheSameEvents() throws Exception {
    placeOrders("labflow");
    placeOrders("labflow");

    receive(4);

    assertThat(jdbc.queryForObject("select count(*) from event_outbox", Integer.class))
        .isEqualTo(4);
    assertThat(jdbc.queryForObject("select count(*) from message", Integer.class)).isEqualTo(2);
  }

  @Test
  void anOrderTheAppRefusedIsPlacedButNotAccepted() throws Exception {
    LABFLOW.stubFor(post("/fhir").willReturn(aResponse().withStatus(400).withBody("{}")));

    postBundle("labflow", ContractFlowsTest.ordersToken("labflow"), read("order-bundle.json"));

    List<Message> messages = receive(2);
    assertThat(messages)
        .allSatisfy(m -> assertThat(m.getMessageProperties().getType()).isEqualTo("order.placed"));
  }

  @Test
  void anOrderTheUpstreamNeverAnsweredMakesNoEvent() throws Exception {
    var response = postBundle("dead", validToken(), read("order-bundle.json"));

    assertThat(response.statusCode()).isEqualTo(502);
    receive(0);
    assertThat(jdbc.queryForObject("select count(*) from event_outbox", Integer.class)).isZero();
    assertThat(jdbc.queryForObject("select count(*) from order_link", Integer.class)).isZero();
  }

  // ---- results --------------------------------------------------------------------------------

  @Test
  void resultsCarryThePseudonymApplicationAndPriorityOfTheirOrder(CapturedOutput output)
      throws Exception {
    placeOrders("clinicreport");
    String pseudonym = hmac("harbourview", "MRN-MARKER-0001");
    receive(4);
    CLINICHUB.stubFor(post("/fhir").willReturn(aResponse().withStatus(200).withBody("{}")));

    // The sender of the result is "labflow" (the token), the order went to "clinicreport".
    postBundle("clinichub", validToken(), read("result-bundle-for-orders.json"));

    List<Message> messages = receive(2);
    JsonNode finalReport = only(messages, "result.final", "service-request|101");
    JsonNode amended = only(messages, "result.amended", "service-request|102");
    assertThat(finalReport.get("patientPseudonym").asText()).isEqualTo(pseudonym);
    assertThat(finalReport.get("application").asText()).isEqualTo("clinicreport");
    assertThat(finalReport.get("clinicSlug").asText()).isEqualTo("harbourview");
    assertThat(finalReport.get("testCode").asText()).isEqualTo("718-7");
    assertThat(finalReport.get("priority").asText()).isEqualTo("routine");
    assertThat(finalReport.get("status").asText()).isEqualTo("final");
    assertThat(amended.get("priority").asText()).isEqualTo("urgent");
    assertThat(amended.get("status").asText()).isEqualTo("amended");
    // A report whose status is "cancelled" makes no event.
    assertThat(messages).hasSize(2);
    String everything =
        new String(messages.get(0).getBody()) + new String(messages.get(1).getBody());
    for (String marker : new String[] {"Zzyzx", "MARKER-"}) {
      assertThat(everything).doesNotContain(marker);
      assertThat(output.getAll()).doesNotContain(marker);
    }
  }

  @Test
  void aResultForAnOrderTheHubNeverSawHasNoPseudonym() throws Exception {
    CLINICHUB.stubFor(post("/fhir").willReturn(aResponse().withStatus(200).withBody("{}")));

    postBundle("clinichub", validToken(), read("result-bundle-unknown-order.json"));

    JsonNode event = body(receive(1).get(0));
    assertThat(event.get("type").asText()).isEqualTo("result.final");
    assertThat(event.get("orderId").asText()).isEqualTo("service-request|999");
    assertThat(event.get("patientPseudonym").isNull()).isTrue();
    assertThat(event.get("priority").isNull()).isTrue();
    assertThat(event.get("application").asText()).isEqualTo("labflow");
    assertThat(event.get("testCode").asText()).isEqualTo("6690-2");
  }

  @Test
  void aResultTheUpstreamRefusedMakesNoEvent() throws Exception {
    CLINICHUB.stubFor(post("/fhir").willReturn(aResponse().withStatus(422).withBody("{}")));

    postBundle("clinichub", validToken(), read("result-bundle-unknown-order.json"));

    receive(0);
  }

  // ---- cancels --------------------------------------------------------------------------------

  @Test
  void aCancelMakesCancelledOrCancelRefused() throws Exception {
    placeOrders("labflow");
    receive(4);
    String path =
        "/route/labflow/fhir/ServiceRequest?identifier=urn:clinichub:harbourview:service-request%7C101";
    byte[] cancel = read("cancel-service-request.json");
    String token = ContractFlowsTest.ordersToken("labflow");

    LABFLOW.stubFor(
        put(urlMatching("/fhir/ServiceRequest.*"))
            .willReturn(aResponse().withStatus(200).withBody("{}")));
    new ContractCaller().put(path, token, cancel);
    JsonNode cancelled = body(receive(1).get(0));
    assertThat(cancelled.get("type").asText()).isEqualTo("order.cancelled");
    assertThat(cancelled.get("orderId").asText()).isEqualTo("service-request|101");
    assertThat(cancelled.get("status").asText()).isEqualTo("revoked");
    assertThat(cancelled.get("patientPseudonym").asText())
        .isEqualTo(hmac("harbourview", "MRN-MARKER-0001"));
    assertThat(cancelled.get("testCode").asText()).isEqualTo("718-7");
    assertThat(cancelled.get("application").asText()).isEqualTo("labflow");

    LABFLOW.stubFor(
        put(urlMatching("/fhir/ServiceRequest.*"))
            .willReturn(aResponse().withStatus(409).withBody("{}")));
    new ContractCaller().put(path, token, cancel);
    JsonNode refused = body(receive(1).get(0));
    assertThat(refused.get("type").asText()).isEqualTo("order.cancel-refused");
    assertThat(refused.get("patientPseudonym").asText())
        .isEqualTo(cancelled.get("patientPseudonym").asText());

    LABFLOW.stubFor(
        put(urlMatching("/fhir/ServiceRequest.*"))
            .willReturn(aResponse().withStatus(500).withBody("{}")));
    new ContractCaller().put(path, token, cancel);
    receive(0);
  }

  /** PUT with a body, which the base class's helper does not do. */
  final class ContractCaller {
    void put(String path, String token, byte[] body) throws Exception {
      var request =
          java.net.http.HttpRequest.newBuilder(
                  java.net.URI.create("http://localhost:" + port + path))
              .header("Authorization", "Bearer " + token)
              .header("Content-Type", "application/fhir+json")
              .PUT(java.net.http.HttpRequest.BodyPublishers.ofByteArray(body))
              .build();
      HTTP.send(request, java.net.http.HttpResponse.BodyHandlers.ofByteArray());
    }
  }

  // ---- finance --------------------------------------------------------------------------------

  @Test
  void eachFinancialDocumentMakesAnEventWithoutAmounts(CapturedOutput output) throws Exception {
    FINANCE.stubFor(
        post("/api/integration/documents").willReturn(aResponse().withStatus(200).withBody("{}")));

    var response =
        send(
            "/route/finance/api/integration/documents",
            ContractFlowsTest.financeToken(),
            "application/json",
            read("finance-documents.json"));
    assertThat(response.statusCode()).isEqualTo(200);

    List<Message> messages = receive(2);
    List<String> externalIds = new ArrayList<>();
    for (Message m : messages) {
      JsonNode event = body(m);
      assertThat(event.get("type").asText()).isEqualTo("finance.document");
      assertThat(event.get("application").asText()).isEqualTo("clinichub");
      assertThat(event.get("clinicSlug").isNull()).isTrue();
      assertThat(event.get("patientPseudonym").isNull()).isTrue();
      assertThat(event.get("finance").get("sourceKey").asText()).isEqualTo("harbourview");
      externalIds.add(event.get("finance").get("externalId").asText());
      String text = new String(m.getBody(), StandardCharsets.UTF_8);
      for (String marker : new String[] {"AB12", "91.17", "12.34", "OHIP", "123456"}) {
        assertThat(text).as(marker).doesNotContain(marker);
      }
    }
    assertThat(externalIds).containsExactlyInAnyOrder("claim:1234:ready", "claim:1235:reversal");
  }

  @Test
  void documentsTheUpstreamRefusedMakeNoEvent() throws Exception {
    FINANCE.stubFor(
        post("/api/integration/documents").willReturn(aResponse().withStatus(400).withBody("{}")));

    send(
        "/route/finance/api/integration/documents",
        ContractFlowsTest.financeToken(),
        "application/json",
        read("finance-documents.json"));

    receive(0);
  }
}
