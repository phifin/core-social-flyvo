package com.flyvo.socialcore.feed;

import static org.assertj.core.api.Assertions.*;

import com.flyvo.socialcore.content.api.ContentCommands;
import com.flyvo.socialcore.content.api.ContentQueries;
import com.flyvo.socialcore.feed.api.FeedQueries;
import com.flyvo.socialcore.platform.api.ActorContext;
import io.micrometer.core.instrument.MeterRegistry;
import java.lang.reflect.*;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.test.context.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Explicit heavy tool: not *Test/*IT, no implicit normal-suite skip or production seed. */
@SpringBootTest(properties = "logging.level.root=WARN")
@ActiveProfiles("test")
@Testcontainers
class FeedBenchmarkTool {
  @Container
  static final PostgreSQLContainer PG =
      new PostgreSQLContainer(
          "postgres@sha256:77f585114c32fbca283dc835b0596f4e52b51b4c6662d7810b2f4084f60a1873");

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry r) {
    r.add("app.database.url", PG::getJdbcUrl);
    r.add("app.database.username", PG::getUsername);
    r.add("app.database.password", PG::getPassword);
  }

  static final AtomicLong STATEMENTS = new AtomicLong();
  static final ConcurrentHashMap<String, AtomicLong> SQL_COUNTS = new ConcurrentHashMap<>();
  static final AtomicLong ACQUISITIONS = new AtomicLong();
  static final AtomicLong ACQUIRE_NANOS = new AtomicLong();
  static final ConcurrentHashMap<String, AtomicLong> EXECUTE_NANOS = new ConcurrentHashMap<>();
  static volatile Trace candidateTrace;
  static volatile Trace hydrateTrace;

  record Trace(String sql, List<Object> bindings) {}

  static class TracingDataSource extends DelegatingDataSource implements AutoCloseable {
    TracingDataSource(DataSource source) {
      super(source);
    }

    public Connection getConnection() throws SQLException {
      long start = System.nanoTime();
      try {
        return trace(super.getConnection(), start);
      } catch (SQLException failure) {
        FeedBenchmarkDiagnostics.failedAcquire(System.nanoTime() - start);
        throw failure;
      } finally {
        ACQUISITIONS.incrementAndGet();
        ACQUIRE_NANOS.addAndGet(System.nanoTime() - start);
      }
    }

    public Connection getConnection(String u, String p) throws SQLException {
      long start = System.nanoTime();
      try {
        return trace(super.getConnection(u, p), start);
      } catch (SQLException failure) {
        FeedBenchmarkDiagnostics.failedAcquire(System.nanoTime() - start);
        throw failure;
      } finally {
        ACQUISITIONS.incrementAndGet();
        ACQUIRE_NANOS.addAndGet(System.nanoTime() - start);
      }
    }

    public void close() throws Exception {
      if (getTargetDataSource() instanceof AutoCloseable closeable) closeable.close();
    }

    static Object invoke(Object target, Method method, Object[] args) throws Throwable {
      try {
        return method.invoke(target, args);
      } catch (InvocationTargetException e) {
        throw e.getCause();
      }
    }

    Connection trace(Connection c, long started) {
      long lease = FeedBenchmarkDiagnostics.borrowed(System.nanoTime() - started);
      return (Connection)
          Proxy.newProxyInstance(
              Connection.class.getClassLoader(),
              new Class[] {Connection.class},
              (proxy, method, args) -> {
                if (method.getName().equals("close")) {
                  try {
                    return invoke(c, method, args);
                  } finally {
                    FeedBenchmarkDiagnostics.released(lease);
                  }
                }
                var result = invoke(c, method, args);
                if (method.getName().equals("createStatement") && result instanceof Statement st) {
                  return Proxy.newProxyInstance(
                      Statement.class.getClassLoader(),
                      new Class[] {Statement.class},
                      (p, m, a) -> {
                        if (!m.getName().startsWith("execute")
                            || a == null
                            || a.length == 0
                            || !(a[0] instanceof String sql)) return invoke(st, m, a);
                        STATEMENTS.incrementAndGet();
                        SQL_COUNTS.computeIfAbsent(sql, k -> new AtomicLong()).incrementAndGet();
                        long start = System.nanoTime();
                        try {
                          return invoke(st, m, a);
                        } finally {
                          FeedBenchmarkDiagnostics.executed(System.nanoTime() - start);
                          EXECUTE_NANOS
                              .computeIfAbsent(sql, k -> new AtomicLong())
                              .addAndGet(System.nanoTime() - start);
                        }
                      });
                }
                if (method.getName().equals("prepareStatement")
                    && args[0] instanceof String sql
                    && result instanceof PreparedStatement ps) {
                  var bindings = new TreeMap<Integer, Object>();
                  return Proxy.newProxyInstance(
                      PreparedStatement.class.getClassLoader(),
                      new Class[] {PreparedStatement.class},
                      (p, m, a) -> {
                        if (m.getName().startsWith("set")
                            && a != null
                            && a.length >= 2
                            && a[0] instanceof Integer index)
                          bindings.put(index, m.getName().equals("setNull") ? null : a[1]);
                        if (m.getName().startsWith("execute")) {
                          STATEMENTS.incrementAndGet();
                          SQL_COUNTS.computeIfAbsent(sql, k -> new AtomicLong()).incrementAndGet();
                          if (sql.startsWith("SELECT candidate.id,candidate.published_at FROM ("))
                            candidateTrace = new Trace(sql, new ArrayList<>(bindings.values()));
                          if (sql.startsWith("SELECT p.*,") && sql.contains("facts WHERE"))
                            hydrateTrace = new Trace(sql, new ArrayList<>(bindings.values()));
                        }
                        if (!m.getName().startsWith("execute")) return invoke(ps, m, a);
                        long start = System.nanoTime();
                        try {
                          return invoke(ps, m, a);
                        } finally {
                          FeedBenchmarkDiagnostics.executed(System.nanoTime() - start);
                          EXECUTE_NANOS
                              .computeIfAbsent(sql, k -> new AtomicLong())
                              .addAndGet(System.nanoTime() - start);
                        }
                      });
                }
                return result;
              });
    }
  }

  @TestConfiguration
  static class Config {
    @Bean
    static BeanPostProcessor benchmarkSqlTrace() {
      return new BeanPostProcessor() {
        public Object postProcessAfterInitialization(Object bean, String name) {
          return bean instanceof DataSource source && name.equals("dataSource")
              ? new TracingDataSource(source)
              : bean;
        }
      };
    }
  }

  @Autowired JdbcTemplate jdbc;
  @Autowired FeedQueries feeds;
  @Autowired ContentQueries content;
  @Autowired ContentCommands commands;
  @Autowired MeterRegistry metrics;
  @Autowired org.springframework.transaction.PlatformTransactionManager manager;
  @Autowired DataSource dataSource;
  @Autowired tools.jackson.databind.ObjectMapper json;

  // Deterministic synthetic UUIDs, not authentication/crypto or production seed IDs.
  UUID fixtureUser(int i) {
    return jdbc.queryForObject("SELECT md5('feed-bench-user:'||?::text)::uuid", UUID.class, i);
  }

  @Test
  void benchmarkMillionPostsTenThousandFollowsSkewBlocksAndFiftyReaders() throws Exception {
    assertThat(System.getProperty("feed.benchmark"))
        .as("Explicit isolated heavy benchmark acknowledgement")
        .isEqualTo("true");
    long seedStart = System.nanoTime();
    jdbc.update(
        "INSERT INTO account.user_account(id,status,created_at,updated_at) SELECT md5('feed-bench-user:'||i)::uuid,'ACTIVE',now(),now() FROM generate_series(1,20050) i");
    jdbc.update(
        "INSERT INTO profile.profile(user_id,handle,handle_normalized,display_name,visibility,created_at,updated_at) SELECT md5('feed-bench-user:'||i)::uuid,'bench_'||i,'bench_'||i,'Synthetic author','AUTHENTICATED',now(),now() FROM generate_series(1,20050) i");
    var tx = new TransactionTemplate(manager);
    tx.executeWithoutResult(
        s -> {
          jdbc.update(
              "INSERT INTO community.community(id,slug_normalized,name,description,visibility,join_mode,status,owner_user_id,created_at,updated_at) SELECT md5('feed-bench-community:'||i)::uuid,'bench-scope-'||i,'Synthetic scope','','PRIVATE','OPEN','ACTIVE',md5('feed-bench-user:1')::uuid,now(),now() FROM generate_series(1,100) i");
          jdbc.update(
              "INSERT INTO community.membership(community_id,user_id,role,status,joined_at,updated_at) SELECT md5('feed-bench-community:'||i)::uuid,md5('feed-bench-user:1')::uuid,'MEMBER','ACTIVE',now(),now() FROM generate_series(1,100) i");
        });
    jdbc.update(
        "INSERT INTO community.membership(community_id,user_id,role,status,joined_at,updated_at) SELECT md5('feed-bench-community:'||c)::uuid,md5('feed-bench-user:'||u)::uuid,'MEMBER','ACTIVE',now(),now() FROM generate_series(20001,20050) u CROSS JOIN generate_series(1,5) c");
    for (int reader = 20001; reader <= 20050; reader++) {
      jdbc.update(
          "INSERT INTO graph.follow(follower_id,followed_id,created_at) SELECT md5('feed-bench-user:'||u)::uuid,md5('feed-bench-user:'||a)::uuid,now() FROM (SELECT ?::int u) reader CROSS JOIN generate_series(4001,14000) a",
          reader);
      jdbc.update(
          "INSERT INTO graph.block(blocker_id,blocked_id,created_at) SELECT md5('feed-bench-user:'||u)::uuid,md5('feed-bench-user:'||a)::uuid,now() FROM (SELECT ?::int u) reader CROSS JOIN generate_series(1,4000) a",
          reader);
      jdbc.update(
          "INSERT INTO graph.mute(muter_id,muted_id,created_at) SELECT md5('feed-bench-user:'||u)::uuid,md5('feed-bench-user:'||a)::uuid,now() FROM (SELECT ?::int u) reader CROSS JOIN generate_series(4001,5000) a",
          reader);
      jdbc.update(
          "INSERT INTO graph.pair_revision(id,low_user_id,high_user_id,version) SELECT md5('feed-bench-pair:'||u||':'||a)::uuid,LEAST(md5('feed-bench-user:'||u)::uuid,md5('feed-bench-user:'||a)::uuid),GREATEST(md5('feed-bench-user:'||u)::uuid,md5('feed-bench-user:'||a)::uuid),1 FROM (SELECT ?::int u) reader CROSS JOIN generate_series(1,14000) a",
          reader);
    }
    jdbc.update(
        "INSERT INTO graph.friend_request(id,requester_id,addressee_id,status,created_at,expires_at,resolved_at,version) SELECT md5('feed-bench-friend:'||u||':'||a)::uuid,md5('feed-bench-user:'||u)::uuid,md5('feed-bench-user:'||a)::uuid,'ACCEPTED',now(),now()+interval '30 days',now(),1 FROM generate_series(20001,20050) u CROSS JOIN generate_series(15001,15100) a");
    jdbc.update(
        "INSERT INTO graph.friendship(low_user_id,high_user_id,established_at,request_id) SELECT low_user_id,high_user_id,now(),id FROM graph.friend_request");
    jdbc.update(
        "INSERT INTO graph.pair_revision(id,low_user_id,high_user_id,version) SELECT md5('feed-bench-friend-pair:'||id)::uuid,low_user_id,high_user_id,1 FROM graph.friend_request");
    for (int batch = 0; batch < 100; batch++) {
      int start = batch * 10000 + 1, end = (batch + 1) * 10000;
      tx.executeWithoutResult(
          s -> {
            jdbc.update(
                """
          INSERT INTO content.post(id,author_id,community_id,body,audience,status,revision_number,published_at,created_at,updated_at)
          SELECT md5('feed-bench-post:'||i)::uuid,md5('feed-bench-user:'||(1+(i::bigint*7919)%20000))::uuid,
            CASE WHEN i%10<3 THEN md5('feed-bench-community:'||(CASE WHEN i%100<28 THEN 1 ELSE 1+i%100 END))::uuid END,
            'Synthetic bounded feed benchmark text',
            CASE WHEN i%10<3 THEN 'COMMUNITY' WHEN i%100=99 THEN 'CUSTOM' WHEN i%10 IN (3,4,5,6) THEN 'PUBLIC' WHEN i%10 IN (7,8) THEN 'FOLLOWERS' ELSE 'ONLY_ME' END,
            'PUBLISHED',1,timestamptz '2026-10-10 00:00:00Z' + i*interval '1 millisecond',now(),now()
          FROM generate_series(?,?) i
          """,
                start, end);
            jdbc.update(
                "INSERT INTO content.post_recipient(post_id,user_id) SELECT md5('feed-bench-post:'||i)::uuid,md5('feed-bench-user:'||u)::uuid FROM generate_series(?,?) i CROSS JOIN generate_series(20001,20050) u WHERE i%100=99",
                start, end);
          });
      if (batch % 10 == 9) System.out.println("FEED_BENCH seed_posts=" + end);
    }
    jdbc.execute("ANALYZE");
    var readers = new ArrayList<ActorContext>();
    for (int i = 20001; i <= 20050; i++)
      readers.add(new ActorContext(fixtureUser(i), UUID.randomUUID()));
    assertThat(jdbc.queryForObject("SELECT count(*) FROM content.post", Long.class))
        .isEqualTo(1000000);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM graph.follow WHERE follower_id=?",
                Long.class,
                readers.getFirst().userId()))
        .isEqualTo(10000);
    System.out.println(
        "FEED_BENCH environment processors="
            + Runtime.getRuntime().availableProcessors()
            + " jvm_max_bytes="
            + Runtime.getRuntime().maxMemory()
            + " pg="
            + jdbc.queryForObject("SELECT version()", String.class)
            + " seed_seconds="
            + (System.nanoTime() - seedStart) / 1e9);
    for (String path :
        List.of("/sys/fs/cgroup/cpu.max", "/sys/fs/cgroup/memory.max", "/proc/meminfo"))
      System.out.println(
          "FEED_BENCH hardware "
              + path
              + "="
              + (Files.isReadable(Path.of(path))
                  ? Files.readString(Path.of(path)).lines().limit(3).toList()
                  : "unavailable on this host"));
    System.out.println(
        "FEED_BENCH database_bytes="
            + jdbc.queryForObject("SELECT pg_database_size(current_database())", Long.class));
    try {
      for (var reader : readers) {
        assertThat(feeds.home(reader, 20, null).items()).hasSize(20);
        if (reader == readers.getFirst()) explain("first_warmup_candidate", candidateTrace, reader);
      }
    } catch (RuntimeException failure) {
      if (candidateTrace != null) {
        System.out.println(
            "FEED_BENCH failed_candidate_plan="
                + jdbc.queryForObject(
                    "EXPLAIN (FORMAT JSON) " + candidateTrace.sql(),
                    String.class,
                    candidateTrace.bindings().toArray()));
      }
      throw failure;
    }
    var hikari =
        (com.zaxxer.hikari.HikariDataSource) ((TracingDataSource) dataSource).getTargetDataSource();
    assertThat(hikari.getMaximumPoolSize()).isEqualTo(10);
    var loadFailures = new ConcurrentLinkedQueue<String>();
    for (int poolSize : List.of(10, 50)) {
      hikari.setMaximumPoolSize(poolSize);
      System.out.println(
          "FEED_BENCH pool_size=" + poolSize + " production_default=" + (poolSize == 10));
      // Preserve the first parallel burst rather than hiding pool/statement warmup.
      // The second burst measures steady state after the same 500 real requests.
      for (String scenario : List.of("first_burst", "post_burst_steady")) {
        STATEMENTS.set(0);
        SQL_COUNTS.clear();
        resetProfile();
        double examinedStart =
            metrics.get("feed.candidates.examined").tag("mode", "home").counter().count();
        double returnedStart =
            metrics.get("feed.items.returned").tag("mode", "home").counter().count();
        try (var diagnostic = new FeedBenchmarkDiagnostics(hikari, observationConnection())) {
          int failuresBefore = loadFailures.size();
          var latencies = new ConcurrentLinkedQueue<Long>();
          var barrier = new CyclicBarrier(50);
          long measuredStart = System.nanoTime();
          try (var pool = Executors.newFixedThreadPool(50)) {
            var futures =
                readers.stream()
                    .map(
                        reader ->
                            pool.submit(
                                () -> {
                                  barrier.await(10, TimeUnit.SECONDS);
                                  for (int round = 0; round < 10; round++) {
                                    long start = System.nanoTime();
                                    try {
                                      var page = feeds.home(reader, 20, null);
                                      assertThat(page.items()).hasSize(20);
                                      latencies.add(System.nanoTime() - start);
                                    } catch (RuntimeException failure) {
                                      System.out.println(
                                          "FEED_BENCH failure="
                                              + json.writeValueAsString(
                                                  diagnostic.failure(
                                                      poolSize, scenario, round + 1, start,
                                                      failure)));
                                      loadFailures.add(
                                          "pool="
                                              + poolSize
                                              + " scenario="
                                              + scenario
                                              + " "
                                              + failure.getClass().getSimpleName());
                                    }
                                  }
                                  return true;
                                }))
                    .toList();
            for (var future : futures) assertThat(future.get(180, TimeUnit.SECONDS)).isTrue();
          }
          long statements = STATEMENTS.get();
          System.out.println("FEED_BENCH cumulative_request_failures=" + loadFailures.size());
          System.out.printf(
              Locale.ROOT,
              "FEED_BENCH outcomes pool=%d scenario=%s attempts=500 successes=%d failures=%d error_rate=%.6f percentiles=success_only%n",
              poolSize,
              scenario,
              latencies.size(),
              loadFailures.size() - failuresBefore,
              (loadFailures.size() - failuresBefore) / 500.0);
          var times = latencies.stream().sorted().toList();
          double seconds = (System.nanoTime() - measuredStart) / 1e9;
          double
              examined =
                  metrics.get("feed.candidates.examined").tag("mode", "home").counter().count()
                      - examinedStart,
              returned =
                  metrics.get("feed.items.returned").tag("mode", "home").counter().count()
                      - returnedStart;
          System.out.printf(
              Locale.ROOT,
              "FEED_BENCH results scenario=%s requests=%d concurrent_readers=50 seconds=%.3f throughput=%.3f p50_ms=%.3f p95_ms=%.3f p99_ms=%.3f sql_per_attempt=%.3f examined=%.0f returned=%.0f scan_ratio=%.3f p95_target_met=%s%n",
              scenario,
              times.size(),
              seconds,
              times.size() / seconds,
              percentile(times, .50),
              percentile(times, .95),
              percentile(times, .99),
              (double) statements / 500,
              examined,
              returned,
              examined / returned,
              percentile(times, .95) < 500);
          profile(scenario, 500);
          System.out.println(
              "FEED_BENCH diagnostics pool="
                  + poolSize
                  + " scenario="
                  + scenario
                  + " "
                  + json.writeValueAsString(diagnostic.summary()));
        }
      }
      feeds.home(readers.getFirst(), 20, null);
      explain("dense_candidate_pool_" + poolSize, candidateTrace, readers.getFirst());
      explain("dense_hydrate_pool_" + poolSize, hydrateTrace, readers.getFirst());
      sparseScenarios(poolSize, readers.getFirst(), loadFailures, hikari);
    }
    for (var mode : List.of(ContentQueries.FeedMode.COMMUNITY, ContentQueries.FeedMode.TIMELINE)) {
      UUID scope =
          mode == ContentQueries.FeedMode.COMMUNITY
              ? jdbc.queryForObject("SELECT md5('feed-bench-community:1')::uuid", UUID.class)
              : fixtureUser(6003);
      long start = System.nanoTime();
      var page =
          mode == ContentQueries.FeedMode.COMMUNITY
              ? feeds.community(readers.getFirst(), scope, 20, null)
              : feeds.timeline(readers.getFirst(), scope, 20, null);
      assertThat(page.items()).hasSize(20);
      System.out.println(
          "FEED_BENCH surface="
              + mode
              + " latency_ms="
              + (System.nanoTime() - start) / 1e6
              + " returned="
              + page.items().size());
    }
    feeds.home(readers.getFirst(), 20, null);
    explain("home_candidate", candidateTrace, readers.getFirst());
    explain("home_hydrate", hydrateTrace, readers.getFirst());
    System.out.println("FEED_BENCH query_shapes=" + SQL_COUNTS.size());
    assertThat(loadFailures).as("Every measured request must succeed in both pools").isEmpty();
  }

  double percentile(List<Long> values, double q) {
    if (values.isEmpty()) return Double.NaN;
    return values.get(Math.min(values.size() - 1, (int) Math.ceil(q * values.size()) - 1)) / 1e6;
  }

  void explain(String name, Trace trace, ActorContext actor) {
    assertThat(trace).isNotNull();
    var tx = new TransactionTemplate(manager);
    tx.setReadOnly(true);
    var plan =
        tx.execute(
            s -> {
              // Apply the actual owner path's LOCAL settings, not a copied test tuning.
              var scope = new ContentQueries.FeedScope(ContentQueries.FeedMode.HOME, null);
              if (trace.sql().startsWith("SELECT p.*,")) {
                var probe =
                    trace.bindings().stream()
                        .filter(UUID.class::isInstance)
                        .map(UUID.class::cast)
                        .filter(id -> !id.equals(actor.userId()))
                        .findFirst()
                        .orElseThrow();
                content.hydrateAuthorized(actor, scope, List.of(probe));
              } else content.scanCandidates(actor, scope, 1, Instant.now(), null);
              return jdbc.queryForObject(
                  "EXPLAIN (ANALYZE,BUFFERS,FORMAT JSON) " + trace.sql(),
                  String.class,
                  trace.bindings().toArray());
            });
    System.out.println("FEED_BENCH explain=" + name + " " + plan);
  }

  void resetProfile() {
    FeedBenchmarkDiagnostics.reset();
    ACQUISITIONS.set(0);
    ACQUIRE_NANOS.set(0);
    EXECUTE_NANOS.clear();
  }

  Connection observationConnection() {
    var properties = new Properties();
    properties.setProperty("user", PG.getUsername());
    properties.setProperty("password", PG.getPassword());
    properties.setProperty("connectTimeout", "2");
    properties.setProperty("socketTimeout", "2");
    try {
      return DriverManager.getConnection(PG.getJdbcUrl(), properties);
    } catch (SQLException failure) {
      throw new IllegalStateException(
          "DIAGNOSTIC_CONNECTION_UNAVAILABLE " + FeedBenchmarkDiagnostics.causes(failure));
    }
  }

  void profile(String scenario, int requests) {
    double candidate =
        EXECUTE_NANOS.entrySet().stream()
                .filter(
                    e -> e.getKey().startsWith("SELECT candidate.id,candidate.published_at FROM ("))
                .mapToLong(e -> e.getValue().get())
                .sum()
            / 1e6
            / requests;
    double all = EXECUTE_NANOS.values().stream().mapToLong(AtomicLong::get).sum() / 1e6 / requests;
    System.out.printf(
        Locale.ROOT,
        "FEED_BENCH profile scenario=%s acquire_calls=%d acquire_ms_per_attempt=%.3f candidate_execute_ms_per_attempt=%.3f all_sql_execute_ms_per_attempt=%.3f%n",
        scenario,
        ACQUISITIONS.get(),
        ACQUIRE_NANOS.get() / 1e6 / requests,
        candidate,
        all);
  }

  void sparseScenarios(
      int poolSize,
      ActorContext dense,
      ConcurrentLinkedQueue<String> loadFailures,
      com.zaxxer.hikari.HikariDataSource hikari)
      throws Exception {
    // Readers beyond the original dense set have no graph/community sources.
    if (poolSize == 10) {
      jdbc.update(
          "INSERT INTO account.user_account(id,status,created_at,updated_at) SELECT md5('feed-bench-user:'||i)::uuid,'ACTIVE',now(),now() FROM generate_series(20051,20055) i");
      jdbc.update(
          "INSERT INTO profile.profile(user_id,handle,handle_normalized,display_name,visibility,created_at,updated_at) SELECT md5('feed-bench-user:'||i)::uuid,'bench_'||i,'bench_'||i,'Synthetic sparse author','AUTHENTICATED',now(),now() FROM generate_series(20051,20055) i");
      jdbc.update(
          "INSERT INTO graph.follow(follower_id,followed_id,created_at) VALUES (?, ?, now())",
          fixtureUser(20053),
          fixtureUser(20052));
      jdbc.update(
          "INSERT INTO content.post(id,author_id,body,audience,status,revision_number,published_at,created_at,updated_at) SELECT md5('feed-bench-rare:'||i)::uuid,?,'Synthetic rare old source','PUBLIC','PUBLISHED',1,timestamptz '2020-01-01Z'+i*interval '1 millisecond',now(),now() FROM generate_series(1,3) i",
          fixtureUser(20052));
      // Most followed authors have only private personal posts; one source is muted,
      // another blocked. These are still current owner-policy checks, not a cache.
      jdbc.update(
          "INSERT INTO graph.follow(follower_id,followed_id,created_at) SELECT ?,md5('feed-bench-user:'||(1+(i::bigint*7919)%20000))::uuid,now() FROM generate_series(9,1989,20) i",
          fixtureUser(20054));
      jdbc.update(
          "INSERT INTO graph.mute(muter_id,muted_id,created_at) VALUES (?,?,now())",
          fixtureUser(20054),
          fixtureUser(11272));
      jdbc.update(
          "DELETE FROM graph.follow WHERE follower_id=? AND followed_id=?",
          fixtureUser(20054),
          fixtureUser(9652));
      jdbc.update(
          "INSERT INTO graph.block(blocker_id,blocked_id,created_at) VALUES (?,?,now())",
          fixtureUser(20054),
          fixtureUser(9652));
      jdbc.execute("ANALYZE");
    }
    var empty = new ActorContext(fixtureUser(20051), UUID.randomUUID());
    var rare = new ActorContext(fixtureUser(20053), UUID.randomUUID());
    var rejected = new ActorContext(fixtureUser(20054), UUID.randomUUID());
    var first = feeds.home(rare, 2, null);
    assertThat(first.items()).hasSize(2);
    assertThat(first.nextCursor()).isNotNull();
    var last = feeds.home(rare, 2, first.nextCursor());
    assertThat(last.items()).hasSize(1);
    assertThat(last.nextCursor()).isNull();
    // The exhausted cursor lies ABOVE all million unrelated posts, not below them.
    // Exercise the real encrypted continuation, not a synthetic owner-API seek.
    var deep = new ActorContext(fixtureUser(20055), UUID.randomUUID());
    jdbc.update(
        "INSERT INTO content.post(id,author_id,body,audience,status,revision_number,published_at,created_at,updated_at) SELECT gen_random_uuid(),?,'Synthetic exhausted source','PUBLIC','PUBLISHED',1,timestamptz '2026-10-10 00:20:00Z'+i*interval '1 millisecond',now(),now() FROM generate_series(1,2) i",
        deep.userId());
    var deepFirst = feeds.home(deep, 1, null);
    assertThat(deepFirst.items()).hasSize(1);
    assertThat(deepFirst.nextCursor()).isNotNull();
    // A real owner mutation exhausts the previously valid continuation.
    for (var id :
        jdbc.queryForList(
            "SELECT id FROM content.post WHERE author_id=? AND status='PUBLISHED'",
            UUID.class,
            deep.userId())) commands.delete(deep, id, 0);
    for (String scenario :
        List.of("no_sources", "rare_old", "last_page", "mostly_rejected", "overlap", "exhausted")) {
      ActorContext actor =
          switch (scenario) {
            case "no_sources" -> empty;
            case "rare_old", "last_page" -> rare;
            case "exhausted" -> deep;
            case "mostly_rejected" -> rejected;
            default -> dense;
          };
      java.util.concurrent.Callable<Integer> query =
          () -> {
            if (scenario.equals("exhausted")) {
              var page = feeds.home(actor, 1, deepFirst.nextCursor());
              assertThat(page.items()).isEmpty();
              assertThat(page.nextCursor()).isNull();
              return 0;
            }
            var page =
                feeds.home(
                    actor,
                    scenario.equals("last_page") ? 2 : 20,
                    scenario.equals("last_page") ? first.nextCursor() : null);
            int expected =
                switch (scenario) {
                  case "no_sources", "mostly_rejected" -> 0;
                  case "rare_old" -> 3;
                  case "last_page" -> 1;
                  default -> 20;
                };
            assertThat(page.items()).hasSize(expected);
            assertThat(page.items().stream().map(i -> i.post().id()).distinct().count())
                .isEqualTo(expected);
            if (expected < 20) assertThat(page.nextCursor()).isNull();
            return expected;
          };
      var explainTx = new TransactionTemplate(manager);
      explainTx.setReadOnly(true);
      var plan =
          explainTx.execute(
              s -> {
                try {
                  query.call();
                } catch (Exception e) {
                  throw new RuntimeException(e);
                }
                var trace = candidateTrace;
                return jdbc.queryForObject(
                    "EXPLAIN (ANALYZE,BUFFERS,FORMAT JSON) " + trace.sql(),
                    String.class,
                    trace.bindings().toArray());
              });
      assertThat(indexRows(json.readTree(plan).get(0).path("Plan"), "post_chronology"))
          .as("Dense optional probe never traverses more than 1001 unrelated keys")
          .isLessThanOrEqualTo(1001);
      long work = physicalPostRows(json.readTree(plan).get(0).path("Plan"));
      if (List.of("no_sources", "rare_old", "last_page", "exhausted").contains(scenario))
        assertThat(work)
            .as("Source-selective physical post work on million-row history")
            .isLessThan(100);
      System.out.println(
          "FEED_BENCH sparse_plan pool="
              + poolSize
              + " scenario="
              + scenario
              + " physical_post_rows="
              + work
              + " "
              + plan);
      STATEMENTS.set(0);
      SQL_COUNTS.clear();
      resetProfile();
      try (var diagnostic = new FeedBenchmarkDiagnostics(hikari, observationConnection())) {
        int failuresBefore = loadFailures.size();
        var latencies = new ConcurrentLinkedQueue<Long>();
        var barrier = new CyclicBarrier(50);
        try (var executor = Executors.newFixedThreadPool(50)) {
          var futures = new ArrayList<Future<Boolean>>();
          for (int i = 0; i < 50; i++)
            futures.add(
                executor.submit(
                    () -> {
                      barrier.await(10, TimeUnit.SECONDS);
                      for (int r = 0; r < 2; r++) {
                        long start = System.nanoTime();
                        try {
                          query.call();
                          latencies.add(System.nanoTime() - start);
                        } catch (Exception failure) {
                          System.out.println(
                              "FEED_BENCH failure="
                                  + json.writeValueAsString(
                                      diagnostic.failure(
                                          poolSize, scenario, r + 1, start, failure)));
                          loadFailures.add(
                              "pool="
                                  + poolSize
                                  + " scenario="
                                  + scenario
                                  + " "
                                  + failure.getClass().getSimpleName());
                        }
                      }
                      return true;
                    }));
          for (var future : futures) assertThat(future.get(180, TimeUnit.SECONDS)).isTrue();
        }
        var times = latencies.stream().sorted().toList();
        System.out.printf(
            Locale.ROOT,
            "FEED_BENCH outcomes pool=%d scenario=%s attempts=100 successes=%d failures=%d error_rate=%.6f percentiles=success_only%n",
            poolSize,
            scenario,
            times.size(),
            loadFailures.size() - failuresBefore,
            (loadFailures.size() - failuresBefore) / 100.0);
        System.out.printf(
            Locale.ROOT,
            "FEED_BENCH sparse_results pool=%d scenario=%s requests=%d concurrent_readers=50 p50_ms=%.3f p95_ms=%.3f p99_ms=%.3f sql_per_attempt=%.3f%n",
            poolSize,
            scenario,
            times.size(),
            percentile(times, .5),
            percentile(times, .95),
            percentile(times, .99),
            (double) STATEMENTS.get() / 100);
        profile(scenario, 100);
        System.out.println(
            "FEED_BENCH diagnostics pool="
                + poolSize
                + " scenario="
                + scenario
                + " "
                + json.writeValueAsString(diagnostic.summary()));
      }
    }
  }

  static long physicalPostRows(tools.jackson.databind.JsonNode node) {
    return Math.round(postVisits(node));
  }

  private static double postVisits(tools.jackson.databind.JsonNode node) {
    double rows = 0;
    if (node.path("Relation Name").asString().equals("post"))
      rows +=
          (node.path("Actual Rows").asDouble(0) + node.path("Rows Removed by Filter").asDouble(0))
              * node.path("Actual Loops").asDouble(0);
    for (var child : node.path("Plans")) rows += postVisits(child);
    return rows;
  }

  static long indexRows(tools.jackson.databind.JsonNode node, String index) {
    long rows = 0;
    if (node.path("Index Name").asString().equals(index))
      rows +=
          (node.path("Actual Rows").asLong(0) + node.path("Rows Removed by Filter").asLong(0))
              * node.path("Actual Loops").asLong(0);
    for (var child : node.path("Plans")) rows += indexRows(child, index);
    return rows;
  }
}
