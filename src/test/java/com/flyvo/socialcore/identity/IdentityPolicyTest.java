package com.flyvo.socialcore.identity;

import static org.assertj.core.api.Assertions.*;

import com.flyvo.socialcore.identity.domain.EmailAddress;
import com.flyvo.socialcore.identity.domain.IdentityState.Session;
import com.flyvo.socialcore.identity.domain.PasswordPolicy;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class IdentityPolicyTest {
  @Test
  void emailNormalizationUsesIdnaAndNeverRewritesDotsOrPlus() {
    assertThat(EmailAddress.parse(" A.B+Tag@BÜCHER.example ").normalized())
        .isEqualTo("a.b+tag@xn--bcher-kva.example");
    for (String value :
        new String[] {"a@", "a..b@example.org", "a@example", "a@b@example.org", "a b@example.org"})
      assertThatThrownBy(() -> EmailAddress.parse(value))
          .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void passwordUsesUnicodeCharactersAndNoCompositionRule() {
    PasswordPolicy.validate("🙂".repeat(15), Set.of());
    PasswordPolicy.validate("x".repeat(128), Set.of());
    for (String value :
        new String[] {
          "x".repeat(14), "x".repeat(129), "passwordpassword", "x".repeat(15) + "\uD800"
        })
      assertThatThrownBy(() -> PasswordPolicy.validate(value, Set.of("passwordpassword")))
          .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void expiryAndReauthenticationBoundariesAreStrict() {
    var now = Instant.parse("2026-10-09T00:00:00Z");
    var session =
        new Session(
            UUID.randomUUID(),
            UUID.randomUUID(),
            now.minusSeconds(300),
            null,
            now,
            now.plusSeconds(86400),
            null,
            false,
            0,
            0);
    assertThat(session.active(now)).isFalse();
    assertThat(session.recent(now)).isFalse();
  }
}
