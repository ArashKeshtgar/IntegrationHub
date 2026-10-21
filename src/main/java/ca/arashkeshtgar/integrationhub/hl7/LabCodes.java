package ca.arashkeshtgar.integrationhub.hl7;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The external lab's test codes against LOINC ({@code hl7_code_map}), kept from the admin web. Read
 * on every order and result: the table is small and a change takes effect at once.
 */
@Component
public class LabCodes {

  private static final Pattern LOINC = Pattern.compile("^\\d{1,7}-\\d$");
  // The lab's code goes into an HL7 component: none of the separators or escape characters.
  private static final Pattern LAB_CODE = Pattern.compile("^[A-Za-z0-9._/-]{1,40}$");

  public record Entry(String loinc, String labCode, String labDisplay, Instant updatedAt) {}

  /** A map that cannot be saved; the message names the row, never more. */
  public static final class Invalid extends RuntimeException {
    public Invalid(String message) {
      super(message);
    }
  }

  private final JdbcTemplate jdbc;

  public LabCodes(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public List<Entry> all() {
    return jdbc.query(
        "select loinc, lab_code, lab_display, updated_at from hl7_code_map order by loinc",
        (rs, i) ->
            new Entry(
                rs.getString(1), rs.getString(2), rs.getString(3), rs.getTimestamp(4).toInstant()));
  }

  public Optional<Entry> forLoinc(String loinc) {
    return all().stream().filter(e -> e.loinc().equals(loinc)).findFirst();
  }

  public Optional<Entry> forLabCode(String code) {
    return all().stream().filter(e -> e.labCode().equalsIgnoreCase(code)).findFirst();
  }

  /** Replaces the whole map; all or nothing. */
  @Transactional
  public List<Entry> replace(List<Entry> entries) {
    Set<String> loincs = new HashSet<>();
    Set<String> codes = new HashSet<>();
    for (int i = 0; i < entries.size(); i++) {
      Entry e = entries.get(i);
      String row = "row " + (i + 1);
      if (e.loinc() == null || !LOINC.matcher(e.loinc().trim()).matches()) {
        throw new Invalid(row + ": the LOINC code is not in the form 1234-5");
      }
      if (e.labCode() == null || !LAB_CODE.matcher(e.labCode().trim()).matches()) {
        throw new Invalid(row + ": the lab's code is 1-40 letters, digits, . _ / -");
      }
      if (e.labDisplay() != null && e.labDisplay().length() > 200) {
        throw new Invalid(row + ": the lab's name is longer than 200 characters");
      }
      if (!loincs.add(e.loinc().trim())) {
        throw new Invalid(row + ": LOINC " + e.loinc().trim() + " is mapped twice");
      }
      if (!codes.add(e.labCode().trim().toUpperCase())) {
        throw new Invalid(row + ": the lab's code " + e.labCode().trim() + " is used twice");
      }
    }
    jdbc.update("delete from hl7_code_map");
    for (Entry e : entries) {
      String display =
          e.labDisplay() == null || e.labDisplay().isBlank() ? null : e.labDisplay().trim();
      jdbc.update(
          "insert into hl7_code_map (loinc, lab_code, lab_display) values (?, ?, ?)",
          e.loinc().trim(),
          e.labCode().trim(),
          display);
    }
    return all();
  }
}
