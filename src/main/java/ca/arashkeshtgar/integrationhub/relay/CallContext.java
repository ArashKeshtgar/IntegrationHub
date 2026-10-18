package ca.arashkeshtgar.integrationhub.relay;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Collects what is known about one call while it is handled, then becomes the log entry. */
final class CallContext {

  private final UUID id;
  private final String route;
  private final String method;
  private final String path;
  private final String contentType;
  private final long declaredSize;
  private String senderClientId;
  private Long requestSize;
  private String sha256;
  private BodySummary summary;
  private final Instant receivedAt = Instant.now();
  private HubProperties.BodyFormat format;
  private Optional<BodySummary.Identifier> queryIdentifier = Optional.empty();

  CallContext(UUID id, String route, HttpServletRequest request, String path) {
    this.id = id;
    this.route = route;
    this.method = request.getMethod();
    this.path = path;
    this.contentType = request.getContentType();
    this.declaredSize = request.getContentLengthLong();
  }

  String route() {
    return route;
  }

  Instant receivedAt() {
    return receivedAt;
  }

  String method() {
    return method;
  }

  String path() {
    return path;
  }

  String sender() {
    return senderClientId;
  }

  String sha256() {
    return sha256;
  }

  BodySummary summary() {
    return summary;
  }

  HubProperties.BodyFormat format() {
    return format;
  }

  Optional<BodySummary.Identifier> queryIdentifier() {
    return queryIdentifier;
  }

  void format(HubProperties.BodyFormat format) {
    this.format = format;
  }

  void queryIdentifier(Optional<BodySummary.Identifier> identifier) {
    this.queryIdentifier = identifier;
  }

  void sender(String clientId) {
    this.senderClientId = clientId;
  }

  void body(byte[] body, String sha256, BodySummary summary) {
    this.requestSize = (long) body.length;
    this.sha256 = sha256;
    this.summary = summary;
  }

  MessageLog.Entry build(Integer upstreamStatus, int responseStatus, long latencyMs, String error) {
    Long size = requestSize != null ? requestSize : (declaredSize >= 0 ? declaredSize : null);
    return new MessageLog.Entry(
        id,
        receivedAt,
        route,
        senderClientId,
        summary == null ? null : summary.clinicSlug(),
        method,
        path,
        contentType,
        size,
        sha256,
        summary == null ? null : summary.bundleId(),
        summary,
        upstreamStatus,
        responseStatus,
        latencyMs,
        error);
  }
}
