package ca.arashkeshtgar.integrationhub.hl7;

import java.util.Map;
import java.util.Optional;
import org.hl7.fhir.r4.model.DiagnosticReport;
import org.hl7.fhir.r4.model.Observation;

/**
 * The mapping tables between HL7 v2 and FHIR R4, in one place so each can be read and tested on its
 * own. Where HL7 and FHIR do not line up the choice is written next to the entry.
 */
public final class Hl7Tables {

  private Hl7Tables() {}

  // ---- HL7 table 0123, OBR-25 result status -> DiagnosticReport.status
  // ----------------------------

  public static final Map<String, DiagnosticReport.DiagnosticReportStatus> REPORT_STATUS =
      Map.ofEntries(
          Map.entry("F", DiagnosticReport.DiagnosticReportStatus.FINAL),
          // C: record coming over is a correction: the platform's word for a changed final is
          // "amended"
          Map.entry("C", DiagnosticReport.DiagnosticReportStatus.AMENDED),
          Map.entry("X", DiagnosticReport.DiagnosticReportStatus.CANCELLED),
          Map.entry("P", DiagnosticReport.DiagnosticReportStatus.PRELIMINARY),
          Map.entry("A", DiagnosticReport.DiagnosticReportStatus.PARTIAL),
          // R: results stored, not yet verified
          Map.entry("R", DiagnosticReport.DiagnosticReportStatus.PRELIMINARY),
          Map.entry("O", DiagnosticReport.DiagnosticReportStatus.REGISTERED),
          Map.entry("I", DiagnosticReport.DiagnosticReportStatus.REGISTERED),
          Map.entry("S", DiagnosticReport.DiagnosticReportStatus.REGISTERED),
          Map.entry("Y", DiagnosticReport.DiagnosticReportStatus.UNKNOWN));

  /**
   * Only these are part of what ClinicHub takes (docs/contracts.md): final, then amended; cancelled
   * cancels.
   */
  public static boolean isForwarded(DiagnosticReport.DiagnosticReportStatus status) {
    return status == DiagnosticReport.DiagnosticReportStatus.FINAL
        || status == DiagnosticReport.DiagnosticReportStatus.AMENDED
        || status == DiagnosticReport.DiagnosticReportStatus.CANCELLED;
  }

  /** The reverse, for the OLIS-style export: what the report status is called in OBR-25. */
  public static Optional<String> reportStatusCode(DiagnosticReport.DiagnosticReportStatus status) {
    return switch (status) {
      case FINAL -> Optional.of("F");
      case AMENDED, CORRECTED, APPENDED -> Optional.of("C");
      case CANCELLED, ENTEREDINERROR -> Optional.of("X");
      default -> Optional.empty();
    };
  }

  // ---- HL7 table 0085, OBX-11 observation result status -> Observation.status
  // ----------------------

  public static final Map<String, Observation.ObservationStatus> OBSERVATION_STATUS =
      Map.ofEntries(
          Map.entry("F", Observation.ObservationStatus.FINAL),
          Map.entry(
              "U",
              Observation.ObservationStatus.FINAL), // status change to final without retransmitting
          Map.entry("C", Observation.ObservationStatus.AMENDED),
          Map.entry("X", Observation.ObservationStatus.CANCELLED),
          Map.entry("P", Observation.ObservationStatus.PRELIMINARY),
          Map.entry("R", Observation.ObservationStatus.PRELIMINARY),
          Map.entry("D", Observation.ObservationStatus.ENTEREDINERROR),
          Map.entry("W", Observation.ObservationStatus.ENTEREDINERROR),
          Map.entry("O", Observation.ObservationStatus.REGISTERED),
          Map.entry("I", Observation.ObservationStatus.REGISTERED),
          Map.entry("S", Observation.ObservationStatus.REGISTERED));

  public static Optional<String> observationStatusCode(Observation.ObservationStatus status) {
    return switch (status) {
      case FINAL -> Optional.of("F");
      case AMENDED, CORRECTED -> Optional.of("C");
      case CANCELLED -> Optional.of("X");
      case PRELIMINARY -> Optional.of("P");
      case ENTEREDINERROR -> Optional.of("W");
      default -> Optional.empty();
    };
  }

  // ---- HL7 table 0078, OBX-8 abnormal flags -> v3 ObservationInterpretation
  // ------------------------

  public static final String INTERPRETATION_SYSTEM =
      "http://terminology.hl7.org/CodeSystem/v3-ObservationInterpretation";

  /**
   * A flag that has no entry here is kept as text, not dropped: a lost "critical" flag is worse
   * than an odd one.
   */
  public static final Map<String, String> ABNORMAL_FLAG =
      Map.ofEntries(
          Map.entry("N", "N"),
          Map.entry("L", "L"),
          Map.entry("H", "H"),
          Map.entry("LL", "LL"),
          Map.entry("HH", "HH"),
          Map.entry("<", "<"),
          Map.entry(">", ">"),
          Map.entry("A", "A"),
          Map.entry("AA", "AA"),
          Map.entry("U", "U"),
          Map.entry("D", "D"),
          Map.entry("B", "B"),
          Map.entry("W", "W"),
          Map.entry("S", "S"),
          Map.entry("R", "R"),
          Map.entry("I", "I"),
          Map.entry("MS", "MS"),
          Map.entry("VS", "VS"),
          Map.entry("POS", "POS"),
          Map.entry("NEG", "NEG"),
          Map.entry("IND", "IND"));

  // ---- coding systems (the third component of a CE/CWE) -> FHIR system URIs
  // ----------------------

  public static final Map<String, String> CODE_SYSTEM =
      Map.ofEntries(
          Map.entry("LN", "http://loinc.org"),
          Map.entry("LOINC", "http://loinc.org"),
          Map.entry("SCT", "http://snomed.info/sct"),
          Map.entry("SNM", "http://snomed.info/sct"),
          Map.entry("SNOMED", "http://snomed.info/sct"),
          Map.entry("UCUM", "http://unitsofmeasure.org"),
          Map.entry("ISO+", "http://unitsofmeasure.org"),
          Map.entry("I10", "http://hl7.org/fhir/sid/icd-10"));

  /**
   * The FHIR system of a coding system name; a name that is not known is local to the sending lab.
   */
  public static String systemOf(String name, String sender) {
    if (name == null || name.isBlank()) {
      return "urn:integrationhub:hl7:" + sender + ":local";
    }
    String known = CODE_SYSTEM.get(name.trim().toUpperCase());
    return known != null ? known : "urn:integrationhub:hl7:" + sender + ":" + name.trim();
  }

  /** The reverse: the HL7 name of a FHIR system URI, or empty when there is none. */
  public static Optional<String> nameOfSystem(String uri) {
    if ("http://loinc.org".equals(uri)) {
      return Optional.of("LN");
    }
    if ("http://snomed.info/sct".equals(uri)) {
      return Optional.of("SCT");
    }
    if ("http://unitsofmeasure.org".equals(uri)) {
      return Optional.of("UCUM");
    }
    return Optional.empty();
  }

  public static Optional<String> interpretationFlag(String code) {
    return Optional.ofNullable(ABNORMAL_FLAG.get(code));
  }
}
