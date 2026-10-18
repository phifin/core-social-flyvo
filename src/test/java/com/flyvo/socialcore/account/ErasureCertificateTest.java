package com.flyvo.socialcore.account;

import static org.assertj.core.api.Assertions.*;

import com.flyvo.socialcore.account.api.AccountErasure;
import com.flyvo.socialcore.account.application.ErasureProtocol;
import com.flyvo.socialcore.account.domain.ErasureCertificate;
import java.util.*;
import org.junit.jupiter.api.Test;

class ErasureCertificateTest {
  List<ErasureCertificate.Proof> proofs() {
    return Arrays.stream(AccountErasure.Step.values())
        .filter(s -> s != AccountErasure.Step.COMPLETION)
        .map(s -> new ErasureCertificate.Proof(s.owner(), s.step(), 7, 1, "receipt-digest"))
        .toList();
  }

  @Test
  void fixedElevenPrerequisitesExcludeOwnReceiptAndFinalEvent() {
    var c =
        new ErasureCertificate(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            1,
            AccountErasure.PROTOCOL,
            AccountErasure.POLICY,
            ErasureProtocol.rosterDigest(),
            1,
            ErasureProtocol.digest("resources"),
            proofs());
    var names =
        Arrays.stream(ErasureCertificate.class.getRecordComponents())
            .map(java.lang.reflect.RecordComponent::getName)
            .toList();
    assertThat(names).doesNotContain("receipt", "completionStepVersion", "deletedEvent");
    assertThat(c.prerequisites())
        .hasSize(11)
        .noneMatch(p -> p.step().equals("completion-manifest"));
    assertThatThrownBy(() -> c.prerequisites().clear())
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void ownStepWrongRoundOrMissingOwnerCannotMakeCertificate() {
    var p = new ArrayList<>(proofs());
    p.set(0, new ErasureCertificate.Proof("account", "completion-manifest", 1, 1, "receipt"));
    assertThatThrownBy(
            () ->
                new ErasureCertificate(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    1,
                    "erasure-v1",
                    "isolated-synthetic-v1",
                    "roster",
                    1,
                    "resources",
                    p))
        .isInstanceOf(IllegalArgumentException.class);
    p.set(0, new ErasureCertificate.Proof("account", "recovery-intent", 1, 2, "receipt"));
    assertThatThrownBy(
            () ->
                new ErasureCertificate(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    1,
                    "erasure-v1",
                    "isolated-synthetic-v1",
                    "roster",
                    1,
                    "resources",
                    p))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
