package com.flyvo.socialcore.media.api;

import java.util.List;
import java.util.UUID;

public interface MediaQueries {
  record Variant(String name, String url, String mime, int width, int height) {}

  /**
   * Internal capability: caller authorizes current context first; never an HTTP media-ID signer.
   */
  List<Variant> access(String purpose, UUID context, UUID asset, boolean historical);

  record BindingFacts(
      UUID assetId, UUID owner, String purpose, String state, UUID context, boolean released) {}

  List<BindingFacts> bindingFacts(List<UUID> assets);
}
