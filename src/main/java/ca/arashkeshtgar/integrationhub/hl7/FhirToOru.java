package ca.arashkeshtgar.integrationhub.hl7;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.DiagnosticReport;
import org.hl7.fhir.r4.model.Observation;
import org.hl7.fhir.r4.model.Quantity;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.Resource;

/**
 * The reverse of {@link OruToFhir}, for the copy sent to an OLIS-style receiver: a result Bundle
 * that went to ClinicHub becomes one ORU^R01 per DiagnosticReport in a state the receiver takes
 * (final, amended, cancelled).
 *
 * <p>The patient in the message is only the pseudonym (PID-3), looked up from the order; there is
 * no name, birth date or health card number because the hub does not hold them. A real OLIS needs
 * those, so this export is for the simulator until that is decided (docs/architecture.md, phase 4,
 * "Not built").
 *
 * <p>A report the mapping cannot express completely (a value type that is not numeric, text or
 * coded; no ClinicHub order to name) is skipped as a whole, never sent with a hole in it.
 */
public final class FhirToOru {

  private static final Pattern ORDER = Pattern.compile("^urn:clinichub:([^:|]+):service-request$");

  /**
   * {@code message} is the ORU text; {@code controlId} its MSH-10; {@code reportId} the
   * DiagnosticReport id.
   */
  public record Message(String reportId, String controlId, String text) {}

  public record Result(List<Message> messages, int skipped) {}

  private final Hl7Properties.Outbound settings;
  private final BiFunction<String, String, Optional<String>> pseudonyms;

  /**
   * {@code pseudonyms}: (clinic slug, order id) to the patient pseudonym of the order, if known.
   */
  public FhirToOru(
      Hl7Properties.Outbound settings, BiFunction<String, String, Optional<String>> pseudonyms) {
    this.settings = settings;
    this.pseudonyms = pseudonyms;
  }

  public Result convert(Bundle bundle, String callId, Instant now) {
    Map<String, Observation> observations = new HashMap<>();
    List<DiagnosticReport> reports = new ArrayList<>();
    for (Bundle.BundleEntryComponent entry : bundle.getEntry()) {
      Resource resource = entry.getResource();
      if (resource instanceof Observation o) {
        if (entry.hasFullUrl()) {
          observations.put(entry.getFullUrl(), o);
        }
        if (o.hasIdElement() && o.getIdElement().hasIdPart()) {
          observations.put("Observation/" + o.getIdElement().getIdPart(), o);
        }
      } else if (resource instanceof DiagnosticReport r) {
        reports.add(r);
      }
    }
    List<Message> out = new ArrayList<>();
    int skipped = 0;
    int index = 0;
    for (DiagnosticReport report : reports) {
      index++;
      Optional<String> status = Hl7Tables.reportStatusCode(report.getStatus());
      if (status.isEmpty()) {
        continue; // not a state the receiver takes: not an error, not exported
      }
      try {
        String controlId = "H" + callId.replace("-", "").substring(0, 16) + "-" + index;
        out.add(
            new Message(
                report.getIdElement().getIdPart(),
                controlId,
                message(report, status.get(), observations, controlId, now)));
      } catch (Hl7Exception e) {
        skipped++;
      }
    }
    return new Result(out, skipped);
  }

