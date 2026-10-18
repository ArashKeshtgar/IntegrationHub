package ca.arashkeshtgar.integrationhub;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.binaryEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching;
import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jwt.JWTClaimsSet;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Date;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class RelayTest extends RelayTestBase {

  static final byte[] BUNDLE = read("contracts/lab-result-bundle.json");
  static final String OUTCOME_409 =
      "{\"resourceType\":\"OperationOutcome\",\"issue\":[{\"severity\":\"error\",\"code\":\"conflict\"}]}";

  static byte[] read(String resource) {
    try {
      return Files.readAllBytes(Path.of("src/test/resources", resource));
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  // ---- pass-through -------------------------------------------------------------------------

  @Test
  void forwardsBodyAndHeadersAndReturnsTheAnswerUnchanged() throws Exception {
    byte[] bundle =
        "{\"resourceType\":\"Bundle\",\"type\":\"transaction\",\"note\":\"é ü\"}"
            .getBytes(StandardCharsets.UTF_8);
    CLINICHUB.stubFor(
        post("/fhir")
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/fhir+json; charset=utf-8")
                    .withHeader("ETag", "W/\"7\"")
                    .withBody("{\"resourceType\":\"Bundle\",\"type\":\"transaction-response\"}")));
    String token = validToken();

    var response = postBundle("clinichub", token, bundle);

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(new String(response.body(), StandardCharsets.UTF_8))
        .isEqualTo("{\"resourceType\":\"Bundle\",\"type\":\"transaction-response\"}");
    // Tomcat writes "type;charset=x" without the space: same media type, same charset.
    assertThat(response.headers().firstValue("Content-Type").orElse("").replace(" ", ""))
        .isEqualTo("application/fhir+json;charset=utf-8");
    assertThat(response.headers().firstValue("ETag")).contains("W/\"7\"");
    CLINICHUB.verify(
        postRequestedFor(urlEqualTo("/fhir"))
            .withRequestBody(binaryEqualTo(bundle))
            .withHeader("Authorization", equalTo("Bearer " + token))
            .withHeader("Content-Type", equalTo("application/fhir+json"))
            .withHeader("X-Forwarded-Proto", equalTo("http"))
            .withHeader("X-Forwarded-For", matching(".+"))
            .withHeader("X-Forwarded-Host", matching("localhost:\\d+"))
            .withHeader("X-Request-Id", matching(".+")));
  }

  @Test
  void keepsTheSendersRequestIdAndSubPath() throws Exception {
    CLINICHUB.stubFor(post("/fhir/DiagnosticReport").willReturn(aResponse().withStatus(201)));
    var request =
        java.net.http.HttpRequest.newBuilder(
                java.net.URI.create(
                    "http://localhost:" + port + "/route/clinichub/fhir/DiagnosticReport"))
            .header("Authorization", "Bearer " + validToken())
            .header("X-Request-Id", "from-labflow-1")
            .POST(java.net.http.HttpRequest.BodyPublishers.ofString("{}"))
            .build();

    var response = HTTP.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(201);
    CLINICHUB.verify(
        postRequestedFor(urlEqualTo("/fhir/DiagnosticReport"))
            .withHeader("X-Request-Id", equalTo("from-labflow-1")));
  }

  static Stream<Arguments> upstreamAnswers() {
    return Stream.of(
        Arguments.of(200, "{\"resourceType\":\"Bundle\"}"),
        Arguments.of(
            400,
            "{\"resourceType\":\"OperationOutcome\",\"issue\":[{\"severity\":\"error\",\"code\":\"invalid\"}]}"),
        Arguments.of(409, OUTCOME_409),
        Arguments.of(422, "{\"resourceType\":\"OperationOutcome\"}"),
        Arguments.of(500, "{\"resourceType\":\"OperationOutcome\",\"issue\":[]}"),
        Arguments.of(503, "plain text, not json"));
  }

  @ParameterizedTest
  @MethodSource("upstreamAnswers")
  void passesEveryUpstreamStatusAndBodyThrough(int status, String body) throws Exception {
    CLINICHUB.stubFor(
        post("/fhir")
            .willReturn(
                aResponse()
                    .withStatus(status)
                    .withHeader("Content-Type", "application/fhir+json")
                    .withBody(body)));

    var response = postBundle("clinichub", validToken(), BUNDLE);

    assertThat(response.statusCode()).isEqualTo(status);
    assertThat(new String(response.body(), StandardCharsets.UTF_8)).isEqualTo(body);
    assertThat(messages())
        .singleElement()
        .satisfies(
            m -> {
              assertThat(m.get("upstream_status")).isEqualTo(status);
              assertThat(m.get("response_status")).isEqualTo(status);
            });
  }

  @Test
  void malformedJsonIsForwardedAndLoggedAsUnparsed() throws Exception {
    CLINICHUB.stubFor(post("/fhir").willReturn(aResponse().withStatus(400).withBody("bad")));
    byte[] garbage = "{ this is not json".getBytes(StandardCharsets.UTF_8);

    var response = postBundle("clinichub", validToken(), garbage);

    assertThat(response.statusCode()).isEqualTo(400);
    CLINICHUB.verify(postRequestedFor(urlEqualTo("/fhir")).withRequestBody(binaryEqualTo(garbage)));
    assertThat(messages())
        .singleElement()
        .satisfies(
            m -> {
              assertThat(m.get("resource_summary").toString()).contains("\"parsed\": false");
              assertThat(m.get("bundle_id")).isNull();
              assertThat(m.get("upstream_status")).isEqualTo(400);
            });
  }

  // ---- upstream trouble -----------------------------------------------------------------------

  @Test
  void answers502WhenTheUpstreamRefusesTheConnection() throws Exception {
    var response = postBundle("dead", validToken(), BUNDLE);

    assertThat(response.statusCode()).isEqualTo(502);
    assertThat(messages())
        .singleElement()
        .satisfies(
            m -> {
              assertThat(m.get("response_status")).isEqualTo(502);
              assertThat(m.get("upstream_status")).isNull();
              assertThat(m.get("error").toString()).contains("upstream-unreachable");
            });
  }

  @Test
  void answers504WhenTheUpstreamIsTooSlow() throws Exception {
    CLINICHUB.stubFor(post("/fhir").willReturn(aResponse().withStatus(200).withFixedDelay(3000)));

    long started = System.nanoTime();
    var response = postBundle("slow", validToken(), BUNDLE);
    long ms = (System.nanoTime() - started) / 1_000_000;

    assertThat(response.statusCode()).isEqualTo(504);
    assertThat(ms).isLessThan(2500);
    assertThat(messages())
        .singleElement()
        .satisfies(m -> assertThat(m.get("error").toString()).contains("upstream-timeout"));
  }

  // ---- tokens ---------------------------------------------------------------------------------

  static Stream<Arguments> badTokens() {
    Instant now = Instant.now();
    return Stream.of(
        Arguments.of("no token", null, "missing-token"),
        Arguments.of("not a jwt", "abc.def.ghi", "invalid-token"),
        Arguments.of(
            "wrong issuer",
            sign(validClaims().issuer("http://evil.example/").build(), KEY),
            "invalid-token"),
        Arguments.of(
            "expired",
            sign(
                validClaims()
                    .issueTime(Date.from(now.minusSeconds(900)))
                    .expirationTime(Date.from(now.minusSeconds(600)))
                    .build(),
                KEY),
            "invalid-token"),
        Arguments.of(
            "signed with another key", sign(validClaims().build(), OTHER_KEY), "invalid-token"),
        Arguments.of(
            "wrong scope",
            sign(validClaims().claim("scope", "orders").build(), KEY),
            "wrong-scope"),
        Arguments.of(
            "no scope",
            sign(new JWTClaimsSet.Builder(validClaims().build()).claim("scope", null).build(), KEY),
            "wrong-scope"));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("badTokens")
  void refusesWith401AndForwardsNothing(String name, String token, String reason) throws Exception {
    CLINICHUB.stubFor(post("/fhir").willReturn(aResponse().withStatus(200)));

    var response = postBundle("clinichub", token, BUNDLE);

    assertThat(response.statusCode()).isEqualTo(401);
    assertThat(response.headers().firstValue("WWW-Authenticate")).contains("Bearer");
    assertThat(new String(response.body(), StandardCharsets.UTF_8)).contains("OperationOutcome");
    CLINICHUB.verify(0, postRequestedFor(urlEqualTo("/fhir")));
    assertThat(messages())
        .singleElement()
        .satisfies(
            m -> {
              assertThat(m.get("response_status")).isEqualTo(401);
              assertThat(m.get("upstream_status")).isNull();
              assertThat(m.get("error").toString()).contains(reason);
              assertThat(m.get("sender_client_id")).isNull();
            });
  }

  @Test
  void scopeAsAJsonArrayIsAccepted() throws Exception {
    CLINICHUB.stubFor(post("/fhir").willReturn(aResponse().withStatus(200)));
    String token =
        sign(validClaims().claim("scope", java.util.List.of("openid", "results")).build(), KEY);

    assertThat(postBundle("clinichub", token, BUNDLE).statusCode()).isEqualTo(200);
  }

  @Test
  void audienceIsCheckedOnlyWhereTheRouteListsOne() throws Exception {
    CLINICHUB.stubFor(post("/fhir").willReturn(aResponse().withStatus(200)));
    String other = sign(validClaims().audience("something-else").build(), KEY);

    assertThat(postBundle("audience", validToken(), BUNDLE).statusCode()).isEqualTo(200);
    assertThat(postBundle("audience", other, BUNDLE).statusCode()).isEqualTo(401);
    assertThat(messages().get(1).get("error").toString()).contains("wrong-audience");
    assertThat(postBundle("clinichub", other, BUNDLE).statusCode()).isEqualTo(200);
  }

  // ---- routes ---------------------------------------------------------------------------------

  @Test
  void unknownRouteIs404AndLogged() throws Exception {
    var response = postBundle("nobody", validToken(), BUNDLE);

    assertThat(response.statusCode()).isEqualTo(404);
    assertThat(messages())
        .singleElement()
        .satisfies(
            m -> {
              assertThat(m.get("route")).isEqualTo("nobody");
              assertThat(m.get("response_status")).isEqualTo(404);
            });
  }

  @Test
  void onlyPostIsRelayed() throws Exception {
    var request =
        java.net.http.HttpRequest.newBuilder(
                java.net.URI.create("http://localhost:" + port + "/route/clinichub/fhir"))
            .header("Authorization", "Bearer " + validToken())
            .GET()
            .build();

    assertThat(HTTP.send(request, java.net.http.HttpResponse.BodyHandlers.ofString()).statusCode())
        .isEqualTo(405);
    CLINICHUB.verify(0, postRequestedFor(urlEqualTo("/fhir")));
  }

  @ParameterizedTest
  @ValueSource(strings = {"/route/clinichub/other", "/route/clinichub/fhirx", "/route/clinichub"})
  void pathsOutsideFhirAreNotRelayed(String path) throws Exception {
    CLINICHUB.stubFor(post(urlMatching(".*")).willReturn(aResponse().withStatus(200)));

    var response =
        send(path, validToken(), "application/json", "{}".getBytes(StandardCharsets.UTF_8));

    assertThat(response.statusCode()).isIn(401, 404, 405);
    CLINICHUB.verify(0, postRequestedFor(urlMatching("/.*")));
  }

  @Test
  void dotSegmentsCannotEscapeTheFhirPrefix() throws Exception {
    CLINICHUB.stubFor(post(urlMatching(".*")).willReturn(aResponse().withStatus(200)));

    var response =
        send(
            "/route/clinichub/fhir/%2e%2e/admin",
            validToken(), "application/json", "{}".getBytes(StandardCharsets.UTF_8));

    assertThat(response.statusCode()).isGreaterThanOrEqualTo(400);
    CLINICHUB.verify(0, postRequestedFor(urlEqualTo("/admin")));
  }

  @Test
  void tooLargeABodyIs413() throws Exception {
    var response = postBundle("clinichub", validToken(), new byte[1_048_576 + 1]);

    assertThat(response.statusCode()).isEqualTo(413);
    CLINICHUB.verify(0, postRequestedFor(urlEqualTo("/fhir")));
  }

  // ---- the message log ------------------------------------------------------------------------

  @Test
  void logsMetadataOfARelayedBundle() throws Exception {
    CLINICHUB.stubFor(post("/fhir").willReturn(aResponse().withStatus(200).withBody("{}")));

    postBundle("clinichub", validToken(), BUNDLE);

    var m = messages().get(0);
    assertThat(m.get("route")).isEqualTo("clinichub");
    assertThat(m.get("direction")).isEqualTo("inbound");
    assertThat(m.get("sender_client_id")).isEqualTo("labflow");
    assertThat(m.get("clinic_slug")).isEqualTo("harbourview");
    assertThat(m.get("method")).isEqualTo("POST");
    assertThat(m.get("path")).isEqualTo("/fhir");
    assertThat(m.get("content_type")).isEqualTo("application/fhir+json");
    assertThat(m.get("request_size")).isEqualTo((long) BUNDLE.length);
    assertThat(m.get("request_sha256")).isEqualTo(sha256Hex(BUNDLE));
    assertThat(m.get("bundle_id")).isEqualTo("bundle-1");
    assertThat(((Number) m.get("latency_ms")).intValue()).isGreaterThanOrEqualTo(0);
    String summary = m.get("resource_summary").toString();
    assertThat(summary)
        .contains("\"DiagnosticReport\": 2")
        .contains("\"Observation\": 1")
        .contains("amended")
        .contains("final")
        .contains("transaction");
    var identifiers =
        jdbc.queryForList(
            "select system, value from message_identifier where message_id = ?", m.get("id"));
    assertThat(identifiers).extracting(r -> r.get("value")).containsExactlyInAnyOrder("42", "43");
    assertThat(identifiers)
        .allSatisfy(
            r ->
                assertThat(r.get("system")).isEqualTo("urn:clinichub:harbourview:service-request"));
  }

  @Test
  void logsNoBodyAndNoPersonalValuesAnywhere(CapturedOutput output) throws Exception {
    CLINICHUB.stubFor(post("/fhir").willReturn(aResponse().withStatus(200).withBody("{}")));
    String badDate =
        "{\"resourceType\":\"Patient\",\"birthDate\":\"MARKER-BAD-DATE\",\"name\":[{\"family\":\"Zzyzx\"}]}";

    postBundle("clinichub", validToken(), BUNDLE);
    postBundle("clinichub", validToken(), badDate.getBytes(StandardCharsets.UTF_8));
    postBundle("clinichub", "x.y.z", BUNDLE);

    String everything =
        String.join(
                "\n", jdbc.queryForList("select row_to_json(m)::text from message m", String.class))
            + String.join(
                "\n",
                jdbc.queryForList(
                    "select row_to_json(i)::text from message_identifier i", String.class));
    for (String marker : new String[] {"Zzyzx", "Testpatient", "MARKER-"}) {
      assertThat(everything).doesNotContain(marker);
      assertThat(output.getAll()).doesNotContain(marker);
    }
    assertThat(messages()).hasSize(3);
  }

  private static String sha256Hex(byte[] bytes) throws Exception {
    return java.util.HexFormat.of()
        .formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
  }
}
