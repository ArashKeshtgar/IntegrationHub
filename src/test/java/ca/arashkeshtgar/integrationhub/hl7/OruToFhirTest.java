package ca.arashkeshtgar.integrationhub.hl7;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ca.uhn.fhir.context.FhirContext;
import java.time.ZoneId;
import java.util.List;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.DiagnosticReport;
import org.hl7.fhir.r4.model.Observation;
import org.hl7.fhir.r4.model.Quantity;
import org.hl7.fhir.r4.model.StringType;
import org.junit.jupiter.api.Test;

class OruToFhirTest {

  private final OruToFhir mapper = new OruToFhir(ZoneId.of("America/Toronto"));

  private Oru map(String sample) throws Exception {
    return mapper.map(Hl7Message.parse(Hl7Samples.load(sample)));
  }

  private static <T> List<T> of(Bundle b, Class<T> type) {
    return b.getEntry().stream()
        .map(Bundle.BundleEntryComponent::getResource)
        .filter(type::isInstance)
        .map(type::cast)
        .toList();
  }

  // MSH
  @Test
  void mshGivesSenderAndControlId() throws Exception {
    Oru oru = map("oru-final.hl7");
    assertThat(oru.sender()).isEqualTo("EXTLAB.LAB1");
    assertThat(oru.controlId()).isEqualTo("MSG0001");
    assertThat(oru.bundle().getType()).isEqualTo(Bundle.BundleType.TRANSACTION);
  }

  // ORC / OBR
  @Test
  void obrBecomesDiagnosticReportBasedOnTheClinicHubOrder() throws Exception {
    DiagnosticReport r = of(map("oru-final.hl7").bundle(), DiagnosticReport.class).get(0);
    assertThat(r.getStatus()).isEqualTo(DiagnosticReport.DiagnosticReportStatus.FINAL);
    assertThat(r.getBasedOnFirstRep().getIdentifier().getSystem())
        .isEqualTo("urn:clinichub:harbourview:service-request");
    assertThat(r.getBasedOnFirstRep().getIdentifier().getValue()).isEqualTo("101");
    assertThat(r.getCode().getCodingFirstRep().getSystem()).isEqualTo("http://loinc.org");
    assertThat(r.getCode().getCodingFirstRep().getCode()).isEqualTo("24323-8");
    assertThat(r.getEffectiveDateTimeType().getValueAsString())
        .isEqualTo("2026-03-15T08:30:00-04:00");
    assertThat(r.getIssuedElement().getValueAsString()).startsWith("2026-03-15T10:00:00");
    assertThat(r.getIdentifierFirstRep().getValue()).isEqualTo("F5001");
    assertThat(r.getResult()).hasSize(4);
  }

  @Test
  void fullClinicHubIdentifierInPlacerNumberIsAccepted() throws Exception {
    String text =
        Hl7Samples.load("oru-final.hl7")
            .replace(
                "ORC|RE|101^harbourview",
                "ORC|RE|urn:clinichub:harbourview:service-request\\F\\101")
            .replace(
                "OBR|1|101^harbourview", "OBR|1|urn:clinichub:harbourview:service-request\\F\\101");
    DiagnosticReport r =
        of(mapper.map(Hl7Message.parse(text)).bundle(), DiagnosticReport.class).get(0);
    assertThat(r.getBasedOnFirstRep().getIdentifier().getValue()).isEqualTo("101");
  }

  @Test
  void placerNumberThatIsNotAClinicHubOrderIsRefused() throws Exception {
    String text = Hl7Samples.load("oru-final.hl7").replace("101^harbourview", "101^bad namespace!");
    assertThatThrownBy(() -> mapper.map(Hl7Message.parse(text))).isInstanceOf(Hl7Exception.class);
    String none = Hl7Samples.load("oru-final.hl7").replace("101^harbourview", "");
    assertThatThrownBy(() -> mapper.map(Hl7Message.parse(none))).isInstanceOf(Hl7Exception.class);
  }

