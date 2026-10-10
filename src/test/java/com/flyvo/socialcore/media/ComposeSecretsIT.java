package com.flyvo.socialcore.media;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import tools.jackson.databind.ObjectMapper;

class ComposeSecretsIT {
  @TempDir Path fixtures;

  @Test
  void resolvedServiceMountsExposeOnlyRequiredSecretsEvenWithSharedUid() throws Exception {
    var builder =
        new ProcessBuilder(
                "docker",
                "compose",
                "--profile",
                "app",
                "--profile",
                "media",
                "config",
                "--format",
                "json")
            .redirectError(ProcessBuilder.Redirect.INHERIT);
    builder.environment().put("FRONTEND_ORIGIN", "https://phase4-frontend.invalid");
    var process = builder.start();
    byte[] output = process.getInputStream().readAllBytes();
    assertThat(process.waitFor(30, TimeUnit.SECONDS)).isTrue();
    assertThat(process.exitValue()).isZero();
    var services = new ObjectMapper().readTree(output).path("services");
    for (String service : java.util.List.of("app", "silo-init"))
      assertThat(services.path(service).path("environment").path("FRONTEND_ORIGIN").asText())
          .isEqualTo("https://phase4-frontend.invalid");
    var expected =
        Map.of(
            "app",
                Set.of(
                    "signing-private.pem",
                    "signing-public.pem",
                    "mail.b64",
                    "limiter.b64",
                    "storage-api.b64",
                    "storage-worker.b64"),
            "silo", Set.of("storage-admin.b64", "storage-kms.b64"),
            "silo-init", Set.of("storage-admin.b64", "storage-api.b64", "storage-worker.b64"));
    var all = new HashSet<String>();
    expected.values().forEach(all::addAll);
    int uid =
        Integer.parseInt(
            new String(new ProcessBuilder("id", "-u").start().getInputStream().readAllBytes())
                .trim());
    for (String name : all) Files.writeString(fixtures.resolve(name), "synthetic-fixture");
    for (var service : expected.entrySet()) {
      var found = new HashSet<String>();
      try (var container =
          new GenericContainer<>(StorageContractIT.IMAGE)
              .withCreateContainerCmdModifier(c -> c.withUser(Integer.toString(uid)))
              .withCommand("sh", "-c", "echo fixture-ready; exec tail -f /dev/null")
              .waitingFor(Wait.forLogMessage(".*fixture-ready.*\\n", 1))
              .withStartupTimeout(Duration.ofSeconds(30))) {
        for (var mount : services.path(service.getKey()).path("volumes")) {
          String target = mount.path("target").asText();
          assertThat(target).isNotEqualTo("/run/secrets");
          if (!target.startsWith("/run/secrets/")) continue;
          String name = target.substring("/run/secrets/".length());
          assertThat(mount.path("source").asText()).endsWith("/.local/secrets/" + name);
          assertThat(mount.path("read_only").asBoolean()).isTrue();
          found.add(name);
          container.withFileSystemBind(
              fixtures.resolve(name).toString(), target, BindMode.READ_ONLY);
        }
        assertThat(found)
            .as("resolved allowlist for %s", service.getKey())
            .containsExactlyInAnyOrderElementsOf(service.getValue());
        container.start();
        for (String name : all) {
          var check = container.execInContainer("sh", "-c", "test -r /run/secrets/" + name);
          assertThat(check.getExitCode() == 0)
              .as("%s can read %s", service.getKey(), name)
              .isEqualTo(service.getValue().contains(name));
          if (!service.getValue().contains(name))
            assertThat(
                    container
                        .execInContainer("sh", "-c", "test ! -e /run/secrets/" + name)
                        .getExitCode())
                .isZero();
        }
      }
    }
  }
}
