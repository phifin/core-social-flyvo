package com.flyvo.socialcore.graph.domain;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

/** UUID text order matches PostgreSQL unsigned UUID byte order. */
public record UserPair(UUID low, UUID high) {
  public UserPair {
    if (low == null || high == null || low.toString().compareTo(high.toString()) >= 0)
      throw new IllegalArgumentException("Distinct ordered users required");
  }

  public static UserPair of(UUID a, UUID b) {
    if (a == null || b == null || a.equals(b))
      throw new IllegalArgumentException("Distinct users required");
    return a.toString().compareTo(b.toString()) < 0 ? new UserPair(a, b) : new UserPair(b, a);
  }

  public long lockKey() {
    try {
      return ByteBuffer.wrap(
              MessageDigest.getInstance("SHA-256")
                  .digest(
                      ("social-core:graph-pair:v1:" + low + ":" + high)
                          .getBytes(StandardCharsets.US_ASCII)))
          .getLong();
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }
}
