package ca.arashkeshtgar.integrationhub.events;

import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * What the hub remembers of an order so that a later result or cancel, which do not carry the
 * patient, can still be told apart: pseudonym, application, test and priority. De-identified.
 */
@Component
public class OrderLinks {

  public record Link(
      String clinicSlug,
      String orderId,
      String patientPseudonym,
      String application,
      String testCodeSystem,
      String testCode,
      String priority) {}

  private final JdbcTemplate jdbc;

  public OrderLinks(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public Optional<Link> find(String clinicSlug, String orderId) {
    return jdbc
        .query(
            """
            select clinic_slug, order_id, patient_pseudonym, application, test_code_system, test_code, priority
              from order_link where clinic_slug = ? and order_id = ?
            """,
            (rs, i) ->
                new Link(
                    rs.getString(1),
                    rs.getString(2),
                    rs.getString(3),
                    rs.getString(4),
                    rs.getString(5),
                    rs.getString(6),
                    rs.getString(7)),
            clinicSlug,
            orderId)
        .stream()
        .findFirst();
  }

  /** Call inside the transaction that writes the log row. A resent order keeps what it had. */
  public void upsert(Link link) {
    jdbc.update(
        """
        insert into order_link (clinic_slug, order_id, patient_pseudonym, application, test_code_system, test_code, priority)
        values (?, ?, ?, ?, ?, ?, ?)
        on conflict (clinic_slug, order_id) do update set
          patient_pseudonym = coalesce(excluded.patient_pseudonym, order_link.patient_pseudonym),
          application = coalesce(excluded.application, order_link.application),
          test_code_system = coalesce(excluded.test_code_system, order_link.test_code_system),
          test_code = coalesce(excluded.test_code, order_link.test_code),
          priority = coalesce(excluded.priority, order_link.priority)
        """,
        link.clinicSlug(),
        link.orderId(),
        link.patientPseudonym(),
        link.application(),
        link.testCodeSystem(),
        link.testCode(),
        link.priority());
  }
}
