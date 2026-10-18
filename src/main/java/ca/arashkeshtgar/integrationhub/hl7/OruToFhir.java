package ca.arashkeshtgar.integrationhub.hl7;

import ca.arashkeshtgar.integrationhub.hl7.Hl7Message.Segment;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.DateTimeType;
import org.hl7.fhir.r4.model.DiagnosticReport;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.InstantType;
import org.hl7.fhir.r4.model.Observation;
import org.hl7.fhir.r4.model.Quantity;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.StringType;
import org.hl7.fhir.r4.model.Type;

/**
 * ORU^R01 (a lab result) to the FHIR transaction Bundle ClinicHub takes from LabFlow: one
 * DiagnosticReport per OBR, {@code basedOn} the ClinicHub ServiceRequest named by the placer order
 * number, and the OBXs as Observations. Segment by segment:
 *
 * <ul>
 *   <li>MSH: sender (3, 4), message type (9), control id (10), version (12), character set (18)
 *   <li>ORC/OBR: placer order number ORC-2 or OBR-2, filler order number ORC-3 or OBR-3, OBR-4 the
 *       test, OBR-7 when it was collected, OBR-22 when it was reported, OBR-25 the status
 *   <li>OBX: 2 value type, 3 the observation, 5 the value, 6 the unit, 7 the range, 8 the flags, 11
 *       the status, 14 when
 *   <li>NTE: a comment on the OBX before it, or on the OBR when it comes before any OBX
 *   <li>PID and every other segment: <b>never read</b>. No patient is named in what is forwarded;
 *       ClinicHub knows the patient from the order.
 * </ul>
 *
 * A result the hub cannot map completely is refused (so the lab and operations know), never
 * forwarded with a value missing.
 */
public final class OruToFhir {

  private static final Pattern URN_ORDER =
      Pattern.compile("^(urn:clinichub:[^:|]+:service-request)\\|(.+)$");
  private static final Pattern SLUG = Pattern.compile("^[A-Za-z0-9._-]{1,60}$");
  private static final Pattern ORDER_ID = Pattern.compile("^[A-Za-z0-9._-]{1,100}$");
  private static final Pattern NUMBER = Pattern.compile("^[+-]?(\\d+(\\.\\d*)?|\\.\\d+)$");
  private static final Pattern COMPARATOR_NUMBER =
      Pattern.compile("^(<=|>=|<|>)\\s*([+-]?(\\d+(\\.\\d*)?|\\.\\d+))$");
  private static final Pattern RANGE =
      Pattern.compile("^([+-]?\\d+(?:\\.\\d+)?)\\s*-\\s*([+-]?\\d+(?:\\.\\d+)?)$");
  private static final Pattern HALF_RANGE =
      Pattern.compile("^(<=|>=|<|>)\\s*([+-]?\\d+(?:\\.\\d+)?)$");

  private final ZoneId zone;

  public OruToFhir(ZoneId zone) {
    this.zone = zone;
  }

  /**
   * MSH-3^MSH-4 as one token safe to use in identifiers, logs and event fields: {@code
   * EXTLAB.LAB1}.
   */
  public static String senderOf(Hl7Message message) throws Hl7Exception {
    Segment msh = message.first("MSH").orElseThrow(() -> new Hl7Exception("no MSH segment"));
    String app = msh.component(3, 1);
    String facility = msh.component(4, 1);
    String sender = facility.isEmpty() ? app : app + "." + facility;
    if (!SLUG.matcher(sender).matches()) {
      throw new Hl7Exception("missing or invalid sending application");
    }
    return sender;
  }

