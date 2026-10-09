package com.flyvo.socialcore.platform.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class DatabaseConfigurationTest {
  private final ApplicationContextRunner runner =
      new ApplicationContextRunner().withUserConfiguration(PropertiesConfiguration.class);

  @Test
  void missingDatabaseConfigurationFailsWithNamedFields() {
    runner.run(
        context -> {
          assertThat(context).hasFailed();
          assertThat(context.getStartupFailure())
              .hasStackTraceContaining("app.database")
              .hasStackTraceContaining("must not be blank");
        });
  }

  @Test
  void refusesNonPostgresqlUrl() {
    runner
        .withPropertyValues(
            "app.database.url=jdbc:h2:mem:test",
            "app.database.username=test",
            "app.database.password=test")
        .run(
            context ->
                assertThat(context.getStartupFailure())
                    .hasStackTraceContaining("must be a PostgreSQL JDBC URL"));
  }

  @TestConfiguration(proxyBeanMethods = false)
  @EnableConfigurationProperties(DatabaseProperties.class)
  static class PropertiesConfiguration {}
}
