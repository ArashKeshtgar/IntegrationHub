package ca.arashkeshtgar.integrationhub.events;

import ca.arashkeshtgar.integrationhub.relay.BodySummary;
import ca.arashkeshtgar.integrationhub.relay.Facts;
import ca.arashkeshtgar.integrationhub.relay.HubProperties.BodyFormat;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Turns one relayed call into zero or more events. Events are about answers: a call the upstream
 * did not answer (unreachable, timeout) makes none, so the sender's retry makes them once.
 *
 * <ul>
 *   <li>{@code order.placed}: an order Bundle the upstream answered, whatever the answer.
 *   <li>{@code order.accepted}: the same, answered 2xx.
 *   <li>{@code order.cancelled} / {@code order.cancel-refused}: a cancel answered 2xx / 409.
 *   <li>{@code result.final} / {@code result.amended}: a DiagnosticReport in that status, answered
 *       2xx. Other statuses make no event.
 *   <li>{@code finance.document}: each financial document of a call answered 2xx.
 * </ul>
 *
 * Event ids are derived from the content, so the same call sent twice is one event.
 */
@Component
public class EventFactory {

  /** Everything about a call that events are made from. */
  public record Evidence(
      String route,
      String method,
      String path,
      String sender,
      Instant receivedAt,
      String bodySha256,
      BodySummary summary,
      Optional<BodySummary.Identifier> queryIdentifier,
      BodyFormat format,
      Integer upstreamStatus) {}

  public record Result(List<ClinicalEvent> events, List<OrderLinks.Link> links) {
    public static final Result NONE = new Result(List.of(), List.of());
  }

  private final EventsProperties properties;
  private final Pseudonymizer pseudonymizer;
  private final OrderLinks links;

  public EventFactory(EventsProperties properties, Pseudonymizer pseudonymizer, OrderLinks links) {
    this.properties = properties;
    this.pseudonymizer = pseudonymizer;
    this.links = links;
  }

  public Result create(Evidence call) {
    if (!properties.enabled() || call.upstreamStatus() == null || call.summary() == null) {
      return Result.NONE;
    }
    Facts facts = call.summary().facts();
    boolean ok = call.upstreamStatus() >= 200 && call.upstreamStatus() < 300;
    List<ClinicalEvent> events = new ArrayList<>();
    List<OrderLinks.Link> newLinks = new ArrayList<>();

    if (call.format() == BodyFormat.FINANCE_DOCUMENTS) {
      if (ok && "POST".equals(call.method())) {
        for (Facts.FinanceDocument doc : facts.documents()) {
          events.add(finance(call, doc));
        }
      }
    } else if ("POST".equals(call.method()) && !facts.orders().isEmpty()) {
      orders(call, facts, ok, events, newLinks);
    } else if ("POST".equals(call.method()) && !facts.reports().isEmpty()) {
      if (ok) {
        results(call, facts, events);
      }
    } else if ("PUT".equals(call.method())
        && call.path().endsWith("/ServiceRequest")
        && call.queryIdentifier().isPresent()) {
      cancel(call, events);
    }
    return events.isEmpty() && newLinks.isEmpty() ? Result.NONE : new Result(events, newLinks);
  }

  private void orders(
      Evidence call,
      Facts facts,
      boolean ok,
      List<ClinicalEvent> events,
      List<OrderLinks.Link> newLinks) {
    for (Facts.Order order : facts.orders()) {
      Optional<String> clinic = BodySummary.clinicOf(order.identifier().system());
      if (clinic.isEmpty()) {
        continue;
      }
      String orderId = orderId(order.identifier().value());
      String pseudonym = pseudonym(clinic.get(), facts);
      newLinks.add(
          new OrderLinks.Link(
              clinic.get(),
              orderId,
              pseudonym,
              call.route(),
              order.codeSystem(),
              order.code(),
              order.priority()));
      events.add(
          event(
              "order.placed",
              call,
              clinic.get(),
              call.route(),
              orderId,
              order.codeSystem(),
              order.code(),
              order.priority(),
              order.status(),
              pseudonym,
              null,
              ""));
      if (ok) {
        events.add(
            event(
                "order.accepted",
                call,
                clinic.get(),
                call.route(),
                orderId,
                order.codeSystem(),
                order.code(),
                order.priority(),
                order.status(),
                pseudonym,
                null,
                ""));
      }
    }
  }

