package com.flyvo.socialcore.account;

import static org.assertj.core.api.Assertions.*;

import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
class ErasureMigrationIT {
  @Container
  static final PostgreSQLContainer PG =
      new PostgreSQLContainer(
              "postgres@sha256:77f585114c32fbca283dc835b0596f4e52b51b4c6662d7810b2f4084f60a1873")
          .withDatabaseName("p9a_migrations");

  Flyway migration(String url, int target) {
    return Flyway.configure()
        .dataSource(url, PG.getUsername(), PG.getPassword())
        .target("%04d".formatted(target))
        .load();
  }

  @Test
  void upgrade32ThroughEveryNewPrefixPreservesHistoricalChecksumsAndBackfillsClaims() {
    var prior = migration(PG.getJdbcUrl(), 32);
    assertThat(prior.migrate().migrationsExecuted).isEqualTo(32);
    var sums =
        Arrays.stream(prior.info().applied())
            .map(org.flywaydb.core.api.MigrationInfo::getChecksum)
            .toList();
    var jdbc =
        new JdbcTemplate(
            new org.springframework.jdbc.datasource.DriverManagerDataSource(
                PG.getJdbcUrl(), PG.getUsername(), PG.getPassword()));
    var user = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO account.user_account(id,status,created_at,updated_at) VALUES (?,'ACTIVE',now(),now())",
        user);
    jdbc.update(
        "INSERT INTO profile.profile(user_id,handle,handle_normalized,display_name,bio,visibility,created_at,updated_at) VALUES (?,'synthetic_upgrade','synthetic_upgrade','Synthetic','','PRIVATE',now(),now())",
        user);
    var suspended = UUID.randomUUID();
    var pending = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO account.user_account(id,status,created_at,updated_at,version) VALUES (?,'SUSPENDED',now(),now(),1)",
        suspended);
    jdbc.update(
        "INSERT INTO account.user_account(id,status,deletion_requested_at,created_at,updated_at,version) VALUES (?,'DELETION_PENDING',now()-interval '2 hours',now()-interval '3 hours',now(),1)",
        pending);
    for (int prefix = 33; prefix <= 52; prefix++) {
      var candidate = migration(PG.getJdbcUrl(), prefix);
      assertThat(candidate.migrate().migrationsExecuted).isEqualTo(1);
      candidate.validate();
      assertThat(
              Arrays.stream(candidate.info().applied())
                  .limit(32)
                  .map(org.flywaydb.core.api.MigrationInfo::getChecksum)
                  .toList())
          .isEqualTo(sums);
    }
    assertThat(
            jdbc.queryForObject(
                "SELECT user_id FROM profile.handle_claim WHERE handle_normalized='synthetic_upgrade'",
                UUID.class))
        .isEqualTo(user);
    assertThat(
            jdbc.queryForObject(
                "SELECT status FROM account.user_account WHERE id=?", String.class, suspended))
        .isEqualTo("SUSPENDED");
    assertThat(
            jdbc.queryForObject(
                "SELECT status FROM account.user_account WHERE id=?", String.class, pending))
        .isEqualTo("DELETION_PENDING");
    assertThat(jdbc.queryForObject("SELECT count(*) FROM account.erasure_request", Integer.class))
        .isZero();
  }

  @Test
  void acceptedPackageIUpgradePreservesAllChecksumsAndLiveSourceValues() {
    var admin =
        new JdbcTemplate(
            new org.springframework.jdbc.datasource.DriverManagerDataSource(
                PG.getJdbcUrl(), PG.getUsername(), PG.getPassword()));
    admin.execute("CREATE DATABASE p9a_ii_upgrade");
    var url = PG.getJdbcUrl().replace("/p9a_migrations?", "/p9a_ii_upgrade?");
    var prior = migration(url, 37);
    assertThat(prior.migrate().migrationsExecuted).isEqualTo(37);
    var sums =
        Arrays.stream(prior.info().applied())
            .map(org.flywaydb.core.api.MigrationInfo::getChecksum)
            .toList();
    var jdbc =
        new JdbcTemplate(
            new org.springframework.jdbc.datasource.DriverManagerDataSource(
                url, PG.getUsername(), PG.getPassword()));
    UUID user = UUID.randomUUID(), post = UUID.randomUUID(), reaction = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO account.user_account(id,status,created_at,updated_at) VALUES (?,'ACTIVE',now(),now())",
        user);
    jdbc.update(
        "INSERT INTO content.post(id,author_id,body,audience,status,revision_number,published_at,created_at,updated_at) VALUES (?,?,'Synthetic upgrade content','PUBLIC','PUBLISHED',1,now(),now(),now())",
        post,
        user);
    jdbc.update(
        "INSERT INTO content.reaction(id,actor_id,post_id,type,active,created_at,updated_at) VALUES (?,?,?,'LOVE',true,now(),now())",
        reaction,
        user,
        post);
    var candidate = migration(url, 52);
    assertThat(candidate.migrate().migrationsExecuted).isEqualTo(15);
    candidate.validate();
    assertThat(
            Arrays.stream(candidate.info().applied())
                .limit(37)
                .map(org.flywaydb.core.api.MigrationInfo::getChecksum)
                .toList())
        .isEqualTo(sums);
    assertThat(jdbc.queryForObject("SELECT body FROM content.post WHERE id=?", String.class, post))
        .isEqualTo("Synthetic upgrade content");
    assertThat(
            jdbc.queryForObject(
                "SELECT type FROM content.reaction WHERE id=?", String.class, reaction))
        .isEqualTo("LOVE");
    assertThat(
            jdbc.queryForObject(
                "SELECT erased_by_account FROM content.reaction WHERE id=?",
                Boolean.class,
                reaction))
        .isFalse();
  }

  @Test
  void preservationFormatUpgradeRetainsLegacyReceiptsAndRollsBackFailedPrefix() {
    var admin =
        new JdbcTemplate(
            new org.springframework.jdbc.datasource.DriverManagerDataSource(
                PG.getJdbcUrl(), PG.getUsername(), PG.getPassword()));
    admin.execute("CREATE DATABASE p9a_ii_format_upgrade");
    String url = PG.getJdbcUrl().replace("/p9a_migrations?", "/p9a_ii_format_upgrade?");
    var prior = migration(url, 51);
    prior.migrate();
    var sums =
        Arrays.stream(prior.info().applied())
            .map(org.flywaydb.core.api.MigrationInfo::getChecksum)
            .toList();
    var jdbc =
        new JdbcTemplate(
            new org.springframework.jdbc.datasource.DriverManagerDataSource(
                url, PG.getUsername(), PG.getPassword()));
    UUID id = UUID.randomUUID(), root = UUID.randomUUID();
    String record = "{\"operation\":\"BASELINE\",\"version\":7}";
    String digest =
        jdbc.queryForObject(
            "SELECT encode(sha256(convert_to(?,'UTF8')),'hex')", String.class, record);
    jdbc.update(
        "INSERT INTO moderation.preservation_export(id,root_type,root_id,root_version,operation,record,digest,receipt_digest,verified_at,execution_id) VALUES (?,'REPORT',?,7,'BASELINE',?,?,?,timestamptz '2020-01-01 00:00:00Z',?)",
        id,
        root,
        record,
        digest,
        digest,
        id);
    jdbc.update(
        "INSERT INTO moderation.preservation_state(root_type,root_id,root_version,complete) VALUES ('REPORT',?,7,true)",
        root);
    jdbc.execute("ALTER TABLE moderation.preservation_state ADD COLUMN format_version text");
    assertThatThrownBy(() -> migration(url, 52).migrate())
        .isInstanceOf(org.flywaydb.core.api.FlywayException.class);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.columns WHERE table_schema='moderation' AND table_name='preservation_export' AND column_name='format_version'",
                Integer.class))
        .isZero();
    jdbc.execute("ALTER TABLE moderation.preservation_state DROP COLUMN format_version");
    var candidate = migration(url, 52);
    assertThat(candidate.migrate().migrationsExecuted).isEqualTo(1);
    candidate.validate();
    assertThat(
            Arrays.stream(candidate.info().applied())
                .limit(51)
                .map(org.flywaydb.core.api.MigrationInfo::getChecksum)
                .toList())
        .isEqualTo(sums);
    assertThat(
            jdbc.queryForObject(
                "SELECT record FROM moderation.preservation_export WHERE id=?", String.class, id))
        .isEqualTo(record);
    assertThat(
            jdbc.queryForObject(
                "SELECT format_version=1 AND receipt_digest=digest AND verified_at=timestamptz '2020-01-01 00:00:00Z' FROM moderation.preservation_export WHERE id=?",
                Boolean.class,
                id))
        .isTrue();
    assertThat(
            jdbc.queryForObject(
                "SELECT complete AND root_version=7 AND format_version=1 FROM moderation.preservation_state WHERE root_id=?",
                Boolean.class,
                root))
        .isTrue();
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE moderation.preservation_export SET format_version=2 WHERE id=?", id))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
  }

  @Test
  void failedPackageIIMigrationRollsBackEveryEarlierStatementThenRecoversForward() {
    var admin =
        new JdbcTemplate(
            new org.springframework.jdbc.datasource.DriverManagerDataSource(
                PG.getJdbcUrl(), PG.getUsername(), PG.getPassword()));
    admin.execute("CREATE DATABASE p9a_ii_failure");
    var url = PG.getJdbcUrl().replace("/p9a_migrations?", "/p9a_ii_failure?");
    migration(url, 45).migrate();
    var jdbc =
        new JdbcTemplate(
            new org.springframework.jdbc.datasource.DriverManagerDataSource(
                url, PG.getUsername(), PG.getPassword()));
    jdbc.execute("CREATE TABLE moderation.preservation_state(synthetic_blocker boolean)");
    assertThatThrownBy(() -> migration(url, 46).migrate())
        .isInstanceOf(org.flywaydb.core.api.FlywayException.class);
    assertThat(
            jdbc.queryForObject(
                "SELECT max(version::int) FROM public.flyway_schema_history WHERE success",
                Integer.class))
        .isEqualTo(45);
    assertThat(
            jdbc.queryForObject(
                "SELECT to_regclass('moderation.preservation_export') IS NULL", Boolean.class))
        .isTrue();
    assertThat(
            jdbc.queryForObject(
                "SELECT to_regclass('moderation.report_erasure_source') IS NULL", Boolean.class))
        .isTrue();
    jdbc.execute("DROP TABLE moderation.preservation_state");
    assertThat(migration(url, 52).migrate().migrationsExecuted).isEqualTo(7);
    migration(url, 52).validate();
  }

  @Test
  void bulkProfileCreationUsesUniqueClaimsWithoutExhaustingAdvisoryLockMemory() {
    var admin =
        new JdbcTemplate(
            new org.springframework.jdbc.datasource.DriverManagerDataSource(
                PG.getJdbcUrl(), PG.getUsername(), PG.getPassword()));
    admin.execute("CREATE DATABASE p9a_bulk_claims");
    var url = PG.getJdbcUrl().replace("/p9a_migrations?", "/p9a_bulk_claims?");
    migration(url, 37).migrate();
    var jdbc =
        new JdbcTemplate(
            new org.springframework.jdbc.datasource.DriverManagerDataSource(
                url, PG.getUsername(), PG.getPassword()));
    var tx =
        new org.springframework.transaction.support.TransactionTemplate(
            new org.springframework.jdbc.datasource.DataSourceTransactionManager(
                jdbc.getDataSource()));
    tx.executeWithoutResult(
        s -> {
          jdbc.update(
              "INSERT INTO account.user_account(id,status,created_at,updated_at) SELECT md5('synthetic-bulk:'||i)::uuid,'ACTIVE',now(),now() FROM generate_series(1,20050) i");
          jdbc.update(
              "INSERT INTO profile.profile(user_id,handle,handle_normalized,display_name,visibility,created_at,updated_at) SELECT md5('synthetic-bulk:'||i)::uuid,'bulk_'||i,'bulk_'||i,'Synthetic','PRIVATE',now(),now() FROM generate_series(1,20050) i");
          assertThat(
                  jdbc.queryForObject(
                      "SELECT count(*) FROM pg_locks WHERE pid=pg_backend_pid() AND locktype='advisory'",
                      Integer.class))
              .isZero();
        });
    assertThat(jdbc.queryForObject("SELECT count(*) FROM profile.handle_claim", Integer.class))
        .isEqualTo(20050);
  }

  @Test
  void failedNewMigrationRollsBackAtomicallyThenOriginalMigrationAppliesForward() {
    var admin =
        new JdbcTemplate(
            new org.springframework.jdbc.datasource.DriverManagerDataSource(
                PG.getJdbcUrl(), PG.getUsername(), PG.getPassword()));
    admin.execute("CREATE DATABASE p9a_failure");
    var url = PG.getJdbcUrl().replace("/p9a_migrations?", "/p9a_failure?");
    migration(url, 35).migrate();
    var jdbc =
        new JdbcTemplate(
            new org.springframework.jdbc.datasource.DriverManagerDataSource(
                url, PG.getUsername(), PG.getPassword()));
    jdbc.execute("CREATE TABLE profile.handle_claim(synthetic_blocker boolean)");
    assertThatThrownBy(() -> migration(url, 36).migrate())
        .isInstanceOf(org.flywaydb.core.api.FlywayException.class);
    assertThat(
            jdbc.queryForObject(
                "SELECT max(version::int) FROM public.flyway_schema_history WHERE success",
                Integer.class))
        .isEqualTo(35);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM pg_trigger WHERE tgname='profile_handle_claim'",
                Integer.class))
        .isZero();
    jdbc.execute(
        "DROP TABLE profile.handle_claim"); // Exact synthetic blocker only; no application data or
    // applied migration edited.
    assertThat(migration(url, 36).migrate().migrationsExecuted).isEqualTo(1);
    migration(url, 36).validate();
    assertThat(migration(url, 37).migrate().migrationsExecuted).isEqualTo(1);
    migration(url, 37).validate();
  }
}
