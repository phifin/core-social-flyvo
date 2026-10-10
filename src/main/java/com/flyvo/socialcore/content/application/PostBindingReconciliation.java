package com.flyvo.socialcore.content.application;

import com.flyvo.socialcore.content.application.port.PostRepository;
import com.flyvo.socialcore.media.api.MediaQueries;
import com.flyvo.socialcore.platform.api.*;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class PostBindingReconciliation implements JobHandler {
  private final PostRepository store;
  private final MediaQueries media;
  private final DurableWork work;

  public PostBindingReconciliation(PostRepository store, MediaQueries media, DurableWork work) {
    this.store = store;
    this.media = media;
    this.work = work;
  }

  public String kind() {
    return "POST_BINDING_RECONCILE";
  }

  public void apply(String payload) {
    var p = store.find(UUID.fromString(payload), false);
    if (p == null || !p.status().equals("PUBLISHED")) return;
    var facts =
        media.bindingFacts(
            p.media().stream()
                .map(com.flyvo.socialcore.content.domain.post.Post.Attachment::id)
                .toList());
    if (facts.size() != p.media().size()
        || facts.stream()
            .anyMatch(
                f ->
                    !p.author().equals(f.owner())
                        || !"POST".equals(f.purpose())
                        || !p.id().equals(f.context())
                        || f.released()
                        || !"READY".equals(f.state())))
      work.audit(
          null, "MEDIA_BINDING_MISMATCH", "post", p.id(), "FAILURE", "RECONCILIATION_REQUIRED");
  }
}
