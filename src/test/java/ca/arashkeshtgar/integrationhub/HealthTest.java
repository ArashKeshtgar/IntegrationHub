package ca.arashkeshtgar.integrationhub;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;

class HealthTest extends PostgresTestBase {

  @Autowired TestRestTemplate http;

  @Test
  void healthIsUpAndOpen() {
    var response = http.getForEntity("/actuator/health", String.class);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).contains("\"status\":\"UP\"");
  }

  @Test
  void infoIsOpenAndHasBuildVersion() {
    var response = http.getForEntity("/actuator/info", String.class);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).contains("\"build\"").contains("\"version\"");
  }

  @Test
  void everythingElseNeedsCredentials() {
    var response = http.getForEntity("/api/messages", String.class);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
  }
}
