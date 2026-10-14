package com.flyvo.socialcore.platform;

import static org.assertj.core.api.Assertions.assertThat;

import com.flyvo.socialcore.platform.api.DurableWork;
import com.flyvo.socialcore.platform.api.EventConsumer;
import com.flyvo.socialcore.platform.api.EventEnvelope;
import com.flyvo.socialcore.platform.infrastructure.persistence.DurableWorkStore;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class EventEnvelopeIT {
  @Container
  static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer(
          "postgres@sha256:77f585114c32fbca283dc835b0596f4e52b51b4c6662d7810b2f4084f60a1873");

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("app.database.url", POSTGRES::getJdbcUrl);
    r.add("app.database.username", POSTGRES::getUsername);
    r.add("app.database.password", POSTGRES::getPassword);
  }

  @TestConfiguration
  static class Fixtures {
    @Bean
    EnvelopeConsumer envelopeConsumer(JdbcTemplate jdbc, DurableWork work) {
      // Consumers call owner use cases that themselves depend on DurableWork.
      return new EnvelopeConsumer(jdbc, work);
    }
  }

  static class EnvelopeConsumer implements EventConsumer {
    private final JdbcTemplate jdbc;
    private final DurableWork work;
    final AtomicReference<EventEnvelope> observed = new AtomicReference<>();

    EnvelopeConsumer(JdbcTemplate jdbc, DurableWork work) {
      this.jdbc = jdbc;
      this.work = work;
    }

    public String name() {
      return "envelope-contract-v1";
    }

    public boolean accepts(String type) {
      return "EnvelopeContract".equals(type);
    }

    public void apply(EventEnvelope event) {
      observed.set(event);
      work.job("ENVELOPE_EFFECT", event.eventId().toString(), "{}", Instant.now());
      if (event.payload().contains("fail")) throw new IllegalStateException("TEST_FAILURE");
      if (event.payload().contains("expire"))
        jdbc.update(
            "UPDATE platform.outbox_delivery SET lease_until=now()-interval '1 second' WHERE event_id=?",
            event.eventId());
    }
  }

  @Autowired JdbcTemplate jdbc;
  @Autowired DurableWork work;
  @Autowired DurableWorkStore store;
  @Autowired PlatformTransactionManager manager;
  @Autowired EnvelopeConsumer consumer;

  UUID append(String payload) {
    var aggregate = UUID.randomUUID();
    new TransactionTemplate(manager)
        .executeWithoutResult(
            s -> work.event("EnvelopeContract", "envelope-contract", aggregate, 73, 2, payload));
    UUID id =
        jdbc.queryForObject(
            "SELECT id FROM platform.outbox_event WHERE aggregate_id=?", UUID.class, aggregate);
    jdbc.update(
        "UPDATE platform.outbox_delivery SET available_at=now()-interval '1 day' WHERE event_id=?",
        id);
    return id;
  }

  @Test
  void deliversExactStoredEnvelopeIncludingLegacyNullCausationWithoutRewritingIt() {
    UUID id = append("{\"sourceId\":\"synthetic\"}");
    UUID cause = UUID.randomUUID();
    jdbc.update(
        "UPDATE platform.outbox_event SET schema_version=3,correlation_id='synthetic-correlation',causation_id=? WHERE id=?",
        cause,
        id);
    var before = jdbc.queryForMap("SELECT * FROM platform.outbox_event WHERE id=?", id);
    store.consumeOne(UUID.randomUUID());
    EventEnvelope event = consumer.observed.get();
    assertThat(event.eventId()).isEqualTo(id);
    assertThat(event.type()).isEqualTo("EnvelopeContract");
    assertThat(event.schemaVersion()).isEqualTo(3);
    assertThat(event.aggregateType()).isEqualTo("envelope-contract");
    assertThat(event.aggregateId()).isEqualTo(before.get("aggregate_id"));
    assertThat(event.aggregateVersion()).isEqualTo(73);
    assertThat(event.eventSequence()).isEqualTo(2);
    assertThat(event.occurredAt())
        .isEqualTo(((java.sql.Timestamp) before.get("occurred_at")).toInstant());
    assertThat(event.correlationId()).isEqualTo("synthetic-correlation");
    assertThat(event.causationId()).isEqualTo(cause);
    assertThat(event.payload()).contains("sourceId", "synthetic");
    assertThat(jdbc.queryForMap("SELECT * FROM platform.outbox_event WHERE id=?", id))
        .isEqualTo(before);
    UUID historical = append("{}");
    store.consumeOne(UUID.randomUUID());
    assertThat(consumer.observed.get().eventId()).isEqualTo(historical);
    assertThat(consumer.observed.get().causationId()).isNull();
  }

  @Test
  void duplicateAndConcurrentWorkersCommitOneReceiptAndEffectWithReclaimedGeneration()
      throws Exception {
    UUID id = append("{}");
    jdbc.update(
        "UPDATE platform.outbox_delivery SET status='RUNNING',attempts=1,lease_generation=8,lease_owner=?,lease_until=now()-interval '1 second' WHERE event_id=?",
        UUID.randomUUID(),
        id);
    try (var pool = Executors.newFixedThreadPool(2)) {
      var a = pool.submit(() -> store.consumeOne(UUID.randomUUID()));
      var b = pool.submit(() -> store.consumeOne(UUID.randomUUID()));
      a.get(10, TimeUnit.SECONDS);
      b.get(10, TimeUnit.SECONDS);
    }
    assertThat(
            jdbc.queryForObject(
                "SELECT lease_generation FROM platform.outbox_delivery WHERE event_id=?",
                Long.class,
                id))
        .isEqualTo(9);
    jdbc.update(
        "UPDATE platform.outbox_delivery SET status='RETRY',available_at=now()-interval '1 day' WHERE event_id=?",
        id);
    store.consumeOne(UUID.randomUUID());
    assertEffects(id, 1);
    assertThat(
            jdbc.queryForObject(
                "SELECT status FROM platform.outbox_delivery WHERE event_id=?", String.class, id))
        .isEqualTo("COMPLETED");
  }

  @Test
  void receiptEffectsAndAcknowledgementRollbackTogetherForFailureOrExpiredFence() {
    for (String mode : java.util.List.of("fail", "expire")) {
      UUID id = append("{\"" + mode + "\":true}");
      store.consumeOne(UUID.randomUUID());
      assertEffects(id, 0);
      assertThat(
              jdbc.queryForObject(
                  "SELECT status FROM platform.outbox_delivery WHERE event_id=?", String.class, id))
          .isEqualTo("RETRY");
      jdbc.update(
          "UPDATE platform.outbox_delivery SET attempts=9,available_at=now()-interval '1 day' WHERE event_id=?",
          id);
      store.consumeOne(UUID.randomUUID());
      assertEffects(id, 0);
      assertThat(
              jdbc.queryForObject(
                  "SELECT status FROM platform.outbox_delivery WHERE event_id=?", String.class, id))
          .isEqualTo("DEAD");
      assertThat(
              jdbc.queryForObject(
                  "SELECT attempts FROM platform.outbox_delivery WHERE event_id=?",
                  Integer.class,
                  id))
          .isEqualTo(10);
    }
  }

  private void assertEffects(UUID id, int count) {
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM platform.consumer_receipt WHERE event_id=?",
                Integer.class,
                id))
        .isEqualTo(count);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM platform.job WHERE kind='ENVELOPE_EFFECT' AND dedup_key=?",
                Integer.class,
                id.toString()))
        .isEqualTo(count);
  }
}
