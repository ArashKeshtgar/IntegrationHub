package ca.arashkeshtgar.integrationhub.admin;

import ca.arashkeshtgar.integrationhub.hl7.Hl7Properties;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * The admin web's API under {@code /api/admin}. Guarded by {@link AdminAccessFilter}: reading needs
 * the view permission, a POST (replay) the replay permission. Metadata only.
 */
@RestController
public class AdminController {

  private static final int MAX_LIMIT = 200;

  private final RouteHealth routes;
  private final Queues queues;
  private final Alerts alerts;
  private final DeadLetters deadLetters;
  private final Hl7Properties hl7;

  public AdminController(
      RouteHealth routes,
      Queues queues,
      Alerts alerts,
      DeadLetters deadLetters,
      Hl7Properties hl7) {
    this.routes = routes;
    this.queues = queues;
    this.alerts = alerts;
    this.deadLetters = deadLetters;
    this.hl7 = hl7;
  }

  /** Who is calling and what they may do, for the web's menus. */
  @GetMapping("/api/admin/me")
  public Map<String, Object> me(Authentication who) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("subject", who.getName());
    body.put(
        "canReplay",
        who.getAuthorities().stream()
            .anyMatch(a -> a.getAuthority().equals(AdminAccessFilter.REPLAY)));
    body.put("hl7Enabled", hl7.enabled());
    return body;
  }

  /** Route health, queue sizes and the number of open alerts: the web's first page. */
  @GetMapping("/api/admin/overview")
  public Map<String, Object> overview() {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("generatedAt", Instant.now());
    body.put("routes", routes.all());
    body.put("queues", queues.counts());
    body.put("openAlerts", alerts.openCount());
    return body;
  }

  @GetMapping("/api/admin/alerts")
  public Map<String, Object> alerts(
      @RequestParam(defaultValue = "open") String state,
      @RequestParam(defaultValue = "50") int limit) {
    if (!state.equals("open") && !state.equals("all")) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "state is open or all");
    }
    return Map.of("items", alerts.list(state.equals("open"), clamp(limit)));
  }

  @GetMapping("/api/admin/dead-letters")
  public Map<String, Object> deadLetters(@RequestParam(defaultValue = "100") int limit) {
    List<DeadLetters.Letter> items = deadLetters.list(clamp(limit));
    return Map.of("items", items, "replayWindowDays", hl7.replayWindowDays());
  }

  /** Puts a given-up or refused HL7 message back in its queue; the worker retries it shortly. */
  @PostMapping("/api/admin/dead-letters/{queue}/{id}/replay")
  public ResponseEntity<Map<String, Object>> replay(
      @PathVariable String queue, @PathVariable UUID id, Authentication who) {
    deadLetters.replay(queue, id, who.getName());
    return ResponseEntity.accepted().body(Map.of("status", "queued"));
  }

  private static int clamp(int limit) {
    if (limit < 1) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "limit must be 1 or more");
    }
    return Math.min(limit, MAX_LIMIT);
  }
}
