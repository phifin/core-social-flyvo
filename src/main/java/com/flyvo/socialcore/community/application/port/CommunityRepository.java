package com.flyvo.socialcore.community.application.port;

import com.flyvo.socialcore.community.domain.CommunityState.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface CommunityRepository {
  Community community(UUID id, boolean lock, boolean exclusive);

  List<Community> communities(List<UUID> ids);

  void create(Community value, Instant now);

  void settings(
      UUID id, String name, String description, String visibility, String joinMode, Instant now);

  void archive(UUID id, Instant now);

  void transfer(UUID id, UUID successor, Instant now);

  boolean ownsActive(UUID user);

  List<Community> discover(boolean authenticated, int size, int offset);

  Member member(UUID community, UUID user);

  List<Member> members(UUID community, int size, int offset);

  List<Member> facts(UUID user, List<UUID> communities);

  void join(UUID community, UUID user, Instant now);

  void membership(
      UUID community, UUID user, String role, String status, Instant suspension, Instant now);

  Invitation invitation(UUID id);

  Invitation pendingInvitation(UUID community, UUID user);

  void invite(Invitation value);

  void resolveInvite(UUID id, String status, Instant now);

  List<Invitation> invitations(UUID user, Instant now, int size, int offset);

  Request request(UUID id);

  Request pendingRequest(UUID community, UUID user);

  void request(Request value);

  void resolveRequest(UUID id, String status, UUID actor, Instant now);

  List<Request> requests(UUID community, int size, int offset);
}