  private void cancel(Evidence call, List<ClinicalEvent> events) {
    BodySummary.Identifier id = call.queryIdentifier().get();
    Optional<String> clinic = BodySummary.clinicOf(id.system());
    int status = call.upstreamStatus();
    String type =
        status >= 200 && status < 300
            ? "order.cancelled"
            : status == 409 ? "order.cancel-refused" : null;
    if (clinic.isEmpty() || type == null) {
      return;
    }
    String orderId = orderId(id.value());
    Optional<OrderLinks.Link> link = links.find(clinic.get(), orderId);
    String requested = call.summary().requestStatuses().stream().findFirst().orElse(null);
    events.add(
        event(
            type,
            call,
            clinic.get(),
            link.map(OrderLinks.Link::application).orElse(call.route()),
            orderId,
            link.map(OrderLinks.Link::testCodeSystem).orElse(null),
            link.map(OrderLinks.Link::testCode).orElse(null),
            link.map(OrderLinks.Link::priority).orElse(null),
            requested,
            link.map(OrderLinks.Link::patientPseudonym).orElse(null),
            null,
            call.bodySha256()));
  }

  private void results(Evidence call, Facts facts, List<ClinicalEvent> events) {
    for (Facts.Report report : facts.reports()) {
      String type =
          "final".equals(report.status())
              ? "result.final"
              : "amended".equals(report.status()) ? "result.amended" : null;
      Optional<BodySummary.Identifier> order =
          report.basedOn().stream()
              .filter(i -> BodySummary.clinicOf(i.system()).isPresent())
              .findFirst();
      if (type == null || order.isEmpty()) {
        continue;
      }
      String clinic = BodySummary.clinicOf(order.get().system()).get();
      String orderId = orderId(order.get().value());
      Optional<OrderLinks.Link> link = links.find(clinic, orderId);
      events.add(
          event(
              type,
              call,
              clinic,
              link.map(OrderLinks.Link::application).orElse(call.sender()),
              orderId,
              report.codeSystem() != null
                  ? report.codeSystem()
                  : link.map(OrderLinks.Link::testCodeSystem).orElse(null),
              report.code() != null
                  ? report.code()
                  : link.map(OrderLinks.Link::testCode).orElse(null),
              link.map(OrderLinks.Link::priority).orElse(null),
              report.status(),
              link.map(OrderLinks.Link::patientPseudonym).orElse(null),
              null,
              call.bodySha256() + "|" + report.status()));
    }
  }

  private ClinicalEvent finance(Evidence call, Facts.FinanceDocument doc) {
    return event(
        "finance.document",
        call,
        null,
        call.sender() != null ? call.sender() : call.route(),
        null,
        null,
        null,
        null,
        null,
        null,
        new ClinicalEvent.Finance(doc.sourceKey(), doc.externalId(), doc.kind()),
        doc.sourceKey() + "|" + doc.externalId() + "|" + call.bodySha256());
  }

  private String pseudonym(String clinic, Facts facts) {
    String system = properties.mrnSystem().replace("{clinic}", clinic);
    // The first patient of the Bundle: an order Bundle is for one patient.
    return facts.patientIdentifiers().stream()
        .filter(i -> system.equals(i.system()))
        .map(BodySummary.Identifier::value)
        .findFirst()
        .map(mrn -> pseudonymizer.of(clinic, mrn))
        .orElse(null);
  }

  private static String orderId(String value) {
    return "service-request|" + value;
  }

  private static ClinicalEvent event(
      String type,
      Evidence call,
      String clinic,
      String application,
      String orderId,
      String codeSystem,
      String code,
      String priority,
      String status,
      String pseudonym,
      ClinicalEvent.Finance finance,
      String discriminator) {
    String key = type + "|" + clinic + "|" + orderId + "|" + discriminator;
    return new ClinicalEvent(
        ClinicalEvent.SCHEMA_VERSION,
        UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)),
        type,
        call.receivedAt(),
        clinic,
        application,
        orderId,
        codeSystem,
        code,
        priority,
        status,
        pseudonym,
        finance);
  }
}
