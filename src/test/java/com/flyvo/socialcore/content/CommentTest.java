package com.flyvo.socialcore.content;

import static org.assertj.core.api.Assertions.*;

import com.flyvo.socialcore.content.domain.comment.Comment;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CommentTest {
  private Comment root() {
    return new Comment(
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        null,
        null,
        "text",
        "VISIBLE",
        Instant.EPOCH,
        null,
        null,
        null,
        null,
        0);
  }

  @Test
  void bodyBoundsUseUnicodeCodePointsAndRejectBlank() {
    assertThatCode(() -> Comment.validateBody("😀".repeat(5000))).doesNotThrowAnyException();
    assertThatThrownBy(() -> Comment.validateBody("😀".repeat(5001)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Comment.validateBody("\n\t "))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void editRemovalRestorationDeletionHaveMonotonicVersionsAndDeletionWins() {
    var c = root().edit("edited", Instant.EPOCH.plusSeconds(1));
    var removed = c.moderate(false, UUID.randomUUID(), Instant.EPOCH.plusSeconds(2));
    assertThat(removed.status()).isEqualTo("REMOVED");
    assertThat(removed.version()).isEqualTo(2);
    var restored = removed.moderate(true, UUID.randomUUID(), Instant.EPOCH.plusSeconds(3));
    assertThat(restored.version()).isEqualTo(3);
    assertThat(restored.removalAction()).isNull();
    assertThat(restored.edited()).isEqualTo(c.edited());
    var deleted = removed.delete(Instant.EPOCH.plusSeconds(4));
    assertThat(deleted.status()).isEqualTo("DELETED");
    assertThatThrownBy(
            () -> deleted.moderate(true, UUID.randomUUID(), Instant.EPOCH.plusSeconds(5)))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> deleted.edit("another", Instant.EPOCH))
        .isInstanceOf(IllegalStateException.class);
  }
}
