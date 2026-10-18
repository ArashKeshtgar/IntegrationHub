package ca.arashkeshtgar.integrationhub;

import static ca.arashkeshtgar.integrationhub.Hl7TestSupport.ackCode;
import static ca.arashkeshtgar.integrationhub.Hl7TestSupport.ackText;
import static ca.arashkeshtgar.integrationhub.Hl7TestSupport.mllp;
import static ca.arashkeshtgar.integrationhub.Hl7TestSupport.sample;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import ca.arashkeshtgar.integrationhub.hl7.Hl7InboundWorker;
import ca.arashkeshtgar.integrationhub.hl7.Mllp;
import ca.arashkeshtgar.integrationhub.hl7.MllpServer;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * ORU^R01 over MLLP to ClinicHub, with store-and-forward. WireMock plays ClinicHub; the lab is a
 * socket.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@ExtendWith(OutputCaptureExtension.class)
class Hl7InboundTest extends RelayTestBase {

  @DynamicPropertySource
  static void hl7(DynamicPropertyRegistry registry) {
    registry.add("hub.hl7.enabled", () -> "true");
    registry.add("hub.hl7.queue-key", () -> Hl7TestSupport.QUEUE_KEY);
    registry.add("hub.hl7.mllp.port", () -> "0");
    registry.add("hub.hl7.mllp.bind-address", () -> "127.0.0.1");
    registry.add("hub.hl7.mllp.max-message-bytes", () -> "20000");
    registry.add("hub.hl7.mllp.read-timeout", () -> "3s");
    registry.add("hub.hl7.clinichub.token-url", () -> CLINICHUB.baseUrl() + "/connect/token");
    registry.add("hub.hl7.clinichub.fhir-url", () -> CLINICHUB.baseUrl() + "/fhir");
    registry.add("hub.hl7.clinichub.client-secret", () -> "test-secret");
    registry.add("hub.hl7.retry.initial-backoff", () -> "1s");
    registry.add("hub.hl7.retry.max-backoff", () -> "1s");
    registry.add("hub.hl7.retry.max-attempts", () -> "3");
    registry.add("hub.hl7.retry.interval", () -> "1h");
  }

  @Autowired MllpServer server;
  @Autowired Hl7InboundWorker worker;

