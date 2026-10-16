package com.flyvo.socialcore.moderation.infrastructure.persistence;

import com.flyvo.socialcore.moderation.application.ActionPayloads;
import com.flyvo.socialcore.moderation.application.port.ModerationActions;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class JdbcModerationActions implements ModerationActions {
  private final JdbcTemplate jdbc;
  private final ActionPayloads payloads;

  JdbcModerationActions(JdbcTemplate jdbc, ActionPayloads payloads) {
    this.jdbc = jdbc;
    this.payloads = payloads;
  }

  public void appendCase(
      UUID id,
      UUID report,
      UUID actor,
      UUID community,
      String targetType,
      UUID target,
      String action,
      String reason,
      String before,
      String after,
      String request,
      Instant now) {
    jdbc.update(
        "INSERT INTO moderation.action(id,report_id,actor_id,community_id,target_type,target_id,action,request_id,created_at) VALUES (?,?,?,?,?,?,?,?,?)",
        id,
        report,
        actor,
        community,
        targetType,
        target,
        action,
        request,
        now.atOffset(ZoneOffset.UTC));
    var encrypted = payloads.encrypt(id, payloads.detail(reason, before, after));
    jdbc.update(
        "INSERT INTO moderation.action_detail(action_id,ciphertext,key_id) VALUES (?,?,?)",
        id,
        encrypted.ciphertext(),
        encrypted.keyId());
    if (report == null)
      jdbc.update("INSERT INTO moderation.action_retention(action_id) VALUES (?)", id);
  }

  public void append(
      UUID id,
      UUID actor,
      UUID community,
      UUID target,
      String action,
      String reason,
      String before,
      String after,
      String request,
      Instant now) {
    appendCase(
        id, null, actor, community, "MEMBER", target, action, reason, before, after, request, now);
  }

  public void appendPost(
      UUID id,
      UUID actor,
      UUID community,
      UUID post,
      String action,
      String reason,
      String before,
      String after,
      String request,
      Instant now) {
    appendCase(
        id, null, actor, community, "POST", post, action, reason, before, after, request, now);
  }
}
