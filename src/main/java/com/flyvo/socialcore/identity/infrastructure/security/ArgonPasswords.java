package com.flyvo.socialcore.identity.infrastructure.security;

import com.flyvo.socialcore.identity.application.port.PasswordHashing;
import com.flyvo.socialcore.platform.api.Failure;
import java.util.Map;
import java.util.concurrent.Semaphore;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class ArgonPasswords implements PasswordHashing {
  private final PasswordEncoder encoder =
      new DelegatingPasswordEncoder(
          "argon2id", Map.of("argon2id", new Argon2PasswordEncoder(16, 32, 1, 19456, 2)));
  private final Semaphore capacity = new Semaphore(4);

  public String encode(String value) {
    return bounded(() -> encoder.encode(value));
  }

  public boolean matches(String value, String encoded) {
    return bounded(() -> encoder.matches(value, encoded));
  }

  public boolean needsUpgrade(String encoded) {
    return encoder.upgradeEncoding(encoded);
  }

  private <T> T bounded(java.util.function.Supplier<T> work) {
    if (!capacity.tryAcquire()) throw new Failure(503, "HASH_CAPACITY_EXCEEDED");
    try {
      return work.get();
    } finally {
      capacity.release();
    }
  }
}
