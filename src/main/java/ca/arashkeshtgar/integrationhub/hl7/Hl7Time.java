package ca.arashkeshtgar.integrationhub.hl7;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * HL7 timestamps ({@code YYYY[MM[DD[HH[MM[SS[.S+]]]]]][+/-ZZZZ]}) as FHIR dates: the precision that
 * was written is kept ({@code 2026-10} stays a month), a time gets the zone it was sent in, or the
 * configured default zone when it was sent without one (HL7 times are local to the sender).
 */
public final class Hl7Time {

  private static final Pattern TS =
      Pattern.compile(
          "^(\\d{4})(\\d{2})?(\\d{2})?(\\d{2})?(\\d{2})?(\\d{2})?(?:\\.\\d{1,4})?([+-]\\d{4})?$");
  private static final DateTimeFormatter FHIR_DATE_TIME =
      DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");

  private Hl7Time() {}

  /** The FHIR form of an HL7 timestamp, or empty when it is not one. */
  public static Optional<String> toFhir(String hl7, ZoneId defaultZone) {
    if (hl7 == null || hl7.isBlank()) {
      return Optional.empty();
    }
    Matcher m = TS.matcher(hl7.trim());
    if (!m.matches()) {
      return Optional.empty();
    }
    try {
      int year = Integer.parseInt(m.group(1));
      if (m.group(2) == null) {
        return Optional.of(String.format("%04d", year));
      }
      int month = Integer.parseInt(m.group(2));
      if (m.group(3) == null) {
        LocalDateTime.of(year, month, 1, 0, 0);
        return Optional.of(String.format("%04d-%02d", year, month));
      }
      int day = Integer.parseInt(m.group(3));
      if (m.group(4) == null) {
        LocalDateTime.of(year, month, day, 0, 0);
        return Optional.of(String.format("%04d-%02d-%02d", year, month, day));
      }
      int hour = Integer.parseInt(m.group(4));
      int minute = m.group(5) == null ? 0 : Integer.parseInt(m.group(5));
      int second = m.group(6) == null ? 0 : Integer.parseInt(m.group(6));
      LocalDateTime local = LocalDateTime.of(year, month, day, hour, minute, second);
      ZoneId zone =
          m.group(7) == null
              ? defaultZone
              : ZoneOffset.of(m.group(7).substring(0, 3) + ":" + m.group(7).substring(3));
      return Optional.of(ZonedDateTime.of(local, zone).format(FHIR_DATE_TIME));
    } catch (RuntimeException e) {
      return Optional.empty();
    }
  }

  /** An instant as an HL7 timestamp in UTC: {@code 20261005133000+0000}. */
  public static String fromInstant(java.time.Instant instant) {
    return DateTimeFormatter.ofPattern("yyyyMMddHHmmssxx").format(instant.atOffset(ZoneOffset.UTC));
  }

  /** A FHIR dateTime/date as an HL7 timestamp, keeping its precision; empty when it is not one. */
  public static Optional<String> fromFhir(String fhir) {
    if (fhir == null || fhir.isBlank()) {
      return Optional.empty();
    }
    try {
      if (fhir.length() > 10) {
        return Optional.of(
            DateTimeFormatter.ofPattern("yyyyMMddHHmmssxx")
                .format(java.time.OffsetDateTime.parse(fhir)));
      }
      return Optional.of(fhir.replace("-", ""));
    } catch (RuntimeException e) {
      return Optional.empty();
    }
  }
}
