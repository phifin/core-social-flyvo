package com.flyvo.socialcore.account;

import static org.assertj.core.api.Assertions.*;

import com.flyvo.socialcore.account.application.*;
import com.flyvo.socialcore.platform.infrastructure.recovery.SyntheticRecoveryArchive;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class ErasureActivationTest {
  @Test
  void defaultHasNoRuntimeArchiveOrMaintenanceFallback() {
    var env = new MockEnvironment();
    var policy = new ErasurePolicy(env);
    assertThat(policy.enabled()).isFalse();
    assertThatThrownBy(policy::requireEnabled).hasMessage("ERASURE_SYNTHETIC_POLICY_DISABLED");
    assertThatThrownBy(
            () ->
                new SyntheticRecoveryArchive(env)
                    .append(UUID.randomUUID(), ErasureProtocol.digest(""), new byte[0]))
        .hasMessage("RECOVERY_ARCHIVE_UNAVAILABLE");
  }

  @Test
  void existingDeveloperDatabaseCannotEnableDestructiveSyntheticPolicy() {
    var env =
        new MockEnvironment()
            .withProperty("app.erasure.enabled", "true")
            .withProperty("app.erasure.policy", "isolated-synthetic-v1")
            .withProperty("app.erasure.lineage", UUID.randomUUID().toString())
            .withProperty("app.database.url", "jdbc:postgresql://localhost:5432/social_core");
    assertThatThrownBy(() -> new ErasurePolicy(env))
        .hasMessage("ERASURE_REQUIRES_ISOLATED_SYNTHETIC_DATABASE");
  }
}