  @BeforeEach
  void clinicHub() {
    jdbc.update("delete from hl7_inbound");
    CLINICHUB.stubFor(
        post("/connect/token")
            .willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "{\"access_token\":\"hub-token\",\"token_type\":\"Bearer\",\"expires_in\":3600}")));
    CLINICHUB.stubFor(post("/fhir").willReturn(aResponse().withStatus(200).withBody("{}")));
  }

  private Map<String, Object> row(String controlId) {
    return jdbc.queryForMap("select * from hl7_inbound where control_id = ?", controlId);
  }

  private void retryUntil(String controlId, String status) {
    Awaitility.await()
        .atMost(Duration.ofSeconds(20))
        .pollInterval(Duration.ofMillis(300))
        .untilAsserted(
            () -> {
              worker.runOnce();
              assertThat(row(controlId).get("status")).isEqualTo(status);
            });
  }

  @Test
  void aFinalResultIsMappedSentToClinicHubAndAcknowledgedAA(CapturedOutput output)
      throws Exception {
    String ack = mllp(server.port(), sample("oru-final.hl7", "T-OK-1"));

    assertThat(ackCode(ack)).isEqualTo("AA");
    assertThat(ack).contains("MSA|AA|T-OK-1|");
    assertThat(ack).startsWith("MSH|^~\\&|INTEGRATIONHUB|HUB|EXTLAB|LAB1|");

    List<LoggedRequest> posts = CLINICHUB.findAll(postRequestedFor(urlEqualTo("/fhir")));
    assertThat(posts).hasSize(1);
    LoggedRequest sent = posts.get(0);
    assertThat(sent.getHeader("Authorization")).isEqualTo("Bearer hub-token");
    assertThat(sent.getHeader("Content-Type")).startsWith("application/fhir+json");
    String body = sent.getBodyAsString();
    assertThat(body)
        .contains("\"resourceType\":\"Bundle\"", "\"type\":\"transaction\"", "DiagnosticReport");
    assertThat(body).contains("urn:clinichub:harbourview:service-request", "\"value\":\"101\"");
    assertThat(body)
        .doesNotContain("TESTPATIENT")
        .doesNotContain("SYNTH0001")
        .doesNotContain("19000101");

    Map<String, Object> row = row("T-OK-1");
    assertThat(row.get("status")).isEqualTo("DELIVERED");
    assertThat(row.get("payload")).as("health information is cleared once delivered").isNull();
    assertThat(row.get("sender")).isEqualTo("EXTLAB.LAB1");

    var logged = messages();
    assertThat(logged).hasSize(1);
    assertThat(logged.get(0))
        .containsEntry("route", "hl7")
        .containsEntry("method", "MLLP")
        .containsEntry("response_status", 200)
        .containsEntry("upstream_status", 200)
        .containsEntry("bundle_id", "T-OK-1");
    assertThat(logged.get(0).toString())
        .doesNotContain("TESTPATIENT")
        .doesNotContain("Synthetic free text");
    assertThat(output.getAll())
        .doesNotContain("TESTPATIENT")
        .doesNotContain("SYNTH0001")
        .doesNotContain("Synthetic free text");
  }

  @Test
  void theTokenIsFetchedOnceAndReused() throws Exception {
    assertThat(ackCode(mllp(server.port(), sample("oru-final.hl7", "T-TOK-1")))).isEqualTo("AA");
    assertThat(ackCode(mllp(server.port(), sample("oru-final.hl7", "T-TOK-2")))).isEqualTo("AA");

    assertThat(CLINICHUB.findAll(postRequestedFor(urlEqualTo("/fhir")))).hasSize(2);
    assertThat(
            CLINICHUB
                .findAll(
                    postRequestedFor(urlEqualTo("/connect/token"))
                        .withRequestBody(
                            equalTo(
                                "grant_type=client_credentials&client_id=integrationhub&client_secret=test-secret&scope=results")))
                .size())
        .isLessThanOrEqualTo(1);
  }

  @Test
  void clinicHubDownMeansAEThenDeliveryWhenItIsBackWithoutResending() throws Exception {
    CLINICHUB.stubFor(post("/fhir").willReturn(aResponse().withStatus(503)));

    String ack = mllp(server.port(), sample("oru-final.hl7", "T-RETRY-1"));
    assertThat(ackCode(ack)).isEqualTo("AE");
    assertThat(ackText(ack)).contains("will retry");

    Map<String, Object> pending = row("T-RETRY-1");
    assertThat(pending.get("status")).isEqualTo("PENDING");
    assertThat(pending.get("attempts")).isEqualTo(1);
    assertThat(pending.get("last_status")).isEqualTo(503);
    byte[] payload = (byte[]) pending.get("payload");
    assertThat(payload).as("kept for the retry").isNotEmpty();
    assertThat(new String(payload, StandardCharsets.ISO_8859_1))
        .as("encrypted at rest")
        .doesNotContain("DiagnosticReport")
        .doesNotContain("Glucose")
        .doesNotContain("harbourview");

    // The lab, told AE, sends it again: recognised, not stored twice, still AE.
    String again = mllp(server.port(), sample("oru-final.hl7", "T-RETRY-1"));
    assertThat(ackCode(again)).isEqualTo("AE");
    assertThat(
            jdbc.queryForObject(
                "select count(*) from hl7_inbound where control_id = 'T-RETRY-1'", Integer.class))
        .isEqualTo(1);

    CLINICHUB.stubFor(post("/fhir").willReturn(aResponse().withStatus(201)));
    retryUntil("T-RETRY-1", "DELIVERED");

    Map<String, Object> done = row("T-RETRY-1");
    assertThat(done.get("payload")).isNull();
    assertThat(done.get("last_status")).isEqualTo(201);
    // After delivery a repeat is acknowledged AA and not sent again.
    int before = CLINICHUB.findAll(postRequestedFor(urlEqualTo("/fhir"))).size();
    String repeat = mllp(server.port(), sample("oru-final.hl7", "T-RETRY-1"));
    assertThat(ackCode(repeat)).isEqualTo("AA");
    assertThat(CLINICHUB.findAll(postRequestedFor(urlEqualTo("/fhir")))).hasSize(before);
    // The retry is in the log as its own row.
    assertThat(messages()).extracting(m -> m.get("method")).contains("MLLP", "RETRY");
  }

  @Test
  void clinicHubUnreachableOrTokenRefusedIsRetriedToo() throws Exception {
    CLINICHUB.stubFor(post("/connect/token").willReturn(aResponse().withStatus(401)));
    // A cached token from earlier tests would hide the refusal; a 401 on the call drops it.
    CLINICHUB.stubFor(post("/fhir").willReturn(aResponse().withStatus(401)));

    String ack = mllp(server.port(), sample("oru-final.hl7", "T-TOKEN-1"));

    assertThat(ackCode(ack)).isEqualTo("AE");
    assertThat(row("T-TOKEN-1").get("status")).isEqualTo("PENDING");
    assertThat(row("T-TOKEN-1").get("last_error")).isIn("unauthorized", "token-refused");
  }

  @Test
  void aRetryThatKeepsFailingIsGivenUpAndTheHealthInformationDropped() throws Exception {
    CLINICHUB.stubFor(post("/fhir").willReturn(aResponse().withStatus(500)));

    assertThat(ackCode(mllp(server.port(), sample("oru-final.hl7", "T-FAIL-1")))).isEqualTo("AE");
    retryUntil("T-FAIL-1", "FAILED");

    Map<String, Object> failed = row("T-FAIL-1");
    assertThat(failed.get("attempts")).isEqualTo(3);
    assertThat(failed.get("payload")).isNull();
    assertThat(failed.get("last_error")).isEqualTo("clinichub-unavailable");

    // The lab can send it again once ClinicHub is fixed: a given-up message is processed anew.
    CLINICHUB.stubFor(post("/fhir").willReturn(aResponse().withStatus(200)));
    assertThat(ackCode(mllp(server.port(), sample("oru-final.hl7", "T-FAIL-1")))).isEqualTo("AA");
    assertThat(row("T-FAIL-1").get("status")).isEqualTo("DELIVERED");
  }

  @Test
  void aResultClinicHubRefusesIsARAndNotRetried() throws Exception {
    CLINICHUB.stubFor(post("/fhir").willReturn(aResponse().withStatus(422)));

    String ack = mllp(server.port(), sample("oru-final.hl7", "T-REFUSED-1"));

    assertThat(ackCode(ack)).isEqualTo("AR");
    Map<String, Object> row = row("T-REFUSED-1");
    assertThat(row.get("status")).isEqualTo("REJECTED");
    assertThat(row.get("payload")).isNull();
    assertThat(row.get("last_status")).isEqualTo(422);
    assertThat(worker.runOnce()).isZero();
    assertThat(messages().get(0)).containsEntry("response_status", 422);
  }

  @Test
  void aPreliminaryResultIsAcceptedAAButNotForwardedOrStored() throws Exception {
    String ack = mllp(server.port(), sample("oru-preliminary.hl7", "T-PRELIM-1"));

    assertThat(ackCode(ack)).isEqualTo("AA");
    assertThat(CLINICHUB.findAll(postRequestedFor(urlEqualTo("/fhir")))).isEmpty();
    assertThat(jdbc.queryForObject("select count(*) from hl7_inbound", Integer.class)).isZero();
  }

  @Test
  void anAmendedAndAFinalReportInOneMessageAreSentTogether() throws Exception {
    assertThat(ackCode(mllp(server.port(), sample("oru-two-reports.hl7", "T-TWO-1"))))
        .isEqualTo("AA");

    List<LoggedRequest> posts = CLINICHUB.findAll(postRequestedFor(urlEqualTo("/fhir")));
    assertThat(posts).hasSize(1);
    assertThat(posts.get(0).getBodyAsString())
        .contains("\"status\":\"final\"", "\"status\":\"amended\"");
    assertThat(row("T-TWO-1").get("report_count")).isEqualTo(2);
  }

  @Test
  void messagesThatCannotBeUsedAreARWithAFixedReasonAndNothingIsStored(CapturedOutput output)
      throws Exception {
    String badValue = sample("oru-final.hl7", "T-BAD-1").replace("|5.4|", "|SECRETVALUE|");
    String noOrder = sample("oru-final.hl7", "T-BAD-2").replace("101^harbourview", "");
    String adt = sample("oru-final.hl7", "T-BAD-3").replace("ORU^R01", "ADT^A01");
    String garbage = "MSH|^~\\&|X\rPID|1||SYNTH-SECRET-ID\r";

    for (String message : List.of(badValue, noOrder, adt, garbage)) {
      String ack = mllp(server.port(), message);
      assertThat(ackCode(ack)).isEqualTo("AR");
      assertThat(ack)
          .doesNotContain("SECRETVALUE")
          .doesNotContain("SYNTH-SECRET-ID")
          .doesNotContain("TESTPATIENT");
    }
    assertThat(jdbc.queryForObject("select count(*) from hl7_inbound", Integer.class)).isZero();
    assertThat(CLINICHUB.findAll(postRequestedFor(urlEqualTo("/fhir")))).isEmpty();
    assertThat(messages())
        .hasSize(4)
        .allSatisfy(m -> assertThat(m).containsEntry("response_status", 422));
    assertThat(output.getAll())
        .doesNotContain("SECRETVALUE")
        .doesNotContain("SYNTH-SECRET-ID")
        .doesNotContain("TESTPATIENT");
  }

  @Test
  void oneConnectionCarriesSeveralMessagesEachAnsweredInOrder() throws Exception {
    try (Socket socket = new Socket("localhost", server.port())) {
      socket.setSoTimeout(15_000);
      OutputStream out = socket.getOutputStream();
      out.write(
          "\r\n".getBytes(StandardCharsets.ISO_8859_1)); // stray bytes between frames are skipped
      for (String id : List.of("T-MULTI-1", "T-MULTI-2")) {
        Mllp.write(out, sample("oru-final.hl7", id).getBytes(StandardCharsets.ISO_8859_1));
        String ack =
            new String(Mllp.read(socket.getInputStream(), 1 << 20), StandardCharsets.ISO_8859_1);
        assertThat(ack).contains("MSA|AA|" + id + "|");
      }
    }
  }

  @Test
  void aMessageOverTheSizeLimitClosesTheConnectionAndIsNotAnswered() throws Exception {
    try (Socket socket = new Socket("localhost", server.port())) {
      socket.setSoTimeout(15_000);
      OutputStream out = socket.getOutputStream();
      out.write(Mllp.VT);
      out.write("MSH|^~\\&|".getBytes(StandardCharsets.ISO_8859_1));
      byte[] chunk = new byte[1000];
      java.util.Arrays.fill(chunk, (byte) 'A');
      try {
        for (int i = 0; i < 40; i++) {
          out.write(chunk);
        }
        out.flush();
      } catch (java.io.IOException closedByServer) {
        // fine: the server may already have closed
      }
      assertThat(readsNothing(socket)).isTrue();
    }
    assertThat(jdbc.queryForObject("select count(*) from hl7_inbound", Integer.class)).isZero();
  }

  private static boolean readsNothing(Socket socket) {
    try {
      return socket.getInputStream().read() < 0;
    } catch (java.io.IOException reset) {
      return true;
    }
  }

  @Test
  void aBrokenFrameEndClosesTheConnection() throws Exception {
    try (Socket socket = new Socket("localhost", server.port())) {
      socket.setSoTimeout(15_000);
      OutputStream out = socket.getOutputStream();
      out.write(Mllp.VT);
      out.write(sample("oru-final.hl7", "T-FRAME-1").getBytes(StandardCharsets.ISO_8859_1));
      out.write(Mllp.FS);
      out.write('X'); // should be <CR>
      out.flush();
      assertThat(readsNothing(socket)).isTrue();
    }
    assertThat(jdbc.queryForObject("select count(*) from hl7_inbound", Integer.class)).isZero();
  }
}
