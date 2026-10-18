package com.flyvo.socialcore.support;

import com.flyvo.socialcore.account.api.AccountErasure;
import java.nio.file.*;
import org.springframework.test.context.DynamicPropertyRegistry;

/** Explicit isolated-container opt-in, never a runtime or application-test fallback. */
public final class SyntheticErasureFixture {
  private SyntheticErasureFixture() {}

  public static void enable(DynamicPropertyRegistry r) {
    final Path archive;
    try {
      archive = Files.createTempDirectory("social-p9a-regression-");
    } catch (java.io.IOException e) {
      throw new IllegalStateException(e);
    }
    r.add("app.erasure.enabled", () -> true);
    r.add("app.erasure.policy", () -> AccountErasure.POLICY);
    var lineage = java.util.UUID.randomUUID().toString();
    r.add("app.erasure.lineage", () -> lineage);
    r.add("app.erasure.archive-directory", archive::toString);
  }
}
