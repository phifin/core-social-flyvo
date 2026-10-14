package com.flyvo.socialcore.platform;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flyvo.socialcore.platform.api.EventEnvelope;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EventEnvelopeTest {
  @Test
  void rejectsInvalidSourceOrderingMetadata() {
    for (long version : new long[] {-1, 0}) {
      assertThatThrownBy(
              () ->
                  new EventEnvelope(
                      UUID.randomUUID(),
                      "Fact",
                      version < 0 ? 1 : 0,
                      "source",
                      UUID.randomUUID(),
                      version,
                      0,
                      Instant.now(),
                      "request",
                      null,
                      "{}"))
          .isInstanceOf(IllegalArgumentException.class);
    }
    assertThatThrownBy(
            () ->
                new EventEnvelope(
                    UUID.randomUUID(),
                    "Fact",
                    1,
                    "source",
                    UUID.randomUUID(),
                    0,
                    -1,
                    Instant.now(),
                    "request",
                    null,
                    "{}"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
