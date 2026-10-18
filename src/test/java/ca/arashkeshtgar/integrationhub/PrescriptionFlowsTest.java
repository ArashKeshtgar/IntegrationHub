package ca.arashkeshtgar.integrationhub;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.binaryEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching;
import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * Phase 5 flows of docs/contracts.md through the hub: electronic prescriptions to PharmaFlow (and
 * their cancel), patient merges, and confidentiality labels. Synthetic samples with markers that
 * must never reach the log.
 */
@ExtendWith(OutputCaptureExtension.class)
class PrescriptionFlowsTest extends RelayTestBase {

  static final byte[] PRESCRIPTION = ContractFlowsTest.read("prescription-bundle.json");
  static final byte[] PRESCRIPTION_RESPONSE = ContractFlowsTest.read("prescription-response.json");
  static final byte[] CANCEL = ContractFlowsTest.read("prescription-cancel-bundle.json");
  static final byte[] MERGE = ContractFlowsTest.read("patient-merge-bundle.json");
  static final String RX_ID = "urn:clinichub:harbourview:prescription";

  @Autowired MeterRegistry meters;

  /** A token ClinicHub signs for a prescription: audience pharmaflow, scope prescriptions. */
  static String prescriptionToken() {
    return sign(
        validClaims()
            .audience("pharmaflow")
            .claim("client_id", "clinichub")
            .subject("clinichub")
            .claim("scope", "prescriptions")
            .claim("clinic_slug", "harbourview")
            .build(),
        KEY);
  }

  private String everythingLogged() {
    return String.join(
            "\n", jdbc.queryForList("select row_to_json(m)::text from message m", String.class))
        + String.join(
            "\n",
            jdbc.queryForList(
                "select row_to_json(i)::text from message_identifier i", String.class));
  }

  // ---- prescriptions --------------------------------------------------------------------------

  @Test
  void aPrescriptionReachesPharmaFlowAndTheTransactionResponseComesBack() throws Exception {
    PHARMAFLOW.stubFor(
        post("/fhir")
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/fhir+json")
                    .withBody(PRESCRIPTION_RESPONSE)));
    String token = prescriptionToken();

