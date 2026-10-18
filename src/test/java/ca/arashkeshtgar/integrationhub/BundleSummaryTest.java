package ca.arashkeshtgar.integrationhub;

import static org.assertj.core.api.Assertions.assertThat;

import ca.arashkeshtgar.integrationhub.relay.BundleSummary;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class BundleSummaryTest {

  private static BundleSummary of(String json) {
    return BundleSummary.of(json.getBytes(StandardCharsets.UTF_8));
  }

  @Test
  void summarisesAResultsBundle() throws Exception {
    var summary =
        BundleSummary.of(
            Files.readAllBytes(Path.of("src/test/resources/contracts/lab-result-bundle.json")));

    assertThat(summary.parsed()).isTrue();
    assertThat(summary.bundleId()).isEqualTo("bundle-1");
    assertThat(summary.bundleType()).isEqualTo("transaction");
    assertThat(summary.resourceTypes())
        .containsEntry("DiagnosticReport", 2)
        .containsEntry("Observation", 1);
    assertThat(summary.reportStatuses()).containsExactlyInAnyOrder("final", "amended");
    assertThat(summary.clinicSlug()).isEqualTo("harbourview");
    assertThat(summary.identifiers())
        .containsExactlyInAnyOrder(
            new BundleSummary.Identifier("urn:clinichub:harbourview:service-request", "42"),
            new BundleSummary.Identifier("urn:clinichub:harbourview:service-request", "43"));
  }

  @Test
  void acceptsAReferenceStringWithoutAQuery() {
    var summary =
        of(
            """
        {"resourceType":"Bundle","type":"transaction","entry":[{"resource":{"resourceType":"DiagnosticReport",
        "status":"cancelled","basedOn":[{"reference":"urn:clinichub:lakeshore:service-request|7"}]}}]}""");

    assertThat(summary.identifiers())
        .containsExactly(
            new BundleSummary.Identifier("urn:clinichub:lakeshore:service-request", "7"));
    assertThat(summary.clinicSlug()).isEqualTo("lakeshore");
    assertThat(summary.reportStatuses()).containsExactly("cancelled");
  }

  @Test
  void aSingleResourceIsSummarisedToo() {
    var summary = of("{\"resourceType\":\"DiagnosticReport\",\"status\":\"final\"}");

    assertThat(summary.parsed()).isTrue();
    assertThat(summary.bundleId()).isNull();
    assertThat(summary.resourceTypes()).containsEntry("DiagnosticReport", 1);
  }

  @Test
  void referencesWithoutAnIdentifierAreIgnored() {
    var summary =
        of(
            """
        {"resourceType":"Bundle","type":"transaction","entry":[{"resource":{"resourceType":"DiagnosticReport",
        "status":"final","basedOn":[{"reference":"ServiceRequest/12"},{"reference":"x|"}]}}]}""");

    assertThat(summary.parsed()).isTrue();
    assertThat(summary.identifiers()).isEmpty();
    assertThat(summary.clinicSlug()).isNull();
  }

  @Test
  void aCapOnIdentifiersKeepsALogRowSmall() {
    var entries = new StringBuilder();
    for (int i = 0; i < 500; i++) {
      entries
          .append(i == 0 ? "" : ",")
          .append(
              "{\"resource\":{\"resourceType\":\"DiagnosticReport\",\"status\":\"final\",\"basedOn\":[{\"identifier\":{\"system\":\"urn:clinichub:a:service-request\",\"value\":\"")
          .append(i)
          .append("\"}}]}}");
    }
    var summary =
        of("{\"resourceType\":\"Bundle\",\"type\":\"transaction\",\"entry\":[" + entries + "]}");

    assertThat(summary.identifiers()).hasSize(200);
    assertThat(summary.resourceTypes()).containsEntry("DiagnosticReport", 500);
  }

  @Test
  void garbageIsUnparsed() {
    assertThat(of("{ nope").parsed()).isFalse();
    assertThat(of("").parsed()).isFalse();
    assertThat(of("[1,2,3]").parsed()).isFalse();
    assertThat(of("{\"resourceType\":\"NotAResource\"}").parsed()).isFalse();
  }
}