  public Oru map(Hl7Message message) throws Hl7Exception {
    Segment msh = message.first("MSH").orElseThrow(() -> new Hl7Exception("no MSH segment"));
    String type = msh.component(9, 1) + "^" + msh.component(9, 2);
    if (!type.equals("ORU^R01")) {
      throw new Hl7Exception("unsupported message type");
    }
    String sender = senderOf(message);
    String controlId = msh.component(10, 1);
    if (controlId.isEmpty() || controlId.length() > 100) {
      throw new Hl7Exception("missing message control id");
    }

    Bundle bundle = new Bundle();
    bundle.setType(Bundle.BundleType.TRANSACTION);
    bundle.setId("hl7-" + sender + "-" + controlId.replaceAll("[^A-Za-z0-9._-]", "_"));
    List<DiagnosticReport.DiagnosticReportStatus> forwarded = new ArrayList<>();
    int seen = 0;

    Segment orc = null;
    Group group = null;
    List<Group> groups = new ArrayList<>();
    for (Segment segment : message.segments()) {
      switch (segment.name()) {
        case "ORC" -> orc = segment;
        case "OBR" -> {
          group = new Group(orc, segment);
          groups.add(group);
          orc = null;
        }
        case "OBX" -> {
          if (group == null) {
            throw new Hl7Exception("OBX before any OBR");
          }
          group.observations.add(new Obs(segment));
        }
        case "NTE" -> {
          if (group == null) {
            break;
          }
          if (group.observations.isEmpty()) {
            group.notes.add(segment);
          } else {
            group.observations.get(group.observations.size() - 1).notes.add(segment);
          }
        }
        default -> {} // PID, PV1, NK1, ...: not read
      }
    }
    if (groups.isEmpty()) {
      throw new Hl7Exception("no OBR segment");
    }

    int index = 0;
    for (Group g : groups) {
      index++;
      seen++;
      DiagnosticReport.DiagnosticReportStatus status = reportStatus(g.obr.field(25));
      if (!Hl7Tables.isForwarded(status)) {
        continue; // preliminary and the like: accepted, not forwarded
      }
      forwarded.add(status);
      bundle
          .addEntry()
          .setFullUrl("urn:uuid:" + uuid(controlId, index, 0))
          .setResource(report(g, status, sender, controlId, index, bundle))
          .getRequest()
          .setMethod(Bundle.HTTPVerb.POST)
          .setUrl("DiagnosticReport");
    }
    return new Oru(sender, controlId, bundle, forwarded, seen);
  }

  private DiagnosticReport report(
      Group g,
      DiagnosticReport.DiagnosticReportStatus status,
      String sender,
      String controlId,
      int index,
      Bundle bundle)
      throws Hl7Exception {
    DiagnosticReport report = new DiagnosticReport();
    report.setId(uuid(controlId, index, 0).toString());
    report.setStatus(status);

    Segment first = g.orc != null && !g.orc.field(2).isEmpty() ? g.orc : g.obr;
    report.addBasedOn(placerOrder(first, g.obr));
    String filler = ei(g.orc != null && !g.orc.field(3).isEmpty() ? g.orc : g.obr, 3);
    if (!filler.isEmpty()) {
      report.addIdentifier(
          new Identifier()
              .setSystem("urn:integrationhub:hl7:" + sender + ":filler-order")
              .setValue(filler));
    }
    report.setCode(codeable(g.obr, 4, sender));
    Hl7Time.toFhir(g.obr.field(7), zone).ifPresent(t -> report.setEffective(new DateTimeType(t)));
    Hl7Time.toFhir(g.obr.field(22), zone)
        .filter(t -> t.length() > 10)
        .ifPresent(t -> report.setIssuedElement(new InstantType(t)));
    if (!g.notes.isEmpty()) {
      report.setConclusion(notes(g.notes));
    }

    int n = 0;
    for (Obs obs : g.observations) {
      n++;
      String id = uuid(controlId, index, n).toString();
      Observation observation = observation(obs, g, sender, id);
      bundle
          .addEntry()
          .setFullUrl("urn:uuid:" + id)
          .setResource(observation)
          .getRequest()
          .setMethod(Bundle.HTTPVerb.POST)
          .setUrl("Observation");
      report.addResult(new Reference("urn:uuid:" + id));
    }
    return report;
  }

  /**
   * The ServiceRequest the result answers. Two forms are read: the order id in the first component
   * and the clinic in the second ({@code 101^harbourview}), or the whole ClinicHub identifier in
   * the first ({@code urn:clinichub:harbourview:service-request|101}, escaped as HL7 requires).
   */
  Reference placerOrder(Segment source, Segment obr) throws Hl7Exception {
    String id = ei(source, 2);
    String namespace = source.component(2, 2);
    if (id.isEmpty() && source != obr) {
      id = ei(obr, 2);
      namespace = obr.component(2, 2);
    }
    if (id.isEmpty()) {
      throw new Hl7Exception("no placer order number");
    }
    Matcher urn = URN_ORDER.matcher(id);
    if (urn.matches() && ORDER_ID.matcher(urn.group(2)).matches()) {
      return identifierReference(urn.group(1), urn.group(2));
    }
    if (SLUG.matcher(namespace).matches() && ORDER_ID.matcher(id).matches()) {
      return identifierReference("urn:clinichub:" + namespace + ":service-request", id);
    }
    throw new Hl7Exception("placer order number is not a ClinicHub order");
  }

  private static Reference identifierReference(String system, String value) {
    return new Reference().setIdentifier(new Identifier().setSystem(system).setValue(value));
  }

