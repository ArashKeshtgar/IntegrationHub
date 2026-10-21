package ca.arashkeshtgar.integrationhub;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.binaryEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.putRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * The flows of docs/contracts.md replayed through the hub with synthetic samples from
 * src/test/resources/contracts/: orders, cancels (accepted and refused with 409) and financial
 * documents.
 */
@ExtendWith(OutputCaptureExtension.class)
class ContractFlowsTest extends RelayTestBase {

  static final byte[] ORDER = read("order-bundle.json");
  static final byte[] ORDER_RESPONSE = read("order-response.json");
  static final byte[] CANCEL = read("cancel-service-request.json");
  static final byte[] CANCEL_REFUSED = read("cancel-refused-409.json");
  static final byte[] DOCUMENTS = read("finance-documents.json");
  static final String ORDER_ID = "urn:clinichub:harbourview:service-request|101";

  static byte[] read(String name) {
    try {
      return Files.readAllBytes(Path.of("src/test/resources/contracts", name));
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  /** A token ClinicHub signs for an order: audience = the app, scope orders, the clinic. */
  static String ordersToken(String app) {
    return sign(
        validClaims()
            .audience(app)
            .claim("client_id", "clinichub")
            .subject("clinichub")
            .claim("scope", "orders")
            .claim("clinic_slug", "harbourview")
            .build(),
        KEY);
  }

  static String financeToken() {
    return sign(
        validClaims()
            .audience("finance")
            .claim("client_id", "clinichub")
            .claim("scope", "finance")
            .build(),
        KEY);
  }

  WireMockServer upstreamOf(String app) {
    return switch (app) {
      case "labflow" -> LABFLOW;
      case "clinicreport" -> CLINICREPORT;
      default -> throw new IllegalArgumentException(app);
    };
  }

  HttpResponse<byte[]> call(
      String method, String path, String bearer, String contentType, byte[] body)
      throws IOException, InterruptedException {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .method(
                method,
                body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofByteArray(body));
    if (bearer != null) {
      request.header("Authorization", "Bearer " + bearer);
    }
    if (contentType != null) {
      request.header("Content-Type", contentType);
    }
    return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
  }

  private String everythingLogged() {
    return String.join(
            "\n", jdbc.queryForList("select row_to_json(m)::text from message m", String.class))
        + String.join(
            "\n",
            jdbc.queryForList(
                "select row_to_json(i)::text from message_identifier i", String.class));
  }

  // ---- orders ---------------------------------------------------------------------------------

  @ParameterizedTest
  @ValueSource(strings = {"labflow", "clinicreport"})
  void anOrderReachesTheAppAndTheTransactionResponseComesBack(String app) throws Exception {
    WireMockServer upstream = upstreamOf(app);
    upstream.stubFor(
        post("/fhir")
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/fhir+json")
                    .withBody(ORDER_RESPONSE)));
    String token = ordersToken(app);

    var response = call("POST", "/route/" + app + "/fhir", token, "application/fhir+json", ORDER);

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body()).isEqualTo(ORDER_RESPONSE);
    upstream.verify(
        postRequestedFor(urlEqualTo("/fhir"))
            .withRequestBody(binaryEqualTo(ORDER))
            .withHeader("Authorization", equalTo("Bearer " + token)));
    assertThat(messages())
        .singleElement()
        .satisfies(
            m -> {
              assertThat(m.get("route")).isEqualTo(app);
              assertThat(m.get("sender_client_id")).isEqualTo("clinichub");
              assertThat(m.get("clinic_slug")).isEqualTo("harbourview");
              assertThat(m.get("bundle_id")).isEqualTo("order-bundle-1");
              assertThat(m.get("upstream_status")).isEqualTo(200);
              assertThat(m.get("resource_summary").toString())
                  .contains("\"Patient\": 1")
                  .contains("\"Practitioner\": 1")
                  .contains("\"ServiceRequest\": 2")
                  .contains("active");
            });
    assertThat(
            jdbc.queryForList("select value from message_identifier order by value", String.class))
        .containsExactly("101", "102");
  }