  private String message(
      DiagnosticReport report,
      String status,
      Map<String, Observation> observations,
      String controlId,
      Instant now)
      throws Hl7Exception {
    String clinic = null;
    String orderId = null;
    for (Reference ref : report.getBasedOn()) {
      if (ref.hasIdentifier()) {
        Matcher m = ORDER.matcher(String.valueOf(ref.getIdentifier().getSystem()));
        if (m.matches() && ref.getIdentifier().hasValue()) {
          clinic = m.group(1);
          orderId = ref.getIdentifier().getValue();
          break;
        }
      }
    }
    if (clinic == null) {
      throw new Hl7Exception("no ClinicHub order");
    }
    String pseudonym = pseudonyms.apply(clinic, orderId).orElse("UNKNOWN");
    String order = esc(orderId) + "^" + esc(clinic);

    List<String> segments = new ArrayList<>();
    segments.add(
        "MSH|^~\\&|"
            + esc(settings.sendingApplication())
            + "|"
            + esc(settings.sendingFacility())
            + "|"
            + esc(settings.receivingApplication())
            + "|"
            + esc(settings.receivingFacility())
            + "|"
            + Hl7Time.fromInstant(now)
            + "||ORU^R01|"
            + controlId
            + "|P|2.5.1");
    segments.add("PID|1||" + esc(pseudonym) + "^^^PSEUDONYM");
    segments.add("ORC|RE|" + order);
    String effective =
        report.hasEffectiveDateTimeType()
            ? Hl7Time.fromFhir(report.getEffectiveDateTimeType().getValueAsString()).orElse("")
            : "";
    String issued =
        report.hasIssued()
            ? Hl7Time.fromFhir(report.getIssuedElement().getValueAsString()).orElse("")
            : "";
    segments.add(
        "OBR|1|"
            + order
            + "||"
            + coded(report.getCode())
            + "|||"
            + effective
            + "|".repeat(15)
            + issued
            + "|||"
            + status);
    if (report.hasConclusion()) {
      segments.add("NTE|1||" + esc(report.getConclusion()));
    }
    int n = 0;
    for (Reference ref : report.getResult()) {
      Observation o = observations.get(ref.getReference());
      if (o == null) {
        throw new Hl7Exception("result not in the bundle");
      }
      n++;
      segments.add(obx(n, o));
      for (var note : o.getNote()) {
        segments.add("NTE|1||" + esc(note.getText()));
      }
    }
    return String.join("\r", segments) + "\r";
  }

  private String obx(int n, Observation o) throws Hl7Exception {
    String type;
    String value;
    String unit = "";
    if (o.hasValueQuantity()) {
      Quantity q = o.getValueQuantity();
      type = "NM";
      value = (q.hasComparator() ? q.getComparator().toCode() : "") + q.getValue().toPlainString();
      if (q.hasCode()) {
        unit = esc(q.getCode()) + "^^UCUM";
      }
    } else if (o.hasValueStringType()) {
      type = "ST";
      value = esc(o.getValueStringType().getValue());
    } else if (o.hasValueCodeableConcept()) {
      type = "CE";
      value = coded(o.getValueCodeableConcept());
    } else {
      throw new Hl7Exception("unsupported value");
    }
    String flags =
        String.join(
            "~",
            o.getInterpretation().stream()
                .map(
                    c ->
                        c.getCodingFirstRep().hasCode() ? esc(c.getCodingFirstRep().getCode()) : "")
                .filter(s -> !s.isEmpty())
                .toList());
    String range =
        o.hasReferenceRange() && o.getReferenceRangeFirstRep().hasText()
            ? esc(o.getReferenceRangeFirstRep().getText())
            : "";
    String status = Hl7Tables.observationStatusCode(o.getStatus()).orElse("F");
    String when =
        o.hasEffectiveDateTimeType()
            ? Hl7Time.fromFhir(o.getEffectiveDateTimeType().getValueAsString()).orElse("")
            : "";
    return "OBX|"
        + n
        + "|"
        + type
        + "|"
        + coded(o.getCode())
        + "||"
        + value
        + "|"
        + unit
        + "|"
        + range
        + "|"
        + flags
        + "|||"
        + status
        + "|||"
        + when;
  }

  private static String coded(CodeableConcept concept) throws Hl7Exception {
    Coding coding = concept.getCodingFirstRep();
    if (!coding.hasCode()) {
      throw new Hl7Exception("missing code");
    }
    String display =
        coding.hasDisplay()
            ? coding.getDisplay()
            : concept.getText() == null ? "" : concept.getText();
    String system = Hl7Tables.nameOfSystem(coding.getSystem()).orElse("L");
    return esc(coding.getCode()) + "^" + esc(display) + "^" + system;
  }

  private static String esc(String text) {
    return Hl7Message.escapeStandard(text == null ? "" : text);
  }
}
