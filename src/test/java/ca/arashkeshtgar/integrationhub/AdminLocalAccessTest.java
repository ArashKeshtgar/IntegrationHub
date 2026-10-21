package ca.arashkeshtgar.integrationhub;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * The dev profile's setting: calls from this machine need no token, calls that were forwarded do.
 */
@TestPropertySource(
    properties = {"hub.admin.local-access=true", "hub.admin.audience=integrationhub"})
class AdminLocalAccessTest extends RelayTestBase {

  HttpResponse<String> get(String path, String bearer, String headerName, String headerValue)
      throws Exception {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET();
    if (bearer != null) {
      request.header("Authorization", "Bearer " + bearer);
    }
    if (headerName != null) {
      request.header(headerName, headerValue);
    }
    return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  static String token(String audience, String permission) {
    return sign(
        validClaims().audience(audience).claim("permission", List.of(permission)).build(), KEY);
  }

  @Test
  void aCallFromThisMachineNeedsNoToken() throws Exception {
    assertThat(get("/api/messages", null, null, null).statusCode()).isEqualTo(200);
    assertThat(get("/actuator/prometheus", null, null, null).statusCode()).isEqualTo(200);
  }

  @Test
  void aForwardedCallIsNotLocal() throws Exception {
    for (String header : List.of("X-Forwarded-For", "Forwarded", "X-Real-IP")) {
      assertThat(get("/api/messages", null, header, "10.0.0.7").statusCode())
          .as(header)
          .isEqualTo(401);
    }
  }

  @Test
  void aForwardedCallWithATokenIsCheckedLikeAnyOther() throws Exception {
    String good = token("integrationhub", AdminApiTest.PERMISSION);
    String wrongAudience = token("clinichub-results", AdminApiTest.PERMISSION);
    String noPermission = token("integrationhub", "integrationhub:payloads.view");

    assertThat(get("/api/messages", good, "X-Forwarded-For", "10.0.0.7").statusCode())
        .isEqualTo(200);
    assertThat(get("/api/messages", wrongAudience, "X-Forwarded-For", "10.0.0.7").statusCode())
        .isEqualTo(403);
    assertThat(get("/api/messages", noPermission, "X-Forwarded-For", "10.0.0.7").statusCode())
        .isEqualTo(403);
  }
}
