package ca.arashkeshtgar.integrationhub.hl7;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.time.Duration;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;
import org.springframework.stereotype.Component;

/**
 * The sockets MLLP runs on, plain or TLS ({@code hub.hl7.tls}). The listener presents the hub's
 * certificate and can ask for or require one from the lab ({@code mllp.client-auth}); a connection
 * the hub makes (to the lab, to the OLIS-style receiver) checks the other side's certificate
 * against the trust store, host name included, and presents the hub's own when the other side asks.
 * Without a trust store the JVM's own trusted roots are used. TLS 1.2 and 1.3 only.
 */
@Component
public class MllpSockets {

  private static final String[] PROTOCOLS = {"TLSv1.3", "TLSv1.2"};

  private final Hl7Properties.Tls tls;
  private volatile SSLContext context;

  public MllpSockets(Hl7Properties properties) {
    this.tls = properties.tls();
  }

  /** The listener: TLS when {@code useTls}; {@code clientAuth} is none, want or need. */
  public ServerSocket listen(int port, String bindAddress, boolean useTls, String clientAuth)
      throws IOException {
    InetAddress bind = InetAddress.getByName(bindAddress);
    if (!useTls) {
      return new ServerSocket(port, 50, bind);
    }
    SSLServerSocket server =
        (SSLServerSocket) context().getServerSocketFactory().createServerSocket(port, 50, bind);
    server.setEnabledProtocols(PROTOCOLS);
    switch (clientAuth) {
      case "need" -> server.setNeedClientAuth(true);
      case "want" -> server.setWantClientAuth(true);
      default -> server.setNeedClientAuth(false);
    }
    return server;
  }

  /** A connection to a lab or receiver; with TLS the handshake is done before it is returned. */
  public Socket connect(String host, int port, Duration timeout, boolean useTls)
      throws IOException {
    int millis = (int) timeout.toMillis();
    Socket plain = new Socket();
    try {
      plain.connect(new InetSocketAddress(host, port), millis);
      plain.setSoTimeout(millis);
      if (!useTls) {
        return plain;
      }
      SSLSocket socket =
          (SSLSocket) context().getSocketFactory().createSocket(plain, host, port, true);
      SSLParameters parameters = socket.getSSLParameters();
      parameters.setProtocols(PROTOCOLS);
      parameters.setEndpointIdentificationAlgorithm("HTTPS"); // the certificate must name the host
      socket.setSSLParameters(parameters);
      socket.setSoTimeout(millis);
      socket.startHandshake();
      return socket;
    } catch (IOException | RuntimeException e) {
      plain.close();
      throw e;
    }
  }

  SSLContext context() {
    SSLContext c = context;
    if (c == null) {
      synchronized (this) {
        if (context == null) {
          context = build();
        }
        c = context;
      }
    }
    return c;
  }

  private SSLContext build() {
    try {
      KeyManagerFactory keys = null;
      if (present(tls.keyStore())) {
        keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keys.init(load(tls.keyStore(), tls.keyStorePassword()), chars(tls.keyStorePassword()));
      }
      TrustManagerFactory trust =
          TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
      trust.init(
          present(tls.trustStore()) ? load(tls.trustStore(), tls.trustStorePassword()) : null);
      SSLContext c = SSLContext.getInstance("TLS");
      c.init(keys == null ? null : keys.getKeyManagers(), trust.getTrustManagers(), null);
      return c;
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("hub.hl7.tls: " + e.getClass().getSimpleName(), e);
    }
  }

  private static KeyStore load(String path, String password) throws GeneralSecurityException {
    try (InputStream in = Files.newInputStream(Path.of(path))) {
      KeyStore store = KeyStore.getInstance("PKCS12");
      store.load(in, chars(password));
      return store;
    } catch (IOException e) {
      throw new UncheckedIOException("hub.hl7.tls: cannot read " + Path.of(path).getFileName(), e);
    }
  }

  private static boolean present(String value) {
    return value != null && !value.isBlank();
  }

  private static char[] chars(String password) {
    return password == null ? new char[0] : password.toCharArray();
  }
}
