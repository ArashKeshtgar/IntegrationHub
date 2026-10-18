package ca.arashkeshtgar.integrationhub.relay;

import ca.arashkeshtgar.integrationhub.events.EventFactory;
import ca.arashkeshtgar.integrationhub.hl7.Hl7Outbound;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /route/{route}/...}: check the token, forward the call to the route's upstream with the
 * same method, path, query, body and {@code Authorization}, and give the upstream's answer back
 * unchanged. Which paths and methods a route relays is in its settings. No retry: the senders'
 * outboxes do that. The log holds metadata only.
 */
@RestController
public class RelayController {

  private static final Logger log = LoggerFactory.getLogger(RelayController.class);
  private static final Pattern ROUTE_PATH = Pattern.compile("^/route/([A-Za-z0-9._-]+)(/.*)?$");
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

  /**
   * Besides these, a query character is percent-encoded when sent upstream (see {@link #query}).
   */
  private static final String QUERY_SAFE = "-._~!$&'()*+,;=:@/?%";

  private final HubProperties properties;
  private final TokenValidator tokens;
  private final MessageLog messages;
  private final EventFactory eventFactory;
  private final MeterRegistry meters;
  private final Hl7Outbound hl7Outbound;
  private final Map<String, HttpClient> clients = new ConcurrentHashMap<>();

  public RelayController(
      HubProperties properties,
      TokenValidator tokens,
      MessageLog messages,
      EventFactory eventFactory,
      MeterRegistry meters,
      ObjectProvider<Hl7Outbound> hl7Outbound) {
    this.properties = properties;
    this.tokens = tokens;
    this.messages = messages;
    this.eventFactory = eventFactory;
    this.meters = meters;
    this.hl7Outbound = hl7Outbound.getIfAvailable();
  }

