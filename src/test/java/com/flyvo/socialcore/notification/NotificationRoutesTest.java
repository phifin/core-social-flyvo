package com.flyvo.socialcore.notification;

import static org.assertj.core.api.Assertions.assertThat;

import com.flyvo.socialcore.notification.api.NotificationQueries.Kind;
import com.flyvo.socialcore.notification.application.NotificationRoutes;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class NotificationRoutesTest {
  @Test
  void mentionReplyCommentPrecedenceIsPerRecipientAndSelfIsSuppressed() {
    var actor = UUID.randomUUID();
    var recipient = UUID.randomUUID();
    var other = UUID.randomUUID();
    var routes =
        List.of(
            new NotificationRoutes.Route(actor, Kind.MENTION),
            new NotificationRoutes.Route(recipient, Kind.COMMENT_ON_POST),
            new NotificationRoutes.Route(recipient, Kind.REPLY_TO_COMMENT),
            new NotificationRoutes.Route(recipient, Kind.MENTION),
            new NotificationRoutes.Route(other, Kind.COMMENT_ON_POST));
    assertThat(NotificationRoutes.deduplicate(actor, routes))
        .containsExactlyInAnyOrder(
            new NotificationRoutes.Route(recipient, Kind.MENTION),
            new NotificationRoutes.Route(other, Kind.COMMENT_ON_POST));
    assertThat(
            NotificationRoutes.deduplicate(
                actor,
                List.of(
                    new NotificationRoutes.Route(recipient, Kind.COMMENT_ON_POST),
                    new NotificationRoutes.Route(recipient, Kind.REPLY_TO_COMMENT))))
        .containsExactly(new NotificationRoutes.Route(recipient, Kind.REPLY_TO_COMMENT));
  }
}
