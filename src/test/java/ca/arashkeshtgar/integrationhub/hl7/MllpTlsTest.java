package ca.arashkeshtgar.integrationhub.hl7;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * MLLP over TLS with the test-only stores in src/test/resources/tls (self-signed, CN=localhost, SAN
 * localhost and 127.0.0.1, password "test-only"): hub.p12 is the hub's key, trust.p12 trusts it,
 * stranger.p12 is a key nobody trusts.
 */
class MllpTlsTest {

  private static final Duration TIMEOUT = Duration.ofSeconds(5);
  private static final String MESSAGE = "MSH|^~\\&|LAB|LAB1|HUB|HUB|20261021||ORU^R01|1|P|2.5";

  private ServerSocket server;

  @AfterEach
  void close() throws IOException {
    if (server != null) {
      server.close();
    }
  }

  static String store(String name) {
    try {
      return Path.of(MllpTlsTest.class.getResource("/tls/" + name).toURI()).toString();
    } catch (URISyntaxException e) {
      throw new IllegalStateException(e);
    }
  }

  static MllpSockets sockets(String keyStore, String trustStore) {
    Hl7Properties base = Hl7UnitTest.properties(false, null, null, false, null);
    Hl7Properties.Tls tls =
        new Hl7Properties.Tls(
            keyStore == null ? null : store(keyStore),
            "test-only",
            trustStore == null ? null : store(trustStore),
            "test-only");
    return new MllpSockets(
        new Hl7Properties(
            base.enabled(),
            base.mllp(),
            base.queueKey(),
            base.zone(),
            base.allowedSenders(),
            base.retry(),
            base.retentionDays(),
            base.replayWindowDays(),
            base.clinichub(),
            base.outbound(),
            base.lab(),
            tls));
  }

  /** Answers each frame on one connection with "ACK:" and the frame; the result is what it read. */
  CompletableFuture<String> serveOnce(MllpSockets sockets, String clientAuth) throws IOException {
    server = sockets.listen(0, "127.0.0.1", true, clientAuth);
    return CompletableFuture.supplyAsync(
        () -> {
          try (Socket s = server.accept()) {
            s.setSoTimeout((int) TIMEOUT.toMillis());
            byte[] frame = Mllp.read(s.getInputStream(), 10_000);
            Mllp.write(
                s.getOutputStream(),
                ("ACK:" + new String(frame, StandardCharsets.UTF_8))
                    .getBytes(StandardCharsets.UTF_8));
            return new String(frame, StandardCharsets.UTF_8);
          } catch (IOException e) {
            return "failed: " + e.getClass().getSimpleName();
          }
        });
  }

  static String exchange(MllpSockets sockets, int port) throws IOException {
    try (Socket s = sockets.connect("localhost", port, TIMEOUT, true)) {
      Mllp.write(s.getOutputStream(), MESSAGE.getBytes(StandardCharsets.UTF_8));
      byte[] answer = Mllp.read(s.getInputStream(), 10_000);
      return answer == null ? null : new String(answer, StandardCharsets.UTF_8);
    }
  }

  @Test
  void aMessageGoesBothWaysOverMutualTls() throws Exception {
    var received = serveOnce(sockets("hub.p12", "trust.p12"), "need");
    String answer = exchange(sockets("hub.p12", "trust.p12"), server.getLocalPort());
    assertThat(answer).isEqualTo("ACK:" + MESSAGE);
    assertThat(received.get(5, TimeUnit.SECONDS)).isEqualTo(MESSAGE);
  }

  @Test
  void aServerWhoseCertificateIsNotTrustedIsRefused() throws Exception {
    var received = serveOnce(sockets("stranger.p12", "trust.p12"), "none");
    assertThatThrownBy(() -> exchange(sockets("hub.p12", "trust.p12"), server.getLocalPort()))
        .isInstanceOf(SSLException.class);
    assertThat(received.get(5, TimeUnit.SECONDS)).startsWith("failed:");
  }

  @Test
  void whenTheServerNeedsAClientCertificateAStrangerIsRefused() throws Exception {
    var received = serveOnce(sockets("hub.p12", "trust.p12"), "need");
    // The client trusts the server, but presents a certificate the server does not trust.
    MllpSockets stranger =
        new MllpSockets(
            withTls(
                new Hl7Properties.Tls(
                    store("stranger.p12"), "test-only", store("trust.p12"), "test-only")));
    assertThatThrownBy(() -> exchange(stranger, server.getLocalPort()))
        .isInstanceOf(IOException.class);
    assertThat(received.get(5, TimeUnit.SECONDS)).startsWith("failed:");
  }

  @Test
  void aPlainConnectionToTheTlsListenerGetsNoAnswer() throws Exception {
    var received = serveOnce(sockets("hub.p12", "trust.p12"), "none");
    byte[] answer = null;
    try (Socket s =
        sockets(null, null).connect("127.0.0.1", server.getLocalPort(), TIMEOUT, false)) {
      Mllp.write(s.getOutputStream(), MESSAGE.getBytes(StandardCharsets.UTF_8));
      answer = Mllp.read(s.getInputStream(), 10_000);
    } catch (IOException e) {
      // the listener closed the connection: nothing was answered
    }
    assertThat(answer).isNull();
    assertThat(received.get(5, TimeUnit.SECONDS)).startsWith("failed:");
  }

  @Test
  void aTlsListenerNeedsAKeyStoreAndClientAuthIsOneOfThree() {
    Hl7Properties base = Hl7UnitTest.properties(false, null, null, false, null);
    Hl7Properties.Mllp tls = new Hl7Properties.Mllp(2575, "127.0.0.1", 1000, TIMEOUT, true, "none");
    assertThatThrownBy(() -> with(base, tls, new Hl7Properties.Tls(null, null, null, null)))
        .hasMessageContaining("hub.hl7.tls.key-store");
    Hl7Properties.Mllp odd =
        new Hl7Properties.Mllp(2575, "127.0.0.1", 1000, TIMEOUT, false, "maybe");
    assertThatThrownBy(
            () -> with(base, odd, new Hl7Properties.Tls(store("hub.p12"), "test-only", null, null)))
        .hasMessageContaining("none, want or need");
    assertThat(List.of("none", "want", "need")).contains(base.mllp().clientAuth());
  }

  private static Hl7Properties withTls(Hl7Properties.Tls tls) {
    Hl7Properties base = Hl7UnitTest.properties(false, null, null, false, null);
    return with(base, base.mllp(), tls);
  }

  private static Hl7Properties with(
      Hl7Properties b, Hl7Properties.Mllp mllp, Hl7Properties.Tls tls) {
    return new Hl7Properties(
        b.enabled(),
        mllp,
        b.queueKey(),
        b.zone(),
        b.allowedSenders(),
        b.retry(),
        b.retentionDays(),
        b.replayWindowDays(),
        b.clinichub(),
        b.outbound(),
        b.lab(),
        tls);
  }
}
