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
 * Prescriptions and patient merges make no event (a drug says too much about a patient). A merge
 * answered 2xx moves the pseudonym remembered with the merged patient's orders to the survivor's,
 * so later results of those orders carry the survivor's pseudonym.
 *
 * <p>Event ids are derived from the content, so the same call sent twice is one event.
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

  /**
   * A patient merged into another: the pseudonym of the merged record is replaced by the
   * survivor's.
   */
  public record PseudonymMove(String clinicSlug, String from, String to) {}

  public record Result(
      List<ClinicalEvent> events, List<OrderLinks.Link> links, List<PseudonymMove> moves) {
    public static final Result NONE = new Result(List.of(), List.of(), List.of());

    public Result(List<ClinicalEvent> events, List<OrderLinks.Link> links) {
      this(events, links, List.of());
    }
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
    List<PseudonymMove> moves = new ArrayList<>();

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
      cancel(
          call,
          call.queryIdentifier().get(),
          call.summary().requestStatuses().stream().findFirst().orElse(null),
          events);
    }
    if (ok && "POST".equals(call.method())) {
      merges(facts, moves);
    }
    return events.isEmpty() && newLinks.isEmpty() && moves.isEmpty()
        ? Result.NONE
        : new Result(events, newLinks, moves);
  }

  /** Patient merges: the clinic comes from the MRN system, the pseudonyms from the two MRNs. */
  private void merges(Facts facts, List<PseudonymMove> moves) {
    for (Facts.PatientMerge merge : facts.merges()) {
      Optional<String> clinic = clinicOfMrnSystem(merge.system());
      if (clinic.isEmpty() || merge.mergedMrn().equals(merge.survivorMrn())) {
        continue;
      }
      moves.add(
          new PseudonymMove(
              clinic.get(),
              pseudonymizer.of(clinic.get(), merge.mergedMrn()),
              pseudonymizer.of(clinic.get(), merge.survivorMrn())));
    }
  }

  /** The clinic slug in an MRN system such as {@code urn:clinichub:harbourview:mrn}. */
  private Optional<String> clinicOfMrnSystem(String system) {
    String template = properties.mrnSystem();
    int at = template.indexOf("{clinic}");
    if (at < 0) {
      return Optional.empty();
    }
    String prefix = template.substring(0, at);
    String suffix = template.substring(at + "{clinic}".length());
    if (!system.startsWith(prefix)
        || !system.endsWith(suffix)
        || system.length() <= prefix.length() + suffix.length()) {
      return Optional.empty();
    }
    String clinic = system.substring(prefix.length(), system.length() - suffix.length());
    return clinic.matches("[^:|]+") ? Optional.of(clinic) : Optional.empty();
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
      // ClinicHub sends a cancel as a transaction Bundle whose entry is the ServiceRequest again,
      // status revoked (a conditional PUT inside the Bundle): a cancel, not a new order.
      if ("revoked".equals(order.status())) {
        cancel(call, order.identifier(), order.status(), events);
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

  private void cancel(
      Evidence call, BodySummary.Identifier id, String requested, List<ClinicalEvent> events) {
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
