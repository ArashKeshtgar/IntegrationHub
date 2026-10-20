package ca.arashkeshtgar.integrationhub.hl7;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Enumerations;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Practitioner;
import org.hl7.fhir.r4.model.ServiceRequest;

/**
 * An order Bundle from ClinicHub (the Patient, the Practitioner and one or more ServiceRequests, or
 * a cancellation: one ServiceRequest with status {@code revoked}) as one ORM^O01 for an external
 * laboratory: one ORC/OBR group per ServiceRequest, {@code ORC-1} NW or CA.
 *
 * <p>The message carries what a lab needs to identify the patient and the orderer (name, birth
 * date, sex, MRN, health card number; the CPSO number), because that is the purpose of an order.
 * The hub does not keep the message or write it to a log; it is built, sent and dropped. The placer
 * order number in ORC-2 and OBR-2 is {@code {order id}^{clinic slug}}, which is what the lab quotes
 * back in its result (see {@link OruToFhir}).
 *
 * <p>A Bundle with no ServiceRequest that names a ClinicHub order is refused as a whole, never sent
 * with a hole in it.
 */
public final class FhirToOrm {

  private static final Pattern ORDER = Pattern.compile("^urn:clinichub:([^:|]+):service-request$");
  private static final Pattern MRN = Pattern.compile("^urn:clinichub:([^:|]+):mrn$");
  private static final String HEALTH_CARD =
      "https://fhir.infoway-inforoute.ca/NamingSystem/ca-on-patient-hcn";
  private static final String CPSO =
      "https://fhir.infoway-inforoute.ca/NamingSystem/ca-on-license-physician";

  /** {@code orders}: the ClinicHub order ids in the message, in order; {@code cancel}: ORC-1 CA. */
  public record Message(String text, String controlId, List<String> orders, boolean cancel) {}

  private final Hl7Properties.Lab settings;

  public FhirToOrm(Hl7Properties.Lab settings) {
    this.settings = settings;
  }

  public Message convert(Bundle bundle, String callId, Instant now) throws Hl7Exception {
    Patient patient = null;
    Practitioner practitioner = null;
    List<ServiceRequest> requests = new ArrayList<>();
    for (Bundle.BundleEntryComponent entry : bundle.getEntry()) {
      if (entry.getResource() instanceof Patient p) {
        patient = p;
      } else if (entry.getResource() instanceof Practitioner p) {
        practitioner = p;
      } else if (entry.getResource() instanceof ServiceRequest r) {
        requests.add(r);
      }
    }
    if (requests.isEmpty()) {
      throw new Hl7Exception("no ServiceRequest");
    }
    boolean cancel = requests.get(0).getStatus() == ServiceRequest.ServiceRequestStatus.REVOKED;
    String controlId = "O" + callId.replace("-", "").substring(0, 16);

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
            + "||ORM^O01|"
            + controlId
            + "|P|2.5||||||UNICODE UTF-8");
    String clinic = clinicOf(requests.get(0));
    segments.add(pid(patient, requests.get(0), clinic));

