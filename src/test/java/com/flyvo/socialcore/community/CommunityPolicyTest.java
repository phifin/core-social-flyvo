package com.flyvo.socialcore.community;

import static org.assertj.core.api.Assertions.*;

import com.flyvo.socialcore.community.application.CommunityPermissionPolicy;
import com.flyvo.socialcore.community.domain.CommunityState.*;
import com.flyvo.socialcore.platform.api.Failure;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CommunityPolicyTest {
  final Instant now = Instant.parse("2026-10-10T00:00:00Z");
  final UUID owner = UUID.randomUUID();

  Community community(String visibility) {
    return new Community(
        UUID.randomUUID(), "test-group", "Test", "", visibility, "OPEN", "ACTIVE", owner, now, 0);
  }

  Member member(Community c, String role) {
    return new Member(
        c.id(),
        role.equals("OWNER") ? owner : UUID.randomUUID(),
        role.equals("OWNER") ? "MEMBER" : role,
        "ACTIVE",
        now,
        null,
        0);
  }

  @Test
  void fullRoleAndSanctionMatrixRejectsSelfPeersAndSuperiors() {
    var c = community("PRIVATE");
    for (var role : java.util.List.of("MEMBER", "MODERATOR", "ADMIN", "OWNER")) {
      var a = member(c, role);
      for (int minimum = 1; minimum <= 4; minimum++) {
        final int threshold = minimum;
        if (CommunityPermissionPolicy.rank(role) >= minimum)
          CommunityPermissionPolicy.require(c, a, now, minimum);
        else
          assertThatThrownBy(() -> CommunityPermissionPolicy.require(c, a, now, threshold))
              .isInstanceOf(Failure.class);
      }
      for (var targetRole : java.util.List.of("MEMBER", "MODERATOR", "ADMIN", "OWNER")) {
        var target = member(c, targetRole);
        boolean allowed =
            CommunityPermissionPolicy.rank(role) >= 2
                && CommunityPermissionPolicy.rank(role)
                    > CommunityPermissionPolicy.rank(targetRole);
        if (allowed) CommunityPermissionPolicy.sanction(c, a, target, now);
        else
          assertThatThrownBy(() -> CommunityPermissionPolicy.sanction(c, a, target, now))
              .isInstanceOf(Failure.class);
      }
      assertThatThrownBy(() -> CommunityPermissionPolicy.sanction(c, a, a, now))
          .isInstanceOf(Failure.class);
    }
  }

  @Test
  void suspensionExpiryAndPublicPrivateReadMatrix() {
    for (String visibility : java.util.List.of("PUBLIC", "PRIVATE")) {
      var c = community(visibility);
      for (String status : java.util.List.of("ACTIVE", "LEFT", "REMOVED", "BANNED"))
        for (var until : java.util.Arrays.asList(null, now, now.plusSeconds(1))) {
          var m = new Member(c.id(), UUID.randomUUID(), "MEMBER", status, now, until, 0);
          boolean excluded =
              status.equals("BANNED")
                  || status.equals("ACTIVE") && until != null && until.isAfter(now);
          boolean expected = !excluded && (visibility.equals("PUBLIC") || m.effective(now));
          assertThat(CommunityPermissionPolicy.readable(c, m, true, now)).isEqualTo(expected);
        }
      assertThat(CommunityPermissionPolicy.readable(c, null, false, now))
          .isEqualTo(visibility.equals("PUBLIC"));
    }
  }

  @Test
  void domainRejectsBanReactivationAndOwnerLeaveUntilArchive() {
    var c = community("PRIVATE");
    var m = new Member(c.id(), UUID.randomUUID(), "MEMBER", "BANNED", now, null, 0);
    assertThatThrownBy(() -> m.transition("MEMBER", "ACTIVE", null, now))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(m.transition("MEMBER", "REMOVED", null, now).status()).isEqualTo("REMOVED");
    assertThat(c.mayLeave(owner)).isFalse();
    var archived =
        new Community(
            c.id(),
            c.slug(),
            c.name(),
            c.description(),
            c.visibility(),
            c.joinMode(),
            "ARCHIVED",
            owner,
            now,
            1);
    assertThat(archived.mayLeave(owner)).isTrue();
  }
}
