package ca.arashkeshtgar.integrationhub.hl7;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.ZoneId;
import java.util.Map;
import java.util.Optional;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.DiagnosticReport;
import org.hl7.fhir.r4.model.Observation;
import org.junit.jupiter.api.Test;

/** A result coded only the lab's way comes back with the LOINC from the code map added. */
class LabCodeMappingTest {

  private static final Map<String, LabCodes.Entry> BY_LAB_CODE =
      Map.of("HGB", new LabCodes.Entry("718-7", "HGB", "Haemoglobin", null));

  private final OruToFhir mapped =
      new OruToFhir(
          ZoneId.of("America/Toronto"), code -> Optional.ofNullable(BY_LAB_CODE.get(code)));

  private static String labCoded() {
    // A final result (the first of two reports) with the test in the lab's own code instead of
    // LOINC.
    return Hl7Samples.load("oru-two-reports.hl7")
        .replace("718-7^Hemoglobin^LN", "HGB^Hemoglobin^L");
  }

  private static <T> T first(Bundle b, Class<T> type) {
    return b.getEntry().stream()
        .map(Bundle.BundleEntryComponent::getResource)
        .filter(type::isInstance)
        .map(type::cast)
        .findFirst()
        .orElseThrow();
  }

  @Test
  void aTestInTheLabsCodeGetsTheLoincAddedOnTheReportAndTheObservation() throws Exception {
    Bundle bundle = mapped.map(Hl7Message.parse(labCoded())).bundle();
    for (var code :
        new org.hl7.fhir.r4.model.CodeableConcept[] {
          first(bundle, DiagnosticReport.class).getCode(),
          first(bundle, Observation.class).getCode()
        }) {
      assertThat(code.getCoding())
          .extracting(Coding::getSystem, Coding::getCode)
          .containsExactly(
              org.assertj.core.groups.Tuple.tuple("urn:integrationhub:hl7:EXTLAB.LAB1:L", "HGB"),
              org.assertj.core.groups.Tuple.tuple("http://loinc.org", "718-7"));
    }
  }

  @Test
  void aTestAlreadyInLoincOrNotInTheMapIsLeftAsItCame() throws Exception {
    Bundle loinc = mapped.map(Hl7Message.parse(Hl7Samples.load("oru-two-reports.hl7"))).bundle();
    assertThat(first(loinc, DiagnosticReport.class).getCode().getCoding()).hasSize(1);
    Bundle unknown =
        mapped
            .map(Hl7Message.parse(labCoded().replace("HGB^Hemoglobin^L", "XYZ^Something^L")))
            .bundle();
    assertThat(first(unknown, DiagnosticReport.class).getCode().getCoding())
        .extracting(Coding::getCode)
        .containsExactly("XYZ");
  }
}
