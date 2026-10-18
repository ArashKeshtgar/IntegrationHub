package ca.arashkeshtgar.integrationhub.relay;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.parser.IParser;
import ca.uhn.fhir.parser.LenientErrorHandler;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.DiagnosticReport;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.ServiceRequest;

/**
 * What the log keeps about a body: for FHIR, resource types and counts, report and order statuses,
 * the Bundle id and the identifiers of orders; for finance documents, each document's {@code
 * sourceKey}, {@code externalId} and {@code kind}. Never names, health cards, birth dates, notes,
 * values, amounts or references. A body that does not parse is {@code parsed = false}; it is still
 * forwarded.
 */
public record BodySummary(
    boolean parsed,
    String bundleId,
    String bundleType,
    Map<String, Integer> resourceTypes,
    Set<String> reportStatuses,
    Set<String> requestStatuses,
    String clinicSlug,
    Set<Identifier> identifiers,
    Map<String, Object> extra) {

  public record Identifier(String system, String value) {}

  static final int MAX_IDENTIFIERS = 200;
  private static final int MAX_LENGTH = 200;
  private static final Pattern CLINICHUB_SERVICE_REQUEST =
      Pattern.compile("^urn:clinichub:([^:|]+):service-request$");
  private static final ObjectMapper JSON = new ObjectMapper();

  /** Finance documents are keyed per sending business: system = this prefix + sourceKey. */
  public static final String FINANCE_SYSTEM_PREFIX = "urn:integrationhub:finance:";

  // Logging is off: the library's warnings can quote the offending value (a date, a name).
  private static final IParser PARSER =
      FhirContext.forR4Cached()
          .newJsonParser()
          .setParserErrorHandler(new LenientErrorHandler(false).setErrorOnInvalidValue(false));

  public static BodySummary unparsed() {
    return new BodySummary(
        false, null, null, Map.of(), Set.of(), Set.of(), null, Set.of(), Map.of());
  }

  /** A FHIR body: a Bundle or a single resource. */
  public static BodySummary of(byte[] body) {
    try {
      IBaseResource resource = PARSER.parseResource(new String(body, StandardCharsets.UTF_8));
      Map<String, Integer> types = new TreeMap<>();
      Set<String> reportStatuses = new TreeSet<>();
      Set<String> requestStatuses = new TreeSet<>();
      Set<Identifier> identifiers = new LinkedHashSet<>();
      String bundleId = null;
      String bundleType = null;
      if (resource instanceof Bundle bundle) {
        bundleId = bundle.getIdElement().getIdPart();
        bundleType = bundle.hasType() ? bundle.getType().toCode() : null;
        for (Bundle.BundleEntryComponent entry : bundle.getEntry()) {
          if (entry.hasResource()) {
            count(entry.getResource(), types, reportStatuses, requestStatuses, identifiers);
          }
        }
      } else {
        count(resource, types, reportStatuses, requestStatuses, identifiers);
      }
      return new BodySummary(
          true,
          bundleId,
          bundleType,
          types,
          reportStatuses,
          requestStatuses,
          clinic(identifiers),
          identifiers,
          Map.of());
    } catch (RuntimeException e) {
      // Not logged with its message: it can quote the body.
      return unparsed();
    }
  }

  /**
   * A JSON array (or one object) of finance documents. Only {@code sourceKey}, {@code externalId}
   * and {@code kind} are read; amounts, parties, references and lines are never looked at.
   */
  public static BodySummary ofFinanceDocuments(byte[] body) {
    try {
      JsonNode root = JSON.readTree(body);
      if (root == null || !(root.isArray() || root.isObject())) {
        return unparsed();
      }
      List<JsonNode> documents = new ArrayList<>();
      if (root.isArray()) {
        root.forEach(documents::add);
      } else {
        documents.add(root);
      }
      Set<Identifier> identifiers = new LinkedHashSet<>();
      List<Map<String, String>> items = new ArrayList<>();
      Map<String, Integer> kinds = new TreeMap<>();
      Set<String> sourceKeys = new TreeSet<>();
      for (JsonNode doc : documents) {
        String sourceKey = text(doc, "sourceKey");
        String externalId = text(doc, "externalId");
        String kind = text(doc, "kind");
        if (kind != null) {
          kinds.merge(kind, 1, Integer::sum);
        }
        if (sourceKey != null) {
          sourceKeys.add(sourceKey);
        }
        if (items.size() < MAX_IDENTIFIERS) {
          Map<String, String> item = new TreeMap<>();
          if (sourceKey != null) item.put("sourceKey", sourceKey);
          if (externalId != null) item.put("externalId", externalId);
          if (kind != null) item.put("kind", kind);
          items.add(item);
        }
        if (sourceKey != null && externalId != null) {
          add(identifiers, new Identifier(FINANCE_SYSTEM_PREFIX + sourceKey, externalId));
        }
      }
      Map<String, Object> extra = new TreeMap<>();
      extra.put("documents", documents.size());
      extra.put("kinds", kinds);
      extra.put("sourceKeys", List.copyOf(sourceKeys));
      extra.put("items", items);
      return new BodySummary(
          true, null, null, Map.of(), Set.of(), Set.of(), null, identifiers, extra);
    } catch (Exception e) {
      return unparsed();
    }
  }

  private static String text(JsonNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || !value.isTextual() || value.asText().isBlank()) {
      return null;
    }
    String text = value.asText();
    return text.length() > MAX_LENGTH ? text.substring(0, MAX_LENGTH) : text;
  }

  /** This summary with one more identifier, e.g. the one named in a cancel's query string. */
  public BodySummary withIdentifier(Identifier id) {
    Set<Identifier> all = new LinkedHashSet<>(identifiers);
    add(all, id);
    return new BodySummary(
        parsed,
        bundleId,
        bundleType,
        resourceTypes,
        reportStatuses,
        requestStatuses,
        clinicSlug != null ? clinicSlug : clinic(all),
        all,
        extra);
  }

  /** {@code identifier=system|value} in a raw query string, percent-decoded. */
  public static Optional<Identifier> identifierInQuery(String rawQuery) {
    if (rawQuery == null) {
      return Optional.empty();
    }
    for (String pair : rawQuery.split("&")) {
      int eq = pair.indexOf('=');
      if (eq > 0 && "identifier".equals(decode(pair.substring(0, eq)))) {
        String value = decode(pair.substring(eq + 1));
        int bar = value.lastIndexOf('|');
        if (bar > 0 && bar < value.length() - 1) {
          return Optional.of(new Identifier(value.substring(0, bar), value.substring(bar + 1)));
        }
      }
    }
    return Optional.empty();
  }

  private static String decode(String s) {
    try {
      return URLDecoder.decode(s, StandardCharsets.UTF_8);
    } catch (IllegalArgumentException e) {
      return "";
    }
  }

  private static String clinic(Set<Identifier> identifiers) {
    return identifiers.stream()
        .map(i -> CLINICHUB_SERVICE_REQUEST.matcher(i.system()))
        .filter(Matcher::matches)
        .map(m -> m.group(1))
        .findFirst()
        .orElse(null);
  }

  private static void count(
      IBaseResource resource,
      Map<String, Integer> types,
      Set<String> reportStatuses,
      Set<String> requestStatuses,
      Set<Identifier> identifiers) {
    types.merge(resource.fhirType(), 1, Integer::sum);
    if (resource instanceof DiagnosticReport report) {
      if (report.hasStatus()) {
        reportStatuses.add(report.getStatus().toCode());
      }
      for (Reference basedOn : report.getBasedOn()) {
        identifier(basedOn).ifPresent(i -> add(identifiers, i));
      }
    } else if (resource instanceof ServiceRequest order) {
      if (order.hasStatus()) {
        requestStatuses.add(order.getStatus().toCode());
      }
      order.getIdentifier().stream()
          .filter(i -> i.hasSystem() && i.hasValue())
          .forEach(i -> add(identifiers, new Identifier(i.getSystem(), i.getValue())));
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
  static Optional<Identifier> identifier(Reference ref) {
    if (ref.hasIdentifier() && ref.getIdentifier().hasSystem() && ref.getIdentifier().hasValue()) {
      return Optional.of(
          new Identifier(ref.getIdentifier().getSystem(), ref.getIdentifier().getValue()));
    }
    if (!ref.hasReference()) {
      return Optional.empty();
    }
    String text = ref.getReference();
    int marker = text.indexOf("identifier=");
    if (marker >= 0) {
      text = text.substring(marker + "identifier=".length());
    }
    int bar = text.lastIndexOf('|');
    if (bar <= 0 || bar == text.length() - 1) {
      return Optional.empty();
    }
    return Optional.of(new Identifier(text.substring(0, bar), text.substring(bar + 1)));
  }

  /** The JSON stored in {@code message.resource_summary}. */
  public Map<String, Object> toJson() {
    Map<String, Object> json = new TreeMap<>(extra);
    json.put("parsed", parsed);
    if (parsed && !resourceTypes.isEmpty()) {
      json.put("bundleType", bundleType);
      json.put("resourceTypes", resourceTypes);
      json.put("reportStatuses", List.copyOf(reportStatuses));
      json.put("requestStatuses", List.copyOf(requestStatuses));
    }
    return json;
  }
}