  @Test
  void theLogHoldsNothingOfThePatientTheDoctorOrTheNote(CapturedOutput output) throws Exception {
    LABFLOW.stubFor(post("/fhir").willReturn(aResponse().withStatus(200).withBody(ORDER_RESPONSE)));

    call("POST", "/route/labflow/fhir", ordersToken("labflow"), "application/fhir+json", ORDER);
    call(
        "PUT",
        "/route/labflow/fhir/ServiceRequest?identifier=x%7C1",
        ordersToken("labflow"),
        "application/fhir+json",
        CANCEL);

    String logged = everythingLogged();
    for (String marker :
        new String[] {
          "Zzyzx", "Testpatient", "MARKER-", "1900-01-01", "HC-MARKER", "CPSO", "Synthetic"
        }) {
      assertThat(logged).as(marker).doesNotContain(marker);
      assertThat(output.getAll()).as(marker).doesNotContain(marker);
    }
  }

  static Stream<Arguments> tokensForTheWrongPlace() {
    return Stream.of(
        Arguments.of(
            "labflow token on clinicreport",
            "/route/clinicreport/fhir",
            ordersToken("labflow"),
            "wrong-audience"),
        Arguments.of(
            "clinicreport token on labflow",
            "/route/labflow/fhir",
            ordersToken("clinicreport"),
            "wrong-audience"),
        Arguments.of(
            "orders token on finance",
            "/route/finance/api/integration/documents",
            ordersToken("finance"),
            "wrong-scope"),
        Arguments.of(
            "finance token on labflow", "/route/labflow/fhir", financeToken(), "wrong-scope"),
        Arguments.of(
            "right audience, results scope",
            "/route/labflow/fhir",
            sign(validClaims().audience("labflow").claim("scope", "results").build(), KEY),
            "wrong-scope"));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("tokensForTheWrongPlace")
  void aTokenForAnotherAppOrScopeIsRefusedAndNothingIsForwarded(
      String name, String path, String token, String reason) throws Exception {
    for (WireMockServer s : List.of(LABFLOW, CLINICREPORT, FINANCE)) {
      s.stubFor(post(urlMatching(".*")).willReturn(aResponse().withStatus(200)));
    }

    var response =
        call("POST", path, token, "application/json", "[]".getBytes(StandardCharsets.UTF_8));

    assertThat(response.statusCode()).isEqualTo(401);
    for (WireMockServer s : List.of(LABFLOW, CLINICREPORT, FINANCE)) {
      s.verify(0, anyRequestedFor(urlMatching(".*")));
    }
    assertThat(messages())
        .singleElement()
        .satisfies(m -> assertThat(m.get("error").toString()).contains(reason));
  }

  // ---- cancels --------------------------------------------------------------------------------

  static Stream<Arguments> cancelQueries() {
    // What the sender writes and what the app must receive.
    return Stream.of(
        Arguments.of(
            "encoded pipe",
            "identifier=urn:clinichub:harbourview:service-request%7C101",
            "identifier=urn:clinichub:harbourview:service-request%7C101"),
        Arguments.of(
            "fully escaped",
            "identifier=urn%3Aclinichub%3Aharbourview%3Aservice-request%7C101",
            "identifier=urn%3Aclinichub%3Aharbourview%3Aservice-request%7C101"),
        Arguments.of(
            "more parameters",
            "identifier=urn%3Aclinichub%3Aharbourview%3Aservice-request%7C101&_format=json&x=%20a%2Fb",
            "identifier=urn%3Aclinichub%3Aharbourview%3Aservice-request%7C101&_format=json&x=%20a%2Fb"));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("cancelQueries")
  void aCancelKeepsItsQueryStringAsSent(String name, String query, String expectedUpstream)
      throws Exception {
    CLINICREPORT.stubFor(
        put(urlMatching("/fhir/ServiceRequest.*"))
            .willReturn(aResponse().withStatus(200).withBody("{}")));
    String token = ordersToken("clinicreport");

    var response =
        call(
            "PUT",
            "/route/clinicreport/fhir/ServiceRequest?" + query,
            token,
            "application/fhir+json",
            CANCEL);

    assertThat(response.statusCode()).isEqualTo(200);
    CLINICREPORT.verify(
        putRequestedFor(urlEqualTo("/fhir/ServiceRequest?" + expectedUpstream))
            .withRequestBody(binaryEqualTo(CANCEL))
            .withHeader("Authorization", equalTo("Bearer " + token)));
    assertThat(messages())
        .singleElement()
        .satisfies(
            m -> {
              assertThat(m.get("method")).isEqualTo("PUT");
              assertThat(m.get("path")).isEqualTo("/fhir/ServiceRequest");
              assertThat(m.get("clinic_slug")).isEqualTo("harbourview");
              assertThat(m.get("resource_summary").toString()).contains("revoked");
            });
    assertThat(
            jdbc.queryForList(
                "select system || '#' || value from message_identifier", String.class))
        .containsExactly("urn:clinichub:harbourview:service-request#101");
  }

  @Test
  void aRawPipeInTheQueryIsAcceptedAndSentAsPercentSevenC() throws Exception {
    LABFLOW.stubFor(
        put(urlMatching("/fhir/ServiceRequest.*"))
            .willReturn(aResponse().withStatus(200).withBody("{}")));
    String token = ordersToken("labflow");

    String status =
        rawPut(
            "/route/labflow/fhir/ServiceRequest?identifier=urn:clinichub:harbourview:service-request|101",
            token,
            CANCEL);

    assertThat(status).isEqualTo("HTTP/1.1 200");
    LABFLOW.verify(
        putRequestedFor(
                urlEqualTo(
                    "/fhir/ServiceRequest?identifier=urn:clinichub:harbourview:service-request%7C101"))
            .withRequestBody(binaryEqualTo(CANCEL)));
    assertThat(jdbc.queryForList("select value from message_identifier", String.class))
        .containsExactly("101");
  }

  @Test
  void aRefusedCancelIsTheLabsAnswerUnchanged() throws Exception {
    LABFLOW.stubFor(
        put(urlMatching("/fhir/ServiceRequest.*"))
            .willReturn(
                aResponse()
                    .withStatus(409)
                    .withHeader("Content-Type", "application/fhir+json")
                    .withBody(CANCEL_REFUSED)));

    var response =
        call(
            "PUT",
            "/route/labflow/fhir/ServiceRequest?identifier=urn:clinichub:harbourview:service-request%7C101",
            ordersToken("labflow"),
            "application/fhir+json",
            CANCEL);

    assertThat(response.statusCode()).isEqualTo(409);
    assertThat(response.body()).isEqualTo(CANCEL_REFUSED);
    assertThat(messages())
        .singleElement()
        .satisfies(
            m -> {
              assertThat(m.get("upstream_status")).isEqualTo(409);
              assertThat(m.get("response_status")).isEqualTo(409);
              assertThat(m.get("error")).isNull();
            });
    // The hub does not retry a 409: the lab was asked once.
    LABFLOW.verify(1, putRequestedFor(urlMatching("/fhir/ServiceRequest.*")));
  }

  // ---- methods and paths ----------------------------------------------------------------------

  static Stream<Arguments> notRelayed() {
    return Stream.of(
        Arguments.of("PUT", "/route/clinichub/fhir/ServiceRequest", 405),
        Arguments.of("DELETE", "/route/labflow/fhir/ServiceRequest", 405),
        Arguments.of("GET", "/route/labflow/fhir/ServiceRequest", 405),
        Arguments.of("PUT", "/route/finance/api/integration/documents", 405),
        Arguments.of("GET", "/route/finance/api/integration/documents", 405),
        Arguments.of("POST", "/route/labflow/api/anything", 404),
        Arguments.of("POST", "/route/finance/fhir", 404),
        Arguments.of("POST", "/route/finance/api/integration/documentsx", 404),
        Arguments.of("POST", "/route/finance/api/integration", 404));
  }

  @ParameterizedTest(name = "{0} {1}")
  @MethodSource("notRelayed")
  void onlyTheMethodsAndPathsOfARouteAreRelayed(String method, String path, int expected)
      throws Exception {
    for (WireMockServer s : List.of(CLINICHUB, LABFLOW, CLINICREPORT, FINANCE)) {
      s.stubFor(post(urlMatching("/(?!\\.well-known).*")).willReturn(aResponse().withStatus(200)));
      s.stubFor(put(urlMatching(".*")).willReturn(aResponse().withStatus(200)));
    }
    String token = path.contains("finance") ? financeToken() : ordersToken("labflow");

    var response =
        call(
            method,
            path,
            token,
            "application/json",
            method.equals("GET") ? null : "{}".getBytes(StandardCharsets.UTF_8));

    assertThat(response.statusCode()).isEqualTo(expected);
    if (expected == 405) {
      assertThat(response.headers().firstValue("Allow")).isPresent();
    }
    for (WireMockServer s : List.of(CLINICHUB, LABFLOW, CLINICREPORT, FINANCE)) {
      s.verify(0, postRequestedFor(urlMatching("/(?!\\.well-known).*")));
      s.verify(0, putRequestedFor(urlMatching(".*")));
    }
    assertThat(messages())
        .singleElement()
        .satisfies(m -> assertThat(m.get("response_status")).isEqualTo(expected));
  }

  // ---- finance --------------------------------------------------------------------------------

  @Test
  void financialDocumentsReachFinancePowerAppAndTheLogKeepsOnlyThreeFields(CapturedOutput output)
      throws Exception {
    FINANCE.stubFor(
        post("/api/integration/documents")
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"accepted\":2}")));
    String token = financeToken();

    var response =
        call(
            "POST",
            "/route/finance/api/integration/documents",
            token,
            "application/json",
            DOCUMENTS);

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(new String(response.body(), StandardCharsets.UTF_8)).isEqualTo("{\"accepted\":2}");
    FINANCE.verify(
        postRequestedFor(urlEqualTo("/api/integration/documents"))
            .withRequestBody(binaryEqualTo(DOCUMENTS))
            .withHeader("Authorization", equalTo("Bearer " + token)));
    var m = messages().get(0);
    assertThat(m.get("route")).isEqualTo("finance");
    assertThat(m.get("path")).isEqualTo("/api/integration/documents");
    assertThat(m.get("clinic_slug")).isNull();
    String summary = m.get("resource_summary").toString();
    assertThat(summary)
        .contains("\"documents\": 2")
        .contains("claim:1234:ready")
        .contains("claim:1235:reversal")
        .contains("Invoice")
        .contains("Reversal")
        .contains("harbourview");
    assertThat(
            jdbc.queryForList(
                "select system || '#' || value from message_identifier order by value",
                String.class))
        .containsExactly(
            "urn:integrationhub:finance:harbourview#claim:1234:ready",
            "urn:integrationhub:finance:harbourview#claim:1235:reversal");
    String logged = everythingLogged();
    for (String marker :
        new String[] {"AB12-001", "91.17", "12.34", "OHIP", "123456", "2026-10-05"}) {
      assertThat(logged).as(marker).doesNotContain(marker);
      assertThat(output.getAll()).as(marker).doesNotContain(marker);
    }
  }

