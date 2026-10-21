package ca.arashkeshtgar.integrationhub.hl7;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Sends queued ORU^R01 copies to the receiver over MLLP and waits for its ACK. AA marks the row
 * sent; AE, AR, no ACK or a connection error is retried on the same schedule as the inbound side,
 * then the row is FAILED.
 */
@Component
@ConditionalOnProperty(name = "hub.hl7.enabled", havingValue = "true")
public class Hl7OutboundWorker {

  private static final Logger log = LoggerFactory.getLogger(Hl7OutboundWorker.class);

  private final Hl7Properties properties;
  private final JdbcTemplate jdbc;
  private final PayloadCipher cipher;
  private final MeterRegistry meters;
  private final ScheduledExecutorService scheduler =
      Executors.newSingleThreadScheduledExecutor(
          r -> {
            Thread t = new Thread(r, "hl7-outbound-worker");
            t.setDaemon(true);
            return t;
          });

  private final MllpSockets sockets;

  public Hl7OutboundWorker(
      Hl7Properties properties,
      JdbcTemplate jdbc,
      PayloadCipher cipher,
      MeterRegistry meters,
      MllpSockets sockets) {
    this.properties = properties;
    this.jdbc = jdbc;
    this.cipher = cipher;
    this.meters = meters;
    this.sockets = sockets;
  }

  @PostConstruct
  void start() {
    if (!properties.sendsOut()) {
      return;
    }
    long every = properties.retry().interval().toMillis();
    scheduler.scheduleWithFixedDelay(this::safely, every, every, TimeUnit.MILLISECONDS);
  }

  @PreDestroy
  void stop() {
    scheduler.shutdownNow();
  }

  private void safely() {
    try {
      runOnce();
    } catch (RuntimeException e) {
      log.error("hl7 outbound round failed: {}", e.getClass().getSimpleName());
    }
  }

  /** One round; package-visible so tests can run it without waiting. Returns how many were sent. */
  public int runOnce() {
    List<UUID> due =
        jdbc.query(
            """
            update hl7_outbound set next_attempt_at = now() + make_interval(secs => 120)
             where id in (select id from hl7_outbound where status = 'PENDING' and next_attempt_at <= now()
                           order by created_at limit ? for update skip locked)
            returning id
            """,
            (rs, i) -> rs.getObject(1, UUID.class),
            properties.retry().batchSize());
    int sent = 0;
    for (UUID id : due) {
      if (send(id)) {
        sent++;
      }
    }
    return sent;
  }

  private boolean send(UUID id) {
    var rows =
        jdbc.query(
            "select payload, attempts from hl7_outbound where id = ? and status = 'PENDING'",
            (rs, i) -> new Object[] {rs.getBytes(1), rs.getInt(2)},
            id);
    if (rows.isEmpty() || rows.get(0)[0] == null) {
      return false;
    }
    byte[] message = cipher.decrypt((byte[]) rows.get(0)[0], id.toString());
    int attempts = (int) rows.get(0)[1];
    String error = deliver(message);
    if (error == null) {
      jdbc.update(
          "update hl7_outbound set status = 'SENT', sent_at = now(), payload = null, attempts = attempts + 1,"
              + " last_error = null where id = ?",
          id);
      meters.counter("hub.hl7.outbound", "result", "sent").increment();
      return true;
    }
    int done = attempts + 1;
    if (done >= properties.retry().maxAttempts()) {
      jdbc.update(
          "update hl7_outbound set status = 'FAILED', payload = case when ? then payload end,"
              + " failed_at = now(), attempts = ?, last_error = ? where id = ?",
          properties.replayWindowDays() > 0,
          done,
          error,
          id);
      meters.counter("hub.hl7.outbound", "result", "failed").increment();
      log.error("hl7 copy given up after {} attempts: {}", done, error);
    } else {
      long seconds = properties.retry().initialBackoff().toSeconds() << Math.min(done - 1, 20);
      seconds = Math.min(seconds, Math.max(1, properties.retry().maxBackoff().toSeconds()));
      jdbc.update(
          "update hl7_outbound set attempts = ?, last_error = ?, next_attempt_at = now() + make_interval(secs => ?)"
              + " where id = ?",
          done,
          error,
          (double) seconds,
          id);
      meters.counter("hub.hl7.outbound", "result", "retry").increment();
    }
    return false;
  }

  /** Null when the receiver answered AA, otherwise a fixed reason. */
  String deliver(byte[] message) {
    Hl7Properties.Outbound out = properties.outbound();
    try (Socket socket = sockets.connect(out.host(), out.port(), out.timeout(), out.tls())) {
      Mllp.write(socket.getOutputStream(), message);
      byte[] ack = Mllp.read(socket.getInputStream(), properties.mllp().maxMessageBytes());
      if (ack == null) {
        return "no-ack";
      }
      Hl7Message parsed = Hl7Message.parse(new String(ack, StandardCharsets.ISO_8859_1));
      String code = parsed.first("MSA").map(m -> m.field(1)).orElse("");
      return Hl7Ack.ACCEPT.equals(code) || "CA".equals(code) ? null : "ack-" + code.toLowerCase();
    } catch (Hl7Exception e) {
      return "bad-ack";
    } catch (IOException e) {
      return "receiver-unreachable";
    }
  }
}
