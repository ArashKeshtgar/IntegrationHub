package ca.arashkeshtgar.integrationhub;

import static ca.arashkeshtgar.integrationhub.Hl7TestSupport.ackCode;
import static ca.arashkeshtgar.integrationhub.Hl7TestSupport.mllp;
import static ca.arashkeshtgar.integrationhub.Hl7TestSupport.sample;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import ca.arashkeshtgar.integrationhub.admin.AlertMonitor;
import ca.arashkeshtgar.integrationhub.hl7.Hl7InboundWorker;
import ca.arashkeshtgar.integrationhub.hl7.MllpServer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Phase 6: route health, alerts, dead letters and their replay, the message timeline, permissions
 * and CORS of the admin API.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AdminOperationsTest extends RelayTestBase {

  static final WireMockServer WEBHOOK =
      new WireMockServer(WireMockConfiguration.options().dynamicPort());

  static {
    WEBHOOK.start();
  }

  @DynamicPropertySource
  static void settings(DynamicPropertyRegistry registry) {
    registry.add("hub.hl7.enabled", () -> "true");
    registry.add("hub.hl7.queue-key", () -> Hl7TestSupport.QUEUE_KEY);
    registry.add("hub.hl7.mllp.port", () -> "0");
    registry.add("hub.hl7.mllp.bind-address", () -> "127.0.0.1");
    registry.add("hub.hl7.clinichub.token-url", () -> CLINICHUB.baseUrl() + "/connect/token");
    registry.add("hub.hl7.clinichub.fhir-url", () -> CLINICHUB.baseUrl() + "/fhir");
    registry.add("hub.hl7.clinichub.client-secret", () -> "test-secret");
    registry.add("hub.hl7.retry.initial-backoff", () -> "1s");
    registry.add("hub.hl7.retry.max-backoff", () -> "1s");
    registry.add("hub.hl7.retry.max-attempts", () -> "2");
    registry.add("hub.hl7.retry.interval", () -> "1h");
    registry.add("hub.alerts.interval", () -> "1h");
    registry.add("hub.alerts.webhook-url", () -> WEBHOOK.baseUrl() + "/hook");
    registry.add("hub.admin.cors-origins", () -> "http://localhost:5701");
  }

  @AfterAll
  static void stopWebhook() {
    WEBHOOK.stop();
  }

  static final String VIEW = "integrationhub:messages.view";
  static final String REPLAY = "integrationhub:messages.replay";

  @Autowired ObjectMapper json;
  @Autowired MllpServer server;
  @Autowired Hl7InboundWorker worker;
  @Autowired AlertMonitor monitor;

  @BeforeEach
  void clean() {
    jdbc.update("delete from hl7_inbound");
    jdbc.update("delete from hl7_outbound");
    jdbc.update("delete from alert");
    WEBHOOK.resetAll();
    WEBHOOK.stubFor(post("/hook").willReturn(aResponse().withStatus(204)));
    CLINICHUB.stubFor(
        post("/connect/token")
            .willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "{\"access_token\":\"hub-token\",\"token_type\":\"Bearer\",\"expires_in\":3600}")));
    CLINICHUB.stubFor(post("/fhir").willReturn(aResponse().withStatus(200).withBody("{}")));
  }

  // ---- helpers --------------------------------------------------------------------------------

  static String token(String subject, String... permissions) {
    return sign(
        validClaims().subject(subject).claim("permission", List.of(permissions)).build(), KEY);
  }

  static String viewer() {
    return token("7", VIEW);
  }

  static String operator() {
    return token("42", VIEW, REPLAY);
  }

  HttpResponse<String> call(String method, String path, String bearer) throws Exception {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .method(method, HttpRequest.BodyPublishers.noBody());
    if (bearer != null) {
      request.header("Authorization", "Bearer " + bearer);
    }
    return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  JsonNode get(String path) throws Exception {
    HttpResponse<String> response = call("GET", path, viewer());
    assertThat(response.statusCode()).as(path).isEqualTo(200);
    return json.readTree(response.body());
  }

  static byte[] read(String name) throws Exception {
    return Files.readAllBytes(Path.of("src/test/resources/contracts", name));
  }

  /** A lab result that ClinicHub refuses with 500 until the hub gives up (2 attempts). */
  String failedInbound(String controlId) throws Exception {
    CLINICHUB.stubFor(post("/fhir").willReturn(aResponse().withStatus(500)));
    assertThat(ackCode(mllp(server.port(), sample("oru-final.hl7", controlId)))).isEqualTo("AE");
    Awaitility.await()
        .atMost(Duration.ofSeconds(20))
        .pollInterval(Duration.ofMillis(300))
        .untilAsserted(
            () -> {
              worker.runOnce();
              assertThat(inbound(controlId).get("status")).isEqualTo("FAILED");
            });
    return inbound(controlId).get("id").toString();
  }

  Map<String, Object> inbound(String controlId) {
    return jdbc.queryForMap("select * from hl7_inbound where control_id = ?", controlId);
  }

  // ---- who may do what ------------------------------------------------------------------------

  @Test
  void theAdminApiNeedsATokenAndReplayNeedsItsOwnPermission() throws Exception {
    for (String path :
        List.of(
            "/api/admin/me",
            "/api/admin/overview",
            "/api/admin/alerts",
            "/api/admin/dead-letters")) {
      assertThat(call("GET", path, null).statusCode()).as(path).isEqualTo(401);
      assertThat(call("GET", path, validToken()).statusCode()).as(path).isEqualTo(403);
      assertThat(call("GET", path, viewer()).statusCode()).as(path).isEqualTo(200);
    }
    String replay = "/api/admin/dead-letters/inbound/" + UUID.randomUUID() + "/replay";
    assertThat(call("POST", replay, null).statusCode()).isEqualTo(401);
    assertThat(call("POST", replay, viewer()).statusCode()).as("view only").isEqualTo(403);
    assertThat(call("POST", replay, token("9", REPLAY)).statusCode())
        .as("replay without view")
        .isEqualTo(403);
    assertThat(call("POST", replay, operator()).statusCode())
        .as("allowed, row unknown")
        .isEqualTo(404);
  }

  @Test
  void permissionsWithoutThePrefixClinicHubStripsAreAccepted() throws Exception {
    String bare = token("5", "messages.view", "messages.replay");
    assertThat(call("GET", "/api/messages", bare).statusCode()).isEqualTo(200);
    JsonNode me = json.readTree(call("GET", "/api/admin/me", bare).body());
    assertThat(me.get("subject").asText()).isEqualTo("5");
    assertThat(me.get("canReplay").asBoolean()).isTrue();
    assertThat(me.get("hl7Enabled").asBoolean()).isTrue();
    JsonNode viewOnly =
        json.readTree(call("GET", "/api/admin/me", token("5", "messages.view")).body());
    assertThat(viewOnly.get("canReplay").asBoolean()).isFalse();
  }

  @Test
  void onlyTheListedPageMayCallFromABrowser() throws Exception {
    HttpRequest preflight =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/admin/overview"))
            .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
            .header("Origin", "http://localhost:5701")
            .header("Access-Control-Request-Method", "GET")
            .header("Access-Control-Request-Headers", "authorization")
            .build();
    HttpResponse<String> allowed = HTTP.send(preflight, HttpResponse.BodyHandlers.ofString());
    assertThat(allowed.statusCode()).isEqualTo(200);
    assertThat(allowed.headers().firstValue("Access-Control-Allow-Origin"))
        .contains("http://localhost:5701");

    HttpRequest other =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/admin/overview"))
            .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
            .header("Origin", "http://evil.example")
            .header("Access-Control-Request-Method", "GET")
            .build();
    HttpResponse<String> refused = HTTP.send(other, HttpResponse.BodyHandlers.ofString());
    assertThat(refused.headers().firstValue("Access-Control-Allow-Origin")).isEmpty();
    assertThat(refused.statusCode()).isIn(401, 403);

    // The relay itself has no CORS.
    HttpRequest relay =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/route/clinichub/fhir"))
            .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
            .header("Origin", "http://localhost:5701")
            .header("Access-Control-Request-Method", "POST")
            .build();
    assertThat(
            HTTP.send(relay, HttpResponse.BodyHandlers.ofString())
                .headers()
                .firstValue("Access-Control-Allow-Origin"))
        .isEmpty();
  }

  // ---- route health and alerts ----------------------------------------------------------------

  @Test
  void aRouteThatKeepsFailingIsDownOpensOneAlertAndResolvesWhenItAnswersAgain() throws Exception {
    CLINICHUB.stubFor(post("/fhir").willReturn(aResponse().withStatus(500)));
    for (int i = 0; i < 3; i++) {
      assertThat(postBundle("clinichub", validToken(), read("lab-result-bundle.json")).statusCode())
          .isEqualTo(500);
    }

    JsonNode routes = get("/api/admin/overview").get("routes");
    JsonNode clinichub = find(routes, "clinichub");
    assertThat(clinichub.get("status").asText()).isEqualTo("down");
    assertThat(clinichub.get("failures15m").asInt()).isEqualTo(3);
    assertThat(find(routes, "labflow").get("status").asText()).isEqualTo("idle");
    assertThat(find(routes, "hl7")).as("the listener is on").isNotNull();

    monitor.runOnce();
    monitor.runOnce();
    JsonNode open = get("/api/admin/alerts?state=open").get("items");
    assertThat(open).as("one alert, not one per round").hasSize(1);
    assertThat(open.get(0).get("kind").asText()).isEqualTo("route-down");
    assertThat(open.get(0).get("subject").asText()).isEqualTo("clinichub");
    assertThat(open.get(0).get("notified").asBoolean()).isTrue();
    assertThat(get("/api/admin/overview").get("openAlerts").asInt()).isEqualTo(1);
    WEBHOOK.verify(
        1,
        postRequestedFor(urlEqualTo("/hook"))
            .withRequestBody(
                equalToJson(
                    "{\"state\":\"opened\",\"kind\":\"route-down\",\"subject\":\"clinichub\"}",
                    true,
                    true)));

    CLINICHUB.stubFor(post("/fhir").willReturn(aResponse().withStatus(200).withBody("{}")));
    assertThat(postBundle("clinichub", validToken(), read("lab-result-bundle.json")).statusCode())
        .isEqualTo(200);
    assertThat(find(get("/api/admin/overview").get("routes"), "clinichub").get("status").asText())
        .isEqualTo("degraded");

    monitor.runOnce();
    assertThat(get("/api/admin/alerts?state=open").get("items")).isEmpty();
    JsonNode all = get("/api/admin/alerts?state=all").get("items");
    assertThat(all).hasSize(1);
    assertThat(all.get(0).get("resolvedAt").isNull()).isFalse();
    WEBHOOK.verify(
        1,
        postRequestedFor(urlEqualTo("/hook"))
            .withRequestBody(
                equalToJson("{\"state\":\"resolved\",\"subject\":\"clinichub\"}", true, true)));
    String sent = WEBHOOK.getAllServeEvents().toString();
    assertThat(sent).doesNotContain("Zzyzx").doesNotContain("MARKER-");
  }

  @Test
  void refusalsAndUpstream4xxAreNotFailures() throws Exception {
    CLINICHUB.stubFor(post("/fhir").willReturn(aResponse().withStatus(409).withBody("{}")));
    for (int i = 0; i < 4; i++) {
      postBundle("clinichub", validToken(), read("lab-result-bundle.json"));
      postBundle("clinichub", "abc.def.ghi", read("lab-result-bundle.json"));
    }
    assertThat(find(get("/api/admin/overview").get("routes"), "clinichub").get("status").asText())
        .isEqualTo("ok");
    monitor.runOnce();
    assertThat(get("/api/admin/alerts").get("items")).isEmpty();
  }

  @Test
  void aWebhookThatIsDownIsTriedAgainNextRound() throws Exception {
    WEBHOOK.stubFor(post("/hook").willReturn(aResponse().withStatus(500)));
    CLINICHUB.stubFor(post("/fhir").willReturn(aResponse().withStatus(500)));
    for (int i = 0; i < 3; i++) {
      postBundle("clinichub", validToken(), read("lab-result-bundle.json"));
    }

    monitor.runOnce();
    assertThat(get("/api/admin/alerts").get("items").get(0).get("notified").asBoolean()).isFalse();

    WEBHOOK.stubFor(post("/hook").willReturn(aResponse().withStatus(204)));
    monitor.runOnce();
    assertThat(get("/api/admin/alerts").get("items").get(0).get("notified").asBoolean()).isTrue();
  }

  // ---- dead letters and replay ----------------------------------------------------------------

  @Test
  void aGivenUpMessageIsListedKeptForReplayAndDeliveredAfterReplay() throws Exception {
    String id = failedInbound("T-DL-1");
    assertThat(inbound("T-DL-1").get("payload"))
        .as("kept, encrypted, for the replay window")
        .isNotNull();
    assertThat(inbound("T-DL-1").get("failed_at")).isNotNull();

    JsonNode list = get("/api/admin/dead-letters");
    assertThat(list.get("replayWindowDays").asInt()).isEqualTo(7);
    assertThat(list.get("items")).hasSize(1);
    JsonNode letter = list.get("items").get(0);
    assertThat(letter.get("queue").asText()).isEqualTo("inbound");
    assertThat(letter.get("status").asText()).isEqualTo("FAILED");
    assertThat(letter.get("controlId").asText()).isEqualTo("T-DL-1");
    assertThat(letter.get("replayable").asBoolean()).isTrue();
    assertThat(letter.get("attempts").asInt()).isEqualTo(2);
    assertThat(list.toString().toLowerCase())
        .doesNotContain("payload")
        .doesNotContain("testpatient");

    monitor.runOnce();
    JsonNode alert = get("/api/admin/alerts").get("items").get(0);
    assertThat(alert.get("kind").asText()).isEqualTo("hl7-dead-letters");
    assertThat(alert.get("subject").asText()).isEqualTo("inbound");

    String path = "/api/admin/dead-letters/inbound/" + id + "/replay";
    assertThat(call("POST", path, viewer()).statusCode()).isEqualTo(403);
    assertThat(call("POST", path, operator()).statusCode()).isEqualTo(202);
    Map<String, Object> queued = inbound("T-DL-1");
    assertThat(queued.get("status")).isEqualTo("PENDING");
    assertThat(queued.get("attempts")).isEqualTo(0);
    assertThat(queued.get("replay_count")).isEqualTo(1);
    assertThat(queued.get("last_replayed_by")).isEqualTo("42");

    CLINICHUB.stubFor(post("/fhir").willReturn(aResponse().withStatus(200).withBody("{}")));
    assertThat(worker.runOnce()).isEqualTo(1);
    Map<String, Object> done = inbound("T-DL-1");
    assertThat(done.get("status")).isEqualTo("DELIVERED");
    assertThat(done.get("payload")).as("cleared once delivered").isNull();
    assertThat(get("/api/admin/dead-letters").get("items")).isEmpty();

    monitor.runOnce();
    assertThat(get("/api/admin/alerts").get("items")).as("resolved by the replay").isEmpty();

    assertThat(call("POST", path, operator()).statusCode()).as("already delivered").isEqualTo(409);
    assertThat(
            call(
                    "POST",
                    "/api/admin/dead-letters/inbound/" + UUID.randomUUID() + "/replay",
                    operator())
                .statusCode())
        .isEqualTo(404);
    assertThat(
            call("POST", "/api/admin/dead-letters/other/" + id + "/replay", operator())
                .statusCode())
        .isEqualTo(404);
  }

  @Test
  void aMessageThatFailsAgainAfterReplayStaysReplayable() throws Exception {
    String id = failedInbound("T-DL-2");
    String path = "/api/admin/dead-letters/inbound/" + id + "/replay";
    assertThat(call("POST", path, operator()).statusCode()).isEqualTo(202);

    Awaitility.await()
        .atMost(Duration.ofSeconds(20))
        .pollInterval(Duration.ofMillis(300))
        .untilAsserted(
            () -> {
              worker.runOnce();
              assertThat(inbound("T-DL-2").get("status")).isEqualTo("FAILED");
            });

    JsonNode letter = get("/api/admin/dead-letters").get("items").get(0);
    assertThat(letter.get("replayable").asBoolean()).isTrue();
    assertThat(letter.get("replayCount").asInt()).isEqualTo(1);
    assertThat(letter.get("lastReplayedBy").asText()).isEqualTo("42");
  }

  @Test
  void aMessageThatClinicHubRefusedCanBeReplayedToo() throws Exception {
    CLINICHUB.stubFor(post("/fhir").willReturn(aResponse().withStatus(422)));
    assertThat(ackCode(mllp(server.port(), sample("oru-final.hl7", "T-DL-3")))).isEqualTo("AR");
    assertThat(inbound("T-DL-3").get("status")).isEqualTo("REJECTED");
    assertThat(inbound("T-DL-3").get("payload")).isNotNull();

    String id = inbound("T-DL-3").get("id").toString();
    CLINICHUB.stubFor(post("/fhir").willReturn(aResponse().withStatus(200).withBody("{}")));
    assertThat(
            call("POST", "/api/admin/dead-letters/inbound/" + id + "/replay", operator())
                .statusCode())
        .isEqualTo(202);
    assertThat(worker.runOnce()).isEqualTo(1);
    assertThat(inbound("T-DL-3").get("status")).isEqualTo("DELIVERED");
  }

  @Test
  void afterTheReplayWindowThePayloadIsDroppedAndTheMessageCannotBeReplayed() throws Exception {
    String id = failedInbound("T-DL-4");
    jdbc.update(
        "update hl7_inbound set failed_at = now() - interval '8 days' where control_id = 'T-DL-4'");

    worker.purge();

    Map<String, Object> row = inbound("T-DL-4");
    assertThat(row.get("payload")).isNull();
    assertThat(row.get("status")).as("the row stays for the log").isEqualTo("FAILED");
    assertThat(get("/api/admin/dead-letters").get("items").get(0).get("replayable").asBoolean())
        .isFalse();
    HttpResponse<String> replay =
        call("POST", "/api/admin/dead-letters/inbound/" + id + "/replay", operator());
    assertThat(replay.statusCode()).isEqualTo(409);
    assertThat(replay.body()).contains("no longer kept");
  }

  @Test
  void aGivenUpCopyForTheReceiverCanBeReplayed() throws Exception {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "insert into hl7_outbound (id, status, attempts, payload, failed_at, last_error)"
            + " values (?, 'FAILED', 12, ?, now(), 'receiver-unreachable')",
        id,
        new byte[] {1, 2, 3});

    JsonNode letter = get("/api/admin/dead-letters").get("items").get(0);
    assertThat(letter.get("queue").asText()).isEqualTo("outbound");
    assertThat(letter.get("lastError").asText()).isEqualTo("receiver-unreachable");

    assertThat(
            call("POST", "/api/admin/dead-letters/outbound/" + id + "/replay", operator())
                .statusCode())
        .isEqualTo(202);
    Map<String, Object> row = jdbc.queryForMap("select * from hl7_outbound where id = ?", id);
    assertThat(row.get("status")).isEqualTo("PENDING");
    assertThat(row.get("replay_count")).isEqualTo(1);
  }

  // ---- timeline -------------------------------------------------------------------------------

  @Test
  void aMessageTimelineShowsItsAnswerAndTheCallsThatShareItsIdentifier() throws Exception {
    LABFLOW.stubFor(post("/fhir").willReturn(aResponse().withStatus(200).withBody("{}")));
    postBundle("labflow", ContractFlowsTest.ordersToken("labflow"), read("order-bundle.json"));
    postBundle("labflow", ContractFlowsTest.ordersToken("labflow"), read("order-bundle.json"));

    JsonNode items = get("/api/messages?route=labflow").get("items");
    assertThat(items).hasSize(2);
    String id = items.get(0).get("id").asText();
    String other = items.get(1).get("id").asText();

    JsonNode timeline = get("/api/messages/" + id + "/timeline");
    assertThat(timeline.get("steps").get(0).get("kind").asText()).isEqualTo("received");
    assertThat(timeline.get("steps").get(1).get("kind").asText()).isEqualTo("forwarded");
    assertThat(timeline.get("steps").get(1).get("text").asText()).contains("answered 200");
    assertThat(timeline.get("related")).hasSize(1);
    assertThat(timeline.get("related").get(0).get("id").asText()).isEqualTo(other);

    assertThat(
            call("GET", "/api/messages/" + UUID.randomUUID() + "/timeline", viewer()).statusCode())
        .isEqualTo(404);
    assertThat(call("GET", "/api/messages/" + id + "/timeline", null).statusCode()).isEqualTo(401);
  }

  @Test
  void anHl7TimelineShowsTheQueueSteps() throws Exception {
    failedInbound("T-DL-5");
    JsonNode latest = get("/api/messages?route=hl7").get("items").get(0);

    JsonNode steps = get("/api/messages/" + latest.get("id").asText() + "/timeline").get("steps");

    String text = steps.findValuesAsText("text").toString();
    assertThat(text)
        .contains("Result queued for ClinicHub")
        .contains("Given up after 2 attempt(s)");
    assertThat(text.toLowerCase()).doesNotContain("testpatient");
  }

  @Test
  void theOverviewCountsTheQueues() throws Exception {
    failedInbound("T-DL-6");

    JsonNode queues = get("/api/admin/overview").get("queues");

    assertThat(queues.get("hl7InboundFailed").asInt()).isEqualTo(1);
    assertThat(queues.get("hl7InboundPending").asInt()).isZero();
    assertThat(queues.get("hl7OutboundFailed").asInt()).isZero();
  }

  private static JsonNode find(JsonNode routes, String name) {
    for (JsonNode route : routes) {
      if (route.get("route").asText().equals(name)) {
        return route;
      }
    }
    return null;
  }
}
