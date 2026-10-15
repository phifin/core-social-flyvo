package com.flyvo.socialcore.search.domain;

import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;

/** A bounded plain-term language; no tsquery/websearch operators are accepted. */
public record SearchTerms(String normalized, String terms) {
  private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}_]+");

  public static String normalize(String value) {
    return Normalizer.normalize(value, Normalizer.Form.NFC)
        .strip()
        .replaceAll("\\s+", " ")
        .toLowerCase(Locale.ROOT);
  }

  public static SearchTerms parse(String value) {
    if (value == null || value.length() > 200) throw new IllegalArgumentException();
    String normalized = normalize(value);
    if (normalized.length() < 2 || normalized.length() > 200) throw new IllegalArgumentException();
    var words =
        WORD.matcher(normalized)
            .results()
            .map(m -> m.group())
            .filter(s -> s.codePoints().anyMatch(Character::isLetterOrDigit))
            .toList();
    if (words.isEmpty() || words.size() > 8) throw new IllegalArgumentException();
    return new SearchTerms(normalized, String.join(" ", words));
  }

  public String prefix() {
    return normalized.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
  }
}