    var response = postBundle("pharmaflow", token, PRESCRIPTION);

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body()).isEqualTo(PRESCRIPTION_RESPONSE);
    PHARMAFLOW.verify(
        postRequestedFor(urlEqualTo("/fhir"))
            .withRequestBody(binaryEqualTo(PRESCRIPTION))
            .withHeader("Authorization", equalTo("Bearer " + token)));
    assertThat(messages())
        .singleElement()
        .satisfies(
            m -> {
              assertThat(m.get("route")).isEqualTo("pharmaflow");
              assertThat(m.get("sender_client_id")).isEqualTo("clinichub");
              assertThat(m.get("clinic_slug")).isEqualTo("harbourview");
              assertThat(m.get("bundle_id")).isEqualTo("prescription-bundle-1");
              assertThat(m.get("upstream_status")).isEqualTo(200);
              assertThat(m.get("resource_summary").toString())
                  .contains("\"MedicationRequest\": 1")
                  .contains("\"Patient\": 1")
                  .contains("\"Practitioner\": 1")
                  .contains("\"prescriptionStatuses\": [\"active\"]");
            });
    assertThat(
            jdbc.queryForList(
                "select system || ' ' || value from message_identifier", String.class))
        .containsExactly(RX_ID + " 501");
  }

  @Test
  void theLogHoldsNothingOfTheDrugTheDirectionsTheDoctorOrThePatient(CapturedOutput output)
      throws Exception {
    PHARMAFLOW.stubFor(
        post("/fhir").willReturn(aResponse().withStatus(200).withBody(PRESCRIPTION_RESPONSE)));
    String token = prescriptionToken();

    postBundle("pharmaflow", token, PRESCRIPTION);
    postBundle("pharmaflow", token, CANCEL);

    String logged = everythingLogged();
    for (String marker :
        new String[] {
          "Zzyzx",
          "Testpatient",
          "MARKER-",
          "DIN-",
          "1900-01-01",
          "HC-MARKER",
          "CPSO",
          "Synthetic",
          "ca-hc-din"
        }) {
      assertThat(logged).as(marker).doesNotContain(marker);
      assertThat(output.getAll()).as(marker).doesNotContain(marker);
    }
  }

  @Test
  void aCancelBundleIsRelayedAsSentAndARefusalComesBackAs409() throws Exception {
    byte[] refused =
        ("{\"resourceType\":\"OperationOutcome\","
                + "\"issue\":[{\"severity\":\"error\",\"code\":\"conflict\"}]}")
            .getBytes(StandardCharsets.UTF_8);
    PHARMAFLOW.stubFor(post("/fhir").willReturn(aResponse().withStatus(409).withBody(refused)));

    var response = postBundle("pharmaflow", prescriptionToken(), CANCEL);

    assertThat(response.statusCode()).isEqualTo(409);
    assertThat(response.body()).isEqualTo(refused);
    PHARMAFLOW.verify(postRequestedFor(urlEqualTo("/fhir")).withRequestBody(binaryEqualTo(CANCEL)));
    assertThat(messages())
        .singleElement()
        .satisfies(
            m -> {
              assertThat(m.get("upstream_status")).isEqualTo(409);
              assertThat(m.get("resource_summary").toString())
                  .contains("\"prescriptionStatuses\": [\"cancelled\"]");
            });
  }

  @Test
  void anOrdersTokenOrAnotherAppsTokenCannotSendPrescriptions() throws Exception {
    PHARMAFLOW.stubFor(post(urlMatching(".*")).willReturn(aResponse().withStatus(200)));
    String[][] cases = {
      // Right scope, another app's audience.
      {
        sign(validClaims().audience("labflow").claim("scope", "prescriptions").build(), KEY),
        "wrong-audience"
      },
      // The scope is checked first: an orders token or a finance token has none of ours.
      {ContractFlowsTest.ordersToken("labflow"), "wrong-scope"},
      {ContractFlowsTest.ordersToken("pharmaflow"), "wrong-scope"},
      {ContractFlowsTest.financeToken(), "wrong-scope"},
    };
    for (String[] c : cases) {
      jdbc.update("delete from message");
      var response = postBundle("pharmaflow", c[0], PRESCRIPTION);
      assertThat(response.statusCode()).as(c[1]).isEqualTo(401);
      assertThat(messages())
          .singleElement()
          .satisfies(m -> assertThat(m.get("error").toString()).contains(c[1]));
    }
    PHARMAFLOW.verify(0, anyRequestedFor(urlMatching(".*")));
  }

  @Test
  void aPrescriptionRouteRelaysOnlyAPost() throws Exception {
    PHARMAFLOW.stubFor(post(urlMatching(".*")).willReturn(aResponse().withStatus(200)));

    var request =
        java.net.http.HttpRequest.newBuilder(
                java.net.URI.create(
                    "http://localhost:" + port + "/route/pharmaflow/fhir/MedicationRequest"))
            .header("Authorization", "Bearer " + prescriptionToken())
            .header("Content-Type", "application/fhir+json")
            .PUT(java.net.http.HttpRequest.BodyPublishers.ofByteArray(CANCEL))
            .build();
    var response = HTTP.send(request, java.net.http.HttpResponse.BodyHandlers.ofByteArray());

    assertThat(response.statusCode()).isGreaterThanOrEqualTo(400);
    PHARMAFLOW.verify(0, anyRequestedFor(urlMatching(".*")));
  }

  // ---- patient merges -------------------------------------------------------------------------

  @Test
  void aMergeBundleIsRelayedAsSentToWhoeverGotTheOrders() throws Exception {
    for (String app : List.of("labflow", "clinicreport")) {
      var server = app.equals("labflow") ? LABFLOW : CLINICREPORT;
      server.stubFor(post("/fhir").willReturn(aResponse().withStatus(200).withBody("{}")));

      var response = postBundle(app, ContractFlowsTest.ordersToken(app), MERGE);

      assertThat(response.statusCode()).as(app).isEqualTo(200);
      server.verify(postRequestedFor(urlEqualTo("/fhir")).withRequestBody(binaryEqualTo(MERGE)));
    }
    assertThat(messages()).hasSize(2);
    assertThat(messages().get(0).get("resource_summary").toString()).contains("\"Patient\": 1");
    // Neither MRN is in the log: it names the patient, not an order.
    assertThat(everythingLogged()).doesNotContain("MRN-MARKER");
  }

  // ---- confidentiality labels -----------------------------------------------------------------

  /** The sample order with a confidentiality label on its first ServiceRequest. */
  static byte[] labelled(String code) {
    String label =
        "\"meta\": {\"security\": [{\"system\":"
            + " \"http://terminology.hl7.org/CodeSystem/v3-Confidentiality\", \"code\": \""
            + code
            + "\"}]},";
    String order = new String(ContractFlowsTest.ORDER, StandardCharsets.UTF_8);
    String marker = "\"resourceType\": \"ServiceRequest\",";
    assertThat(order).contains(marker);
    return order
        .replaceFirst(java.util.regex.Pattern.quote(marker), marker + " " + label)
        .getBytes(StandardCharsets.UTF_8);
  }

  @Test
  void aRestrictedOrderPassesUnchangedAndIsCountedByItsLabel() throws Exception {
    LABFLOW.stubFor(
        post("/fhir")
            .willReturn(aResponse().withStatus(200).withBody(ContractFlowsTest.ORDER_RESPONSE)));
    byte[] order = labelled("R");
    double before = count("labflow", "R");

    var response = postBundle("labflow", ContractFlowsTest.ordersToken("labflow"), order);

    assertThat(response.statusCode()).isEqualTo(200);
    LABFLOW.verify(postRequestedFor(urlEqualTo("/fhir")).withRequestBody(binaryEqualTo(order)));
    assertThat(messages().get(0).get("resource_summary").toString())
        .contains("\"securityLabels\": [\"R\"]");
    assertThat(count("labflow", "R")).isEqualTo(before + 1);
  }

  @Test
  void onlyAShortCodeIsKeptAsTheLabel() throws Exception {
    LABFLOW.stubFor(post("/fhir").willReturn(aResponse().withStatus(200).withBody("{}")));

    postBundle(
        "labflow", ContractFlowsTest.ordersToken("labflow"), labelled("MARKER-free-text-label"));

    assertThat(messages().get(0).get("resource_summary").toString())
        .contains("\"securityLabels\": [\"other\"]");
    assertThat(everythingLogged()).doesNotContain("MARKER-free");
  }

  @Test
  void anUnlabelledOrderIsNotCounted() throws Exception {
    LABFLOW.stubFor(post("/fhir").willReturn(aResponse().withStatus(200).withBody("{}")));
    double before = count("labflow", "R");

    postBundle("labflow", ContractFlowsTest.ordersToken("labflow"), ContractFlowsTest.ORDER);

    assertThat(messages().get(0).get("resource_summary").toString())
        .doesNotContain("securityLabels");
    assertThat(count("labflow", "R")).isEqualTo(before);
  }

  private double count(String route, String code) {
    var counter = meters.find("hub.consent.labelled").tags("route", route, "code", code).counter();
    return counter == null ? 0 : counter.count();
  }
}
