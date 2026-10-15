package com.flyvo.socialcore.notification;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

import com.flyvo.socialcore.account.api.*;
import com.flyvo.socialcore.content.api.*;
import com.flyvo.socialcore.identity.application.Authentication;
import com.flyvo.socialcore.identity.application.port.AccessTokens;
import com.flyvo.socialcore.identity.infrastructure.security.JwtAccessTokens;
import com.flyvo.socialcore.notification.api.*;
import com.flyvo.socialcore.notification.infrastructure.realtime.NotificationStreams;
import com.flyvo.socialcore.platform.api.*;
import com.flyvo.socialcore.platform.infrastructure.persistence.DurableWorkStore;
import com.flyvo.socialcore.profile.application.Profiles;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"app.notification.stream.max-global=3"})
@ActiveProfiles("test")
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class NotificationStreamsIT {
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

  static class TestClock extends Clock {
    volatile long offset;

    public Instant instant() {
      return Instant.now().plusSeconds(offset);
    }

    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    public Clock withZone(ZoneId z) {
      return this;
    }
  }

  @TestConfiguration
  static class Config {
    @Bean
    @Primary
    TestClock streamClock() {
      return new TestClock();
    }
  }

  @Autowired TestClock clock;
  @Autowired AccountCommands accounts;
  @Autowired Profiles profiles;
  @Autowired Authentication auth;
  @Autowired AccessTokens tokens;
  @Autowired Secrets secrets;
  @Autowired io.micrometer.core.instrument.MeterRegistry metrics;
  @Autowired JwtAccessTokens jwt;
  @Autowired NotificationStreams streams;
  @Autowired NotificationQueries queries;
  @Autowired ContentCommands posts;
  @Autowired Interactions interactions;
  @Autowired com.flyvo.socialcore.graph.api.GraphCommands graph;
  @Autowired DurableWorkStore worker;
  @Autowired JdbcTemplate jdbc;
  @Autowired javax.sql.DataSource dataSource;

  @Autowired
  org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext
      serverContext;

  @Autowired org.springframework.transaction.PlatformTransactionManager manager;
  @LocalServerPort int port;
  TransactionTemplate tx;
  private final List<InputStream> bodies = new ArrayList<>();

  @BeforeEach
  void setup() {
    clock.offset += 1200;
    tx = new TransactionTemplate(manager);
  }

  @AfterEach
  void cleanup() throws Exception {
    for (var b : bodies) b.close();
    bodies.clear();
    await()
        .atMost(Duration.ofSeconds(12))
        .untilAsserted(() -> assertThat(streams.active()).isZero());
    var tomcat =
        ((org.springframework.boot.tomcat.TomcatWebServer) serverContext.getWebServer())
            .getTomcat();
    var nativeContext =
        (org.apache.catalina.core.StandardContext) tomcat.getHost().findChildren()[0];
    await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(
            () ->
                assertThat(nativeContext.getInProgressAsyncCount())
                    .as("container async requests released")
                    .isZero());
    await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(
            () -> {
              for (var wrapper : nativeContext.findChildren())
                assertThat(((org.apache.catalina.core.StandardWrapper) wrapper).getCountAllocated())
                    .as("container servlet requests released")
                    .isZero();
            });
  }

  ActorContext user() {
    UUID id =
        tx.execute(
            s -> {
              var u = accounts.createVerified();
              profiles.provision(u);
              return u;
            });
    UUID sid = UUID.randomUUID();
    var t = clock.instant().atOffset(ZoneOffset.UTC);
    jdbc.update(
        "INSERT INTO identity.auth_session(id,user_id,created_at,last_seen_at,authenticated_at,idle_expires_at,absolute_expires_at,updated_at) VALUES (?,?,?,?,?,?,?,?)",
        sid,
        id,
        t,
        t,
        t,
        t.plusDays(7),
        t.plusDays(30),
        t);
    return new ActorContext(id, sid);
  }

  HttpRequest request(String path, String token) {
    var b =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .timeout(Duration.ofSeconds(10));
    if (token != null) b.header("Authorization", "Bearer " + token);
    return b.GET().build();
  }

  HttpResponse<InputStream> open(String token) throws Exception {
    var response =
        HttpClient.newHttpClient()
            .send(
                request("/api/v1/notifications/stream", token),
                HttpResponse.BodyHandlers.ofInputStream());
    bodies.add(response.body());
    return response;
  }

  String sign(ActorContext actor, Duration expiry, String issuer, String audience)
      throws Exception {
    var issued = com.nimbusds.jwt.SignedJWT.parse(tokens.issue(actor));
    var claims = issued.getJWTClaimsSet();
    var encoder = (JwtEncoder) ReflectionTestUtils.getField(jwt, "encoder");
    return encoder
        .encode(
            JwtEncoderParameters.from(
                JwsHeader.with(SignatureAlgorithm.RS256)
                    .keyId(issued.getHeader().getKeyID())
                    .build(),
                JwtClaimsSet.builder()
                    .issuer(issuer == null ? claims.getIssuer() : issuer)
                    .audience(audience == null ? claims.getAudience() : List.of(audience))
                    .subject(actor.userId().toString())
                    .claim("sid", actor.sessionId().toString())
                    .issuedAt(clock.instant().minusSeconds(60))
                    .expiresAt(clock.instant().plus(expiry))
                    .id(UUID.randomUUID().toString())
                    .build()))
        .getTokenValue();
  }

  String firstFrame(HttpResponse<InputStream> response) throws Exception {
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.headers().firstValue("content-type").orElse(""))
        .startsWith("text/event-stream");
    var reader =
        new BufferedReader(
            new InputStreamReader(response.body(), java.nio.charset.StandardCharsets.UTF_8));
    String line = reader.readLine();
    assertThat(line).contains("connected");
    return line;
  }

  long closedWithin(HttpResponse<InputStream> response, long started) throws Exception {
    try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
      var result = pool.submit(() -> response.body().readAllBytes());
      result.get(14, TimeUnit.SECONDS);
    }
    long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    assertThat(millis).isLessThan(15000);
    return millis;
  }

  @Test
  void realHttpRejectsMissingInvalidWrongSignatureIssuerAudienceAndActualExpiredJwt()
      throws Exception {
    var actor = user();
    String withinSkew = sign(actor, Duration.ofSeconds(-1), null, null);
    assertThat(tokens.validate(withinSkew)).isEqualTo(actor);
    for (String token :
        Arrays.asList(
            null,
            "invalid",
            sign(actor, Duration.ofMinutes(1), "https://wrong.example", null),
            sign(actor, Duration.ofMinutes(1), null, "wrong"),
            withinSkew)) {
      var response = open(token);
      assertThat(response.statusCode()).isEqualTo(401);
      String body =
          new String(response.body().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
      assertThat(body).doesNotContain("Bearer ", "eyJ");
    }
    String signed = tokens.issue(actor);
    var parts = signed.split("\\.");
    byte[] signature = Base64.getUrlDecoder().decode(parts[2]);
    signature[0] ^= 1;
    assertThat(
            open(parts[0]
                    + "."
                    + parts[1]
                    + "."
                    + Base64.getUrlEncoder().withoutPadding().encodeToString(signature))
                .statusCode())
        .isEqualTo(401);
    assertThat(streams.active()).isZero();
  }

  @Test
  void streamExpiryUsesVerifiedExpWithoutJwtClockSkewOrStoredConnection() throws Exception {
    var actor = user();
    var response = open(sign(actor, Duration.ofSeconds(3), null, null));
    firstFrame(response);
    long started = System.nanoTime();
    long elapsed = closedWithin(response, started);
    assertThat(elapsed).isLessThan(4500);
    await()
        .atMost(Duration.ofSeconds(3))
        .untilAsserted(
            () ->
                assertThat(
                        ((com.zaxxer.hikari.HikariDataSource) dataSource)
                            .getHikariPoolMXBean()
                            .getActiveConnections())
                    .isZero());
    System.out.println("SSE expiry elapsed-ms=" + elapsed + "; retained-connections=0");
  }

  @Test
  void logoutAllSessionRevocationAndSuspensionCloseRealStreamsWithinTarget() throws Exception {
    for (String mode : List.of("LOGOUT", "SESSION", "LOGOUT_ALL", "SUSPEND")) {
      var actor = user();
      var response = open(tokens.issue(actor));
      firstFrame(response);
      long started = System.nanoTime();
      switch (mode) {
        case "LOGOUT" -> {
          String secret = secrets.randomToken();
          var t = clock.instant().atOffset(ZoneOffset.UTC);
          jdbc.update(
              "INSERT INTO identity.refresh_token(id,session_id,generation,token_hash,issued_at,expires_at) VALUES (?,?,0,?,?,?)",
              UUID.randomUUID(),
              actor.sessionId(),
              secrets.hash(secret),
              t,
              t.plusDays(7));
          auth.logout(secret);
        }
        case "SESSION" -> auth.revokeSession(actor, actor.sessionId());
        case "LOGOUT_ALL" -> auth.logoutAll(actor);
        default -> {
          var admin = user();
          jdbc.update(
              "UPDATE account.user_account SET platform_role='ADMIN' WHERE id=?", admin.userId());
          accounts.suspend(admin, actor.userId(), "TEST_SUSPENSION");
        }
      }
      long elapsed = closedWithin(response, started);
      assertThat(streams.active()).isZero();
      System.out.println("SSE " + mode + " closure-ms=" + elapsed);
      clock.offset += 120;
    }
  }

  @Test
  void databaseOutageFailsClosedAndOrdinaryRestRecovers() throws Exception {
    var actor = user();
    String token = tokens.issue(actor);
    var response = open(token);
    firstFrame(response);
    long started = System.nanoTime();
    PG.getDockerClient().pauseContainerCmd(PG.getContainerId()).exec();
    try {
      long elapsed = closedWithin(response, started);
      assertThat(streams.active()).isZero();
      System.out.println("SSE database-outage closure-ms=" + elapsed);
    } finally {
      PG.getDockerClient().unpauseContainerCmd(PG.getContainerId()).exec();
    }
    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(
            () ->
                assertThat(
                        HttpClient.newHttpClient()
                            .send(
                                request("/api/v1/notifications", token),
                                HttpResponse.BodyHandlers.discarding())
                            .statusCode())
                    .isEqualTo(200));
  }

  @Test
  void userAndGlobalCapsDenyAndDisconnectReleasesReservations() throws Exception {
    var a = user();
    var b = user();
    var c = user();
    String token = tokens.issue(a);
    var first = open(token);
    firstFrame(first);
    var second = open(token);
    firstFrame(second);
    var userDenied = open(token);
    assertThat(userDenied.statusCode()).isEqualTo(429);
    assertThat(userDenied.headers().firstValue("Retry-After")).contains("5");
    var third = open(tokens.issue(b));
    firstFrame(third);
    assertThat(open(tokens.issue(c)).statusCode()).isEqualTo(429);
    first.body().close();
    second.body().close();
    third.body().close();
    await()
        .atMost(Duration.ofSeconds(12))
        .untilAsserted(() -> assertThat(streams.active()).isZero());
    var reopened = open(token);
    firstFrame(reopened);
    reopened.body().close();
  }

  @Test
  void realHttpStreamRateBudgetRemainsSeparateFromCaps() throws Exception {
    var actor = user();
    String token = tokens.issue(actor);
    firstFrame(open(token));
    firstFrame(open(token));
    for (int i = 0; i < 8; i++) {
      var denied = open(token);
      assertThat(denied.statusCode()).isEqualTo(429);
      assertThat(denied.headers().firstValue("Retry-After")).contains("5");
    }
    var rate = open(token);
    assertThat(rate.statusCode()).isEqualTo(429);
    assertThat(rate.headers().firstValue("Retry-After")).contains("60");
    assertThat(streams.active()).isEqualTo(2);
  }

  @RepeatedTest(3)
  void realSocketSlowReaderClosesWithBoundedNonblockingFrameQueue() throws Exception {
    var actor = user();
    String token = tokens.issue(actor);
    try (var socket = new Socket()) {
      socket.setReceiveBufferSize(1024);
      socket.setSoTimeout(10_000);
      socket.connect(new InetSocketAddress("localhost", port));
      socket
          .getOutputStream()
          .write(
              ("GET /api/v1/notifications/stream HTTP/1.1\r\nHost: localhost\r\nAuthorization: Bearer "
                      + token
                      + "\r\nConnection: close\r\n\r\n")
                  .getBytes(java.nio.charset.StandardCharsets.US_ASCII));
      socket.getOutputStream().flush();
      var input = socket.getInputStream();
      var header = new ByteArrayOutputStream();
      int matched = 0;
      while (matched < 4 && header.size() < 8192) {
        int b = input.read();
        assertThat(b).isNotEqualTo(-1);
        header.write(b);
        matched = b == "\r\n\r\n".charAt(matched) ? matched + 1 : (b == '\r' ? 1 : 0);
      }
      assertThat(header.toString(java.nio.charset.StandardCharsets.US_ASCII)).contains("200");
      var live = (Map<?, ?>) ReflectionTestUtils.getField(streams, "streams");
      assertThat(live).hasSize(1);
      Object stream = live.values().iterator().next();
      long started = System.nanoTime();
      String hint = "event: notifications.changed\ndata: {\"type\":\"notifications.changed\"}\n\n";
      // Saturate the real socket through the production nonblocking writer. This
      // test injection contains only hints and never invokes a source/auth bypass.
      int writes = 0;
      for (;
          writes < 100_000
              && streams.active() > 0
              && System.nanoTime() - started < TimeUnit.SECONDS.toNanos(5);
          writes++) ReflectionTestUtils.invokeMethod(stream, "enqueue", hint);
      await()
          .atMost(Duration.ofSeconds(5))
          .untilAsserted(() -> assertThat(streams.active()).isZero());
      assertThat(((Deque<?>) ReflectionTestUtils.getField(stream, "frames")).size())
          .isLessThanOrEqualTo(2);
      var counter =
          metrics.find("social.notification.sse.closed").tag("reason", "BACKPRESSURE").counter();
      assertThat(counter).isNotNull();
      assertThat(counter.count()).isGreaterThan(0);
      System.out.println(
          "SSE slow-reader hint-writes="
              + writes
              + "; queued-frames<=2; closure-ms="
              + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
    }
  }

  @Test
  void realInvalidationFrameContainsNoActorTargetBodyCountOrCredential() throws Exception {
    var owner = user();
    var actor = user();
    String token = tokens.issue(owner);
    var response = open(token);
    firstFrame(response);
    var p =
        posts.publish(
            owner,
            new ContentCommands.Publish(
                "Synthetic notification source text",
                null,
                null,
                new ContentCommands.Audience("PUBLIC", List.of()),
                List.of(),
                List.of()),
            null);
    var reaction = interactions.react(actor, p.id(), null, Interactions.ReactionType.LIKE);
    UUID event =
        jdbc.queryForObject(
            "SELECT id FROM platform.outbox_event WHERE aggregate_id=?", UUID.class, reaction.id());
    jdbc.update(
        "UPDATE platform.outbox_delivery SET available_at=? WHERE event_id=?",
        clock.instant().minusSeconds(50L * 365 * 86400).atOffset(ZoneOffset.UTC),
        event);
    worker.consumeOne(UUID.randomUUID());
    String frame;
    try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
      frame =
          pool.submit(
                  () -> {
                    var reader =
                        new BufferedReader(
                            new InputStreamReader(
                                response.body(), java.nio.charset.StandardCharsets.UTF_8));
                    var text = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                      text.append(line).append('\n');
                      if (line.isEmpty() && text.toString().contains("notifications.changed"))
                        return text.toString();
                    }
                    return text.toString();
                  })
              .get(12, TimeUnit.SECONDS);
    }
    assertThat(frame)
        .contains("notifications.changed")
        .doesNotContain(
            actor.userId().toString(),
            owner.userId().toString(),
            p.id().toString(),
            "actorCount",
            "unreadCount",
            "source text",
            "Bearer");
    assertThat(frame.contains(token)).isFalse();
    assertThat(queries.unread(owner)).isEqualTo(1);
  }

  @Test
  void currentMuteDisappearanceInvalidatesWithoutNotificationProjectionOrHiddenCount()
      throws Exception {
    var owner = user();
    var actor = user();
    var response = open(tokens.issue(owner));
    firstFrame(response);
    var p =
        posts.publish(
            owner,
            new ContentCommands.Publish(
                "Synthetic current-source fixture",
                null,
                null,
                new ContentCommands.Audience("PUBLIC", List.of()),
                List.of(),
                List.of()),
            null);
    var reaction = interactions.react(actor, p.id(), null, Interactions.ReactionType.LIKE);
    UUID event =
        jdbc.queryForObject(
            "SELECT id FROM platform.outbox_event WHERE aggregate_id=?", UUID.class, reaction.id());
    jdbc.update(
        "UPDATE platform.outbox_delivery SET available_at=? WHERE event_id=?",
        clock.instant().minusSeconds(50L * 365 * 86400).atOffset(ZoneOffset.UTC),
        event);
    worker.consumeOne(UUID.randomUUID());
    var reader =
        new BufferedReader(
            new InputStreamReader(response.body(), java.nio.charset.StandardCharsets.UTF_8));
    for (int phase = 0; phase < 2; phase++) {
      try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
        String frame =
            pool.submit(
                    () -> {
                      var text = new StringBuilder();
                      String line;
                      while ((line = reader.readLine()) != null) {
                        text.append(line).append('\n');
                        if (line.isEmpty() && text.toString().contains("notifications.changed"))
                          return text.toString();
                      }
                      return text.toString();
                    })
                .get(12, TimeUnit.SECONDS);
        assertThat(frame)
            .contains("notifications.changed")
            .doesNotContain("actorCount", "unreadCount", actor.userId().toString());
      }
      if (phase == 0) {
        assertThat(queries.unread(owner)).isEqualTo(1);
        graph.mute(owner, actor.userId(), true);
      }
    }
    assertThat(queries.unread(owner)).isZero();
  }

  @Test
  void disconnectedHintsContainNoDetailsAndRestRepairsLostTransport() throws Exception {
    var owner = user();
    var actor = user();
    var response = open(tokens.issue(owner));
    firstFrame(response);
    response.body().close();
    var p =
        posts.publish(
            owner,
            new ContentCommands.Publish(
                "Synthetic private-free notification fixture",
                null,
                null,
                new ContentCommands.Audience("PUBLIC", List.of()),
                List.of(),
                List.of()),
            null);
    var r = interactions.react(actor, p.id(), null, Interactions.ReactionType.LIKE);
    var event =
        jdbc.queryForObject(
            "SELECT id FROM platform.outbox_event WHERE aggregate_id=?", UUID.class, r.id());
    jdbc.update(
        "UPDATE platform.outbox_delivery SET available_at=? WHERE event_id=?",
        clock.instant().minusSeconds(50L * 365 * 86400).atOffset(ZoneOffset.UTC),
        event);
    worker.consumeOne(UUID.randomUUID());
    assertThat(queries.unread(owner)).isEqualTo(1);
    await()
        .atMost(Duration.ofSeconds(12))
        .untilAsserted(() -> assertThat(streams.active()).isZero());
    var repaired = open(tokens.issue(owner));
    firstFrame(repaired);
    String rest =
        HttpClient.newHttpClient()
            .send(
                request("/api/v1/notifications", tokens.issue(owner)),
                HttpResponse.BodyHandlers.ofString())
            .body();
    assertThat(rest).contains("REACTION_ON_CONTENT");
  }
}
