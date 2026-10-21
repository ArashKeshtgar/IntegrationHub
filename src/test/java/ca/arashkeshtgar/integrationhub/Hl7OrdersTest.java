package ca.arashkeshtgar.integrationhub;

import static org.assertj.core.api.Assertions.assertThat;

import ca.arashkeshtgar.integrationhub.hl7.LabCodes;
import ca.arashkeshtgar.integrationhub.hl7.Mllp;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
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
 * Orders for an external laboratory: ClinicHub posts its order Bundle to the {@code externallab}
 * route, the hub sends an ORM^O01 over MLLP and the lab's ACK decides the answer. The lab is a
 * socket in this test. Synthetic samples with markers that must never reach the log.
 */
@ExtendWith(OutputCaptureExtension.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class Hl7OrdersTest extends RelayTestBase {

  static final byte[] ORDER = ContractFlowsTest.read("order-bundle.json");
  static final byte[] CANCEL = ContractFlowsTest.read("cancel-bundle.json");

  /** Plays the lab: keeps what it receives and answers with {@link #ackCode}. */
  static final class FakeLab implements AutoCloseable {
    final ServerSocket socket;
    final List<String> received = new CopyOnWriteArrayList<>();
    volatile String ackCode = "AA";
    volatile boolean silent;
    volatile boolean hangUp;

    FakeLab() throws IOException {
      socket = new ServerSocket(0);
      Thread t = new Thread(this::serve, "fake-lab");
      t.setDaemon(true);
      t.start();
    }

    private void serve() {
      while (!socket.isClosed()) {
        try (Socket s = socket.accept()) {
          if (hangUp) {
            continue;
          }
          byte[] message = Mllp.read(s.getInputStream(), 1 << 20);
          if (message == null) {
            continue;
          }
          String text = new String(message, StandardCharsets.UTF_8);
          received.add(text);
          if (silent) {
            Thread.sleep(2000);
            continue;
          }
          String id = text.split("\r")[0].split("\\|", -1)[9];
          String ack =
              "MSH|^~\\&|EXTLAB|LAB1|INTEGRATIONHUB|HUB|20261020||ACK^O01|A1|P|2.5\rMSA|"
                  + ackCode
                  + "|"
                  + id
                  + "\r";
          Mllp.write(s.getOutputStream(), ack.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
          // the test closed the socket, or the hub gave up waiting
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          return;
        }
      }
    }

    int port() {
      return socket.getLocalPort();
    }

    @Override
    public void close() throws IOException {
      socket.close();
    }
  }

  static final FakeLab LAB;

  static {
    try {
      LAB = new FakeLab();
    } catch (IOException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  @DynamicPropertySource
  static void route(DynamicPropertyRegistry registry) {
    registry.add("hub.routes.externallab.hl7-order", () -> "true");
    registry.add("hub.routes.externallab.allowed-audiences", () -> "externallab");
    registry.add("hub.routes.externallab.required-scopes", () -> "orders");
    registry.add("hub.routes.externallab.allowed-methods", () -> "POST,PUT");
    registry.add("hub.hl7.lab.host", () -> "localhost");
    registry.add("hub.hl7.lab.port", () -> String.valueOf(LAB.port()));
    registry.add("hub.hl7.lab.timeout", () -> "1s");
  }

  @AfterAll
  static void stopLab() throws IOException {
    LAB.close();
  }

  @BeforeEach
  void reset() {
    LAB.received.clear();
    LAB.ackCode = "AA";
    LAB.silent = false;
    LAB.hangUp = false;
  }

  /**
   * A token ClinicHub signs for an order to the external lab: audience externallab, scope orders.
   */
  static String orderToken() {
    return sign(
        validClaims()
            .audience("externallab")
            .claim("client_id", "clinichub")
            .subject("clinichub")
            .claim("scope", "orders")
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

  @Test
  void anOrderBecomesAnOrmAndTheLabsAckIsTheAnswer(CapturedOutput output) throws Exception {
    var response = postBundle("externallab", orderToken(), ORDER);

    assertThat(response.statusCode()).isEqualTo(200);
    String body = new String(response.body(), StandardCharsets.UTF_8);
    assertThat(body).contains("transaction-response");
    // The patient and the practitioner, then one entry per order, as ClinicHub counts them.
    assertThat(body.split("\"response\"", -1)).hasSize(5);

    assertThat(LAB.received).hasSize(1);
    String[] segments = LAB.received.get(0).split("\r");
    assertThat(segments[0]).startsWith("MSH|^~\\&|INTEGRATIONHUB|HUB|EXTLAB|LAB1|");
    assertThat(segments[0]).contains("||ORM^O01|").endsWith("|P|2.5||||||UNICODE UTF-8");
    assertThat(segments[1])
        .isEqualTo(
            "PID|1||MRN-MARKER-0001^^^harbourview^MR~HC-MARKER-123456^^^CANON^JHN"
                + "||Zzyzx^Testpatient||19000101|U");
    assertThat(segments[2])
        .startsWith("ORC|NW|101^harbourview||||||||")
        .endsWith("|CPSO-MARKER-000000^Synthetic^Doc^^^^^^CPSO");
    assertThat(segments[3]).startsWith("OBR|1|101^harbourview||718-7^^LN|");
    String[] obr = segments[3].split("\\|", -1);
    assertThat(obr).hasSize(28);
    assertThat(obr[16]).startsWith("CPSO-MARKER-000000^Synthetic^Doc");
    assertThat(obr[27]).isEqualTo("^^^^^R");
    assertThat(segments[4]).isEqualTo("NTE|1||MARKER-NOTE-TEXT");
    assertThat(segments[5]).startsWith("ORC|NW|102^harbourview|");
    assertThat(segments[6].split("\\|", -1)[27]).isEqualTo("^^^^^A");

    // The hub keeps and logs none of it.
    assertThat(everythingLogged() + output.getAll())
        .doesNotContain("MARKER", "Zzyzx", "Testpatient", "19000101");
    assertThat(messages()).hasSize(1);
    assertThat(messages().get(0).get("response_status")).isEqualTo(200);
  }

  @Autowired LabCodes codes;

  @Test
  void aMappedTestGoesOutInTheLabsOwnCodeWithTheLoincAsTheAlternate() throws Exception {
    codes.replace(List.of(new LabCodes.Entry("718-7", "HGB", "Haemoglobin", null)));
    try {
      assertThat(postBundle("externallab", orderToken(), ORDER).statusCode()).isEqualTo(200);
      String[] segments = LAB.received.get(0).split("\r");
      assertThat(segments[3]).startsWith("OBR|1|101^harbourview||HGB^Haemoglobin^L^718-7^^LN|");
      // A test the map does not name goes out as before.
      assertThat(segments[6]).doesNotContain("^L^");
    } finally {
      codes.replace(List.of());
    }
  }

  @Test
  void aCancellationBecomesACancelOrderAndIsAcceptedWithCa() throws Exception {
    LAB.ackCode = "CA";
    var response = postBundle("externallab", orderToken(), CANCEL);

    assertThat(response.statusCode()).isEqualTo(200);
    String[] segments = LAB.received.get(0).split("\r");
    assertThat(segments[1]).startsWith("PID|1||MRN-MARKER-0001^^^harbourview^MR||");
    assertThat(segments[2]).startsWith("ORC|CA|101^harbourview|");
    assertThat(segments[3]).startsWith("OBR|1|101^harbourview||718-7^Hemoglobin^LN|");
  }

  @Test
  void aLabThatRefusesAnOrderIsAFailureToRetryAndARefusedCancelIsAnAnswer() throws Exception {
    LAB.ackCode = "AR";
    assertThat(postBundle("externallab", orderToken(), ORDER).statusCode()).isEqualTo(422);
    LAB.ackCode = "AE";
    assertThat(postBundle("externallab", orderToken(), CANCEL).statusCode()).isEqualTo(409);
  }

  @Test
  void aLabThatDoesNotAnswerOrHangsUpIsBadGateway() throws Exception {
    LAB.silent = true;
    assertThat(postBundle("externallab", orderToken(), ORDER).statusCode()).isEqualTo(502);
    LAB.silent = false;
    LAB.hangUp = true;
    assertThat(postBundle("externallab", orderToken(), ORDER).statusCode()).isEqualTo(502);
  }

  @Test
  void onlyAnOrderTokenForTheLabIsAcceptedAndOnlyOrdersAreSent() throws Exception {
    assertThat(postBundle("externallab", null, ORDER).statusCode()).isEqualTo(401);
    assertThat(postBundle("externallab", validToken(), ORDER).statusCode()).isEqualTo(401);
    byte[] notAnOrder =
        "{\"resourceType\":\"Bundle\",\"type\":\"transaction\"}".getBytes(StandardCharsets.UTF_8);
    assertThat(postBundle("externallab", orderToken(), notAnOrder).statusCode()).isEqualTo(400);
    assertThat(postBundle("externallab", orderToken(), new byte[0]).statusCode()).isEqualTo(400);
    assertThat(LAB.received).isEmpty();
  }
}
