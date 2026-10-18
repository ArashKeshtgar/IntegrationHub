package ca.arashkeshtgar.integrationhub.relay;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.parser.IParser;
import ca.uhn.fhir.parser.LenientErrorHandler;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.DiagnosticReport;
import org.hl7.fhir.r4.model.Reference;

/**
 * What the log keeps about a FHIR body: resource types and counts, report statuses, the Bundle id
 * and the identifiers of the orders a report is based on. Never names, birth dates, notes or
 * values. A body that does not parse is {@code parsed = false}; it is still forwarded.
 */
public record BundleSummary(
    boolean parsed,
    String bundleId,
    String bundleType,
    Map<String, Integer> resourceTypes,
    Set<String> reportStatuses,
    String clinicSlug,
    Set<Identifier> identifiers) {

  public record Identifier(String system, String value) {}

  static final int MAX_IDENTIFIERS = 200;
  private static final int MAX_LENGTH = 200;
  private static final Pattern CLINICHUB_SERVICE_REQUEST =
      Pattern.compile("^urn:clinichub:([^:|]+):service-request$");

  // Logging is off: the library's warnings can quote the offending value (a date, a name).
  private static final IParser PARSER =
      FhirContext.forR4Cached()
          .newJsonParser()
          .setParserErrorHandler(new LenientErrorHandler(false).setErrorOnInvalidValue(false));

  public static BundleSummary unparsed() {
    return new BundleSummary(false, null, null, Map.of(), Set.of(), null, Set.of());
  }

  public static BundleSummary of(byte[] body) {
    try {
      IBaseResource resource = PARSER.parseResource(new String(body, StandardCharsets.UTF_8));
      Map<String, Integer> types = new TreeMap<>();
      Set<String> statuses = new TreeSet<>();
      Set<Identifier> identifiers = new LinkedHashSet<>();
      String bundleId = null;
      String bundleType = null;
      if (resource instanceof Bundle bundle) {
        bundleId = bundle.getIdElement().getIdPart();
        bundleType = bundle.hasType() ? bundle.getType().toCode() : null;
        for (Bundle.BundleEntryComponent entry : bundle.getEntry()) {
          if (entry.hasResource()) {
            count(entry.getResource(), types, statuses, identifiers);
          }
        }
      } else {
        count(resource, types, statuses, identifiers);
      }
      String clinic =
          identifiers.stream()
              .map(i -> CLINICHUB_SERVICE_REQUEST.matcher(i.system()))
              .filter(Matcher::matches)
              .map(m -> m.group(1))
              .findFirst()
              .orElse(null);
      return new BundleSummary(true, bundleId, bundleType, types, statuses, clinic, identifiers);
    } catch (RuntimeException e) {
      // Not logged with its message: it can quote the body.
      return unparsed();
    }
  }

  private static void count(
      IBaseResource resource,
      Map<String, Integer> types,
      Set<String> statuses,
      Set<Identifier> identifiers) {
    types.merge(resource.fhirType(), 1, Integer::sum);
    if (resource instanceof DiagnosticReport report) {
      if (report.hasStatus()) {
        statuses.add(report.getStatus().toCode());
      }
      for (Reference basedOn : report.getBasedOn()) {
        identifier(basedOn).ifPresent(i -> add(identifiers, i));
      }
    }
  }

  private static void add(Set<Identifier> identifiers, Identifier id) {
    if (identifiers.size() < MAX_IDENTIFIERS
        && id.system().length() <= MAX_LENGTH
        && id.value().length() <= MAX_LENGTH) {
      identifiers.add(id);
    }
  }

  /**
   * An order is named by {@code basedOn.identifier}, or by a reference string such as {@code
   * urn:clinichub:harbourview:service-request|42} or {@code
   * ServiceRequest?identifier=urn:clinichub:harbourview:service-request|42}.
   */
  static java.util.Optional<Identifier> identifier(Reference ref) {
    if (ref.hasIdentifier() && ref.getIdentifier().hasSystem() && ref.getIdentifier().hasValue()) {
      return java.util.Optional.of(
          new Identifier(ref.getIdentifier().getSystem(), ref.getIdentifier().getValue()));
    }
    if (!ref.hasReference()) {
      return java.util.Optional.empty();
    }
    String text = ref.getReference();
    int marker = text.indexOf("identifier=");
    if (marker >= 0) {
      text = text.substring(marker + "identifier=".length());
    }
    int bar = text.lastIndexOf('|');
    if (bar <= 0 || bar == text.length() - 1) {
      return java.util.Optional.empty();
    }
    return java.util.Optional.of(new Identifier(text.substring(0, bar), text.substring(bar + 1)));
  }

  /** The JSON stored in {@code message.resource_summary}. */
  public Map<String, Object> toJson() {
    Map<String, Object> json = new TreeMap<>();
    json.put("parsed", parsed);
    if (parsed) {
      json.put("bundleType", bundleType);
      json.put("resourceTypes", resourceTypes);
      json.put("reportStatuses", List.copyOf(reportStatuses));
    }
    return json;
  }
}
