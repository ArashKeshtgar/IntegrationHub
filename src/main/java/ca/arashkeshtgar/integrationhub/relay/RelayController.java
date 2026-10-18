package ca.arashkeshtgar.integrationhub.relay;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /route/{route}/fhir[/**]}: check the token, forward the call to the route's upstream
 * with the same body and {@code Authorization}, and give the upstream's answer back unchanged. No
 * retry: the senders' outboxes do that. The log holds metadata only.
 */
@RestController
public class RelayController {

  private static final Logger log = LoggerFactory.getLogger(RelayController.class);
  private static final Pattern ROUTE_PATH =
      Pattern.compile("^/route/([A-Za-z0-9._-]+)(/fhir(?:/.*)?)$");
  private static final List<String> FORWARDED_HEADERS =
      List.of("Authorization", "Content-Type", "Accept", "X-Request-Id");
  private static final Set<String> NOT_COPIED_BACK =
      Set.of(
          "connection",
          "keep-alive",
          "transfer-encoding",
          "te",
          "trailer",
          "upgrade",
          "proxy-authenticate",
          "proxy-authorization",
          "content-length");

  private final HubProperties properties;
  private final TokenValidator tokens;
  private final MessageLog messages;
  private final Map<String, HttpClient> clients = new ConcurrentHashMap<>();

  public RelayController(HubProperties properties, TokenValidator tokens, MessageLog messages) {
    this.properties = properties;
    this.tokens = tokens;
    this.messages = messages;
  }

  @PostMapping({"/route/{route}/fhir", "/route/{route}/fhir/**"})
  public void relay(HttpServletRequest request, HttpServletResponse response) throws IOException {
    long started = System.nanoTime();
    UUID id = UUID.randomUUID();
    Matcher match = ROUTE_PATH.matcher(request.getRequestURI());
    String routeName = match.matches() ? match.group(1) : "?";
    String path = match.matches() ? match.group(2) : request.getRequestURI();
    CallContext call = new CallContext(id, routeName, request, path);

    HubProperties.Route route = match.matches() ? properties.routes().get(routeName) : null;
    if (route == null || hasDotSegments(path)) {
      refuse(response, call, started, 404, "not-found", "No such route.", "unknown-route");
      return;
    }

    TokenValidator.Sender sender;
    try {
      sender = tokens.validate(request.getHeader("Authorization"), route);
    } catch (TokenValidator.Rejected e) {
      response.setHeader("WWW-Authenticate", "Bearer");
      refuse(
          response,
          call,
          started,
          401,
          "login",
          "The access token was not accepted.",
          e.getMessage());
      return;
    } catch (TokenValidator.Unavailable e) {
      refuse(
          response,
          call,
          started,
          503,
          "transient",
          "Tokens cannot be verified right now.",
          e.getMessage());
      return;
    }
    call.sender(sender.clientId());

    byte[] body =
        request
            .getInputStream()
            .readNBytes((int) Math.min(properties.maxBodyBytes(), Integer.MAX_VALUE - 1) + 1);
    if (body.length > properties.maxBodyBytes()) {
      refuse(response, call, started, 413, "too-long", "The body is too large.", "body-too-large");
      return;
    }
    call.body(body, sha256(body), BundleSummary.of(body));

    URI target;
    try {
      String query = request.getQueryString();
      target = URI.create(route.upstream() + path + (query == null ? "" : "?" + query));
    } catch (IllegalArgumentException e) {
      refuse(
          response,
          call,
          started,
          400,
          "invalid",
          "The request target is not valid.",
          "invalid-target");
      return;
    }

    HttpRequest.Builder upstream =
        HttpRequest.newBuilder(target)
            .timeout(route.timeout())
            .POST(HttpRequest.BodyPublishers.ofByteArray(body));
    for (String name : FORWARDED_HEADERS) {
      List<String> values = Collections.list(request.getHeaders(name));
      if (!values.isEmpty()) {
        upstream.header(name, String.join(", ", values));
      }
    }
    if (request.getHeader("X-Request-Id") == null) {
      upstream.header("X-Request-Id", id.toString());
    }
    String forwardedFor = request.getHeader("X-Forwarded-For");
    upstream.header(
        "X-Forwarded-For",
        forwardedFor == null
            ? request.getRemoteAddr()
            : forwardedFor + ", " + request.getRemoteAddr());
    upstream.header("X-Forwarded-Proto", request.getScheme());
    upstream.header(
        "X-Forwarded-Host", request.getHeader("Host") == null ? "" : request.getHeader("Host"));

    HttpResponse<InputStream> answer;
    try {
      answer =
          client(routeName, route)
              .send(upstream.build(), HttpResponse.BodyHandlers.ofInputStream());
    } catch (HttpTimeoutException e) {
      refuse(
          response,
          call,
          started,
          504,
          "timeout",
          "The upstream did not answer in time.",
          "upstream-timeout");
      return;
    } catch (IOException e) {
      refuse(
          response,
          call,
          started,
          502,
          "transient",
          "The upstream could not be reached.",
          "upstream-unreachable");
      return;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      refuse(response, call, started, 502, "transient", "The call was interrupted.", "interrupted");
      return;
    }

    response.setStatus(answer.statusCode());
    answer
        .headers()
        .map()
        .forEach(
            (name, values) -> {
              if (!NOT_COPIED_BACK.contains(name.toLowerCase())) {
                values.forEach(v -> response.addHeader(name, v));
              }
            });
    String error = null;
    try (InputStream in = answer.body()) {
      in.transferTo(response.getOutputStream());
      response.flushBuffer();
    } catch (IOException e) {
      error = "answer-interrupted";
    }
    finish(call, started, answer.statusCode(), answer.statusCode(), error);
  }

  private HttpClient client(String name, HubProperties.Route route) {
    return clients.computeIfAbsent(
        name,
        n ->
            HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(route.connectTimeout())
                .build());
  }

  private void refuse(
      HttpServletResponse response,
      CallContext call,
      long started,
      int status,
      String code,
      String text,
      String reason)
      throws IOException {
    response.setStatus(status);
    response.setContentType("application/fhir+json");
    response
        .getOutputStream()
        .write(
            ("{\"resourceType\":\"OperationOutcome\",\"issue\":[{\"severity\":\"error\",\"code\":\""
                    + code
                    + "\",\"diagnostics\":\""
                    + text
                    + "\"}]}")
                .getBytes(StandardCharsets.UTF_8));
    finish(call, started, null, status, status + " " + reason);
  }

  private void finish(
      CallContext call, long started, Integer upstreamStatus, int responseStatus, String error) {
    long ms = (System.nanoTime() - started) / 1_000_000;
    messages.write(call.build(upstreamStatus, responseStatus, ms, error));
    log.info(
        "relay route={} status={} upstream={} ms={} note={}",
        call.route(),
        responseStatus,
        upstreamStatus,
        ms,
        error);
  }

  private static boolean hasDotSegments(String path) {
    for (String segment : URLDecoder.decode(path, StandardCharsets.UTF_8).split("/")) {
      if (segment.equals("..") || segment.equals(".")) {
        return true;
      }
    }
    return false;
  }

  private static String sha256(byte[] body) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