  private static String ei(Segment segment, int field) {
    return segment.component(field, 1);
  }

  private Observation observation(Obs obs, Group group, String sender, String id)
      throws Hl7Exception {
    Segment x = obs.segment;
    Observation observation = new Observation();
    observation.setId(id);
    String statusCode = x.field(11);
    Observation.ObservationStatus status = Hl7Tables.OBSERVATION_STATUS.get(statusCode);
    observation.setStatus(status == null ? Observation.ObservationStatus.UNKNOWN : status);
    if (x.component(3, 1).isEmpty() && x.component(3, 2).isEmpty()) {
      throw new Hl7Exception("OBX without an observation identifier");
    }
    observation.setCode(codeable(x, 3, sender));
    observation.setValue(value(x, sender));

    for (String flag : x.repetitions(8)) {
      String code = x.componentOf(flag, 1).trim();
      if (code.isEmpty()) {
        continue;
      }
      CodeableConcept interpretation = new CodeableConcept();
      if (Hl7Tables.interpretationFlag(code).isPresent()) {
        interpretation.addCoding().setSystem(Hl7Tables.INTERPRETATION_SYSTEM).setCode(code);
      } else {
        interpretation.setText(code);
      }
      observation.addInterpretation(interpretation);
    }
    String range = x.unescapedField(7).trim();
    if (!range.isEmpty()) {
      observation.addReferenceRange(referenceRange(range, x));
    }
    String when = Hl7Time.toFhir(x.field(14), zone).orElse(null);
    if (when == null) {
      when = Hl7Time.toFhir(group.obr.field(7), zone).orElse(null);
    }
    if (when != null) {
      observation.setEffective(new DateTimeType(when));
    }
    if (!obs.notes.isEmpty()) {
      observation.addNote().setText(notes(obs.notes));
    }
    return observation;
  }

  private Type value(Segment x, String sender) throws Hl7Exception {
    String type = x.field(2).trim();
    List<String> values = x.repetitions(5);
    switch (type) {
      case "NM" -> {
        String text = single(values, x);
        Matcher cmp = COMPARATOR_NUMBER.matcher(text);
        Quantity quantity = new Quantity();
        if (cmp.matches()) {
          quantity.setComparator(Quantity.QuantityComparator.fromCode(cmp.group(1)));
          quantity.setValue(new BigDecimal(cmp.group(2)));
        } else if (NUMBER.matcher(text).matches()) {
          quantity.setValue(new BigDecimal(text));
        } else {
          throw new Hl7Exception("OBX numeric value is not a number");
        }
        return withUnit(quantity, x);
      }
      case "SN" -> {
        String raw = single(values, x);
        String comparator = x.componentOf(raw, 1);
        String number = x.componentOf(raw, 2);
        String separator = x.componentOf(raw, 3);
        if (!separator.isEmpty()
            || !x.componentOf(raw, 4).isEmpty()
            || !NUMBER.matcher(number).matches()) {
          throw new Hl7Exception("unsupported structured numeric OBX value");
        }
        Quantity quantity = new Quantity().setValue(new BigDecimal(number));
        if (!comparator.isEmpty()) {
          try {
            quantity.setComparator(Quantity.QuantityComparator.fromCode(comparator));
          } catch (Exception e) {
            throw new Hl7Exception("unsupported structured numeric OBX value");
          }
        }
        return withUnit(quantity, x);
      }
      case "ST", "TX", "FT" -> {
        StringBuilder text = new StringBuilder();
        for (String repetition : values) {
          if (text.length() > 0) {
            text.append('\n');
          }
          text.append(
              x.componentOf(repetition, 1).isEmpty() && repetition.contains("\\")
                  ? repetition
                  : unescapeWhole(x, repetition));
        }
        return new StringType(text.toString());
      }
      case "CE", "CWE", "CNE" -> {
        String raw = single(values, x);
        CodeableConcept concept = new CodeableConcept();
        addCoding(
            concept, x.componentOf(raw, 1), x.componentOf(raw, 2), x.componentOf(raw, 3), sender);
        addCoding(
            concept, x.componentOf(raw, 4), x.componentOf(raw, 5), x.componentOf(raw, 6), sender);
        concept.setText(
            x.componentOf(raw, 2).isEmpty() ? x.componentOf(raw, 1) : x.componentOf(raw, 2));
        return concept;
      }
      default -> throw new Hl7Exception("unsupported OBX value type");
    }
  }

  private static String unescapeWhole(Segment x, String repetition) {
    // ST/TX/FT: the whole repetition is text; a component separator inside it is data only if
    // escaped, so the
    // first component is the text.
    return x.componentOf(repetition, 1);
  }

