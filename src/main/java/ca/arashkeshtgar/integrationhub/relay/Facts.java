package ca.arashkeshtgar.integrationhub.relay;

import java.util.List;

/**
 * What the event feed needs from a body, kept in memory for the length of one call and never logged
 * or stored as it is. {@code patientIdentifiers} holds only ClinicHub's own identifiers (the
 * clinic's MRN), never the health card number. {@code toString} shows counts only.
 */
public record Facts(
    List<Order> orders,
    List<Report> reports,
    List<FinanceDocument> documents,
    List<BodySummary.Identifier> patientIdentifiers,
    List<PatientMerge> merges) {

  public record Order(
      BodySummary.Identifier identifier,
      String codeSystem,
      String code,
      String priority,
      String status) {}

  public record Report(
      String status, String codeSystem, String code, List<BodySummary.Identifier> basedOn) {}

  public record FinanceDocument(String sourceKey, String externalId, String kind) {}

  /**
   * A Patient marked {@code replaced-by} another: both MRNs are ClinicHub's own, in the same
   * identifier system (so the same clinic). In memory for the length of one call, like the rest.
   */
  public record PatientMerge(String system, String mergedMrn, String survivorMrn) {}

  public static Facts empty() {
    return new Facts(List.of(), List.of(), List.of(), List.of(), List.of());
  }

  @Override
  public String toString() {
    return "Facts[orders=%d, reports=%d, documents=%d, merges=%d]"
        .formatted(orders.size(), reports.size(), documents.size(), merges.size());
  }
}
