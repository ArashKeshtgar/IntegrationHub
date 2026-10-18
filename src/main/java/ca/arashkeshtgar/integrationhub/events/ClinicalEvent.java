package ca.arashkeshtgar.integrationhub.events;

import java.time.Instant;
import java.util.UUID;

/**
 * A de-identified event, schema version 1 (see docs/events.md). Nothing here can name a person: the
 * patient is an HMAC pseudonym, and there are no names, health cards, birth dates, notes, result
 * values or amounts.
 */
public record ClinicalEvent(
    int schemaVersion,
    UUID eventId,
    String type,
    Instant occurredAt,
    String clinicSlug,
    String application,
    String orderId,
    String testCodeSystem,
    String testCode,
    String priority,
    String status,
    String patientPseudonym,
    Finance finance) {

  public static final int SCHEMA_VERSION = 1;

  /** Only for {@code finance.document}: which document, not what is in it. */
  public record Finance(String sourceKey, String externalId, String kind) {}
}
