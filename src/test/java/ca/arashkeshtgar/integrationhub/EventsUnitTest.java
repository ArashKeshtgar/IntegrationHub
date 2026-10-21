package ca.arashkeshtgar.integrationhub;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ca.arashkeshtgar.integrationhub.events.EventsProperties;
import ca.arashkeshtgar.integrationhub.events.Pseudonymizer;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class EventsUnitTest {

  static EventsProperties properties(boolean enabled, String secret) {
    return new EventsProperties(
        enabled,
        secret,
        "urn:clinichub:{clinic}:mrn",
        "clinical.events",
        "q",
        Duration.ofSeconds(2),
        Duration.ofMinutes(5),
        50,
        7,
        365);
  }

  @Test
  void aPseudonymIsStableAndDependsOnClinicMrnAndSecret() {
    var p = new Pseudonymizer(properties(true, "a".repeat(40)));
    String one = p.of("harbourview", "MRN-1");

    assertThat(one).hasSize(64).matches("[0-9a-f]{64}");
    assertThat(p.of("harbourview", "MRN-1")).isEqualTo(one);
    assertThat(p.of("harbourview", "MRN-2")).isNotEqualTo(one);
    assertThat(p.of("lakeshore", "MRN-1")).isNotEqualTo(one);
    assertThat(new Pseudonymizer(properties(true, "b".repeat(40))).of("harbourview", "MRN-1"))
        .isNotEqualTo(one);
    // "a|bc" and "ab|c" are different people.
    assertThat(p.of("a", "bc")).isNotEqualTo(p.of("ab", "c"));
  }

  @Test
  void enablingEventsNeedsALongSecret() {
    assertThatThrownBy(() -> properties(true, null)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> properties(true, "too-short"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(properties(false, null).enabled()).isFalse();
    assertThat(properties(true, "s".repeat(32)).enabled()).isTrue();
  }

  @Test
  void aPseudonymizerWithoutASecretRefuses() {
    assertThatThrownBy(() -> new Pseudonymizer(properties(false, null)).of("c", "m"))
        .isInstanceOf(IllegalStateException.class);
  }
}
