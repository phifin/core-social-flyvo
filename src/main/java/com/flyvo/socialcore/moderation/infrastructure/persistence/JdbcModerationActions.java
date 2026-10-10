package com.flyvo.socialcore.moderation.infrastructure.persistence;

import com.flyvo.socialcore.moderation.application.port.ModerationActions;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class JdbcModerationActions implements ModerationActions {
  private final JdbcTemplate jdbc;

  JdbcModerationActions(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
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
    jdbc.update(
        "INSERT INTO moderation.action(id,actor_id,community_id,target_type,target_id,action,reason,before_state,after_state,request_id,created_at) VALUES (?,?,?,'MEMBER',?,?,?,?::jsonb,?::jsonb,?,?)",
        id,
        actor,
        community,
        target,
        action,
        reason,
        before,
        after,
        request,
        now.atOffset(ZoneOffset.UTC));
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
    jdbc.update(
        "INSERT INTO moderation.action(id,actor_id,community_id,target_type,target_id,action,reason,before_state,after_state,request_id,created_at) VALUES (?,?,?,'POST',?,?,?,?::jsonb,?::jsonb,?,?)",
        id,
        actor,
        community,
        post,
        action,
        reason,
        before,
        after,
        request,
        now.atOffset(ZoneOffset.UTC));
  }
}
