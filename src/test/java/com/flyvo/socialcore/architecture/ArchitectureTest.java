package com.flyvo.socialcore.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import jakarta.persistence.Entity;
import org.junit.jupiter.api.Test;

class ArchitectureTest {
  private static final String ROOT = "com.flyvo.socialcore";
  private static final com.tngtech.archunit.core.domain.JavaClasses PRODUCTION =
      new ClassFileImporter()
          .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
          .importPackages(ROOT);

  @Test
  void moduleBoundariesAndDependencyGraphAreEnforced() {
    ModuleRules.boundaries(ROOT).check(PRODUCTION);
    slices().matching(ROOT + ".(*)..").should().beFreeOfCycles().check(PRODUCTION);
    assertThat(PRODUCTION).isNotEmpty();
  }

  @Test
  void moderationProfileDependencyIsOnlyVisibleReportProof() {
    var calls =
        PRODUCTION.stream()
            .filter(c -> c.getPackageName().contains(".moderation."))
            .flatMap(c -> c.getMethodCallsFromSelf().stream())
            .filter(c -> c.getTargetOwner().getPackageName().contains(".profile."))
            .toList();
    assertThat(calls)
        .allSatisfy(
            call ->
                assertThat(call.getTargetOwner().getName())
                    .isIn(
                        ROOT + ".profile.api.ProfileQueries",
                        ROOT + ".profile.api.ProfileQueries$Summary"));
    var ownerCalls =
        calls.stream().filter(c -> !c.getTargetOwner().getName().contains("$")).toList();
    assertThat(ownerCalls).hasSize(1);
    assertThat(ownerCalls.getFirst().getTargetOwner().getName())
        .isEqualTo(ROOT + ".profile.api.ProfileQueries");
    assertThat(ownerCalls.getFirst().getTarget().getName()).isEqualTo("visible");
  }

  @Test
  void notificationIdentityDependencyIsOnlyStreamProofAndCurrentSession() {
    var calls =
        PRODUCTION.stream()
            .filter(c -> c.getPackageName().contains(".notification."))
            .flatMap(c -> c.getMethodCallsFromSelf().stream())
            .filter(c -> c.getTargetOwner().getPackageName().contains(".identity."))
            .toList();
    assertThat(calls).isNotEmpty();
    assertThat(calls)
        .allSatisfy(
            call -> {
              String type = call.getTargetOwner().getName();
              assertThat(type)
                  .isIn(
                      ROOT + ".identity.api.StreamAuthentication",
                      ROOT + ".identity.api.StreamAuthentication$Proof",
                      ROOT + ".identity.api.SessionQueries");
              if (!type.contains("$"))
                assertThat(call.getTarget().getName()).isIn("verify", "requireCurrent");
            });
  }

  @Test
  void erasureAcknowledgementsComeOnlyFromAssignedOwnerHandlers() {
    var methods =
        java.util.Set.of(
            "begin",
            "advance",
            "recordResources",
            "resourcesExported",
            "resourceIds",
            "stepDone",
            "executionFailed");
    var allowed =
        java.util.Set.of(
            ROOT + ".account.application.Erasures",
            ROOT + ".account.application.ErasureExports",
            ROOT + ".account.application.ErasureTechnical",
            ROOT + ".identity.application.IdentityErasure",
            ROOT + ".profile.application.ProfileErasure");
    PRODUCTION.stream()
        .flatMap(c -> c.getMethodCallsFromSelf().stream())
        .filter(
            c ->
                c.getTargetOwner().getName().equals(ROOT + ".account.api.AccountErasure")
                    && methods.contains(c.getTarget().getName()))
        .forEach(c -> assertThat(allowed).contains(c.getOriginOwner().getName()));
  }

  @Test
  void deliberatelyForbiddenFixtureIsDetected() {
    var fixtures = new ClassFileImporter().importPackages("com.flyvo.archfixtures");
    var result = ModuleRules.boundaries("com.flyvo.archfixtures").evaluate(fixtures);
    assertThat(result.hasViolation()).isTrue();
    assertThat(result.getFailureReport().getDetails())
        .anyMatch(detail -> detail.contains("SecretRepository"));
  }

  @Test
  void domainAndApplicationDoNotDependOnTransport() {
    noClasses()
        .that()
        .resideInAnyPackage("..domain..", "..application..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage("..web..", "org.springframework.web..", "jakarta.servlet..")
        .allowEmptyShould(true)
        .check(PRODUCTION);
    noClasses()
        .that()
        .resideInAnyPackage("..domain..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(
            "org.springframework..",
            "tools.jackson..",
            "com.fasterxml.jackson..",
            "..application..",
            "..infrastructure..")
        .allowEmptyShould(true)
        .check(PRODUCTION);
  }

  @Test
  void publicContractsAndWebDoNotExposePersistence() {
    noClasses()
        .that()
        .resideInAnyPackage("..api..", "..web..")
        .should()
        .dependOnClassesThat()
        .areAnnotatedWith(Entity.class)
        .allowEmptyShould(true)
        .check(PRODUCTION);
    noClasses()
        .that()
        .resideInAnyPackage("..api..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(
            "org.springframework..",
            "jakarta.persistence..",
            "..domain..",
            "..infrastructure..",
            "..application..",
            "..web..")
        .allowEmptyShould(true)
        .check(PRODUCTION);
    noClasses()
        .that()
        .resideInAnyPackage("..web..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage("..infrastructure.persistence..", "..application.port..")
        .check(PRODUCTION);
  }

  @Test
  void injectionUsesConstructors() {
    for (String annotation :
        new String[] {
          "org.springframework.beans.factory.annotation.Autowired",
          "jakarta.inject.Inject",
          "jakarta.annotation.Resource"
        }) {
      noFields().should().beAnnotatedWith(annotation).check(PRODUCTION);
    }
  }
}
