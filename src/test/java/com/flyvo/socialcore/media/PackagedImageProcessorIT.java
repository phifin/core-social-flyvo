package com.flyvo.socialcore.media;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class PackagedImageProcessorIT {
  @Test
  void packagedBootJarLaunchesTheCredentialFreeIsolatedWebpDecoder() throws Exception {
    Path jar = Path.of("target/social-core-0.0.1-SNAPSHOT.jar").toAbsolutePath();
    assertThat(jar).exists();
    var process =
        new ProcessBuilder(
                System.getProperty("java.home") + "/bin/java",
                "-Dloader.path=" + Path.of("target/test-classes").toAbsolutePath(),
                "-Dloader.main=com.flyvo.fixtures.media.PackagedImageProbe",
                "-cp",
                jar.toString(),
                "org.springframework.boot.loader.launch.PropertiesLauncher")
            .redirectErrorStream(true)
            .start();
    try {
      assertThat(process.waitFor(25, TimeUnit.SECONDS)).isTrue();
      String output =
          new String(
              process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
      assertThat(process.exitValue()).describedAs(output).isZero();
      assertThat(output).contains("PACKAGED_DECODER_OK");
    } finally {
      process.destroyForcibly();
    }
  }
}
