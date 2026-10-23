package com.flyvo.socialcore.content.application;

import com.flyvo.socialcore.account.api.AccountQueries;
import com.flyvo.socialcore.content.api.ContentNotificationSources;
import com.flyvo.socialcore.content.application.port.NotificationSourceRepository;
import com.flyvo.socialcore.platform.api.Failure;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ContentNotificationProofs implements ContentNotificationSources {
  private final NotificationSourceRepository store;
  private final AccountQueries accounts;
  private final Clock clock;

  public ContentNotificationProofs(
      NotificationSourceRepository store, AccountQueries accounts, Clock clock) {
    this.store = store;
    this.accounts = accounts;
    this.clock = clock;
  }

  private void validate(Type type, List<UUID> ids) {
    if (type == null
        || ids == null
        || ids.size() > 100
        || ids.stream().anyMatch(java.util.Objects::isNull)) throw Failure.invalid();
  }

  @Transactional(readOnly = true)
  public List<Source> inspect(Type type, List<UUID> ids) {
    validate(type, ids);
    return ids.isEmpty() ? List.of() : store.inspect(type, ids);
  }

  @Transactional(readOnly = true)
  public List<UUID> ownedTargets(Type type, UUID owner, UUID after, int limit) {
    if (type == null || type == Type.REACTION || owner == null || limit < 1 || limit > 100)
      throw Failure.invalid();
    return store.ownedTargets(type, owner, after, limit);
  }

  @Transactional(readOnly = true)
  public Set<UUID> eligible(UUID recipient, Type type, String kind, List<UUID> ids) {
    validate(type, ids);
    if (recipient == null) throw Failure.unauthorized();
    var account = accounts.find(recipient);
    if (account == null || !account.active()) throw Failure.unauthorized();
    if (!Set.of("MENTION", "COMMENT_ON_POST", "REPLY_TO_COMMENT", "REACTION_ON_CONTENT")
        .contains(kind)) throw Failure.invalid();
    return ids.isEmpty() ? Set.of() : store.eligible(recipient, type, kind, ids, clock.instant());
  }
}
