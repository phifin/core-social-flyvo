package com.flyvo.socialcore.platform.api;

public final class ResourceVersions {
  private ResourceVersions() {}

  public static long require(String match) {
    if (match == null) throw new Failure(428, "PRECONDITION_REQUIRED");
    if (!match.matches("\"[0-9]{1,18}\"")) throw Failure.invalid();
    return Long.parseLong(match.substring(1, match.length() - 1));
  }
}