  @RequestMapping("/route/{route}/**")
  public void relay(HttpServletRequest request, HttpServletResponse response) throws IOException {
    long started = System.nanoTime();
    UUID id = UUID.randomUUID();
    Matcher match = ROUTE_PATH.matcher(request.getRequestURI());
    String routeName = match.matches() ? match.group(1) : "?";
    String path = match.matches() && match.group(2) != null ? match.group(2) : "";
    CallContext call = new CallContext(id, routeName, request, path);

    HubProperties.Route route = match.matches() ? properties.routes().get(routeName) : null;
    if (route == null || !route.allowsPath(path) || hasDotSegments(path)) {
      refuse(response, call, started, 404, "not-found", "No such route.", "unknown-route-or-path");
      return;
    }
    call.format(route.bodyFormat());
    String method = request.getMethod();
    if (!route.allowedMethods().contains(method)) {
      response.setHeader("Allow", String.join(", ", route.allowedMethods()));
      refuse(
          response,
          call,
          started,
          405,
          "not-supported",
          "Method not allowed.",
          "method-not-allowed");
      return;
    }

    String authorization = request.getHeader("Authorization");
    if (authorization == null && hasApiKey(route, request)) {
      // The upstream checks the key; the hub cannot.
      call.sender("api-key");
    } else {
      try {
        call.sender(tokens.validate(authorization, route).clientId());
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
    }

    byte[] body =
        request
            .getInputStream()
            .readNBytes((int) Math.min(properties.maxBodyBytes(), Integer.MAX_VALUE - 1) + 1);
    if (body.length > properties.maxBodyBytes()) {
      refuse(response, call, started, 413, "too-long", "The body is too large.", "body-too-large");
      return;
    }
    String rawQuery = request.getQueryString();
    BodySummary summary = body.length == 0 ? BodySummary.unparsed() : summarise(route, body);
    var queryIdentifier = BodySummary.identifierInQuery(rawQuery);
    call.queryIdentifier(queryIdentifier);
    summary = queryIdentifier.map(summary::withIdentifier).orElse(summary);
    call.body(body, sha256(body), summary);

    URI target;
    try {
      target =
          URI.create(route.upstream() + path + (rawQuery == null ? "" : "?" + query(rawQuery)));
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
            .method(
                method,
                body.length == 0
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofByteArray(body));
    List<String> headers = new ArrayList<>(FORWARDED_HEADERS);
    headers.addAll(route.forwardHeaders());
    for (String name : headers) {
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

  private static BodySummary summarise(HubProperties.Route route, byte[] body) {
    return switch (route.bodyFormat()) {
      case FHIR -> BodySummary.of(body);
      case FINANCE_DOCUMENTS -> BodySummary.ofFinanceDocuments(body);
    };
  }

  private static boolean hasApiKey(HubProperties.Route route, HttpServletRequest request) {
    if (route.apiKeyHeader() == null) {
      return false;
    }
    String key = request.getHeader(route.apiKeyHeader());
    return key != null && !key.isBlank();
  }

  /**
   * The query string as the sender wrote it. Only characters that may not appear in a URI (such as
   * a raw {@code |} in {@code identifier=system|42}, which Tomcat is set to accept) are
   * percent-encoded, because the JDK client cannot send them; existing {@code %XX} stay as they
   * are. The receiver decodes both forms to the same value.
   */
  public static String query(String raw) {
    StringBuilder out = new StringBuilder(raw.length() + 8);
    raw.codePoints()
        .forEach(
            cp -> {
              if ((cp >= 'a' && cp <= 'z')
                  || (cp >= 'A' && cp <= 'Z')
                  || (cp >= '0' && cp <= '9')
                  || (cp < 128 && QUERY_SAFE.indexOf(cp) >= 0)) {
                out.appendCodePoint(cp);
              } else {
                for (byte b : new String(Character.toChars(cp)).getBytes(StandardCharsets.UTF_8)) {
                  out.append('%').append(String.format("%02X", b & 0xff));
                }
              }
            });
    return out.toString();
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
    EventFactory.Result events = EventFactory.Result.NONE;
    try {
      events =
          eventFactory.create(
              new EventFactory.Evidence(
                  call.route(),
                  call.method(),
                  call.path(),
                  call.sender(),
                  call.receivedAt(),
                  call.sha256(),
                  call.summary(),
                  call.queryIdentifier(),
                  call.format(),
                  upstreamStatus));
    } catch (RuntimeException e) {
      // Events must never stop a relay; the call is still logged.
      log.error(
          "making events failed for route {}: {}", call.route(), e.getClass().getSimpleName());
    }
    messages.write(
        call.build(upstreamStatus, responseStatus, ms, error),
        events,
        hl7Copy(call, upstreamStatus));
    record(call.route(), upstreamStatus, responseStatus, ms);
    log.info(
        "relay route={} status={} upstream={} ms={} note={}",
        call.route(),
        responseStatus,
        upstreamStatus,
        ms,
        error);
  }

  /**
   * A result that ClinicHub took (2xx on a POST to the clinichub route) is also queued as ORU^R01
   * for the OLIS receiver, in the transaction of the log row. Null when that export is off.
   */
  private Runnable hl7Copy(CallContext call, Integer upstreamStatus) {
    if (hl7Outbound == null
        || !hl7Outbound.enabled()
        || !"clinichub".equals(call.route())
        || !"POST".equals(call.method())
        || call.body() == null
        || upstreamStatus == null
        || upstreamStatus < 200
        || upstreamStatus >= 300) {
      return null;
    }
    return () -> hl7Outbound.enqueue(call.id(), call.body());
  }

  /** Counters and a latency histogram. The route tag is only ever a configured name. */
  private void record(String route, Integer upstreamStatus, int responseStatus, long ms) {
    String tag = properties.routes().containsKey(route) ? route : "unknown";
    meters
        .counter("hub.messages", "route", tag, "status", String.valueOf(responseStatus))
        .increment();
    Timer.builder("hub.message.latency")
        .description("Time from the call arriving to the answer being sent")
        .tag("route", tag)
        .publishPercentileHistogram()
        .register(meters)
        .record(ms, TimeUnit.MILLISECONDS);
    String kind =
        responseStatus == 504
            ? "timeout"
            : responseStatus == 502
                ? "unreachable"
                : upstreamStatus != null && upstreamStatus >= 500 ? "http-5xx" : null;
    if (kind != null) {
      meters.counter("hub.upstream.errors", "route", tag, "kind", kind).increment();
    }
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
