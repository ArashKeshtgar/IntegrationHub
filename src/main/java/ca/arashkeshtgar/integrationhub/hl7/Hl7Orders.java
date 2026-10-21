package ca.arashkeshtgar.integrationhub.hl7;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.parser.LenientErrorHandler;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.hl7.fhir.r4.model.Bundle;
import org.springframework.stereotype.Component;

/**
 * Orders for an external laboratory (the route with {@code hl7-order: true}): the Bundle becomes an
 * ORM^O01, goes over MLLP and the lab's ACK is the answer. Synchronous and without a queue of its
 * own, because ClinicHub's outbox already retries; the message is not stored or logged here.
 */
@Component
public class Hl7Orders {

  /** What happened; {@code reason} is a short fixed phrase, never content. */
  public enum Outcome {
    /** AA or CA from the lab. */
    ACCEPTED,
    /** AE or AR: the lab answered and said no. */
    REFUSED,
    /** No answer, no connection, or an answer that is not an ACK: try again later. */
    UNAVAILABLE,
    /** The Bundle cannot be an order message. */
    INVALID,
    /** {@code hub.hl7.lab.host} is not set. */
    NOT_CONFIGURED
  }

  public record Result(
      Outcome outcome, String reason, List<String> orders, boolean cancel, int serviceRequests) {}

  private final Hl7Properties properties;
  private final MeterRegistry meters;
  private final MllpSockets sockets;
  private final LabCodes codes;

  public Hl7Orders(
      Hl7Properties properties, MeterRegistry meters, MllpSockets sockets, LabCodes codes) {
    this.properties = properties;
    this.meters = meters;
    this.sockets = sockets;
    this.codes = codes;
  }

  public Result send(UUID callId, byte[] body) {
    Hl7Properties.Lab lab = properties.lab();
    if (!lab.configured()) {
      return count(new Result(Outcome.NOT_CONFIGURED, "lab-not-configured", List.of(), false, 0));
    }
    FhirToOrm.Message message;
    try {
      var parser = FhirContext.forR4Cached().newJsonParser();
      parser.setParserErrorHandler(new LenientErrorHandler().setErrorOnInvalidValue(false));
      var resource = parser.parseResource(new String(body, StandardCharsets.UTF_8));
      if (!(resource instanceof Bundle bundle)) {
        return count(new Result(Outcome.INVALID, "not-a-bundle", List.of(), false, 0));
      }
      message =
          new FhirToOrm(lab, codes::forLoinc).convert(bundle, callId.toString(), Instant.now());
    } catch (Hl7Exception e) {
      return count(new Result(Outcome.INVALID, e.getMessage(), List.of(), false, 0));
    } catch (RuntimeException e) {
      return count(new Result(Outcome.INVALID, "unreadable-bundle", List.of(), false, 0));
    }
    String error = deliver(lab, message.text().getBytes(StandardCharsets.UTF_8));
    Outcome outcome =
        error == null
            ? Outcome.ACCEPTED
            : error.startsWith("ack-") ? Outcome.REFUSED : Outcome.UNAVAILABLE;
    return count(
        new Result(
            outcome,
            error == null ? "accepted" : error,
            message.orders(),
            message.cancel(),
            message.orders().size()));
  }

  /** Null when the lab answered AA or CA, otherwise a fixed reason. */
  private String deliver(Hl7Properties.Lab lab, byte[] message) {
    try (Socket socket = sockets.connect(lab.host(), lab.port(), lab.timeout(), lab.tls())) {
      Mllp.write(socket.getOutputStream(), message);
      byte[] ack = Mllp.read(socket.getInputStream(), properties.mllp().maxMessageBytes());
      if (ack == null) {
        return "no-ack";
      }
      Hl7Message parsed = Hl7Message.parse(new String(ack, StandardCharsets.UTF_8));
      String code = parsed.first("MSA").map(m -> m.field(1)).orElse("");
      if (Hl7Ack.ACCEPT.equals(code) || "CA".equals(code)) {
        return null;
      }
      return List.of("AE", "AR", "CE", "CR").contains(code)
          ? "ack-" + code.toLowerCase()
          : "bad-ack";
    } catch (Hl7Exception e) {
      return "bad-ack";
    } catch (IOException e) {
      return "lab-unreachable";
    }
  }

  private Result count(Result result) {
    meters.counter("hub.hl7.orders", "result", result.outcome().name().toLowerCase()).increment();
    return result;
  }
}
