package com.flyvo.socialcore.search;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

import com.flyvo.socialcore.account.api.AccountCommands;
import com.flyvo.socialcore.content.api.*;
import com.flyvo.socialcore.platform.api.ActorContext;
import com.flyvo.socialcore.profile.application.Profiles;
import com.flyvo.socialcore.search.api.SearchQueries;
import com.flyvo.socialcore.search.application.SearchSources;
import java.lang.reflect.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest(properties = "app.worker.enabled=true")
@ActiveProfiles("test")
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class SearchMeasurementsIT {
  @Container
  static final PostgreSQLContainer PG =
      new PostgreSQLContainer(
          "postgres@sha256:77f585114c32fbca283dc835b0596f4e52b51b4c6662d7810b2f4084f60a1873");

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("app.database.url", PG::getJdbcUrl);
    r.add("app.database.username", PG::getUsername);
    r.add("app.database.password", PG::getPassword);
  }

  record Trace(String sql, List<Object> args) {}

  static final ThreadLocal<List<Trace>> CAPTURE = new ThreadLocal<>();

  static Object invoke(Object target, Method m, Object[] args) throws Throwable {
    try {
      return m.invoke(target, args);
    } catch (InvocationTargetException e) {
      throw e.getCause();
    }
  }

  static class ObservedDataSource extends DelegatingDataSource implements AutoCloseable {
    ObservedDataSource(DataSource source) {
      super(source);
    }

    public void close() throws Exception {
      if (getTargetDataSource() instanceof AutoCloseable a) a.close();
    }

    public Connection getConnection() throws SQLException {
      return trace(super.getConnection());
    }

    public Connection getConnection(String u, String p) throws SQLException {
      return trace(super.getConnection(u, p));
    }

    Connection trace(Connection c) {
      return (Connection)
          Proxy.newProxyInstance(
              Connection.class.getClassLoader(),
              new Class[] {Connection.class},
              (proxy, m, args) -> {
                var result = invoke(c, m, args);
                if (m.getName().equals("prepareStatement")
                    && result instanceof PreparedStatement s) {
                  String sql = (String) args[0];
                  var bound = new TreeMap<Integer, Object>();
                  return Proxy.newProxyInstance(
                      PreparedStatement.class.getClassLoader(),
                      new Class[] {PreparedStatement.class},
                      (p, method, a) -> {
                        if (method.getName().startsWith("set")
                            && a != null
                            && a.length >= 2
                            && a[0] instanceof Integer at)
                          bound.put(at, method.getName().equals("setNull") ? null : a[1]);
                        if (method.getName().startsWith("execute") && CAPTURE.get() != null)
                          CAPTURE.get().add(new Trace(sql, new ArrayList<>(bound.values())));
                        return invoke(s, method, a);
                      });
                }
                if (m.getName().equals("createStatement")
                    && result instanceof Statement statement) {
                  return Proxy.newProxyInstance(
                      Statement.class.getClassLoader(),
                      new Class[] {Statement.class},
                      (p, method, a) -> {
                        if (method.getName().startsWith("execute")
                            && a != null
                            && a.length > 0
                            && a[0] instanceof String sql
                            && CAPTURE.get() != null) CAPTURE.get().add(new Trace(sql, List.of()));
                        return invoke(statement, method, a);
                      });
                }
                return result;
              });
    }
  }

  @TestConfiguration
  static class Config {
    @Bean
    static BeanPostProcessor sqlCounter() {
      return new BeanPostProcessor() {
        public Object postProcessAfterInitialization(Object bean, String name) {
          return bean instanceof DataSource d && name.equals("dataSource")
              ? new ObservedDataSource(d)
              : bean;
        }
      };
    }
  }

  @Autowired AccountCommands accounts;
  @Autowired Profiles profiles;
  @Autowired ContentCommands posts;
  @Autowired SearchSources sources;
  @Autowired SearchQueries queries;
  @Autowired JdbcTemplate jdbc;
  @Autowired org.springframework.transaction.PlatformTransactionManager manager;
  @Autowired io.micrometer.core.instrument.MeterRegistry metrics;

  ActorContext user() {
    var tx = new TransactionTemplate(manager);
    UUID id =
        tx.execute(
            s -> {
              var u = accounts.createVerified();
              profiles.provision(u);
              return u;
            });
    return new ActorContext(id, UUID.randomUUID());
  }

  @Test
  void configuredSchedulerProjectsTenPostBurstWithinInitialThirtySecondTarget() {
    var owner = user();
    await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(
            () ->
                assertThat(
                        jdbc.queryForObject(
                            "SELECT count(*) FROM platform.outbox_delivery WHERE status<>'COMPLETED'",
                            Integer.class))
                    .isZero());
    var ids = new ArrayList<UUID>();
    long start = System.nanoTime();
    for (int i = 0; i < 10; i++)
      ids.add(
          posts
              .publish(
                  owner,
                  new ContentCommands.Publish(
                      "Synthetic lag fixture " + i,
                      null,
                      null,
                      new ContentCommands.Audience("PUBLIC", List.of()),
                      List.of(),
                      List.of()),
                  null)
              .id());
    long left =
        30_000 - java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
    assertThat(left).isPositive();
    await()
        .atMost(Duration.ofMillis(left))
        .untilAsserted(
            () ->
                assertThat(
                        jdbc.queryForObject(
                            "SELECT count(*) FROM search.document WHERE source_id IN ("
                                + String.join(",", Collections.nCopies(ids.size(), "?"))
                                + ") AND NOT tombstone",
                            Integer.class,
                            ids.toArray()))
                    .isEqualTo(10));
    long elapsed = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
    double max =
        metrics
            .get("social.search.projection.lag")
            .tag("kind", "POST")
            .timer()
            .max(java.util.concurrent.TimeUnit.SECONDS);
    assertThat(max).isLessThan(30);
    assertThat(elapsed).isLessThan(30_000);
    System.out.println(
        "Search scheduled lag workload=10 posts,1 owner,poll-ms=1000,notification+search deliveries; elapsed-ms="
            + elapsed
            + "; max-lag-seconds="
            + max);
  }

  @Test
  void skewedProjectionUsesActualBoundCandidatePlanAndConstantBatchSql() {
    var owner = user();
    var viewer = user();
    String q = "needle" + UUID.randomUUID().toString().replace("-", "");
    var ids = new ArrayList<UUID>();
    for (int i = 0; i < 50; i++) {
      UUID id = UUID.randomUUID();
      ids.add(id);
      jdbc.update(
          "INSERT INTO content.post(id,author_id,body,audience,status,revision_number,published_at,created_at,updated_at) VALUES (?,?,?,'PUBLIC','PUBLISHED',1,now(),now(),now())",
          id,
          owner.userId(),
          q);
    }
    new TransactionTemplate(manager)
        .executeWithoutResult(s -> sources.refresh(SearchQueries.Kind.POST, ids));
    jdbc.execute(
        "INSERT INTO search.document(source_kind,source_id,source_version,owner_id,account_version,tombstone,missing_source,source_key,title,body,key_normalized,name_normalized,updated_at) SELECT 'POST',md5('search-plan-'||g)::uuid,0,'"
            + owner.userId()
            + "'::uuid,0,false,false,'','','Common synthetic ballast','','',clock_timestamp() FROM generate_series(1,10000) g");
    jdbc.execute("ANALYZE search.document");
    List<Trace> traces = new ArrayList<>();
    SearchQueries.Page<SearchQueries.Post> page;
    CAPTURE.set(traces);
    try {
      page = queries.posts(viewer, q, null, 50, null);
    } finally {
      CAPTURE.remove();
    }
    assertThat(page.items()).hasSize(50);
    assertThat(traces.size()).isLessThanOrEqualTo(9);
    var candidate =
        traces.stream().filter(t -> t.sql().contains("ranked AS")).findFirst().orElseThrow();
    String plan =
        String.join(
            "\n",
            jdbc.queryForList(
                "EXPLAIN (ANALYZE,BUFFERS) " + candidate.sql(),
                String.class,
                candidate.args().toArray()));
    // Fresh GIN pending entries can make a small-table sequential scan cheaper.
    // Record that real plan too; normal maintenance must restore selective GIN
    // work without forcing planner switches, extra indexes or larger timeouts.
    jdbc.execute("VACUUM (ANALYZE) search.document");
    String maintained =
        String.join(
            "\n",
            jdbc.queryForList(
                "EXPLAIN (ANALYZE,BUFFERS) " + candidate.sql(),
                String.class,
                candidate.args().toArray()));
    assertThat(maintained).contains("document_fts");
    assertThat(
            traces.stream()
                .filter(t -> t.sql().contains("FROM content.post p WHERE p.id IN"))
                .count())
        .isEqualTo(1);
    System.out.println(
        "Search skew fixture=10000 ballast+50 eligible; page=50; SQL-count="
            + traces.size()
            + " (background worker/implicit transaction SQL excluded)");
    System.out.println("Search fresh candidate EXPLAIN:\n" + plan);
    System.out.println("Search maintained candidate EXPLAIN:\n" + maintained);
  }
}
