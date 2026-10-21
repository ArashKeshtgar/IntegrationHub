package ca.arashkeshtgar.integrationhub;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** The message search and the metrics: who may read them, and what they show. */
class AdminApiTest extends RelayTestBase {

  static final String PERMISSION = "integrationhub:messages.view";

  @Autowired ObjectMapper json;

  static String adminToken() {
    return sign(validClaims().claim("permission", List.of(PERMISSION)).build(), KEY);
  }

  HttpResponse<String> get(String path, String bearer) throws Exception {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET();
    if (bearer != null) {
      request.header("Authorization", "Bearer " + bearer);
    }
    return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  JsonNode search(String query) throws Exception {
    var response = get("/api/messages" + query, adminToken());
    assertThat(response.statusCode()).as(query).isEqualTo(200);
    return json.readTree(response.body());
  }

  static byte[] read(String name) throws Exception {
    return Files.readAllBytes(Path.of("src/test/resources/contracts", name));
  }

  /** Four calls in a known order: results, an order, a refusal, an unknown route. */
  void seed() throws Exception {
    CLINICHUB.stubFor(post("/fhir").willReturn(aResponse().withStatus(200).withBody("{}")));
    LABFLOW.stubFor(post("/fhir").willReturn(aResponse().withStatus(200).withBody("{}")));
    postBundle("clinichub", validToken(), read("lab-result-bundle.json"));
    Thread.sleep(15);
    postBundle("labflow", ContractFlowsTest.ordersToken("labflow"), read("order-bundle.json"));
    Thread.sleep(15);
    postBundle("clinichub", "abc.def.ghi", read("lab-result-bundle.json"));
    Thread.sleep(15);
    postBundle("nobody", validToken(), "{}".getBytes(StandardCharsets.UTF_8));
  }

  // ---- who may read ---------------------------------------------------------------------------

  @Test
  void theLogAndTheMetricsNeedAnAdminToken() throws Exception {
    for (String path :
        List.of(
            "/api/messages",
            "/api/messages/" + java.util.UUID.randomUUID(),
            "/actuator/prometheus")) {
      assertThat(get(path, null).statusCode()).as(path + " without token").isEqualTo(401);
      assertThat(get(path, "abc.def.ghi").statusCode()).as(path + " bad token").isEqualTo(401);
      assertThat(get(path, validToken()).statusCode())
          .as(path + " token without the permission")
          .isEqualTo(403);
      assertThat(
              get(
                      path,
                      sign(
                          validClaims()
                              .claim("permission", List.of("integrationhub:payloads.view"))
                              .build(),
                          KEY))
                  .statusCode())
          .as(path + " other permission")
          .isEqualTo(403);
    }
    var ok = get("/actuator/prometheus", adminToken());
    assertThat(ok.statusCode()).isEqualTo(200);
  }

  @Test
  void aTokenWithOnePermissionAsAStringIsAccepted() throws Exception {
    String token = sign(validClaims().claim("permission", PERMISSION).build(), KEY);
    assertThat(get("/api/messages", token).statusCode()).isEqualTo(200);
  }

  // ---- search ---------------------------------------------------------------------------------

  @Test
  void findsMessagesNewestFirstWithoutAnyBody() throws Exception {
    seed();

    JsonNode all = search("");

    assertThat(all.get("items")).hasSize(4);
    assertThat(all.get("limit").asInt()).isEqualTo(50);
    assertThat(all.get("items").findValuesAsText("route"))
        .containsExactly("nobody", "clinichub", "labflow", "clinichub");
    JsonNode first = all.get("items").get(0);
    assertThat(first.fieldNames())
        .toIterable()
        .noneMatch(n -> n.toLowerCase().contains("body") || n.toLowerCase().contains("payload"));
    JsonNode order = all.get("items").get(2);
    assertThat(order.get("requestSha256").asText()).hasSize(64);
    assertThat(order.get("senderClientId").asText()).isEqualTo("clinichub");
    assertThat(order.get("resourceSummary").get("resourceTypes").get("ServiceRequest").asInt())
        .isEqualTo(2);
    assertThat(order.get("identifiers")).hasSize(2);
    assertThat(order.get("responseStatus").asInt()).isEqualTo(200);
    assertThat(all.toString()).doesNotContain("Zzyzx").doesNotContain("MARKER-");
  }

  @Test
  void filtersByRouteClinicAndStatus() throws Exception {
    seed();

    assertThat(search("?route=clinichub").get("items")).hasSize(2);
    assertThat(search("?route=labflow").get("items")).hasSize(1);
    assertThat(search("?clinic=harbourview").get("items")).hasSize(2);
    assertThat(search("?status=401").get("items")).hasSize(1);
    assertThat(search("?status=404").get("items")).hasSize(1);
    assertThat(search("?status=200").get("items")).hasSize(2);
    assertThat(search("?route=clinichub&status=200").get("items")).hasSize(1);
    assertThat(search("?route=none").get("items")).isEmpty();
  }

  @Test
  void filtersByIdentifierWithOrWithoutItsSystem() throws Exception {
    seed();

    assertThat(search("?identifier=42").get("items")).hasSize(1);
    assertThat(search("?identifier=101").get("items")).hasSize(1);
    assertThat(search("?identifier=urn:clinichub:harbourview:service-request%7C101").get("items"))
        .hasSize(1);
    assertThat(search("?identifier=urn:clinichub:harbourview:service-request%7C42").get("items"))
        .hasSize(1);
    assertThat(search("?identifier=urn:clinichub:lakeshore:service-request%7C101").get("items"))
        .isEmpty();
    assertThat(search("?identifier=9999").get("items")).isEmpty();
  }

  @Test
  void filtersByTimeAndPages() throws Exception {
    seed();
    Instant now = Instant.now();

    assertThat(search("?from=" + now.plusSeconds(3600)).get("items")).isEmpty();
    assertThat(search("?to=" + now.plusSeconds(3600)).get("items")).hasSize(4);
    assertThat(search("?to=" + now.minusSeconds(3600)).get("items")).isEmpty();
    assertThat(
            search("?from=" + now.minusSeconds(3600) + "&to=" + now.plusSeconds(3600)).get("items"))
        .hasSize(4);

    JsonNode page1 = search("?limit=3");
    JsonNode page2 = search("?limit=3&offset=3");
    assertThat(page1.get("items")).hasSize(3);
    assertThat(page2.get("items")).hasSize(1);
    assertThat(page2.get("offset").asInt()).isEqualTo(3);
    assertThat(page1.get("items").findValuesAsText("id"))
        .doesNotContainAnyElementsOf(page2.get("items").findValuesAsText("id"));
    assertThat(search("?limit=1000").get("limit").asInt()).isEqualTo(200);
  }

  @Test
  void badParametersAre400AndOneMessageCanBeRead() throws Exception {
    seed();
    String token = adminToken();

    assertThat(get("/api/messages?limit=0", token).statusCode()).isEqualTo(400);
    assertThat(get("/api/messages?offset=-1", token).statusCode()).isEqualTo(400);
    assertThat(get("/api/messages?from=yesterday", token).statusCode()).isEqualTo(400);
    assertThat(get("/api/messages?status=abc", token).statusCode()).isEqualTo(400);
    assertThat(get("/api/messages/not-a-uuid", token).statusCode()).isEqualTo(400);
    assertThat(get("/api/messages/" + java.util.UUID.randomUUID(), token).statusCode())
        .isEqualTo(404);

    String id = search("?route=labflow").get("items").get(0).get("id").asText();
    var one = get("/api/messages/" + id, token);
    assertThat(one.statusCode()).isEqualTo(200);
    assertThat(json.readTree(one.body()).get("identifiers")).hasSize(2);
  }

  @Test
  void searchTextIsNeverPlacedInSql() throws Exception {
    seed();

    assertThat(search("?route=x'%20or%20'1'='1").get("items")).isEmpty();
    assertThat(search("?identifier=x'%20or%20'1'='1").get("items")).isEmpty();
    assertThat(jdbc.queryForObject("select count(*) from message", Integer.class)).isEqualTo(4);
  }

  // ---- metrics --------------------------------------------------------------------------------

  @Test
  void metricsCountMessagesByRouteAndStatusAndTimeThem() throws Exception {
    seed();
    CLINICHUB.stubFor(post("/fhir").willReturn(aResponse().withStatus(503).withBody("{}")));
    postBundle("clinichub", validToken(), read("lab-result-bundle.json"));
    postBundle("dead", validToken(), read("lab-result-bundle.json"));
    CLINICHUB.stubFor(post("/fhir").willReturn(aResponse().withStatus(200).withFixedDelay(2000)));
    postBundle("slow", validToken(), read("lab-result-bundle.json"));

    String text = get("/actuator/prometheus", adminToken()).body();

    assertThat(text)
        .containsPattern(
            "hub_messages_total\\{[^}]*route=\"clinichub\"[^}]*status=\"200\"[^}]*\\} \\d")
        .containsPattern("hub_messages_total\\{[^}]*route=\"clinichub\"[^}]*status=\"401\"")
        .containsPattern("hub_messages_total\\{[^}]*route=\"labflow\"[^}]*status=\"200\"")
        .containsPattern("hub_messages_total\\{[^}]*route=\"unknown\"[^}]*status=\"404\"")
        .containsPattern("hub_message_latency_seconds_bucket\\{[^}]*route=\"clinichub\"")
        .containsPattern("hub_message_latency_seconds_count\\{[^}]*route=\"clinichub\"")
        .containsPattern(
            "hub_upstream_errors_total\\{[^}]*kind=\"http-5xx\"[^}]*route=\"clinichub\"")
        .containsPattern("hub_upstream_errors_total\\{[^}]*kind=\"unreachable\"[^}]*route=\"dead\"")
        .containsPattern("hub_upstream_errors_total\\{[^}]*kind=\"timeout\"[^}]*route=\"slow\"")
        .containsPattern("hub_events_outbox_pending ");
    // A route name a caller made up is never a label.
    assertThat(text).doesNotContain("route=\"nobody\"");
  }
}
