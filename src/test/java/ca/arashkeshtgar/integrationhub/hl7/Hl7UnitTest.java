package ca.arashkeshtgar.integrationhub.hl7;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ca.uhn.fhir.context.FhirContext;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.hl7.fhir.r4.model.Bundle;
import org.junit.jupiter.api.Test;

/**
 * The pieces of phase 4 that need no database or sockets: framing, ACKs, the cipher, settings, ORU
 * out.
 */
class Hl7UnitTest {

  private static final String KEY = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";

  static Hl7Properties properties(
      boolean enabled, String queueKey, String secret, boolean outbound, String host) {
    return new Hl7Properties(
        enabled,
        new Hl7Properties.Mllp(2575, "127.0.0.1", 1000, java.time.Duration.ofSeconds(1)),
        queueKey,
        "America/Toronto",
        List.of(),
        new Hl7Properties.Retry(
            java.time.Duration.ofSeconds(1),
            java.time.Duration.ofSeconds(2),
            3,
            java.time.Duration.ofSeconds(1),
            5),
        14,
        new Hl7Properties.ClinicHub(
            "http://t/token",
            "http://t/fhir",
            "integrationhub",
            secret,
            "results",
            java.time.Duration.ofSeconds(1)),
        new Hl7Properties.Outbound(
            outbound,
            host,
            2576,
            "INTEGRATIONHUB",
            "HUB",
            "OLIS",
            "OLIS",
            java.time.Duration.ofSeconds(1)));
  }

  @Test
  void mllpFramesAndReadsBack() throws Exception {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    Mllp.write(out, "MSH|a".getBytes(StandardCharsets.ISO_8859_1));
    Mllp.write(out, "MSH|b".getBytes(StandardCharsets.ISO_8859_1));
    byte[] bytes = out.toByteArray();
    assertThat(bytes[0]).isEqualTo((byte) 0x0B);
    assertThat(bytes[bytes.length - 2]).isEqualTo((byte) 0x1C);

    var in = new ByteArrayInputStream(bytes);
    assertThat(new String(Mllp.read(in, 100), StandardCharsets.ISO_8859_1)).isEqualTo("MSH|a");
    assertThat(new String(Mllp.read(in, 100), StandardCharsets.ISO_8859_1)).isEqualTo("MSH|b");
    assertThat(Mllp.read(in, 100)).isNull();
  }

  @Test
  void mllpRefusesBrokenFrames() {
    assertThatThrownBy(
            () -> Mllp.read(new ByteArrayInputStream(new byte[] {0x0B, 'a', 0x1C, 'x'}), 100))
        .isInstanceOf(Mllp.FrameException.class);
    assertThatThrownBy(() -> Mllp.read(new ByteArrayInputStream(new byte[] {0x0B, 'a', 'b'}), 100))
        .isInstanceOf(Mllp.FrameException.class);
    assertThatThrownBy(
            () -> Mllp.read(new ByteArrayInputStream(new byte[] {0x0B, 'a', 0x0B, 'b'}), 100))
        .isInstanceOf(Mllp.FrameException.class);
    byte[] big = new byte[200];
    java.util.Arrays.fill(big, (byte) 'a');
    var framed = new ByteArrayOutputStream();
    framed.write(0x0B);
    framed.writeBytes(big);
    assertThatThrownBy(() -> Mllp.read(new ByteArrayInputStream(framed.toByteArray()), 100))
        .isInstanceOf(Mllp.FrameException.class);
  }

  @Test
  void ackAddressesTheSenderAndEscapesItsText() throws Exception {
    Hl7Message m = Hl7Message.parse(Hl7Samples.load("oru-final.hl7"));
    String ack = Hl7Ack.build(m, Hl7Ack.ERROR, "a|b^c");
    String[] segments = ack.split("\r");
    assertThat(segments[0])
        .startsWith("MSH|^~\\&|INTEGRATIONHUB|HUB|EXTLAB|LAB1|")
        .contains("|ACK^R01|");
    assertThat(segments[1]).isEqualTo("MSA|AE|MSG0001|a\\F\\b\\S\\c");
    assertThat(Hl7Message.parse(ack).first("MSA").orElseThrow().unescapedField(3))
        .isEqualTo("a|b^c");

    String anonymous = Hl7Ack.build(null, Hl7Ack.REJECT, "x");
    assertThat(anonymous.split("\r")[1]).isEqualTo("MSA|AR||x");
  }

