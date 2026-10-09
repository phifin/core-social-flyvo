package com.flyvo.socialcore.identity.domain;

import java.net.IDN;
import java.util.Locale;

public record EmailAddress(String normalized) {
  public static EmailAddress parse(String input) {
    if (input == null || input.length() > 320) throw new IllegalArgumentException("Invalid email");
    String text = input.trim().toLowerCase(Locale.ROOT);
    int at = text.lastIndexOf('@');
    if (at < 1 || at != text.indexOf('@')) throw new IllegalArgumentException("Invalid email");
    String local = text.substring(0, at);
    String domain =
        IDN.toASCII(text.substring(at + 1), IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
    if (local.length() > 64
        || !local.matches("[\\p{L}\\p{N}.!#$%&'*+/=?^_`{|}~-]+")
        || local.startsWith(".")
        || local.endsWith(".")
        || local.contains("..")
        || !domain.contains(".")
        || text.length() > 254) throw new IllegalArgumentException("Invalid email");
    String normalized = local + "@" + domain;
    if (normalized.length() > 254) throw new IllegalArgumentException("Invalid email");
    return new EmailAddress(normalized);
  }
}
