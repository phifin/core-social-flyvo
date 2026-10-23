package com.flyvo.socialcore.moderation.application;

import com.flyvo.socialcore.moderation.application.port.*;
import com.flyvo.socialcore.platform.api.*;
import java.time.Clock;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
@ConditionalOnProperty(name = "app.erasure.enabled", havingValue = "true")
public class PreservationScan implements JobHandler {
  public record Pass(CaseStore.Root after) {}

  private final PreservationStore store;
  private final CaseStore cases;
  private final ModerationPreservation preservation;
  private final DurableWork work;
  private final Clock clock;
  private final ObjectMapper json;

  public PreservationScan(
      PreservationStore store,
      CaseStore cases,
      ModerationPreservation preservation,
      DurableWork work,
      Clock clock,
      ObjectMapper json) {
    this.store = store;
    this.cases = cases;
    this.preservation = preservation;
    this.work = work;
    this.clock = clock;
    this.json = json;
  }

  public String kind() {
    return "MODERATION_PRESERVATION";
  }

  public void apply(String payload) {
    var p = json.readValue(payload, Pass.class);
    var root = store.scan(p.after());
    if (root == null) return;
    var c = cases.control(root, true);
    if (c == null) throw new IllegalStateException("PRESERVATION_ROOT_MISSING");
    var result = preservation.baseline(c);
    work.job(
        kind(),
        UUID.randomUUID().toString(),
        json.writeValueAsString(new Pass(result.ready() ? root : p.after())),
        clock.instant().plusSeconds(result.ready() ? 0 : 60));
  }
}
