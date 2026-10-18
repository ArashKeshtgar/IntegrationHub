package ca.arashkeshtgar.integrationhub;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

class MessageLogSchemaTest extends PostgresTestBase {

  @Autowired JdbcTemplate jdbc;

  @Test
  void migrationCreatesTheLogTables() {
    List<String> tables =
        jdbc.queryForList(
            "select table_name from information_schema.tables where table_schema = 'public'",
            String.class);
    assertThat(tables).contains("message", "message_identifier");
  }

  @Test
  void messageTableHasNoColumnThatCouldHoldABody() {
    List<String> columns =
        jdbc.queryForList(
            "select column_name from information_schema.columns where table_name = 'message'",
            String.class);
    assertThat(columns)
        .contains("request_sha256", "request_size", "resource_summary")
        .doesNotContain("body", "request_body", "payload", "response_body");
  }

  @Test
  void identifiersAreSearchableAndDeletedWithTheirMessage() {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "insert into message (id, route, direction, method, path) values (?, 'clinichub',"
            + " 'inbound', 'POST', '/fhir')",
        id);
    jdbc.update(
        "insert into message_identifier (message_id, system, value) values (?,"
            + " 'urn:clinichub:harbourview:service-request', '42')",
        id);

    Integer found =
        jdbc.queryForObject(
            "select count(*) from message_identifier where message_id = ? and system ="
                + " 'urn:clinichub:harbourview:service-request' and value = '42'",
            Integer.class,
            id);
    assertThat(found).isEqualTo(1);

    jdbc.update("delete from message where id = ?", id);
    // Other tests share the database: only this message's identifiers are counted.
    assertThat(
            jdbc.queryForObject(
                "select count(*) from message_identifier where message_id = ?", Integer.class, id))
        .isZero();
  }

  @Test
  void directionIsConstrained() {
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "insert into message (id, route, direction, method, path) values (?, 'x',"
                        + " 'sideways', 'GET', '/')",
                    UUID.randomUUID()))
        .isInstanceOf(DataIntegrityViolationException.class);
  }
}
