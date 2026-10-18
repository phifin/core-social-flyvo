package com.flyvo.socialcore.account.application;

import com.flyvo.socialcore.account.api.AccountErasure;
import com.flyvo.socialcore.account.api.AccountErasure.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

public final class ErasureProtocol {
  private ErasureProtocol() {}

  public static String digest(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  public static String rosterDigest() {
    return digest(
        AccountErasure.PROTOCOL
            + ":"
            + AccountErasure.POLICY
            + ":"
            + Arrays.stream(Step.values()).map(s -> s.owner() + "/" + s.step()).toList());
  }

  public static List<Step> predecessors(Step step) {
    return switch (step) {
      case RECOVERY_INTENT -> List.of();
      case IDENTITY, PROFILE, COMMUNITY, GRAPH, CONTENT, MODERATION, TECHNICAL ->
          List.of(Step.RECOVERY_INTENT);
      case MEDIA -> List.of(Step.PROFILE, Step.CONTENT);
      case NOTIFICATION, SEARCH ->
          List.of(
              Step.IDENTITY,
              Step.PROFILE,
              Step.COMMUNITY,
              Step.GRAPH,
              Step.CONTENT,
              Step.MODERATION,
              Step.TECHNICAL,
              Step.MEDIA);
      case COMPLETION -> Arrays.stream(Step.values()).filter(s -> s != Step.COMPLETION).toList();
    };
  }
}
