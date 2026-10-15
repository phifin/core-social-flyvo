package com.flyvo.socialcore.community.application;

import com.flyvo.socialcore.community.api.CommunitySearchSources;
import com.flyvo.socialcore.platform.api.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CommunitySearch implements CommunitySearchSources {
  private final CommunityAccess access;

  public CommunitySearch(CommunityAccess access) {
    this.access = access;
  }

  private void batch(List<UUID> ids) {
    if (ids == null || ids.size() > 100 || ids.stream().anyMatch(Objects::isNull))
      throw Failure.invalid();
  }

  @Transactional(readOnly = true)
  public List<UUID> scan(UUID after, int limit) {
    if (limit < 1 || limit > 100) throw Failure.invalid();
    return access.store().searchScan(after, limit);
  }

  @Transactional(readOnly = true)
  public List<Source> inspect(List<UUID> ids) {
    batch(ids);
    return access.store().communities(ids).stream()
        .map(c -> new Source(c.id(), c.version(), c.active(), c.slug(), c.name(), c.description()))
        .toList();
  }

  @Transactional(readOnly = true)
  public List<Card> hydrate(ActorContext actor, List<UUID> ids) {
    batch(ids);
    access.actor(actor);
    // Existing authenticated discovery-card policy includes private community
    // name/description, but never joins posts or exposes owner/member details.
    return access.store().communities(ids).stream()
        .filter(c -> c.active())
        .map(
            c -> new Card(c.id(), c.slug(), c.name(), c.description(), c.visibility(), c.version()))
        .toList();
  }
}
