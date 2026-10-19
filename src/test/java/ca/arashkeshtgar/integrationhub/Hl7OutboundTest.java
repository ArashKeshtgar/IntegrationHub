package ca.arashkeshtgar.integrationhub;

import static ca.arashkeshtgar.integrationhub.Hl7TestSupport.ackCode;
import static ca.arashkeshtgar.integrationhub.Hl7TestSupport.ackText;
import static ca.arashkeshtgar.integrationhub.Hl7TestSupport.mllp;
import static ca.arashkeshtgar.integrationhub.Hl7TestSupport.sample;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static org.assertj.core.api.Assertions.assertThat;

import ca.arashkeshtgar.integrationhub.hl7.Hl7OutboundWorker;
import ca.arashkeshtgar.integrationhub.hl7.MllpServer;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The OLIS copy: a result relayed to ClinicHub is also sent as ORU^R01 to tools/olis-sim (started
 * here as a process, the way a developer runs it). Also checks that only listed senders are
 * accepted on the inbound side.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class Hl7OutboundTest extends RelayTestBase {

  static final int OLIS_PORT;

  static {
    try (ServerSocket socket = new ServerSocket(0)) {
      OLIS_PORT = socket.getLocalPort();
    } catch (IOException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  @DynamicPropertySource
  static void hl7(DynamicPropertyRegistry registry) {
    registry.add("hub.hl7.enabled", () -> "true");
    registry.add("hub.hl7.queue-key", () -> Hl7TestSupport.QUEUE_KEY);
    registry.add("hub.hl7.mllp.port", () -> "0");
    registry.add("hub.hl7.mllp.bind-address", () -> "127.0.0.1");
    registry.add("hub.hl7.allowed-senders", () -> "SOMEONE.ELSE");
    registry.add("hub.hl7.clinichub.token-url", () -> CLINICHUB.baseUrl() + "/connect/token");
    registry.add("hub.hl7.clinichub.fhir-url", () -> CLINICHUB.baseUrl() + "/fhir");
    registry.add("hub.hl7.clinichub.client-secret", () -> "test-secret");
    registry.add("hub.hl7.retry.initial-backoff", () -> "1s");
    registry.add("hub.hl7.retry.max-backoff", () -> "1s");
    registry.add("hub.hl7.retry.max-attempts", () -> "3");
    registry.add("hub.hl7.retry.interval", () -> "1h");
    registry.add("hub.hl7.outbound.enabled", () -> "true");
    registry.add("hub.hl7.outbound.host", () -> "localhost");
    registry.add("hub.hl7.outbound.port", () -> String.valueOf(OLIS_PORT));
    registry.add("hub.hl7.outbound.timeout", () -> "1s");
  }

  @Autowired Hl7OutboundWorker worker;
  @Autowired MllpServer server;

  private Process sim;
  private Path out;

  @BeforeEach
  void clean() throws IOException {
    jdbc.update("delete from hl7_outbound");
    jdbc.update("delete from hl7_inbound");
    CLINICHUB.stubFor(post("/fhir").willReturn(aResponse().withStatus(200).withBody("{}")));
    out = Files.createTempDirectory("olis-sim");
  }

  @AfterEach
  void stopSim() {
    if (sim != null) {
      sim.destroy();
      sim = null;
    }
  }

  private void startSim(String mode) throws Exception {
    stopSim();
    sim =
        new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "tools/olis-sim/OlisSim.java",
                "--port",
                String.valueOf(OLIS_PORT),
                "--out",
                out.toString(),
                "--mode",
                mode)
            .redirectErrorStream(true)
            .start();
    BufferedReader lines = new BufferedReader(new InputStreamReader(sim.getInputStream()));
    // The JVM may print notices (JAVA_TOOL_OPTIONS) before the simulator's own line.
    String line;
    do {
      line = lines.readLine();
    } while (line != null && !line.contains("listening"));
    assertThat(line).as("simulator started").isNotNull();
  }

  private List<Path> received() throws IOException {
    try (Stream<Path> files = Files.list(out)) {
      return files.sorted().toList();
    }
  }

  private void relayResult() throws Exception {
    var response =
        postBundle(
            "clinichub",
            validToken(),
            Files.readAllBytes(Path.of("src/test/resources/hl7/result-bundle-for-olis.json")));
    assertThat(response.statusCode()).isEqualTo(200);
  }

  private Map<String, Object> outbound() {
    return jdbc.queryForMap("select * from hl7_outbound");
  }

  @Test
  void aRelayedResultIsSentAsOruR01WithOnlyThePseudonymAndAnAckMarksItSent() throws Exception {
    startSim("accept");

    relayResult();

    // Queued in the transaction of the log row; the second report (a boolean value) cannot be
    // expressed and is skipped.
    assertThat(jdbc.queryForObject("select count(*) from hl7_outbound", Integer.class))
        .isEqualTo(1);
    Map<String, Object> queued = outbound();
    assertThat(queued.get("status")).isEqualTo("PENDING");
    assertThat(new String((byte[]) queued.get("payload"), StandardCharsets.ISO_8859_1))
        .doesNotContain("MSH")
        .doesNotContain("Glucose");

    assertThat(worker.runOnce()).isEqualTo(1);

    Map<String, Object> sent = outbound();
    assertThat(sent.get("status")).isEqualTo("SENT");
    assertThat(sent.get("payload")).isNull();
    List<Path> files = received();
    assertThat(files).hasSize(1);
    String oru = Files.readString(files.get(0), StandardCharsets.ISO_8859_1);
    String[] segments = oru.split("\r");
    assertThat(segments[0])
        .startsWith("MSH|^~\\&|INTEGRATIONHUB|HUB|OLIS|OLIS|")
        .contains("||ORU^R01|H");
    assertThat(segments[1]).isEqualTo("PID|1||UNKNOWN^^^PSEUDONYM");
    assertThat(segments[2]).isEqualTo("ORC|RE|101^harbourview");
    assertThat(segments[3])
        .startsWith(
            "OBR|1|101^harbourview||24323-8^Comprehensive metabolic panel^LN|||20260315083000-0400")
        .endsWith("|F");
    assertThat(segments[3]).contains("20260315100000-0400");
    assertThat(oru).contains("NTE|1||Synthetic conclusion");
    assertThat(oru).contains("OBX|1|NM|2345-7^Glucose^LN||5.4|mmol/L^^UCUM|3.9-6.1|H|||F");
    assertThat(oru).contains("OBX|2|ST|600-7^^LN||Synthetic text with \\F\\ and \\S\\ characters|");
    assertThat(oru).doesNotContain("Zzyzx").doesNotContain("Testpatient");
    assertThat(messages()).hasSize(1);
  }

  @Test
  void aResultClinicHubDidNotTakeOrAnotherRouteMakesNoCopy() throws Exception {
    startSim("accept");
    CLINICHUB.stubFor(post("/fhir").willReturn(aResponse().withStatus(422).withBody("{}")));

    var response =
        postBundle(
            "clinichub",
            validToken(),
            Files.readAllBytes(Path.of("src/test/resources/hl7/result-bundle-for-olis.json")));

    assertThat(response.statusCode()).isEqualTo(422);
    assertThat(jdbc.queryForObject("select count(*) from hl7_outbound", Integer.class)).isZero();
  }

  @Test
  void aReceiverThatIsDownIsRetriedAndGetsTheCopyWhenItIsBack() throws Exception {
    relayResult(); // nothing listens on the port yet

    assertThat(worker.runOnce()).isZero();
    Map<String, Object> waiting = outbound();
    assertThat(waiting.get("status")).isEqualTo("PENDING");
    assertThat(waiting.get("last_error")).isEqualTo("receiver-unreachable");
    assertThat(waiting.get("attempts")).isEqualTo(1);

    startSim("accept");
    Awaitility.await()
        .atMost(Duration.ofSeconds(20))
        .pollInterval(Duration.ofMillis(300))
        .untilAsserted(
            () -> {
              worker.runOnce();
              assertThat(outbound().get("status")).isEqualTo("SENT");
            });
    assertThat(received()).hasSize(1);
  }

  @Test
  void aReceiverThatRefusesOrNeverAnswersEndsInFailedAndTheMessageIsKeptForReplay()
      throws Exception {
    startSim("reject");
    relayResult();

    Awaitility.await()
        .atMost(Duration.ofSeconds(25))
        .pollInterval(Duration.ofMillis(300))
        .untilAsserted(
            () -> {
              worker.runOnce();
              assertThat(outbound().get("status")).isEqualTo("FAILED");
            });
    Map<String, Object> failed = outbound();
    assertThat(failed.get("last_error")).isEqualTo("ack-ar");
    assertThat(failed.get("attempts")).isEqualTo(3);
    assertThat(failed.get("payload")).as("kept for the replay window").isNotNull();

    jdbc.update("delete from hl7_outbound");
    startSim("silent");
    relayResult();
    worker.runOnce();
    assertThat(outbound().get("last_error")).isEqualTo("receiver-unreachable");
  }

  @Test
  void onlyListedSendersAreAcceptedOnTheInboundSide() throws Exception {
    String ack = mllp(server.port(), sample("oru-final.hl7", "T-SENDER-1"));

    assertThat(ackCode(ack)).isEqualTo("AR");
    assertThat(ackText(ack)).isEqualTo("sender not accepted");
    assertThat(jdbc.queryForObject("select count(*) from hl7_inbound", Integer.class)).isZero();
  }

  @Test
  void theSimulatorRejectsBadArguments() throws Exception {
    Process p =
        new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "tools/olis-sim/OlisSim.java",
                "--mode",
                "nonsense")
            .redirectErrorStream(true)
            .start();
    assertThat(p.waitFor()).isEqualTo(2);
    new File(out.toString()).delete();
  }
}
