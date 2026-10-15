package com.flyvo.socialcore.notification;

import static org.assertj.core.api.Assertions.assertThat;

import com.flyvo.socialcore.SocialCoreApplication;
import com.flyvo.socialcore.account.api.AccountCommands;
import com.flyvo.socialcore.identity.application.port.AccessTokens;
import com.flyvo.socialcore.notification.infrastructure.realtime.NotificationStreams;
import com.flyvo.socialcore.platform.api.ActorContext;
import com.flyvo.socialcore.profile.application.Profiles;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
class NotificationStreamShutdownIT {
  @Container
  static final PostgreSQLContainer PG =
      new PostgreSQLContainer(
          "postgres@sha256:77f585114c32fbca283dc835b0596f4e52b51b4c6662d7810b2f4084f60a1873");

  @Test
  void shutdownCompletesOpenStreamBeforeTomcatGracefulDrain() throws Exception {
    // Own this context explicitly: closing a Spring TestContext-managed context
    // inside a test conflicts with its automatic lifecycle restart callbacks.
    try (var client = HttpClient.newHttpClient();
        var context =
            new SpringApplicationBuilder(SocialCoreApplication.class)
                .profiles("test")
                .run(
                    "--server.port=0",
                    "--app.database.url=" + PG.getJdbcUrl(),
                    "--app.database.username=" + PG.getUsername(),
                    "--app.database.password=" + PG.getPassword())) {
      var server = (ServletWebServerApplicationContext) context;
      var tx = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
      UUID user =
          tx.execute(
              s -> {
                UUID id = context.getBean(AccountCommands.class).createVerified();
                context.getBean(Profiles.class).provision(id);
                return id;
              });
      UUID session = UUID.randomUUID();
      var now = Instant.now().atOffset(ZoneOffset.UTC);
      context
          .getBean(JdbcTemplate.class)
          .update(
              "INSERT INTO identity.auth_session(id,user_id,created_at,last_seen_at,authenticated_at,idle_expires_at,absolute_expires_at,updated_at) VALUES (?,?,?,?,?,?,?,?)",
              session,
              user,
              now,
              now,
              now,
              now.plusDays(7),
              now.plusDays(30),
              now);
      String token = context.getBean(AccessTokens.class).issue(new ActorContext(user, session));
      var streams = context.getBean(NotificationStreams.class);
      var response =
          client.send(
              HttpRequest.newBuilder(
                      URI.create(
                          "http://localhost:"
                              + server.getWebServer().getPort()
                              + "/api/v1/notifications/stream"))
                  .timeout(Duration.ofSeconds(10))
                  .header("Authorization", "Bearer " + token)
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofInputStream());
      assertThat(response.statusCode()).isEqualTo(200);
      assertThat(streams.active()).isEqualTo(1);
      long started = System.nanoTime();
      try (var body = response.body();
          var pool = Executors.newSingleThreadExecutor()) {
        var shutdown = pool.submit(context::close);
        try {
          shutdown.get(5, TimeUnit.SECONDS);
        } finally {
          body.close();
        }
      }
      assertThat(streams.active()).isZero();
      long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
      assertThat(elapsed).isLessThan(5000);
      System.out.println("SSE graceful-shutdown with open stream elapsed-ms=" + elapsed);
    }
  }
}
