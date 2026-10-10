package com.flyvo.socialcore.content;

import static org.assertj.core.api.Assertions.*;

import com.flyvo.socialcore.content.application.policy.ContentAccessPolicy;
import com.flyvo.socialcore.content.application.policy.ContentAccessPolicy.*;
import com.flyvo.socialcore.content.domain.post.PostAudience;
import com.flyvo.socialcore.content.domain.post.PostBody;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PostPolicyTest {
  static final UUID AUTHOR = UUID.randomUUID();
  static final UUID VIEWER = UUID.randomUUID();
  static final UUID COMMUNITY = UUID.randomUUID();
  static final Instant NOW = Instant.parse("2026-10-10T00:00:00Z");

  @Test
  void personalAudienceMatrixKeepsCurrentDenialPrecedenceAndMuteIsNotPermission() {
    for (String audience : List.of("PUBLIC", "FOLLOWERS", "FRIENDS", "CUSTOM", "ONLY_ME")) {
      for (boolean active : List.of(false, true))
        for (boolean blocked : List.of(false, true))
          for (boolean following : List.of(false, true))
            for (boolean friends : List.of(false, true))
              for (boolean recipient : List.of(false, true))
                for (boolean muted : List.of(false, true)) {
                  var post = new PostFacts(AUTHOR, null, audience, "PUBLISHED");
                  var facts =
                      facts(
                          active, true, blocked, following, friends, recipient, muted, null, null,
                          null);
                  boolean eligible =
                      switch (audience) {
                        case "PUBLIC" -> true;
                        case "FOLLOWERS" -> following;
                        case "FRIENDS" -> friends;
                        case "CUSTOM" -> recipient;
                        default -> false;
                      };
                  assertThat(ContentAccessPolicy.canRead(VIEWER, post, facts, NOW))
                      .isEqualTo(active && !blocked && eligible);
                  assertThat(ContentAccessPolicy.canRead(AUTHOR, post, facts, NOW))
                      .isEqualTo(active && !blocked);
                  assertThat(ContentAccessPolicy.canRead(null, post, facts, NOW))
                      .isEqualTo(active && audience.equals("PUBLIC"));
                  assertThat(ContentAccessPolicy.canReadHistory(VIEWER, post, facts, NOW))
                      .isFalse();
                }
    }
  }

  @Test
  void communityMatrixIncludesAuthorDepartureArchiveExclusionAndLiveSuspension() {
    var post = new PostFacts(AUTHOR, COMMUNITY, "COMMUNITY", "PUBLISHED");
    for (String visibility : List.of("PUBLIC", "PRIVATE"))
      for (String status : List.of("ACTIVE", "ARCHIVED"))
        for (String membership : List.of("ACTIVE", "LEFT", "REMOVED", "BANNED", ""))
          for (Instant until : List.of(NOW.minusSeconds(1), NOW, NOW.plusSeconds(1))) {
            var facts =
                facts(true, true, false, false, false, false, false, visibility, membership, until);
            facts =
                new Facts(
                    facts.authorActive(),
                    facts.viewerActive(),
                    facts.blocked(),
                    false,
                    false,
                    false,
                    false,
                    visibility,
                    status,
                    membership,
                    "MODERATOR",
                    until);
            boolean eligible =
                !membership.equals("BANNED")
                    && !(membership.equals("ACTIVE") && until.isAfter(NOW))
                    && (visibility.equals("PUBLIC") || membership.equals("ACTIVE"));
            assertThat(ContentAccessPolicy.canRead(VIEWER, post, facts, NOW)).isEqualTo(eligible);
            assertThat(ContentAccessPolicy.canRead(AUTHOR, post, facts, NOW)).isEqualTo(eligible);
            assertThat(ContentAccessPolicy.canRead(null, post, facts, NOW))
                .isEqualTo(visibility.equals("PUBLIC"));
            assertThat(ContentAccessPolicy.canReadHistory(VIEWER, post, facts, NOW))
                .isEqualTo(eligible && membership.equals("ACTIVE"));
          }
  }

  @Test
  void unknownAndUnavailableFactsNeverGrantReadAndDeletionBeatsOwnership() {
    var valid = facts(true, true, false, true, true, true, false, null, null, null);
    for (String status : List.of("DELETED", "REMOVED", "UNKNOWN"))
      assertThat(
              ContentAccessPolicy.canRead(
                  AUTHOR, new PostFacts(AUTHOR, null, "PUBLIC", status), valid, NOW))
          .isFalse();
    assertThat(
            ContentAccessPolicy.canRead(
                VIEWER, new PostFacts(AUTHOR, null, "UNKNOWN", "PUBLISHED"), valid, NOW))
        .isFalse();
    assertThat(
            ContentAccessPolicy.canRead(
                VIEWER, new PostFacts(AUTHOR, COMMUNITY, "COMMUNITY", "PUBLISHED"), valid, NOW))
        .isFalse();
    assertThat(
            ContentAccessPolicy.canRead(
                VIEWER, new PostFacts(AUTHOR, null, "PUBLIC", "PUBLISHED"), null, NOW))
        .isFalse();
    assertThat(
            ContentAccessPolicy.canRead(
                VIEWER,
                new PostFacts(AUTHOR, null, "PUBLIC", "PUBLISHED"),
                facts(true, false, false, true, true, true, false, null, null, null),
                NOW))
        .isFalse();
  }

  @Test
  void audienceDiscriminantAndCustomRecipientsAreBoundedFixedAndCanonical() {
    assertThatThrownBy(
            () -> new PostAudience(AUTHOR, COMMUNITY, PostAudience.Type.PUBLIC, List.of()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new PostAudience(AUTHOR, null, PostAudience.Type.COMMUNITY, List.of()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new PostAudience(AUTHOR, null, PostAudience.Type.CUSTOM, List.of()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> new PostAudience(AUTHOR, null, PostAudience.Type.CUSTOM, List.of(AUTHOR)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> new PostAudience(AUTHOR, null, PostAudience.Type.CUSTOM, List.of(VIEWER, VIEWER)))
        .isInstanceOf(IllegalArgumentException.class);
    var hundred =
        java.util.stream.IntStream.range(0, 100).mapToObj(i -> UUID.randomUUID()).toList();
    assertThat(new PostAudience(AUTHOR, null, PostAudience.Type.CUSTOM, hundred).recipients())
        .isSortedAccordingTo(java.util.Comparator.comparing(UUID::toString));
    assertThatThrownBy(
            () ->
                new PostAudience(
                    AUTHOR,
                    null,
                    PostAudience.Type.CUSTOM,
                    java.util.stream.IntStream.range(0, 101)
                        .mapToObj(i -> UUID.randomUUID())
                        .toList()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void bodyMentionUtf16AndNonemptyConstraintsAreIndependentOfAudience() {
    String body = "Hi 😀 Bob";
    var valid =
        new PostBody(body, "https://example.org/link", List.of(new PostBody.Mention(VIEWER, 6, 9)));
    valid.requireNonempty(0);
    assertThatThrownBy(() -> new PostBody(body, null, List.of(new PostBody.Mention(VIEWER, 4, 5))))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new PostBody(body, null, List.of(new PostBody.Mention(VIEWER, 0, 4))))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new PostBody(body, null, List.of(new PostBody.Mention(VIEWER, 0, 10))))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new PostBody(body, "file:///private", List.of()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new PostBody(body, "https://user:secret@example.org", List.of()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new PostBody("x".repeat(10001), null, List.of()))
        .isInstanceOf(IllegalArgumentException.class);
    new PostBody("😀".repeat(10000), null, List.of()).requireNonempty(0);
    assertThatThrownBy(() -> new PostBody("\uD800", null, List.of()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new PostBody("\uDC00", null, List.of()))
        .isInstanceOf(IllegalArgumentException.class);
    var empty = new PostBody(" ", null, List.of());
    assertThatThrownBy(() -> empty.requireNonempty(0)).isInstanceOf(IllegalArgumentException.class);
    empty.requireNonempty(10);
    assertThatThrownBy(() -> empty.requireNonempty(11))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void hashtagsNormalizeNfkcRootLocaleOnceWithoutExpandingMentionAudience() {
    var body =
        new PostBody("#Ｆｌｙ #fly #Việt_Nam", null, List.of(new PostBody.Mention(VIEWER, 1, 4)));
    assertThat(body.hashtags())
        .extracting(PostBody.Hashtag::normalized)
        .containsExactly("fly", "việt_nam");
    assertThat(body.hashtags().getFirst().display()).isEqualTo("Ｆｌｙ");
    assertThat(body.mentions()).hasSize(1);
    assertThatThrownBy(() -> new PostBody("#" + "x".repeat(51), null, List.of()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new PostBody("#a #b #c #d #e #f #g #h #i #j #k", null, List.of()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static Facts facts(
      boolean author,
      boolean viewer,
      boolean block,
      boolean follow,
      boolean friends,
      boolean recipient,
      boolean mute,
      String visibility,
      String member,
      Instant until) {
    return new Facts(
        author,
        viewer,
        block,
        follow,
        friends,
        recipient,
        mute,
        visibility,
        visibility == null ? null : "ACTIVE",
        member,
        "MEMBER",
        until);
  }
}
