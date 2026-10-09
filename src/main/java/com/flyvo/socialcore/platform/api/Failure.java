package com.flyvo.socialcore.platform.api;

public final class Failure extends RuntimeException {
  private final int status;
  private final String code;

  public Failure(int status, String code) {
    super(code);
    this.status = status;
    this.code = code;
  }

  public int status() {
    return status;
  }

  public String code() {
    return code;
  }

  public static Failure invalid() {
    return new Failure(400, "INVALID_INPUT");
  }

  public static Failure unauthorized() {
    return new Failure(401, "AUTHENTICATION_REQUIRED");
  }

  public static Failure conflict() {
    return new Failure(409, "CONFLICT");
  }
}
