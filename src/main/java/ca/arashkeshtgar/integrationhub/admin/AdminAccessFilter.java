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
 * Guards {@code /api/messages}, {@code /api/admin} and {@code /actuator/prometheus}. On success it
 * gives the request the authority {@link #AUTHORITY}, and {@link #REPLAY} when the caller may
 * replay; the security configuration requires them, so a path this filter somehow misses stays
 * closed. A POST under {@code /api/admin} (a replay) needs the replay permission.
 */
public class AdminAccessFilter extends OncePerRequestFilter {

  public static final String AUTHORITY = "HUB_ADMIN";
  public static final String REPLAY = "HUB_REPLAY";

  private final AdminProperties properties;
  private final TokenValidator tokens;

  public AdminAccessFilter(AdminProperties properties, TokenValidator tokens) {
    this.properties = properties;
    this.tokens = tokens;
  }

  public static boolean guards(String path) {
    return path.equals("/api/messages")
        || path.startsWith("/api/messages/")
        || path.equals("/api/admin")
        || path.startsWith("/api/admin/")
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
    if (request.getMethod().equals("OPTIONS")) {
      chain.doFilter(
          request, response); // a CORS preflight carries no token; CorsFilter answered it
      return;
    }
    if (properties.localAccess() && isLocal(request)) {
      allow(request, response, chain, "local", true);
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
    Set<String> granted = permissions(jwt);
    boolean canView = has(granted, properties.permission());
    boolean canReplay = has(granted, properties.replayPermission());
    boolean replaying = request.getRequestURI().startsWith("/api/admin") && isWrite(request);
    if (!audienceOk || !canView || (replaying && !canReplay)) {
      deny(
          response,
          403,
          replaying
              ? "No permission to replay messages."
              : "No permission to read the message log.");
      return;
    }
    allow(request, response, chain, jwt.getSubject(), canReplay);
  }

  private static boolean isWrite(HttpServletRequest request) {
    return !request.getMethod().equals("GET") && !request.getMethod().equals("HEAD");
  }

  /** {@code integrationhub:messages.view} or the same without the prefix ClinicHub strips. */
  static boolean has(Set<String> granted, String wanted) {
    if (granted.contains(wanted)) {
      return true;
    }
    int colon = wanted.indexOf(':');
    return colon > 0 && granted.contains(wanted.substring(colon + 1));
  }

  private void allow(
      HttpServletRequest request,
      HttpServletResponse response,
      FilterChain chain,
      String who,
      boolean canReplay)
      throws IOException, ServletException {
    List<SimpleGrantedAuthority> authorities =
        canReplay
            ? List.of(new SimpleGrantedAuthority(AUTHORITY), new SimpleGrantedAuthority(REPLAY))
            : List.of(new SimpleGrantedAuthority(AUTHORITY));
    SecurityContextHolder.getContext()
        .setAuthentication(
            UsernamePasswordAuthenticationToken.authenticated(who, null, authorities));
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
