package com.flyvo.socialcore.feed.api;

import com.flyvo.socialcore.content.api.ContentQueries;
import com.flyvo.socialcore.content.api.Interactions;
import com.flyvo.socialcore.platform.api.ActorContext;
import java.util.List;
import java.util.UUID;

public interface FeedQueries {
  record Item(ContentQueries.PostView post, Interactions.Engagement engagement) {}

  record Page(List<Item> items, String nextCursor) {}

  Page home(ActorContext actor, int size, String after);

  Page community(ActorContext actor, UUID community, int size, String after);

  Page timeline(ActorContext actor, UUID user, int size, String after);
}
