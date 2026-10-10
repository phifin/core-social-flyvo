package com.flyvo.socialcore.content.domain.post;

import java.net.URI;
import java.text.Normalizer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/** Plain text and explicitly validated UTF-16 mention spans. No remote link fetching. */
public record PostBody(
    String text, String linkUrl, List<Mention> mentions, List<Hashtag> hashtags) {
  private static final Pattern TAG = Pattern.compile("#([\\p{L}\\p{N}\\p{M}_]+)");

  public record Mention(UUID userId, int start, int end) {}

  public record Hashtag(String normalized, String display) {}

  public PostBody(String text, String linkUrl, List<Mention> mentions) {
    this(text, linkUrl, mentions, null);
  }

  public PostBody {
    if (text == null
        || text.codePointCount(0, text.length()) > 10000
        || mentions == null
        || mentions.size() > 20) throw invalid();
    for (int at = 0; at < text.length(); at++) {
      char unit = text.charAt(at);
      if (Character.isHighSurrogate(unit)) {
        if (++at >= text.length() || !Character.isLowSurrogate(text.charAt(at))) throw invalid();
      } else if (Character.isLowSurrogate(unit)) throw invalid();
    }
    if (linkUrl != null) {
      try {
        var uri = URI.create(linkUrl);
        if (linkUrl.length() > 2048
            || !("http".equalsIgnoreCase(uri.getScheme())
                || "https".equalsIgnoreCase(uri.getScheme()))
            || uri.getHost() == null
            || uri.getRawUserInfo() != null) throw invalid();
      } catch (IllegalArgumentException failure) {
        throw invalid();
      }
    }
    var seen = new java.util.HashSet<String>();
    for (var mention : mentions) {
      if (mention == null
          || mention.userId() == null
          || mention.start() < 0
          || mention.end() <= mention.start()
          || mention.end() > text.length()
          || splitsSurrogate(text, mention.start())
          || splitsSurrogate(text, mention.end())
          || !seen.add(mention.userId() + ":" + mention.start())) throw invalid();
    }
    mentions = List.copyOf(mentions);
    hashtags = parseHashtags(text); // Normalize once; never trust supplied derived values.
  }

  public void requireNonempty(int mediaCount) {
    if (mediaCount < 0 || mediaCount > 10 || text.isBlank() && linkUrl == null && mediaCount == 0)
      throw invalid();
  }

  private static List<Hashtag> parseHashtags(String text) {
    var tags = new LinkedHashMap<String, Hashtag>();
    var matcher = TAG.matcher(text);
    while (matcher.find()) {
      String display = matcher.group(1);
      String normalized =
          Normalizer.normalize(display, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
      if (display.codePointCount(0, display.length()) > 50
          || normalized.codePointCount(0, normalized.length()) > 50) throw invalid();
      tags.putIfAbsent(normalized, new Hashtag(normalized, display));
      if (tags.size() > 10) throw invalid();
    }
    return List.copyOf(tags.values());
  }

  private static boolean splitsSurrogate(String text, int at) {
    return at > 0
        && at < text.length()
        && Character.isHighSurrogate(text.charAt(at - 1))
        && Character.isLowSurrogate(text.charAt(at));
  }

  private static IllegalArgumentException invalid() {
    return new IllegalArgumentException("INVALID_POST_BODY");
  }
}
