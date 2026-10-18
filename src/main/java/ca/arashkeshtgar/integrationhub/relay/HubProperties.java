package ca.arashkeshtgar.integrationhub.relay;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
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

  /**
   * One upstream application. {@code allowedAudiences} empty means the audience is not checked;
   * {@code requiredScopes} empty means no scope is required.
   */
  public record Route(
      @NotBlank String upstream,
      @DefaultValue("30s") Duration timeout,
      @DefaultValue("5s") Duration connectTimeout,
      @DefaultValue List<String> allowedAudiences,
      @DefaultValue List<String> requiredScopes) {

    public Route {
      URI uri = URI.create(upstream.trim());
      if (uri.getHost() == null
          || !("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))) {
        throw new IllegalArgumentException("hub.routes.*.upstream must be an http(s) URL");
      }
      upstream = upstream.trim().replaceAll("/+$", "");
    }
  }
}
