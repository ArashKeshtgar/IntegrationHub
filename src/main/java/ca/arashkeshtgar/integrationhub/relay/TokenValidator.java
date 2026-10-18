package ca.arashkeshtgar.integrationhub.relay;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.proc.DefaultJOSEObjectTypeVerifier;
import com.nimbusds.jose.proc.SecurityContext;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Component;

/**
 * Checks the sender's access token (signature against ClinicHub's JWKS, issuer, expiry) and then
 * the route's audience and scope. The token itself is forwarded untouched; this only decides
 * whether to forward.
 */
@Component
public class TokenValidator {

  /** Who sent the call, as far as the token says. */
  public record Sender(String clientId) {}

  /** The call must be answered {@code 401}. {@code reason} is a short code safe to log. */
  public static class Rejected extends Exception {
    public Rejected(String reason) {
      super(reason);
    }
  }

  /** The keys could not be fetched: not the sender's fault, answered {@code 503}. */
  public static class Unavailable extends Exception {
    public Unavailable(String reason) {
      super(reason);
    }
  }

  private final NimbusJwtDecoder decoder;

  public TokenValidator(HubProperties properties) {
    // ClinicHub signs with RS256. If that changes, add the algorithm here.
    decoder =
        NimbusJwtDecoder.withJwkSetUri(properties.auth().effectiveJwksUri())
            .jwsAlgorithm(SignatureAlgorithm.RS256)
            // OpenIddict marks access tokens "at+jwt" (RFC 9068); the default accepts only "JWT".
            .jwtProcessorCustomizer(
                processor ->
                    processor.setJWSTypeVerifier(
                        new DefaultJOSEObjectTypeVerifier<SecurityContext>(
                            JOSEObjectType.JWT, new JOSEObjectType("at+jwt"), null)))
            .build();
    decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(properties.auth().issuer()));
  }

  public Sender validate(String authorization, HubProperties.Route route)
      throws Rejected, Unavailable {
    if (authorization == null || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
      throw new Rejected("missing-token");
    }
    Jwt jwt;
    try {
      jwt = decoder.decode(authorization.substring(7).trim());
    } catch (BadJwtException e) {
      throw new Rejected("invalid-token: " + firstLine(e.getMessage()));
    } catch (JwtException e) {
      throw new Unavailable("token-verification-unavailable");
    }
    if (!route.requiredScopes().isEmpty() && !scopes(jwt).containsAll(route.requiredScopes())) {
      throw new Rejected("wrong-scope");
    }
    if (!route.allowedAudiences().isEmpty()
        && route.allowedAudiences().stream()
            .noneMatch(a -> jwt.getAudience() != null && jwt.getAudience().contains(a))) {
      throw new Rejected("wrong-audience");
    }
    return new Sender(clientId(jwt));
  }

  /** OpenIddict writes {@code scope} as a space separated string; a JSON array is accepted too. */
  static Set<String> scopes(Jwt jwt) {
    Object claim = jwt.getClaims().get("scope");
    Set<String> result = new HashSet<>();
    if (claim instanceof String s) {
      for (String part : s.trim().split("\\s+")) {
        if (!part.isEmpty()) {
          result.add(part);
        }
      }
    } else if (claim instanceof Collection<?> c) {
      c.forEach(o -> result.add(String.valueOf(o)));
    }
    return result;
  }

  private static String clientId(Jwt jwt) {
    for (String name : List.of("client_id", "azp", "sub")) {
      String value = jwt.getClaimAsString(name);
      if (value != null && !value.isBlank()) {
        return value;
      }
    }
    return null;
  }

  private static String firstLine(String message) {
    if (message == null) {
      return "";
    }
    String line = message.lines().findFirst().orElse("");
    return line.length() > 160 ? line.substring(0, 160) : line;
  }
}
