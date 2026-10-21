package ca.arashkeshtgar.integrationhub;

import static org.assertj.core.api.Assertions.assertThat;

import ca.arashkeshtgar.integrationhub.relay.BodySummary;
import ca.arashkeshtgar.integrationhub.relay.RelayController;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class BodySummaryTest {

  private static BodySummary of(String json) {
    return BodySummary.of(json.getBytes(StandardCharsets.UTF_8));
  }

  @Test
  void summarisesAResultsBundle() throws Exception {
    var summary =
        BodySummary.of(
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
            new BodySummary.Identifier("urn:clinichub:harbourview:service-request", "42"),
            new BodySummary.Identifier("urn:clinichub:harbourview:service-request", "43"));
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
            new BodySummary.Identifier("urn:clinichub:lakeshore:service-request", "7"));
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

  @Test
  void summarisesAnOrderBundle() throws Exception {
    var summary =
        BodySummary.of(
            Files.readAllBytes(Path.of("src/test/resources/contracts/order-bundle.json")));

    assertThat(summary.resourceTypes())
        .containsEntry("Patient", 1)
        .containsEntry("Practitioner", 1)
        .containsEntry("ServiceRequest", 2);
    assertThat(summary.requestStatuses()).containsExactly("active");
    assertThat(summary.clinicSlug()).isEqualTo("harbourview");
    // Only the orders' identifiers: not the MRN or the health card number on the Patient.
    assertThat(summary.identifiers())
        .containsExactlyInAnyOrder(
            new BodySummary.Identifier("urn:clinichub:harbourview:service-request", "101"),
            new BodySummary.Identifier("urn:clinichub:harbourview:service-request", "102"));
    assertThat(summary.toJson().toString()).doesNotContain("MARKER").doesNotContain("Zzyzx");
  }

  @Test
  void summarisesFinanceDocumentsWithoutAmountsOrParties() throws Exception {
    var summary =
        BodySummary.ofFinanceDocuments(
            Files.readAllBytes(Path.of("src/test/resources/contracts/finance-documents.json")));

    assertThat(summary.parsed()).isTrue();
    assertThat(summary.identifiers())
        .containsExactlyInAnyOrder(
            new BodySummary.Identifier(
                "urn:integrationhub:finance:harbourview", "claim:1234:ready"),
            new BodySummary.Identifier(
                "urn:integrationhub:finance:harbourview", "claim:1235:reversal"));
    String json = summary.toJson().toString();
    assertThat(json).contains("claim:1234:ready", "Invoice", "Reversal", "harbourview");
    assertThat(json).doesNotContain("AB12", "91.17", "OHIP", "123456");
  }

  @Test
  void financeBodiesThatAreNotDocumentsAreUnparsed() {
    assertThat(BodySummary.ofFinanceDocuments("nope".getBytes(StandardCharsets.UTF_8)).parsed())
        .isFalse();
    assertThat(BodySummary.ofFinanceDocuments("42".getBytes(StandardCharsets.UTF_8)).parsed())
        .isFalse();
    assertThat(BodySummary.ofFinanceDocuments("\"text\"".getBytes(StandardCharsets.UTF_8)).parsed())
        .isFalse();
    var partial =
        BodySummary.ofFinanceDocuments(
            "[{\"kind\":\"Bill\"},{\"externalId\":1}]".getBytes(StandardCharsets.UTF_8));
    assertThat(partial.parsed()).isTrue();
    assertThat(partial.identifiers()).isEmpty();
  }

  @Test
  void findsTheIdentifierOfACancelInTheQuery() {
    var expected = new BodySummary.Identifier("urn:clinichub:lakeshore:service-request", "55");
    for (String query :
        new String[] {
          "identifier=urn:clinichub:lakeshore:service-request|55",
          "identifier=urn:clinichub:lakeshore:service-request%7C55",
          "identifier=urn%3Aclinichub%3Alakeshore%3Aservice-request%7C55",
          "_format=json&identifier=urn%3Aclinichub%3Alakeshore%3Aservice-request%7C55&x=1"
        }) {
      assertThat(BodySummary.identifierInQuery(query)).as(query).contains(expected);
    }
    assertThat(BodySummary.identifierInQuery(null)).isEmpty();
    assertThat(BodySummary.identifierInQuery("identifier=nobar")).isEmpty();
    assertThat(BodySummary.identifierInQuery("identifier=%zz%7C1")).isEmpty();
    assertThat(BodySummary.identifierInQuery("other=a%7Cb")).isEmpty();
  }

  @Test
  void aQueryIdentifierSetsTheClinicOfAnUnparsedBody() {
    var summary =
        BodySummary.unparsed()
            .withIdentifier(
                new BodySummary.Identifier("urn:clinichub:lakeshore:service-request", "55"));

    assertThat(summary.parsed()).isFalse();
    assertThat(summary.clinicSlug()).isEqualTo("lakeshore");
  }

  @Test
  void queryEncodingTouchesOnlyWhatAUriCannotCarry() {
    assertThat(RelayController.query("a=1&b=2")).isEqualTo("a=1&b=2");
    assertThat(RelayController.query("identifier=x:y|42")).isEqualTo("identifier=x:y%7C42");
    assertThat(RelayController.query("identifier=x%3Ay%7C42")).isEqualTo("identifier=x%3Ay%7C42");
    assertThat(RelayController.query("a=[1]&b={2}^")).isEqualTo("a=%5B1%5D&b=%7B2%7D%5E");
    assertThat(RelayController.query("n=caf\u00e9")).isEqualTo("n=caf%C3%A9");
    assertThat(RelayController.query("q='x'(y)*+,;=@/?")).isEqualTo("q='x'(y)*+,;=@/?");
  }
}
