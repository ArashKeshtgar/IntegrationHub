package ca.arashkeshtgar.integrationhub.hl7;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * The MLLP listener (port 2575). One virtual thread per connection; a connection may carry many
 * messages, each answered before the next is read. A connection that is idle longer than {@code
 * mllp.read-timeout} is closed. With {@code mllp.tls} the listener speaks TLS ({@link
 * MllpSockets}); a connection whose handshake fails is closed and logged without its content.
 */
@Component
@ConditionalOnProperty(name = "hub.hl7.enabled", havingValue = "true")
public class MllpServer {

  private static final Logger log = LoggerFactory.getLogger(MllpServer.class);

  private final Hl7Properties.Mllp settings;
  private final Hl7Inbound inbound;
  private final MllpSockets sockets;
  private final ExecutorService connections = Executors.newVirtualThreadPerTaskExecutor();
  private ServerSocket server;
  private Thread acceptor;

  public MllpServer(Hl7Properties properties, Hl7Inbound inbound, MllpSockets sockets) {
    this.settings = properties.mllp();
    this.inbound = inbound;
    this.sockets = sockets;
  }

  @PostConstruct
  void start() throws IOException {
    server =
        sockets.listen(
            settings.port(), settings.bindAddress(), settings.tls(), settings.clientAuth());
    acceptor = new Thread(this::acceptLoop, "mllp-acceptor");
    acceptor.setDaemon(true);
    acceptor.start();
    log.info(
        "MLLP listening on {}:{}{}",
        settings.bindAddress(),
        server.getLocalPort(),
        settings.tls() ? " (TLS)" : "");
  }

  /** The port actually bound (useful when configured as 0). */
  public int port() {
    return server.getLocalPort();
  }

  @PreDestroy
  void stop() throws IOException {
    server.close();
    connections.shutdownNow();
  }

  private void acceptLoop() {
    while (!server.isClosed()) {
      try {
        Socket socket = server.accept();
        connections.execute(() -> serve(socket));
      } catch (IOException e) {
        if (!server.isClosed()) {
          log.warn("MLLP accept failed: {}", e.getClass().getSimpleName());
        }
      }
    }
  }

  private void serve(Socket socket) {
    try (socket) {
      socket.setSoTimeout((int) settings.readTimeout().toMillis());
      InputStream in = socket.getInputStream();
      OutputStream out = socket.getOutputStream();
      while (true) {
        byte[] frame = Mllp.read(in, settings.maxMessageBytes());
        if (frame == null) {
          return;
        }
        Mllp.write(out, inbound.handle(frame));
      }
    } catch (SocketTimeoutException e) {
      // idle: close quietly
    } catch (Mllp.FrameException e) {
      log.warn("MLLP framing error: {}", e.getMessage());
    } catch (javax.net.ssl.SSLException e) {
      log.warn("MLLP TLS handshake failed: {}", e.getClass().getSimpleName());
    } catch (IOException e) {
      log.debug("MLLP connection ended: {}", e.getClass().getSimpleName());
    }
  }
}