  private static String single(List<String> values, Segment x) throws Hl7Exception {
    if (values.isEmpty()) {
      throw new Hl7Exception("OBX without a value");
    }
    if (values.size() > 1) {
      throw new Hl7Exception("repeating OBX value");
    }
    String raw = values.get(0);
    return raw.contains("^")
        ? raw
        : x.componentOf(raw, 1).isEmpty() ? raw : x.componentOf(raw, 1).trim();
  }

  private Quantity withUnit(Quantity quantity, Segment x) {
    String unit = x.component(6, 1);
    String text = x.component(6, 2);
    String system = x.component(6, 3);
    if (!unit.isEmpty()) {
      quantity.setUnit(text.isEmpty() ? unit : text).setCode(unit);
      if (!system.isEmpty() && Hl7Tables.CODE_SYSTEM.containsKey(system.toUpperCase())) {
        quantity.setSystem(Hl7Tables.CODE_SYSTEM.get(system.toUpperCase()));
      } else if (system.isEmpty() || "ISO+".equalsIgnoreCase(system)) {
        quantity.setSystem("http://unitsofmeasure.org");
      }
    }
    return quantity;
  }

  private org.hl7.fhir.r4.model.Observation.ObservationReferenceRangeComponent referenceRange(
      String range, Segment x) {
    var component = new Observation.ObservationReferenceRangeComponent();
    component.setText(range);
    Matcher both = RANGE.matcher(range);
    Matcher half = HALF_RANGE.matcher(range);
    String unit = x.component(6, 1);
    if (both.matches()) {
      component.setLow(rangeQuantity(both.group(1), unit));
      component.setHigh(rangeQuantity(both.group(2), unit));
    } else if (half.matches()) {
      Quantity q = rangeQuantity(half.group(2), unit);
      if (half.group(1).startsWith("<")) {
        component.setHigh(q);
      } else {
        component.setLow(q);
      }
    }
    return component;
  }

  private static Quantity rangeQuantity(String number, String unit) {
    Quantity q = new Quantity().setValue(new BigDecimal(number));
    if (!unit.isEmpty()) {
      q.setUnit(unit).setCode(unit).setSystem("http://unitsofmeasure.org");
    }
    return q;
  }

  private CodeableConcept codeable(Segment segment, int field, String sender) throws Hl7Exception {
    CodeableConcept concept = new CodeableConcept();
    addCoding(
        concept,
        segment.component(field, 1),
        segment.component(field, 2),
        segment.component(field, 3),
        sender);
    addCoding(
        concept,
        segment.component(field, 4),
        segment.component(field, 5),
        segment.component(field, 6),
        sender);
    if (!concept.hasCoding()) {
      throw new Hl7Exception("missing test identifier");
    }
    String text = segment.component(field, 2);
    if (!text.isEmpty()) {
      concept.setText(text);
    }
    return concept;
  }

  private static void addCoding(
      CodeableConcept concept, String code, String display, String system, String sender) {
    if (code.isEmpty()) {
      return;
    }
    Coding coding = concept.addCoding().setCode(code).setSystem(Hl7Tables.systemOf(system, sender));
    if (!display.isEmpty()) {
      coding.setDisplay(display);
    }
  }

  static DiagnosticReport.DiagnosticReportStatus reportStatus(String code) throws Hl7Exception {
    DiagnosticReport.DiagnosticReportStatus status = Hl7Tables.REPORT_STATUS.get(code.trim());
    if (status == null) {
      throw new Hl7Exception(code.isBlank() ? "no result status" : "unknown result status");
    }
    return status;
  }

  private static String notes(List<Segment> notes) {
    StringBuilder text = new StringBuilder();
    for (Segment nte : notes) {
      for (String repetition : nte.repetitions(3)) {
        if (text.length() > 0) {
          text.append('\n');
        }
        text.append(nte.componentOf(repetition, 1));
      }
    }
    return text.toString();
  }

  private static UUID uuid(String controlId, int report, int observation) {
    return UUID.nameUUIDFromBytes(
        (controlId + "|" + report + "|" + observation).getBytes(StandardCharsets.UTF_8));
  }

  private static final class Group {
    final Segment orc;
    final Segment obr;
    final List<Obs> observations = new ArrayList<>();
    final List<Segment> notes = new ArrayList<>();

    Group(Segment orc, Segment obr) {
      this.orc = orc;
      this.obr = obr;
    }
  }

  private static final class Obs {
    final Segment segment;
    final List<Segment> notes = new ArrayList<>();

    Obs(Segment segment) {
      this.segment = segment;
    }
  }
}
