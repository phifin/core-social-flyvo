package com.flyvo.socialcore.identity;

import static org.assertj.core.api.Assertions.*;

import com.flyvo.socialcore.platform.infrastructure.security.ProtectedSecrets;
import java.nio.file.*;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

class SecretPersistenceTest {
  @TempDir Path directory;

  @Test
  void externalKeysRecoverMailAcrossRestartAndRejectTamperAndWrongPurpose() throws Exception {
    var key = new byte[32];
    new java.security.SecureRandom().nextBytes(key);
    var path = directory.resolve("key.b64");
    Files.writeString(path, Base64.getEncoder().encodeToString(key));
    var env =
        new MockEnvironment()
            .withProperty("app.security.encryption-key-file", path.toString())
            .withProperty("app.security.limiter-key-file", path.toString());
    var first = new ProtectedSecrets(env);
    var encrypted = first.encrypt("mail:fixture", "fixture secret payload");
    var restarted = new ProtectedSecrets(env);
    assertThat(restarted.decrypt("mail:fixture", encrypted)).isEqualTo("fixture secret payload");
    assertThatThrownBy(() -> restarted.decrypt("mail:other", encrypted))
        .isInstanceOf(IllegalStateException.class);
    encrypted[encrypted.length - 1] ^= 1;
    assertThatThrownBy(() -> restarted.decrypt("mail:fixture", encrypted))
        .isInstanceOf(IllegalStateException.class);
    assertThat(restarted.randomToken()).hasSize(43);
  }

  @Test
  void productionCannotEnableEphemeralSecrets() {
    var env = new MockEnvironment().withProperty("app.security.ephemeral-keys", "true");
    assertThatThrownBy(() -> new ProtectedSecrets(env)).isInstanceOf(IllegalStateException.class);
  }
}
