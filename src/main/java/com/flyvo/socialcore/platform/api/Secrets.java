package com.flyvo.socialcore.platform.api;

public interface Secrets {
  String randomToken();

  byte[] hash(String value);

  byte[] emailKey(String value);

  byte[] encrypt(String purpose, String value);

  String decrypt(String purpose, byte[] value);

  String keyId();
}
