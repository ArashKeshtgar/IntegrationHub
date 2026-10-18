package ca.arashkeshtgar.integrationhub.hl7;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Sends a result Bundle to ClinicHub as the client {@code integrationhub} (client credentials,
 * scope {@code results}). The token is kept until shortly before it expires. The answer is sorted
 * into three: taken, try again later (ClinicHub or the network is down, 408/425/429/5xx, a token
 * refused), and refused (any other 4xx: sending it again will not change the answer).
 */
@Component
@ConditionalOnProperty(name = "hub.hl7.enabled", havingValue = "true")
public class ClinicHubClient {

  public enum Outcome {
    DELIVERED,
    RETRY,
    REJECTED
  }

  /** {@code note} is a fixed reason, never content from either side. */
  public record Delivery(Outcome outcome, Integer status, String note) {}

  private final Hl7Properties.ClinicHub settings;
  private final ObjectMapper json;
  private final HttpClient client;
  private volatile String token;
  private volatile Instant tokenExpires = Instant.EPOCH;

  public ClinicHubClient(Hl7Properties properties, ObjectMapper json) {
    this.settings = properties.clinichub();
    this.json = json;
    this.client = HttpClient.newBuilder().connectTimeout(settings.timeout()).build();
  }

  public Delivery post(byte[] bundleJson) {
    String bearer;
    try {
      bearer = bearer();
    } catch (TokenException e) {
      return new Delivery(Outcome.RETRY, e.status, "token-" + e.reason);
    }
    try {
      HttpResponse<Void> response =
          client.send(
              HttpRequest.newBuilder(URI.create(settings.fhirUrl()))
                  .timeout(settings.timeout())
                  .header("Authorization", "Bearer " + bearer)
                  .header("Content-Type", "application/fhir+json")
                  .header("Accept", "application/fhir+json")
                  .POST(HttpRequest.BodyPublishers.ofByteArray(bundleJson))
                  .build(),
              HttpResponse.BodyHandlers.discarding());
      int status = response.statusCode();
      if (status >= 200 && status < 300) {
        return new Delivery(Outcome.DELIVERED, status, null);
      }
      if (status == 401) {
        token = null; // expired or revoked early: fetch a new one next time
        return new Delivery(Outcome.RETRY, status, "unauthorized");
      }
      if (status == 408 || status == 425 || status == 429 || status >= 500) {
        return new Delivery(Outcome.RETRY, status, "clinichub-unavailable");
      }
      return new Delivery(Outcome.REJECTED, status, "clinichub-refused");
    } catch (IOException e) {
      return new Delivery(Outcome.RETRY, null, "clinichub-unreachable");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return new Delivery(Outcome.RETRY, null, "interrupted");
    }
  }

  private static final class TokenException extends Exception {
    final Integer status;
    final String reason;

    TokenException(Integer status, String reason) {
      super(reason, null, false, false);
      this.status = status;
      this.reason = reason;
    }
  }

  private synchronized String bearer() throws TokenException {
    if (token != null && Instant.now().isBefore(tokenExpires.minusSeconds(30))) {
      return token;
    }
    String form =
        "grant_type=client_credentials&client_id="
            + enc(settings.clientId())
            + "&client_secret="
            + enc(settings.clientSecret())
            + "&scope="
            + enc(settings.scope());
    try {
      HttpResponse<byte[]> response =
          client.send(
              HttpRequest.newBuilder(URI.create(settings.tokenUrl()))
                  .timeout(settings.timeout())
                  .header("Content-Type", "application/x-www-form-urlencoded")
                  .POST(HttpRequest.BodyPublishers.ofString(form))
                  .build(),
              HttpResponse.BodyHandlers.ofByteArray());
      if (response.statusCode() != 200) {
        throw new TokenException(response.statusCode(), "refused");
      }
      JsonNode body = json.readTree(response.body());
      String access = body.path("access_token").asText("");
      if (access.isEmpty()) {
        throw new TokenException(response.statusCode(), "empty");
      }
      token = access;
      tokenExpires = Instant.now().plus(Duration.ofSeconds(body.path("expires_in").asLong(300)));
      return access;
    } catch (IOException e) {
      throw new TokenException(null, "unreachable");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new TokenException(null, "interrupted");
    }
  }

  private static String enc(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }
}
