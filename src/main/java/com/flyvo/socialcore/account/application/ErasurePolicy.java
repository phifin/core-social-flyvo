package com.flyvo.socialcore.account.application;

import com.flyvo.socialcore.account.api.AccountErasure;
import com.flyvo.socialcore.platform.api.Failure;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class ErasurePolicy {
  private final boolean enabled;
  private final boolean archiveConfigured;
  private final java.util.UUID lineage;

  public ErasurePolicy(Environment env) {
    enabled = env.getProperty("app.erasure.enabled", Boolean.class, false);
    archiveConfigured = !env.getProperty("app.erasure.archive-directory", "").isBlank();
    lineage =
        enabled ? java.util.UUID.fromString(env.getRequiredProperty("app.erasure.lineage")) : null;
    if (enabled
        && (!AccountErasure.POLICY.equals(env.getProperty("app.erasure.policy"))
            || !env.getRequiredProperty("app.database.url")
                .matches(
                    "jdbc:postgresql://(?:localhost|127\\.0\\.0\\.1|postgres):[0-9]+/p9a_[a-z0-9_]+(?:\\?.*)?")))
      throw new IllegalStateException("ERASURE_REQUIRES_ISOLATED_SYNTHETIC_DATABASE");
  }

  public boolean enabled() {
    return enabled;
  }

  public java.util.UUID lineage() {
    return lineage;
  }

  public void requireEnabled() {
    if (!enabled) throw new Failure(503, "ERASURE_SYNTHETIC_POLICY_DISABLED");
    if (!archiveConfigured) throw new Failure(503, "RECOVERY_ARCHIVE_UNAVAILABLE");
  }
}
