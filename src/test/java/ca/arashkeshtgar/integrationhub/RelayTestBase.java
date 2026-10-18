package ca.arashkeshtgar.integrationhub;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * WireMock plays ClinicHub twice: its API (the route's upstream) and its keys (JWKS). Tokens are
 * signed with a key generated for the test run, the way OpenIddict signs: RS256, {@code typ:
 * at+jwt}.
 */
abstract class RelayTestBase extends PostgresTestBase {

  static final String ISSUER = "http://localhost:5301/";
  static final WireMockServer CLINICHUB =
      new WireMockServer(WireMockConfiguration.options().dynamicPort());
  static final RSAKey KEY;
  static final RSAKey OTHER_KEY;
  static final int CLOSED_PORT;
  static final HttpClient HTTP = HttpClient.newHttpClient();

  static {
    try {
      CLINICHUB.start();
      KEY = new RSAKeyGenerator(2048).keyID("test-key").generate();
      OTHER_KEY = new RSAKeyGenerator(2048).keyID("test-key").generate();
      try (ServerSocket socket = new ServerSocket(0)) {
        CLOSED_PORT = socket.getLocalPort();
      }
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  @DynamicPropertySource
  static void relayProperties(DynamicPropertyRegistry registry) {
    registry.add("hub.auth.issuer", () -> ISSUER);
    registry.add("hub.auth.jwks-uri", () -> CLINICHUB.baseUrl() + "/.well-known/jwks");
    registry.add("hub.max-body-bytes", () -> "1048576");
    registry.add("hub.routes.clinichub.upstream", CLINICHUB::baseUrl);
    // Only for the audience check.
    registry.add("hub.routes.audience.upstream", CLINICHUB::baseUrl);
    registry.add("hub.routes.audience.allowed-audiences", () -> "clinichub-results");
    registry.add("hub.routes.audience.required-scopes", () -> "results");
    // Nothing listens here.
    registry.add("hub.routes.dead.upstream", () -> "http://localhost:" + CLOSED_PORT);
    registry.add("hub.routes.dead.required-scopes", () -> "results");
    // Shorter timeout than the stubbed delay.
    registry.add("hub.routes.slow.upstream", CLINICHUB::baseUrl);
    registry.add("hub.routes.slow.timeout", () -> "500ms");
    registry.add("hub.routes.slow.required-scopes", () -> "results");
  }

  @LocalServerPort int port;
  @Autowired JdbcTemplate jdbc;

  @BeforeEach
  void resetStubsAndLog() {
    CLINICHUB.resetAll();
    CLINICHUB.stubFor(
        get("/.well-known/jwks").willReturn(okJson(new JWKSet(KEY.toPublicJWK()).toString())));
    jdbc.update("delete from message");
  }

  /** A token as ClinicHub would issue it to LabFlow: scope results, expires in 5 minutes. */
  static JWTClaimsSet.Builder validClaims() {
    Instant now = Instant.now();
    return new JWTClaimsSet.Builder()
        .issuer(ISSUER)
        .subject("labflow")
        .claim("client_id", "labflow")
        .claim("scope", "results")
        .audience("clinichub-results")
        .issueTime(Date.from(now))
        .expirationTime(Date.from(now.plusSeconds(300)));
  }

  static String sign(JWTClaimsSet claims, RSAKey key) {
    try {
      SignedJWT jwt =
          new SignedJWT(
              new JWSHeader.Builder(JWSAlgorithm.RS256)
                  .type(new JOSEObjectType("at+jwt"))
                  .keyID(key.getKeyID())
                  .build(),
              claims);
      jwt.sign(new RSASSASigner(key));
      return jwt.serialize();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  static String validToken() {
    return sign(validClaims().build(), KEY);
  }

  HttpResponse<byte[]> send(String path, String bearer, String contentType, byte[] body)
      throws IOException, InterruptedException {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .POST(HttpRequest.BodyPublishers.ofByteArray(body));
    if (bearer != null) {
      request.header("Authorization", "Bearer " + bearer);
    }
    if (contentType != null) {
      request.header("Content-Type", contentType);
    }
    return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
  }

  HttpResponse<byte[]> postBundle(String route, String bearer, byte[] body)
      throws IOException, InterruptedException {
    return send("/route/" + route + "/fhir", bearer, "application/fhir+json", body);
  }

  List<java.util.Map<String, Object>> messages() {
    return jdbc.queryForList("select * from message order by received_at");
  }
}
