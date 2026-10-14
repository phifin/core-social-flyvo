package com.flyvo.socialcore.feed;

import static org.assertj.core.api.Assertions.*;

import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import org.junit.jupiter.api.Test;

class FeedBenchmarkDiagnosticsTest {
  @Test
  void exceptionDiagnosticsRetainCauseStateWithoutMessagesQueriesOrCredentials() {
    var root =
        new SQLTransientConnectionException(
            "private content and connection credentials", "08001", 42);
    root.setNextException(new SQLException("secret SQL bindings", "invalid-private-state", 7));
    var failure = new IllegalStateException("raw session proof must not print", root);
    var details = FeedBenchmarkDiagnostics.causes(failure);
    assertThat(details).hasSize(3);
    assertThat(details.get(1).sqlState()).isEqualTo("08001");
    assertThat(details.get(1).vendorCode()).isEqualTo(42);
    assertThat(details.get(2).sqlState()).isNull();
    var encoded =
        tools.jackson.databind.json.JsonMapper.builder()
            .build()
            .writeValueAsString(
                new FeedBenchmarkDiagnostics.Failure(
                    10,
                    "synthetic",
                    1,
                    5000,
                    details,
                    new FeedBenchmarkDiagnostics.Pool(10, 0, 40, 10, 10),
                    null,
                    null));
    assertThat(encoded)
        .doesNotContain(
            "private content", "credentials", "bindings", "session proof", "invalid-private-state");
  }

  @Test
  void cyclicAndLongCauseChainsRemainBounded() {
    var first = new IllegalStateException("private first");
    var next = new IllegalArgumentException("private next");
    first.initCause(next);
    next.initCause(first);
    assertThat(FeedBenchmarkDiagnostics.causes(first)).hasSize(2);
    Throwable chain = new SQLException("private leaf", "57014");
    for (int i = 0; i < 30; i++) chain = new IllegalStateException("private wrapper", chain);
    assertThat(FeedBenchmarkDiagnostics.causes(chain)).hasSize(16);
  }
}