  @Test
  void resultStatusTable() throws Exception {
    assertThat(Hl7Tables.REPORT_STATUS.get("F"))
        .isEqualTo(DiagnosticReport.DiagnosticReportStatus.FINAL);
    assertThat(Hl7Tables.REPORT_STATUS.get("C"))
        .isEqualTo(DiagnosticReport.DiagnosticReportStatus.AMENDED);
    assertThat(Hl7Tables.REPORT_STATUS.get("X"))
        .isEqualTo(DiagnosticReport.DiagnosticReportStatus.CANCELLED);
    assertThat(Hl7Tables.REPORT_STATUS.get("P"))
        .isEqualTo(DiagnosticReport.DiagnosticReportStatus.PRELIMINARY);
    assertThat(Hl7Tables.isForwarded(DiagnosticReport.DiagnosticReportStatus.PRELIMINARY))
        .isFalse();
    assertThat(Hl7Tables.reportStatusCode(DiagnosticReport.DiagnosticReportStatus.FINAL))
        .contains("F");
  }

  @Test
  void preliminaryIsAcceptedButNotForwarded() throws Exception {
    Oru oru = map("oru-preliminary.hl7");
    assertThat(oru.reportsSeen()).isEqualTo(1);
    assertThat(oru.hasForwardable()).isFalse();
    assertThat(oru.bundle().getEntry()).isEmpty();
  }

  @Test
  void unknownOrMissingResultStatusIsRefused() {
    String bad = Hl7Samples.load("oru-final.hl7").replace("|F\rOBX|1", "|Q\rOBX|1");
    assertThatThrownBy(() -> mapper.map(Hl7Message.parse(bad))).isInstanceOf(Hl7Exception.class);
  }

  @Test
  void twoReportsInOneMessage() throws Exception {
    Oru oru = map("oru-two-reports.hl7");
    assertThat(oru.reportsSeen()).isEqualTo(2);
    List<DiagnosticReport> reports = of(oru.bundle(), DiagnosticReport.class);
    assertThat(reports)
        .extracting(DiagnosticReport::getStatus)
        .containsExactly(
            DiagnosticReport.DiagnosticReportStatus.FINAL,
            DiagnosticReport.DiagnosticReportStatus.AMENDED);
    assertThat(reports.get(1).getBasedOnFirstRep().getIdentifier().getValue()).isEqualTo("104");
    assertThat(reports.get(0).getResult().get(0).getReference())
        .isNotEqualTo(reports.get(1).getResult().get(0).getReference());
  }

  // OBX
  @Test
  void numericObxWithUnitRangeAndFlag() throws Exception {
    List<Observation> obs = of(map("oru-final.hl7").bundle(), Observation.class);
    Observation glucose = obs.get(0);
    assertThat(glucose.getStatus()).isEqualTo(Observation.ObservationStatus.FINAL);
    assertThat(glucose.getValueQuantity().getValue()).isEqualByComparingTo("5.4");
    assertThat(glucose.getValueQuantity().getCode()).isEqualTo("mmol/L");
    assertThat(glucose.getValueQuantity().getSystem()).isEqualTo("http://unitsofmeasure.org");
    assertThat(glucose.getReferenceRangeFirstRep().getLow().getValue()).isEqualByComparingTo("3.9");
    assertThat(glucose.getReferenceRangeFirstRep().getHigh().getValue())
        .isEqualByComparingTo("6.1");
    assertThat(glucose.getInterpretationFirstRep().getCodingFirstRep().getCode()).isEqualTo("N");
    assertThat(glucose.getEffectiveDateTimeType().getValueAsString())
        .isEqualTo("2026-03-15T09:30:00-04:00");
  }

  @Test
  void repeatedFlagsAreAllKept() throws Exception {
    Observation sodium = of(map("oru-final.hl7").bundle(), Observation.class).get(1);
    assertThat(sodium.getInterpretation())
        .extracting(c -> c.getCodingFirstRep().getCode())
        .containsExactly("H", "A");
  }

