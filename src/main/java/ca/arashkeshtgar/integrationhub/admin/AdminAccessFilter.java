package ca.arashkeshtgar.integrationhub.admin;

import ca.arashkeshtgar.integrationhub.relay.TokenValidator;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Guards {@code /api/messages} and {@code /actuator/prometheus}. On success it gives the request
 * the authority {@link #AUTHORITY}; the security configuration requires it, so a path this filter
 * somehow misses stays closed.
 */
public class AdminAccessFilter extends OncePerRequestFilter {

  public static final String AUTHORITY = "HUB_ADMIN";

  private final AdminProperties properties;
  private final TokenValidator tokens;

  public AdminAccessFilter(AdminProperties properties, TokenValidator tokens) {
    this.properties = properties;
    this.tokens = tokens;
  }

  public static boolean guards(String path) {
    return path.equals("/api/messages")
        || path.startsWith("/api/messages/")
        || path.equals("/actuator/prometheus");
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !guards(request.getRequestURI());
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    if (properties.localAccess() && isLocal(request)) {
      allow(request, response, chain, "local");
      return;
    }
    Jwt jwt;
    try {
      jwt = tokens.decode(request.getHeader("Authorization"));
    } catch (TokenValidator.Rejected e) {
      response.setHeader("WWW-Authenticate", "Bearer");
      deny(response, 401, "Sign in.");
      return;
    } catch (TokenValidator.Unavailable e) {
      deny(response, 503, "Tokens cannot be verified right now.");
      return;
    }
    boolean audienceOk =
        properties.audience().isBlank()
            || (jwt.getAudience() != null && jwt.getAudience().contains(properties.audience()));
    if (!audienceOk || !permissions(jwt).contains(properties.permission())) {
      deny(response, 403, "No permission to read the message log.");
      return;
    }
    allow(request, response, chain, jwt.getSubject());
  }

  private void allow(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain, String who)
      throws IOException, ServletException {
    SecurityContextHolder.getContext()
        .setAuthentication(
            UsernamePasswordAuthenticationToken.authenticated(
                who, null, List.of(new SimpleGrantedAuthority(AUTHORITY))));
    chain.doFilter(request, response);
  }

  private static void deny(HttpServletResponse response, int status, String text)
      throws IOException {
    response.setStatus(status);
    response.setContentType("application/json");
    response
        .getOutputStream()
        .write(("{\"error\":\"" + text + "\"}").getBytes(StandardCharsets.UTF_8));
  }

  /** {@code permission}: a JSON array, or one string (possibly space separated). */
  static Set<String> permissions(Jwt jwt) {
    Object claim = jwt.getClaims().get("permission");
    Set<String> result = new HashSet<>();
    if (claim instanceof String s) {
      for (String part : s.trim().split("\\s+")) {
        result.add(part);
      }
    } else if (claim instanceof Collection<?> c) {
      c.forEach(o -> result.add(String.valueOf(o)));
    }
    return result;
  }

  private static boolean isLocal(HttpServletRequest request) {
    if (request.getHeader("X-Forwarded-For") != null
        || request.getHeader("Forwarded") != null
        || request.getHeader("X-Real-IP") != null) {
      return false;
    }
    try {
      return InetAddress.getByName(request.getRemoteAddr()).isLoopbackAddress();
    } catch (IOException e) {
      return false;
    }
  }
}
