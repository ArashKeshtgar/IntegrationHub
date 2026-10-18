package ca.arashkeshtgar.integrationhub.hl7;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class Hl7MessageTest {

  @Test
  void parsesSegmentsAndFields() throws Exception {
    Hl7Message m = Hl7Message.parse(Hl7Samples.load("oru-final.hl7"));
    assertThat(m.first("MSH").orElseThrow().component(9, 1)).isEqualTo("ORU");
    assertThat(m.first("MSH").orElseThrow().component(9, 2)).isEqualTo("R01");
    assertThat(m.first("MSH").orElseThrow().field(10)).isEqualTo("MSG0001");
    assertThat(m.all("OBX")).hasSize(4);
    assertThat(m.all("OBX").get(0).component(3, 2)).isEqualTo("Glucose");
  }

  @Test
  void acceptsLfAndCrlfAndMllpFraming() throws Exception {
    String text = Hl7Samples.load("oru-preliminary.hl7");
    assertThat(Hl7Message.parse(text.replace("\r", "\n")).all("OBX")).hasSize(1);
    assertThat(Hl7Message.parse(text.replace("\r", "\r\n")).all("OBX")).hasSize(1);
    assertThat(Hl7Message.parse("\u000b" + text + "\u001c\r").all("OBX")).hasSize(1);
  }

  @Test
  void repetitionsAndEscapes() throws Exception {
    Hl7Message m = Hl7Message.parse(Hl7Samples.load("oru-final.hl7"));
    assertThat(m.all("OBX").get(1).repetitions(8)).containsExactly("H", "A");
    assertThat(m.all("OBX").get(2).unescapedField(5))
        .isEqualTo("Synthetic free text with & ampersand");
  }

  @Test
  void hexEscapesAreDecodedAndMalformedOnesDropped() throws Exception {
    String base = "MSH|^~\\&|A|B|C|D|20260101||ORU^R01|1|P|2.5\rOBX|1|ST|x||";
    assertThat(Hl7Message.parse(base + "a\\X41\\b\r").all("OBX").get(0).unescapedField(5))
        .isEqualTo("aAb");
    assertThat(Hl7Message.parse(base + "a\\XZZ\\b\r").all("OBX").get(0).unescapedField(5))
        .isEqualTo("ab");
    assertThat(Hl7Message.parse(base + "a\\X4\\b\r").all("OBX").get(0).unescapedField(5))
        .isEqualTo("ab");
  }

  @Test
  void escapeStandardRoundTrips() throws Exception {
    String text = "a|b^c~d\\e&f";
    String msg =
        "MSH|^~\\&|A|B|C|D|20260101||ORU^R01|1|P|2.5\rNTE|1||"
            + Hl7Message.escapeStandard(text)
            + "\r";
    assertThat(Hl7Message.parse(msg).first("NTE").orElseThrow().unescapedField(3)).isEqualTo(text);
  }

  @Test
  void rejectsMalformedMessages() {
    assertThatThrownBy(() -> Hl7Message.parse("")).isInstanceOf(Hl7Exception.class);
    assertThatThrownBy(() -> Hl7Message.parse("PID|1\r")).isInstanceOf(Hl7Exception.class);
    assertThatThrownBy(() -> Hl7Message.parse("MSH|^\r")).isInstanceOf(Hl7Exception.class);
    assertThatThrownBy(() -> Hl7Message.parse("MSH|^~\\&|A\rx|1\r"))
        .isInstanceOf(Hl7Exception.class);
  }

  @Test
  void timeKeepsPrecisionAndZone() {
    var zone = java.time.ZoneId.of("America/Toronto");
    assertThat(Hl7Time.toFhir("2026", zone)).contains("2026");
    assertThat(Hl7Time.toFhir("202603", zone)).contains("2026-03");
    assertThat(Hl7Time.toFhir("20260315", zone)).contains("2026-03-15");
    assertThat(Hl7Time.toFhir("20260315101500-0400", zone)).contains("2026-03-15T10:15:00-04:00");
    assertThat(Hl7Time.toFhir("20260315101500", zone)).contains("2026-03-15T10:15:00-04:00");
    assertThat(Hl7Time.toFhir("", zone)).isEmpty();
    assertThat(Hl7Time.toFhir("garbage", zone)).isEmpty();
  }
}
