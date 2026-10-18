package ca.arashkeshtgar.integrationhub.events;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Writes events to {@code event_outbox}; call it inside the transaction of the log row. */
@Component
public class EventOutbox {

  private final JdbcTemplate jdbc;
  private final ObjectMapper json;

  public EventOutbox(JdbcTemplate jdbc, ObjectMapper json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  /** The same event twice (a resent call) is stored once. */
  public void add(UUID messageId, ClinicalEvent event) throws JsonProcessingException {
    jdbc.update(
        "insert into event_outbox (id, message_id, event_type, payload) values (?, ?, ?, ?::jsonb)"
            + " on conflict (id) do nothing",
        event.eventId(),
        messageId,
        event.type(),
        json.writeValueAsString(event));
  }
}
