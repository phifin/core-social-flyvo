package com.flyvo.socialcore.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.flyvo.socialcore.identity.api.SessionQueries;
import com.flyvo.socialcore.identity.api.StreamAuthentication;
import com.flyvo.socialcore.notification.api.NotificationQueries;
import com.flyvo.socialcore.notification.application.port.InboxRepository;
import com.flyvo.socialcore.notification.infrastructure.realtime.NotificationStreams;
import com.flyvo.socialcore.platform.api.ActorContext;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.time.*;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

class NotificationStreamLifecycleTest {
  @Test
  void nativeBufferedFrameKeepsDeadlineAndAbortsBeforeCompletionOnlyOnce() throws Exception {
    var request = mock(HttpServletRequest.class);
    var response = mock(HttpServletResponse.class);
    var async = mock(AsyncContext.class);
    var output = mock(ServletOutputStream.class);
    var abort = mock(Runnable.class);
    when(request.startAsync()).thenReturn(async);
    when(request.getAttribute(anyString())).thenReturn(abort);
    when(response.getOutputStream()).thenReturn(output);
    var ready = new AtomicBoolean(true);
    when(output.isReady()).thenAnswer(i -> ready.get());
    doAnswer(
            i -> {
              ready.set(false);
              return null;
            })
        .when(output)
        .write(any(byte[].class));
    var now = Instant.parse("2026-10-15T00:00:00Z");
    var streams =
        new NotificationStreams(
            mock(SessionQueries.class),
            mock(NotificationQueries.class),
            mock(InboxRepository.class),
            Clock.fixed(now, ZoneOffset.UTC),
            new MockEnvironment(),
            new SimpleMeterRegistry());
    try {
      streams.start();
      streams.open(
          new StreamAuthentication.Proof(
              new ActorContext(UUID.randomUUID(), UUID.randomUUID()), now.plusSeconds(60)),
          request,
          response);
      Object stream =
          ((Map<?, ?>) ReflectionTestUtils.getField(streams, "streams")).values().iterator().next();
      assertThat((Deque<?>) ReflectionTestUtils.getField(stream, "frames")).isEmpty();
      assertThat((long) ReflectionTestUtils.getField(stream, "queuedAt")).isPositive();
      ReflectionTestUtils.setField(
          stream, "queuedAt", System.nanoTime() - TimeUnit.SECONDS.toNanos(4));
      ReflectionTestUtils.invokeMethod(streams, "tick");
      var order = inOrder(abort, async);
      order.verify(abort).run();
      order.verify(async).complete();
      assertThat(streams.active()).isZero();
      var listener = ArgumentCaptor.forClass(AsyncListener.class);
      verify(async).addListener(listener.capture());
      listener.getValue().onComplete(new AsyncEvent(async));
      listener.getValue().onError(new AsyncEvent(async));
      verify(abort).run();
    } finally {
      streams.stop();
    }
  }

  @Test
  void lateContainerErrorCompletesNativeCycleAfterLogicalDisconnectWithoutDoubleRelease()
      throws Exception {
    var request = mock(HttpServletRequest.class);
    var response = mock(HttpServletResponse.class);
    var async = mock(AsyncContext.class);
    var output = mock(ServletOutputStream.class);
    when(request.startAsync()).thenReturn(async);
    when(request.getAttribute(anyString())).thenReturn((Runnable) () -> {});
    when(response.getOutputStream()).thenReturn(output);
    when(output.isReady()).thenReturn(true);
    var now = Instant.parse("2026-10-15T00:00:00Z");
    var metrics = new SimpleMeterRegistry();
    var streams =
        new NotificationStreams(
            mock(SessionQueries.class),
            mock(NotificationQueries.class),
            mock(InboxRepository.class),
            Clock.fixed(now, ZoneOffset.UTC),
            new MockEnvironment(),
            metrics);
    try {
      streams.start();
      streams.open(
          new StreamAuthentication.Proof(
              new ActorContext(UUID.randomUUID(), UUID.randomUUID()), now.plusSeconds(60)),
          request,
          response);
      var writer = ArgumentCaptor.forClass(WriteListener.class);
      verify(output).setWriteListener(writer.capture());
      var listener = ArgumentCaptor.forClass(AsyncListener.class);
      verify(async).addListener(listener.capture());
      writer.getValue().onError(new IOException("SYNTHETIC_DISCONNECT"));
      assertThat(streams.active()).isZero();
      verify(async).complete();
      // Tomcat may enter its async error cycle after complete's final write fails.
      // Logical cleanup must not suppress the subsequent native completion.
      listener.getValue().onError(new AsyncEvent(async));
      verify(async, times(2)).complete();
      listener.getValue().onComplete(new AsyncEvent(async));
      verify(async, times(2)).complete();
      assertThat(metrics.counter("social.notification.sse.closed", "reason", "DISCONNECT").count())
          .isEqualTo(1);
    } finally {
      streams.stop();
      metrics.close();
    }
  }
}