  @Test
  void theDevelopmentKeyIsPassedThroughWithTheToken() throws Exception {
    FINANCE.stubFor(post("/api/integration/documents").willReturn(aResponse().withStatus(200)));

    call2("finance", "dev-key-123", financeToken());

    FINANCE.verify(
        postRequestedFor(urlEqualTo("/api/integration/documents"))
            .withHeader("X-Finance-Key", equalTo("dev-key-123")));
    assertThat(everythingLogged()).doesNotContain("dev-key-123");
  }

  @Test
  void financeErrorsAreTheUpstreamsAnswerUnchanged() throws Exception {
    String problem = "{\"title\":\"Unknown sourceKey\",\"status\":400}";
    FINANCE.stubFor(
        post("/api/integration/documents")
            .willReturn(
                aResponse()
                    .withStatus(400)
                    .withHeader("Content-Type", "application/problem+json")
                    .withBody(problem)));

    var response =
        call(
            "POST",
            "/route/finance/api/integration/documents",
            financeToken(),
            "application/json",
            DOCUMENTS);

    assertThat(response.statusCode()).isEqualTo(400);
    assertThat(new String(response.body(), StandardCharsets.UTF_8)).isEqualTo(problem);
    assertThat(response.headers().firstValue("Content-Type")).contains("application/problem+json");
  }