  @Test
  void cipherRoundTripsAndBindsThePayloadToItsRow() {
    PayloadCipher cipher = new PayloadCipher(properties(true, KEY, "s", false, null));
    byte[] plain = "synthetic payload".getBytes(StandardCharsets.UTF_8);
    byte[] sealed = cipher.encrypt(plain, "row-1");

    assertThat(new String(sealed, StandardCharsets.ISO_8859_1)).doesNotContain("synthetic");
    assertThat(cipher.decrypt(sealed, "row-1")).isEqualTo(plain);
    assertThat(cipher.encrypt(plain, "row-1")).as("fresh IV each time").isNotEqualTo(sealed);
    assertThatThrownBy(() -> cipher.decrypt(sealed, "row-2"))
        .isInstanceOf(IllegalStateException.class);
    sealed[sealed.length - 1] ^= 1;
    assertThatThrownBy(() -> cipher.decrypt(sealed, "row-1"))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void settingsAreCheckedWhenEnabled() {
    properties(false, null, null, false, null); // off: nothing needed
    assertThatThrownBy(() -> properties(true, null, "s", false, null))
        .hasMessageContaining("queue-key");
    assertThatThrownBy(() -> properties(true, "c2hvcnQ=", "s", false, null))
        .hasMessageContaining("queue-key");
    assertThatThrownBy(() -> properties(true, KEY, "", false, null))
        .hasMessageContaining("client-secret");
    assertThatThrownBy(() -> properties(true, KEY, "s", true, ""))
        .hasMessageContaining("outbound.host");
    assertThat(properties(true, KEY, "s", true, "olis").sendsOut()).isTrue();
    assertThat(properties(true, KEY, "s", false, null).sendsOut()).isFalse();
  }

  @Test
  void oruOutUsesThePseudonymOfTheOrderAndSkipsWhatItCannotExpress() throws Exception {
    var bundle =
        (Bundle)
            FhirContext.forR4Cached()
                .newJsonParser()
                .parseResource(
                    java.nio.file.Files.readString(
                        java.nio.file.Path.of(
                            "src/test/resources/hl7/result-bundle-for-olis.json")));
    var settings = properties(true, KEY, "s", true, "olis").outbound();

    var result =
        new FhirToOru(
                settings,
                (clinic, order) ->
                    clinic.equals("harbourview") && order.equals("101")
                        ? Optional.of("abc123")
                        : Optional.empty())
            .convert(
                bundle,
                "12345678-aaaa-bbbb-cccc-1234567890ab",
                Instant.parse("2026-03-15T14:00:00Z"));

    assertThat(result.messages()).hasSize(1);
    assertThat(result.skipped()).isEqualTo(1);
    String text = result.messages().get(0).text();
    assertThat(text).contains("PID|1||abc123^^^PSEUDONYM\r");
    assertThat(text).contains("|20260315140000+0000||ORU^R01|H12345678aaaabbbb-1|P|2.5.1");

    var obr = Hl7Message.parse(text).first("OBR").orElseThrow();
    assertThat(obr.field(22)).isEqualTo("20260315100000-0400");
    assertThat(obr.field(25)).isEqualTo("F");
    assertThat(obr.field(7)).isEqualTo("20260315083000-0400");
    // What it wrote is a valid message, and mapping it back gives the same order and status.
    Oru back = new OruToFhir(java.time.ZoneId.of("America/Toronto")).map(Hl7Message.parse(text));
    assertThat(back.forwardedStatuses()).hasSize(1);
    String json = FhirContext.forR4Cached().newJsonParser().encodeResourceToString(back.bundle());
    assertThat(json)
        .contains("urn:clinichub:harbourview:service-request", "\"value\":\"101\"", "5.4");
  }

  @Test
  void aReportWithoutAClinicHubOrderIsNotExported() {
    Bundle bundle = new Bundle();
    bundle
        .addEntry()
        .setResource(
            new org.hl7.fhir.r4.model.DiagnosticReport()
                .setStatus(org.hl7.fhir.r4.model.DiagnosticReport.DiagnosticReportStatus.FINAL));
    var result =
        new FhirToOru(
                properties(true, KEY, "s", true, "olis").outbound(), (c, o) -> Optional.empty())
            .convert(bundle, "12345678-aaaa-bbbb-cccc-1234567890ab", Instant.now());
    assertThat(result.messages()).isEmpty();
    assertThat(result.skipped()).isEqualTo(1);
  }
}
