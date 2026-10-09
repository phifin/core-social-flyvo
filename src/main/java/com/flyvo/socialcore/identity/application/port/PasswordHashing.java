package com.flyvo.socialcore.identity.application.port;

public interface PasswordHashing {
  String encode(String password);

  boolean matches(String password, String encoded);

  boolean needsUpgrade(String encoded);
}
