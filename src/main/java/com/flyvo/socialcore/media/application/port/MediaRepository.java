package com.flyvo.socialcore.media.application.port;

import com.flyvo.socialcore.media.domain.MediaState.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface MediaRepository {
  Asset asset(UUID id, boolean lock);

  Quota quota(UUID owner, Instant now);

  void charge(UUID owner, long reservedDelta, long retainedDelta);

  void create(Asset asset, Instant now);

  void issued(UUID owner, Instant now, boolean reset);

  void finalizeUpload(UUID id, String version, Instant now);

  void processing(UUID id, long generation, Instant now);

  void ready(UUID id, long generation, List<Variant> variants, Instant now);

  void state(UUID id, String state, String code, Instant now);

  Binding binding(UUID asset);

  void bind(UUID asset, String purpose, UUID context, Instant now);

  void release(UUID asset, Instant now, Instant purgeAfter);

  List<Variant> variants(UUID asset, long generation);

  List<com.flyvo.socialcore.media.api.MediaQueries.BindingFacts> bindingFacts(List<UUID> assets);

  List<UUID> owners(UUID after, int size);

  List<UUID> settledAssets(UUID after, int size, Instant uploadsExpiredBefore);

  Quota actualQuota(UUID owner);
}
