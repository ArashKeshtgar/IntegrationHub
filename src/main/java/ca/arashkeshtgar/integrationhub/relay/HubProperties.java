package ca.arashkeshtgar.integrationhub.relay;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/** Settings under {@code hub.*}: where tokens come from and which routes exist. */
@Validated
@ConfigurationProperties("hub")
public record HubProperties(
    @Valid @NotNull Auth auth,
    @Valid Map<String, Route> routes,
    @DefaultValue("33554432") @Positive long maxBodyBytes) {

  public HubProperties {
    routes = routes == null ? Map.of() : Map.copyOf(routes);
  }

  /** The issuer is ClinicHub (OpenIddict); its keys are at {@code {issuer}.well-known/jwks}. */
  public record Auth(@NotBlank String issuer, String jwksUri) {

    public String effectiveJwksUri() {
      if (jwksUri != null && !jwksUri.isBlank()) {
        return jwksUri;
      }
      return issuer.endsWith("/") ? issuer + ".well-known/jwks" : issuer + "/.well-known/jwks";
    }
  }

  /** How a route's request bodies are summarised for the log. */
  public enum BodyFormat {
    /** FHIR R4: a Bundle or one resource. */
    FHIR,
    /** FinancePowerApp's {@code /api/integration/documents}: JSON, only three fields are read. */
    FINANCE_DOCUMENTS
  }

  /**
   * One upstream application. {@code allowedAudiences} empty means the audience is not checked;
   * {@code requiredScopes} empty means no scope is required. Only calls whose path starts with one
   * of {@code allowedPaths} and whose method is in {@code allowedMethods} are relayed. {@code
   * forwardHeaders} are passed on besides the usual ones. {@code apiKeyHeader}, when set, lets a
   * call with no {@code Authorization} but that header through to the upstream, which decides
   * (FinancePowerApp's development {@code X-Finance-Key}); leave it empty elsewhere. {@code
   * hl7Order} makes the route answer for an external laboratory itself: the order Bundle is turned
   * into an ORM^O01 and sent over MLLP to {@code hub.hl7.lab}; there is no HTTP upstream.
   */
  public record Route(
      String upstream,
      @DefaultValue("30s") Duration timeout,
      @DefaultValue("5s") Duration connectTimeout,
      @DefaultValue List<String> allowedAudiences,
      @DefaultValue List<String> requiredScopes,
      @DefaultValue("/fhir") List<String> allowedPaths,
      @DefaultValue("POST") List<String> allowedMethods,
      @DefaultValue("FHIR") BodyFormat bodyFormat,
      @DefaultValue List<String> forwardHeaders,
      String apiKeyHeader,
      @DefaultValue("false") boolean hl7Order) {

    private static final Set<String> METHODS = Set.of("GET", "POST", "PUT", "DELETE");

    public Route {
      if (hl7Order) {
        upstream = null;
      } else {
        if (upstream == null || upstream.isBlank()) {
          throw new IllegalArgumentException("hub.routes.*.upstream is required");
        }
        URI uri = URI.create(upstream.trim());
        if (uri.getHost() == null
            || !("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))) {
          throw new IllegalArgumentException("hub.routes.*.upstream must be an http(s) URL");
        }
        upstream = upstream.trim().replaceAll("/+$", "");
      }
      allowedPaths = allowedPaths.stream().map(p -> p.trim().replaceAll("/+$", "")).toList();
      if (allowedPaths.isEmpty() || allowedPaths.stream().anyMatch(p -> !p.startsWith("/"))) {
        throw new IllegalArgumentException("hub.routes.*.allowed-paths must start with /");
      }
      allowedMethods = allowedMethods.stream().map(m -> m.trim().toUpperCase()).toList();
      if (allowedMethods.isEmpty() || !METHODS.containsAll(allowedMethods)) {
        throw new IllegalArgumentException(
            "hub.routes.*.allowed-methods must be of GET, POST, PUT, DELETE");
      }
      apiKeyHeader = apiKeyHeader == null || apiKeyHeader.isBlank() ? null : apiKeyHeader.trim();
    }

    public boolean allowsPath(String path) {
      return allowedPaths.stream().anyMatch(p -> path.equals(p) || path.startsWith(p + "/"));
    }
  }
}