  @Test
  void aSingleDocumentAndAMalformedBodyAreBothForwarded() throws Exception {
    FINANCE.stubFor(post("/api/integration/documents").willReturn(aResponse().withStatus(200)));
    byte[] single =
        "{\"sourceKey\":\"lab\",\"externalId\":\"order:7\",\"kind\":\"Invoice\",\"lines\":[]}"
            .getBytes(StandardCharsets.UTF_8);
    byte[] broken = "[{ nope".getBytes(StandardCharsets.UTF_8);

    call(
        "POST",
        "/route/finance/api/integration/documents",
        financeToken(),
        "application/json",
        single);
    call(
        "POST",
        "/route/finance/api/integration/documents",
        financeToken(),
        "application/json",
        broken);

    FINANCE.verify(
        postRequestedFor(urlEqualTo("/api/integration/documents"))
            .withRequestBody(binaryEqualTo(single)));
    FINANCE.verify(
        postRequestedFor(urlEqualTo("/api/integration/documents"))
            .withRequestBody(binaryEqualTo(broken)));
    var logged = messages();
    assertThat(logged.get(0).get("resource_summary").toString()).contains("\"documents\": 1");
    assertThat(logged.get(1).get("resource_summary").toString()).contains("\"parsed\": false");
    assertThat(logged.get(1).get("upstream_status")).isEqualTo(200);
  }