    List<String> orders = new ArrayList<>();
    int n = 0;
    for (ServiceRequest r : requests) {
      Identifier id = orderIdentifier(r);
      String order = esc(id.getValue()) + "^" + esc(clinicOf(r));
      n++;
      orders.add(id.getValue());
      String orderer = orderer(practitioner);
      segments.add(
          "ORC|"
              + (cancel ? "CA" : "NW")
              + "|"
              + order
              + "||"
              + (r.hasRequisition() ? esc(r.getRequisition().getValue()) : "")
              + "|||||"
              + (r.hasAuthoredOn() ? Hl7Time.fromInstant(r.getAuthoredOn().toInstant()) : "")
              + "|||"
              + orderer);
      segments.add(
          "OBR|"
              + n
              + "|"
              + order
              + "||"
              + coded(r)
              + "||||||||||||"
              + orderer
              + "|".repeat(11)
              + "^^^^^"
              + priority(r));
      if (r.hasNote() && r.getNoteFirstRep().hasText()) {
        segments.add("NTE|1||" + esc(r.getNoteFirstRep().getText()));
      }
    }
    return new Message(String.join("\r", segments) + "\r", controlId, orders, cancel);
  }

  private static String pid(Patient patient, ServiceRequest first, String clinic)
      throws Hl7Exception {
    List<String> ids = new ArrayList<>();
    String name = "";
    String birth = "";
    String sex = "U";
    if (patient != null) {
      for (Identifier i : patient.getIdentifier()) {
        Matcher m = MRN.matcher(String.valueOf(i.getSystem()));
        if (m.matches() && i.hasValue()) {
          ids.add(esc(i.getValue()) + "^^^" + esc(m.group(1)) + "^MR");
        } else if (HEALTH_CARD.equals(i.getSystem()) && i.hasValue()) {
          ids.add(esc(i.getValue()) + "^^^CANON^JHN");
        }
      }
      if (patient.hasName()) {
        var n = patient.getNameFirstRep();
        name = esc(n.getFamily()) + "^" + esc(n.hasGiven() ? n.getGiven().get(0).getValue() : "");
      }
      if (patient.hasBirthDate()) {
        birth = patient.getBirthDateElement().getValueAsString().replace("-", "");
      }
      sex = sex(patient.getGender());
    } else if (first.getSubject().hasIdentifier()) {
      // A cancellation names the patient by MRN only.
      Identifier i = first.getSubject().getIdentifier();
      Matcher m = MRN.matcher(String.valueOf(i.getSystem()));
      if (m.matches() && i.hasValue()) {
        ids.add(esc(i.getValue()) + "^^^" + esc(m.group(1)) + "^MR");
      }
    }
    if (ids.isEmpty()) {
      throw new Hl7Exception("no patient identifier");
    }
    return "PID|1||" + String.join("~", ids) + "||" + name + "||" + birth + "|" + sex;
  }

  private static String orderer(Practitioner p) {
    if (p == null) {
      return "";
    }
    String id =
        p.getIdentifier().stream()
            .filter(i -> CPSO.equals(i.getSystem()) && i.hasValue())
            .map(i -> esc(i.getValue()))
            .findFirst()
            .orElse("");
    var n = p.getNameFirstRep();
    return id
        + "^"
        + esc(n.getFamily())
        + "^"
        + esc(n.hasGiven() ? n.getGiven().get(0).getValue() : "")
        + (id.isEmpty() ? "" : "^^^^^^CPSO");
  }

  private static String coded(ServiceRequest r) throws Hl7Exception {
    var coding = r.getCode().getCodingFirstRep();
    if (!coding.hasCode()) {
      throw new Hl7Exception("missing code");
    }
    String display = coding.hasDisplay() ? coding.getDisplay() : r.getCode().getText();
    String system = Hl7Tables.nameOfSystem(coding.getSystem()).orElse("L");
    return esc(coding.getCode()) + "^" + esc(display) + "^" + system;
  }

  private static String priority(ServiceRequest r) {
    if (r.getPriority() == null) {
      return "R";
    }
    return switch (r.getPriority()) {
      case STAT -> "S";
      case URGENT, ASAP -> "A";
      default -> "R";
    };
  }

  private static String sex(Enumerations.AdministrativeGender gender) {
    if (gender == null) {
      return "U";
    }
    return switch (gender) {
      case MALE -> "M";
      case FEMALE -> "F";
      case OTHER -> "O";
      default -> "U";
    };
  }

  private static Identifier orderIdentifier(ServiceRequest r) throws Hl7Exception {
    for (Identifier i : r.getIdentifier()) {
      if (ORDER.matcher(String.valueOf(i.getSystem())).matches() && i.hasValue()) {
        return i;
      }
    }
    throw new Hl7Exception("no ClinicHub order");
  }

  private static String clinicOf(ServiceRequest r) throws Hl7Exception {
    Matcher m = ORDER.matcher(String.valueOf(orderIdentifier(r).getSystem()));
    if (!m.matches()) {
      throw new Hl7Exception("no ClinicHub order");
    }
    return m.group(1);
  }

  private static String esc(String text) {
    return Hl7Message.escapeStandard(text == null ? "" : text);
  }
}
