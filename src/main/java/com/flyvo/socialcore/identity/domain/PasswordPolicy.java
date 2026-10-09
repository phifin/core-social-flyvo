package com.flyvo.socialcore.identity.domain;

import java.util.Locale;
import java.util.Set;

public final class PasswordPolicy {
  private PasswordPolicy() {}

  public static void validate(String value, Set<String> compromised) {
    if (value == null
        || value.codePointCount(0, value.length()) < 15
        || value.codePointCount(0, value.length()) > 128
        || compromised.contains(value.toLowerCase(Locale.ROOT)))
      throw new IllegalArgumentException("Invalid password");
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (Character.isHighSurrogate(c)) {
        if (i + 1 == value.length() || !Character.isLowSurrogate(value.charAt(++i)))
          throw new IllegalArgumentException("Invalid password");
      } else if (Character.isLowSurrogate(c))
        throw new IllegalArgumentException("Invalid password");
    }
  }
}
