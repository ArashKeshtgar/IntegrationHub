package ca.arashkeshtgar.integrationhub.hl7;

import java.util.List;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.DiagnosticReport;

/** What came out of mapping one ORU^R01. */
public record Oru(
    String sender,
    String controlId,
    Bundle bundle,
    List<DiagnosticReport.DiagnosticReportStatus> forwardedStatuses,
    int reportsSeen) {

  /** At least one report in a state ClinicHub takes. */
  public boolean hasForwardable() {
    return !forwardedStatuses.isEmpty();
  }
}