  @Test
  void unknownFlagIsKeptAsText() throws Exception {
    String text = Hl7Samples.load("oru-final.hl7").replace("|H~A|", "|ZZ|");
    Observation o = of(mapper.map(Hl7Message.parse(text)).bundle(), Observation.class).get(1);
    CodeableConcept c = o.getInterpretationFirstRep();
    assertThat(c.getText()).isEqualTo("ZZ");
    assertThat(c.hasCoding()).isFalse();
  }

  @Test
  void textObxIsUnescaped() throws Exception {
    Observation o = of(map("oru-final.hl7").bundle(), Observation.class).get(2);
    assertThat(o.getValue()).isInstanceOf(StringType.class);
    assertThat(o.getValueStringType().getValue()).isEqualTo("Synthetic free text with & ampersand");
  }

  @Test
  void codedObx() throws Exception {
    Observation o = of(map("oru-final.hl7").bundle(), Observation.class).get(3);
    assertThat(o.getValueCodeableConcept().getCodingFirstRep().getCode()).isEqualTo("Y");
    assertThat(o.getValueCodeableConcept().getText()).isEqualTo("Yellow");
    assertThat(o.getValueCodeableConcept().getCodingFirstRep().getSystem()).contains("L");
  }

  @Test
  void comparatorsInNumbers() throws Exception {
    Oru oru = map("oru-two-reports.hl7");
    List<Observation> obs = of(oru.bundle(), Observation.class);
    assertThat(obs.get(0).getValueQuantity().getComparator())
        .isEqualTo(Quantity.QuantityComparator.GREATER_THAN);
    assertThat(obs.get(0).getValueQuantity().getValue()).isEqualByComparingTo("200");
    assertThat(obs.get(1).getValueQuantity().getComparator())
        .isEqualTo(Quantity.QuantityComparator.LESS_THAN);
    assertThat(obs.get(1).getStatus()).isEqualTo(Observation.ObservationStatus.AMENDED);
  }

  @Test
  void valueThatCannotBeMappedRefusesTheWholeMessage() {
    String bad = Hl7Samples.load("oru-final.hl7").replace("|5.4|", "|five|");
    assertThatThrownBy(() -> mapper.map(Hl7Message.parse(bad))).isInstanceOf(Hl7Exception.class);
    String type = Hl7Samples.load("oru-final.hl7").replace("|NM|2345-7", "|ED|2345-7");
    assertThatThrownBy(() -> mapper.map(Hl7Message.parse(type))).isInstanceOf(Hl7Exception.class);
  }

  // NTE
  @Test
  void nteAttachesToThePrecedingObx() throws Exception {
    Observation o = of(map("oru-final.hl7").bundle(), Observation.class).get(1);
    assertThat(o.getNoteFirstRep().getText()).isEqualTo("Synthetic comment on the sodium");
  }

  // PID
  @Test
  void pidIsNeverCopiedIntoTheBundle() throws Exception {
    String json =
        FhirContext.forR4Cached()
            .newJsonParser()
            .encodeResourceToString(map("oru-final.hl7").bundle());
    assertThat(json)
        .doesNotContain("SYNTH0001")
        .doesNotContain("TESTPATIENT")
        .doesNotContain("19000101");
    assertThat(of(map("oru-final.hl7").bundle(), org.hl7.fhir.r4.model.Patient.class)).isEmpty();
  }

  @Test
  void onlyOruR01IsSupported() {
    String adt = Hl7Samples.load("oru-final.hl7").replace("ORU^R01", "ADT^A01");
    assertThatThrownBy(() -> mapper.map(Hl7Message.parse(adt))).isInstanceOf(Hl7Exception.class);
  }

  @Test
  void idsAreStableForRedelivery() throws Exception {
    String a =
        FhirContext.forR4Cached()
            .newJsonParser()
            .encodeResourceToString(map("oru-final.hl7").bundle());
    String b =
        FhirContext.forR4Cached()
            .newJsonParser()
            .encodeResourceToString(map("oru-final.hl7").bundle());
    assertThat(a).isEqualTo(b);
  }
}
