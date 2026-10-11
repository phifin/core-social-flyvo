package com.flyvo.socialcore.moderation;

import static org.assertj.core.api.Assertions.*;

import com.flyvo.socialcore.moderation.domain.Report;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ReportTest {
  @Test
  void transitionsRetainIdentityEvidenceAndMonotonicVersionAndAreTerminal() {
    var ciphertext = new byte[] {1, 2, 3};
    var now = Instant.parse("2026-10-11T00:00:00Z");
    var r =
        new Report(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "USER",
            UUID.randomUUID(),
            null,
            null,
            null,
            "SPAM",
            ciphertext,
            "test-key",
            "OPEN",
            now,
            null,
            0);
    ciphertext[0] = 9;
    assertThat(r.evidence()).containsExactly((byte) 1, (byte) 2, (byte) 3);
    var returned = r.evidence();
    returned[1] = 9;
    assertThat(r.evidence()).containsExactly((byte) 1, (byte) 2, (byte) 3);
    var review = r.transition(true, false, now);
    assertThat(review.status()).isEqualTo("IN_REVIEW");
    assertThat(review.version()).isEqualTo(1);
    assertThatThrownBy(() -> review.transition(true, false, now))
        .isInstanceOf(IllegalStateException.class);
    var resolved = review.transition(false, false, now.plusSeconds(1));
    assertThat(resolved.version()).isEqualTo(2);
    assertThat(resolved.resolved()).isEqualTo(now.plusSeconds(1));
    assertThatThrownBy(() -> resolved.transition(false, true, now))
        .isInstanceOf(IllegalStateException.class);
    assertThat(r.transition(false, true, now).status()).isEqualTo("DISMISSED");
  }
}