  // ---- the development key --------------------------------------------------------------------

  @Test
  void withoutAnApiKeyHeaderConfiguredATokenIsAlwaysRequired() throws Exception {
    FINANCE.stubFor(post("/api/integration/documents").willReturn(aResponse().withStatus(200)));

    var response = call2("finance", "dev-key-123", null);

    assertThat(response.statusCode()).isEqualTo(401);
    FINANCE.verify(0, postRequestedFor(urlEqualTo("/api/integration/documents")));
  }

  @Test
  void whereConfiguredACallWithOnlyTheKeyIsLeftToTheUpstream() throws Exception {
    FINANCE.stubFor(
        post("/api/integration/documents").willReturn(aResponse().withStatus(200).withBody("{}")));

    var response = call2("keyed", "dev-key-123", null);

    assertThat(response.statusCode()).isEqualTo(200);
    FINANCE.verify(
        postRequestedFor(urlEqualTo("/api/integration/documents"))
            .withHeader("X-Finance-Key", equalTo("dev-key-123"))
            .withRequestBody(binaryEqualTo(DOCUMENTS)));
    assertThat(messages())
        .singleElement()
        .satisfies(m -> assertThat(m.get("sender_client_id")).isEqualTo("api-key"));
  }

  @Test
  void whereConfiguredNeitherTokenNorKeyIs401AndABadTokenIsNotRescuedByAKey() throws Exception {
    FINANCE.stubFor(post("/api/integration/documents").willReturn(aResponse().withStatus(200)));

    assertThat(call2("keyed", null, null).statusCode()).isEqualTo(401);
    assertThat(call2("keyed", "dev-key-123", "abc.def.ghi").statusCode()).isEqualTo(401);
    FINANCE.verify(0, postRequestedFor(urlEqualTo("/api/integration/documents")));
  }

  private HttpResponse<byte[]> call2(String route, String key, String bearer) throws Exception {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(
                URI.create(
                    "http://localhost:" + port + "/route/" + route + "/api/integration/documents"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofByteArray(DOCUMENTS));
    if (key != null) {
      request.header("X-Finance-Key", key);
    }
    if (bearer != null) {
      request.header("Authorization", "Bearer " + bearer);
    }
    return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
  }

  /** A request line with an unescaped pipe, which Java's HTTP client refuses to write. */
  private String rawPut(String target, String bearer, byte[] body) throws IOException {
    try (Socket socket = new Socket("localhost", port)) {
      var out = socket.getOutputStream();
      out.write(
          ("PUT "
                  + target
                  + " HTTP/1.1\r\nHost: localhost:"
                  + port
                  + "\r\nAuthorization: Bearer "
                  + bearer
                  + "\r\nContent-Type: application/fhir+json\r\nContent-Length: "
                  + body.length
                  + "\r\nConnection: close\r\n\r\n")
              .getBytes(StandardCharsets.US_ASCII));
      out.write(body);
      out.flush();
      ByteArrayOutputStream all = new ByteArrayOutputStream();
      socket.getInputStream().transferTo(all);
      String text = all.toString(StandardCharsets.ISO_8859_1);
      return text.substring(0, text.indexOf("\r\n"))
          .substring(0, Math.min(12, text.indexOf("\r\n")));
    }
  }
}
