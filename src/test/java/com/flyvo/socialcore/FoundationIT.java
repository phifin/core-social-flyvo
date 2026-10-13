package com.flyvo.socialcore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import jakarta.persistence.EntityManagerFactory;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@org.springframework.test.context.ActiveProfiles("test")
class FoundationIT {
  @Container
  static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer(
          "postgres@sha256:77f585114c32fbca283dc835b0596f4e52b51b4c6662d7810b2f4084f60a1873");

  @DynamicPropertySource
  static void databaseProperties(DynamicPropertyRegistry registry) {
    registry.add("app.database.url", POSTGRES::getJdbcUrl);
    registry.add("app.database.username", POSTGRES::getUsername);
    registry.add("app.database.password", POSTGRES::getPassword);
  }

  @Autowired MockMvc mvc;
  @Autowired Flyway flyway;
  @Autowired DataSource dataSource;
  @Autowired EntityManagerFactory entityManagerFactory;
  @Autowired org.springframework.core.env.Environment environment;
  @Autowired com.flyvo.socialcore.identity.application.port.AccessTokens accessTokens;

  @Test
  void migrationsRunOnRealPostgresAndRemainRepeatable() {
    var jdbc = new JdbcTemplate(dataSource);
    assertThat(
            jdbc.queryForObject("select current_setting('server_version_num')::int", Integer.class))
        .isBetween(180000, 189999);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from information_schema.schemata where schema_name = 'platform'",
                Integer.class))
        .isEqualTo(1);
    assertThat(flyway.info().applied()).hasSize(26);
    flyway.validate();
    assertThat(flyway.migrate().migrationsExecuted).isZero();
    assertThat(entityManagerFactory.getProperties())
        .containsEntry("hibernate.hbm2ddl.auto", "validate");
    assertThat(environment.getProperty("spring.jpa.open-in-view", Boolean.class)).isFalse();
    assertThat(jdbc.queryForObject("show timezone", String.class)).isEqualTo("UTC");
  }

  @Test
  void homeSourceIndexUpgradePreservesTwentyThreeChecksumsAndExistingPost() {
    assertFeedIndexUpgrade(
        "0023", 23, "content.post_personal_author", "INCLUDE (audience)", "community_id IS NULL");
  }

  @Test
  void customSourceIndexUpgradePreservesTwentyFourChecksumsAndExistingPost() {
    assertFeedIndexUpgrade(
        "0024", 24, "content.post_custom_source", "INCLUDE (author_id, published_at)", "CUSTOM");
  }

  @Test
  void communitySourceIndexUpgradePreservesTwentyFiveChecksumsAndExistingPost() {
    assertFeedIndexUpgrade(
        "0025", 25, "content.post_community_source", "INCLUDE (author_id)", "COMMUNITY");
  }

  private void assertFeedIndexUpgrade(
      String target, int priorCount, String index, String... definition) {
    var name = "feed_upgrade_" + java.util.UUID.randomUUID().toString().replace("-", "");
    new JdbcTemplate(dataSource).execute("CREATE DATABASE " + name);
    String url = POSTGRES.getJdbcUrl().replace("/test?", "/" + name + "?");
    var prior =
        Flyway.configure()
            .dataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword())
            .target(target)
            .load();
    assertThat(prior.migrate().migrationsExecuted).isEqualTo(priorCount);
    var checksums =
        java.util.Arrays.stream(prior.info().applied())
            .map(org.flywaydb.core.api.MigrationInfo::getChecksum)
            .toList();
    var sql =
        new JdbcTemplate(
            new org.springframework.jdbc.datasource.DriverManagerDataSource(
                url, POSTGRES.getUsername(), POSTGRES.getPassword()));
    var author = java.util.UUID.randomUUID();
    var post = java.util.UUID.randomUUID();
    sql.update(
        "INSERT INTO account.user_account(id,status,created_at,updated_at) VALUES (?,'ACTIVE',now(),now())",
        author);
    sql.update(
        "INSERT INTO content.post(id,author_id,body,audience,status,revision_number,published_at,created_at,updated_at) VALUES (?,?,'Synthetic retained post','PUBLIC','PUBLISHED',1,now(),now(),now())",
        post,
        author);
    var upgrade =
        Flyway.configure().dataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword()).load();
    assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(26 - priorCount);
    upgrade.validate();
    assertThat(
            java.util.Arrays.stream(upgrade.info().applied())
                .limit(priorCount)
                .map(org.flywaydb.core.api.MigrationInfo::getChecksum)
                .toList())
        .isEqualTo(checksums);
    assertThat(sql.queryForObject("SELECT body FROM content.post WHERE id=?", String.class, post))
        .isEqualTo("Synthetic retained post");
    assertThat(
            sql.queryForObject(
                "SELECT indisvalid FROM pg_index WHERE indexrelid=?::regclass",
                Boolean.class,
                index))
        .isTrue();
    assertThat(sql.queryForObject("SELECT pg_get_indexdef(?::regclass)", String.class, index))
        .contains(definition);
    assertThat(upgrade.migrate().migrationsExecuted).isZero();
  }

  @Test
  void interactionSchemaUpgradePreservesAllTwentyTwoChecksums() {
    var name = "reports_upgrade_" + java.util.UUID.randomUUID().toString().replace("-", "");
    new JdbcTemplate(dataSource).execute("CREATE DATABASE " + name);
    String url = POSTGRES.getJdbcUrl().replace("/test?", "/" + name + "?");
    var prior =
        Flyway.configure()
            .dataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword())
            .target("0022")
            .load();
    assertThat(prior.migrate().migrationsExecuted).isEqualTo(22);
    var checksums =
        java.util.Arrays.stream(prior.info().applied())
            .map(org.flywaydb.core.api.MigrationInfo::getChecksum)
            .toList();
    var upgrade =
        Flyway.configure().dataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword()).load();
    assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(4);
    upgrade.validate();
    assertThat(
            java.util.Arrays.stream(upgrade.info().applied())
                .limit(22)
                .map(org.flywaydb.core.api.MigrationInfo::getChecksum)
                .toList())
        .isEqualTo(checksums);
  }

  @Test
  void phaseFourUpgradePreservesAllTwentyOneAppliedChecksums() {
    var name = "phase_four_upgrade_" + java.util.UUID.randomUUID().toString().replace("-", "");
    new JdbcTemplate(dataSource).execute("CREATE DATABASE " + name);
    String url = POSTGRES.getJdbcUrl().replace("/test?", "/" + name + "?");
    var prior =
        Flyway.configure()
            .dataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword())
            .target("0021")
            .load();
    assertThat(prior.migrate().migrationsExecuted).isEqualTo(21);
    var checksums =
        java.util.Arrays.stream(prior.info().applied())
            .map(org.flywaydb.core.api.MigrationInfo::getChecksum)
            .toList();
    var upgrade =
        Flyway.configure().dataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword()).load();
    assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(5);
    upgrade.validate();
    assertThat(
            java.util.Arrays.stream(upgrade.info().applied())
                .limit(21)
                .map(org.flywaydb.core.api.MigrationInfo::getChecksum)
                .toList())
        .isEqualTo(checksums);
    assertThat(upgrade.migrate().migrationsExecuted).isZero();
  }

  @Test
  void hibernateValidationRejectsMissingTablesWithoutCreatingThem() {
    var configuration =
        new org.hibernate.cfg.Configuration()
            .addAnnotatedClass(com.flyvo.fixtures.persistence.MissingTableProbe.class)
            .setProperty("hibernate.hbm2ddl.auto", "validate");
    configuration.getProperties().put("hibernate.connection.datasource", dataSource);
    assertThatThrownBy(
            () -> {
              try (var ignored = configuration.buildSessionFactory()) {
                throw new AssertionError("Schema validation unexpectedly accepted a missing table");
              }
            })
        .isInstanceOf(org.hibernate.tool.schema.spi.SchemaManagementException.class)
        .hasMessageContaining("missing_table_probe");
    assertThat(
            new JdbcTemplate(dataSource)
                .queryForObject(
                    "select count(*) from information_schema.tables where table_schema = 'platform' and table_name = 'missing_table_probe'",
                    Integer.class))
        .isZero();
  }

  @Test
  void phaseZeroSchemaUpgradesWithoutChangingItsChecksum() {
    var name = "upgrade_" + java.util.UUID.randomUUID().toString().replace("-", "");
    new JdbcTemplate(dataSource).execute("CREATE DATABASE " + name);
    String url = POSTGRES.getJdbcUrl().replace("/test?", "/" + name + "?");
    var phaseZero =
        Flyway.configure()
            .dataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword())
            .target("0001")
            .load();
    assertThat(phaseZero.migrate().migrationsExecuted).isEqualTo(1);
    var checksum = phaseZero.info().applied()[0].getChecksum();
    var upgrade =
        Flyway.configure().dataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword()).load();
    assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(25);
    assertThat(upgrade.info().applied()[0].getChecksum()).isEqualTo(checksum);
    upgrade.validate();
  }

  @Test
  void phaseThreeUpgradePreservesAllFourteenChecksumsAndExistingAccountProfile() {
    var name = "phase_three_upgrade_" + java.util.UUID.randomUUID().toString().replace("-", "");
    new JdbcTemplate(dataSource).execute("CREATE DATABASE " + name);
    String url = POSTGRES.getJdbcUrl().replace("/test?", "/" + name + "?");
    var prior =
        Flyway.configure()
            .dataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword())
            .target("0014")
            .load();
    assertThat(prior.migrate().migrationsExecuted).isEqualTo(14);
    var checksums =
        java.util.Arrays.stream(prior.info().applied())
            .map(org.flywaydb.core.api.MigrationInfo::getChecksum)
            .toList();
    var sql =
        new JdbcTemplate(
            new org.springframework.jdbc.datasource.DriverManagerDataSource(
                url, POSTGRES.getUsername(), POSTGRES.getPassword()));
    var id = java.util.UUID.randomUUID();
    sql.update(
        "INSERT INTO account.user_account(id,status,platform_role,created_at,updated_at) VALUES (?,'ACTIVE','USER',now(),now())",
        id);
    sql.update(
        "INSERT INTO profile.profile(user_id,handle,handle_normalized,display_name,visibility,created_at,updated_at) VALUES (?,'synthetic','synthetic','Synthetic','FRIENDS',now(),now())",
        id);
    var upgrade =
        Flyway.configure().dataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword()).load();
    assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(12);
    upgrade.validate();
    assertThat(
            java.util.Arrays.stream(upgrade.info().applied())
                .limit(14)
                .map(org.flywaydb.core.api.MigrationInfo::getChecksum)
                .toList())
        .isEqualTo(checksums);
    assertThat(
            sql.queryForObject(
                "SELECT visibility FROM profile.profile WHERE user_id=?", String.class, id))
        .isEqualTo("FRIENDS");
    assertThat(
            sql.queryForObject(
                "SELECT avatar_media_id FROM profile.profile WHERE user_id=?",
                java.util.UUID.class,
                id))
        .isNull();
    assertThat(upgrade.migrate().migrationsExecuted).isZero();
  }

  @Test
  @org.junit.jupiter.api.Timeout(30)
  void databaseOutageAffectsReadinessButNotLiveness() throws Exception {
    var docker = org.testcontainers.DockerClientFactory.instance().client();
    docker.pauseContainerCmd(POSTGRES.getContainerId()).exec();
    try {
      mvc.perform(get("/actuator/health/readiness"))
          .andExpect(status().isServiceUnavailable())
          .andExpect(jsonPath("$.components").doesNotExist());
      mvc.perform(get("/actuator/health/liveness"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.status").value("UP"));
      var token =
          accessTokens.issue(
              new com.flyvo.socialcore.platform.api.ActorContext(
                  java.util.UUID.randomUUID(), java.util.UUID.randomUUID()));
      mvc.perform(get("/api/v1/me/account").header("Authorization", "Bearer " + token))
          .andExpect(status().isServiceUnavailable())
          .andExpect(jsonPath("$.code").value("DEPENDENCY_UNAVAILABLE"));
    } finally {
      docker.unpauseContainerCmd(POSTGRES.getContainerId()).exec();
    }
    await()
        .atMost(java.time.Duration.ofSeconds(10))
        .untilAsserted(
            () -> mvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk()));
  }

  @Test
  void phaseOneSchemaUpgradesWithAllEightChecksumsAndExistingAccountPreserved() {
    var name = "phase_one_upgrade_" + java.util.UUID.randomUUID().toString().replace("-", "");
    new JdbcTemplate(dataSource).execute("CREATE DATABASE " + name);
    String url = POSTGRES.getJdbcUrl().replace("/test?", "/" + name + "?");
    var prior =
        Flyway.configure()
            .dataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword())
            .target("0008")
            .load();
    assertThat(prior.migrate().migrationsExecuted).isEqualTo(8);
    var checksums =
        java.util.Arrays.stream(prior.info().applied())
            .map(org.flywaydb.core.api.MigrationInfo::getChecksum)
            .toList();
    var ds =
        new org.springframework.jdbc.datasource.DriverManagerDataSource(
            url, POSTGRES.getUsername(), POSTGRES.getPassword());
    var jdbc = new JdbcTemplate(ds);
    var id = java.util.UUID.randomUUID();
    jdbc.update(
        "INSERT INTO account.user_account(id,status,platform_role,created_at,updated_at) VALUES (?,'ACTIVE','USER',now(),now())",
        id);
    var upgrade =
        Flyway.configure().dataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword()).load();
    assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(18);
    upgrade.validate();
    assertThat(
            java.util.Arrays.stream(upgrade.info().applied())
                .limit(8)
                .map(org.flywaydb.core.api.MigrationInfo::getChecksum)
                .toList())
        .isEqualTo(checksums);
    assertThat(
            jdbc.queryForObject(
                "SELECT status FROM account.user_account WHERE id=?", String.class, id))
        .isEqualTo("ACTIVE");
  }

  @Test
  void phaseTwoSchemaUpgradesWithoutRewritingMigrationsOrExistingProfiles() {
    var name = "phase_two_upgrade_" + java.util.UUID.randomUUID().toString().replace("-", "");
    new JdbcTemplate(dataSource).execute("CREATE DATABASE " + name);
    String url = POSTGRES.getJdbcUrl().replace("/test?", "/" + name + "?");
    var prior =
        Flyway.configure()
            .dataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword())
            .target("0011")
            .load();
    assertThat(prior.migrate().migrationsExecuted).isEqualTo(11);
    var checksums =
        java.util.Arrays.stream(prior.info().applied())
            .map(org.flywaydb.core.api.MigrationInfo::getChecksum)
            .toList();
    var sql =
        new JdbcTemplate(
            new org.springframework.jdbc.datasource.DriverManagerDataSource(
                url, POSTGRES.getUsername(), POSTGRES.getPassword()));
    var id = java.util.UUID.randomUUID();
    sql.update(
        "INSERT INTO account.user_account(id,status,platform_role,created_at,updated_at) VALUES (?,'ACTIVE','USER',now(),now())",
        id);
    sql.update(
        "INSERT INTO profile.profile(user_id,handle,handle_normalized,display_name,visibility,created_at,updated_at) VALUES (?,'fixture','fixture','Synthetic','PRIVATE',now(),now())",
        id);
    var upgrade =
        Flyway.configure().dataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword()).load();
    assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(15);
    upgrade.validate();
    assertThat(
            java.util.Arrays.stream(upgrade.info().applied())
                .limit(11)
                .map(org.flywaydb.core.api.MigrationInfo::getChecksum)
                .toList())
        .isEqualTo(checksums);
    assertThat(
            sql.queryForObject(
                "SELECT visibility FROM profile.profile WHERE user_id=?", String.class, id))
        .isEqualTo("PRIVATE");
    assertThat(sql.update("UPDATE profile.profile SET visibility='FRIENDS' WHERE user_id=?", id))
        .isEqualTo(1);
  }

  @Test
  void databaseTimestampRoundTripDoesNotDependOnJvmTimezone() throws Exception {
    var instant = java.time.Instant.parse("2026-10-08T10:20:30.123456Z");
    try (var connection = dataSource.getConnection();
        var statement = connection.prepareStatement("select ?::timestamptz")) {
      statement.setObject(1, instant.atOffset(java.time.ZoneOffset.ofHours(7)));
      try (var result = statement.executeQuery()) {
        assertThat(result.next()).isTrue();
        assertThat(result.getObject(1, java.time.OffsetDateTime.class).toInstant())
            .isEqualTo(instant);
      }
    }
  }

  @Test
  void healthProbesArePublicAndDoNotRevealComponents() throws Exception {
    for (String path :
        new String[] {
          "/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness"
        }) {
      mvc.perform(get(path).header("X-Request-ID", "health-check"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.status").value("UP"))
          .andExpect(jsonPath("$.components").doesNotExist())
          .andExpect(header().string("X-Request-ID", "health-check"));
    }
  }

  @Test
  void unknownApiAndOperationalEndpointsFailClosed() throws Exception {
    for (String path :
        new String[] {
          "/api/v1/comments", "/api/v1/unknown", "/actuator/env", "/actuator/prometheus", "/login"
        }) {
      mvc.perform(get(path).header("X-Request-ID", "denial-123"))
          .andExpect(status().isUnauthorized())
          .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
          .andExpect(jsonPath("$.status").value(401))
          .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"))
          .andExpect(jsonPath("$.requestId").value("denial-123"))
          .andExpect(jsonPath("$.instance").value(path))
          .andExpect(header().doesNotExist("Set-Cookie"));
    }
  }

  @Test
  void evenAnAuthenticatedPrincipalCannotBypassTheDenyAllShell() throws Exception {
    mvc.perform(
            get("/api/v1/unknown")
                .with(
                    org.springframework.security.test.web.servlet.request
                        .SecurityMockMvcRequestPostProcessors.user("test-actor")))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
  }

  @Test
  void unsafeMethodsRequireCsrfAndReturnProblemDetails() throws Exception {
    var result =
        mvc.perform(post("/api/v1/unknown").header("X-Request-ID", "csrf-123"))
            .andExpect(status().isForbidden())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.code").value("ACCESS_DENIED"))
            .andExpect(jsonPath("$.requestId").value("csrf-123"))
            .andReturn();
    assertThat(result.getRequest().getSession(false)).isNull();
  }
}
