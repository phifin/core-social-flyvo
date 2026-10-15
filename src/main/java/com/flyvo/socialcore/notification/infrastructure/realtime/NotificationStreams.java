package com.flyvo.socialcore.notification.infrastructure.realtime;

import com.flyvo.socialcore.identity.api.SessionQueries;
import com.flyvo.socialcore.identity.api.StreamAuthentication;
import com.flyvo.socialcore.notification.api.NotificationQueries;
import com.flyvo.socialcore.notification.application.port.InboxRepository;
import com.flyvo.socialcore.platform.api.Failure;
import jakarta.annotation.PreDestroy;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Bounded single-instance invalidations. Servlet nonblocking output owns socket backpressure. */
@Component
public class NotificationStreams implements org.springframework.context.SmartLifecycle {
  private static final long HEARTBEAT_NANOS = TimeUnit.SECONDS.toNanos(5),
      CHECK_DEADLINE_NANOS = TimeUnit.SECONDS.toNanos(3);
  private final SessionQueries sessions;
  private final NotificationQueries inbox;
  private final InboxRepository store;
  private final Clock clock;
  private final int globalCap, userCap;
  private final ConcurrentMap<UUID, Stream> streams = new ConcurrentHashMap<>();
  private final ScheduledExecutorService timer;
  private final ThreadPoolExecutor checks;
  private final io.micrometer.core.instrument.MeterRegistry metrics;
  private volatile boolean running;

  public NotificationStreams(
      SessionQueries sessions,
      NotificationQueries inbox,
      InboxRepository store,
      Clock clock,
      Environment env,
      io.micrometer.core.instrument.MeterRegistry metrics) {
    this.sessions = sessions;
    this.inbox = inbox;
    this.store = store;
    this.clock = clock;
    this.metrics = metrics;
    globalCap = bound(env, "max-global", 100, 1, 1000);
    userCap = bound(env, "max-per-user", 2, 1, 5);
    timer =
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              var t = new Thread(r, "notification-stream-timer");
              t.setDaemon(true);
              return t;
            });
    checks =
        new ThreadPoolExecutor(
            4,
            4,
            0,
            TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(globalCap),
            r -> {
              var t = new Thread(r, "notification-stream-check");
              t.setDaemon(true);
              return t;
            },
            new ThreadPoolExecutor.AbortPolicy());
    io.micrometer.core.instrument.Gauge.builder(
            "social.notification.sse.active", streams, Map::size)
        .register(metrics);
    io.micrometer.core.instrument.Gauge.builder(
            "social.notification.sse.pending", checks, e -> e.getQueue().size())
        .register(metrics);
    timer.scheduleWithFixedDelay(this::tick, 100, 100, TimeUnit.MILLISECONDS);
  }

  private static int bound(Environment env, String name, int fallback, int min, int max) {
    int v = env.getProperty("app.notification.stream." + name, Integer.class, fallback);
    if (v < min || v > max) throw new IllegalStateException("Invalid notification stream limit");
    return v;
  }

  public synchronized void open(
      StreamAuthentication.Proof proof, HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    if (!running) throw new Failure(503, "STREAM_UNAVAILABLE");
    if (!(request.getAttribute(TomcatStreamAbort.ATTRIBUTE) instanceof Runnable abort))
      throw new Failure(503, "STREAM_UNAVAILABLE");
    if (!clock.instant().isBefore(proof.expiresAt())) throw Failure.unauthorized();
    sessions.requireCurrent(proof.actor());
    if (streams.size() >= globalCap
        || streams.values().stream()
                .filter(s -> s.proof.actor().userId().equals(proof.actor().userId()))
                .count()
            >= userCap) {
      response.setHeader("Retry-After", "5");
      throw new Failure(429, "STREAM_LIMIT");
    }
    if (!clock.instant().isBefore(proof.expiresAt())) throw Failure.unauthorized();
    response.setStatus(200);
    response.setContentType("text/event-stream");
    response.setCharacterEncoding("UTF-8");
    response.setHeader("Cache-Control", "no-store");
    response.setHeader("X-Accel-Buffering", "no");
    var async = request.startAsync();
    async.setTimeout(Math.max(1, Duration.between(clock.instant(), proof.expiresAt()).toMillis()));
    var stream = new Stream(UUID.randomUUID(), proof, async, response.getOutputStream(), abort);
    streams.put(stream.id, stream);
    try {
      async.addListener(
          new AsyncListener() {
            public void onComplete(AsyncEvent e) {
              stream.release("DISCONNECT");
            }

            public void onTimeout(AsyncEvent e) {
              stream.close("EXPIRY");
            }

            public void onError(AsyncEvent e) {
              stream.close("DISCONNECT");
            }

            public void onStartAsync(AsyncEvent e) {
              stream.close("DISCONNECT");
            }
          });
      stream.output.setWriteListener(
          new WriteListener() {
            public void onWritePossible() {
              stream.drain();
            }

            public void onError(Throwable t) {
              stream.close("DISCONNECT");
            }
          });
      stream.enqueue(": connected; refresh REST\n\n");
    } catch (RuntimeException e) {
      stream.close("DISCONNECT");
      throw e;
    }
  }

  private void tick() {
    if (!running) return;
    long now = System.nanoTime();
    for (var s : streams.values()) {
      if (!clock.instant().isBefore(s.proof.expiresAt())) {
        s.close("EXPIRY");
        continue;
      }
      if (s.pending && now - s.checkStarted >= CHECK_DEADLINE_NANOS) {
        s.close("AUTH_TIMEOUT");
        continue;
      }
      if (s.queuedAt != 0 && now - s.queuedAt >= CHECK_DEADLINE_NANOS) {
        s.close("BACKPRESSURE");
        continue;
      }
      if (!s.pending && now - s.lastCheck >= HEARTBEAT_NANOS) {
        s.checkStarted = now;
        s.pending = true;
        try {
          checks.execute(() -> check(s));
        } catch (RejectedExecutionException e) {
          s.close("CAPACITY");
        }
      }
    }
  }

  private void check(Stream s) {
    try {
      if (s.closed.get()) return;
      sessions.requireCurrent(s.proof.actor());
      long watermark = store.watermark(s.proof.actor().userId());
      boolean eligible = !inbox.inbox(s.proof.actor(), 1, null).items().isEmpty();
      // Source policy/actor changes need not write notification's watermark.
      // Periodic generic repair while eligible (and once on disappearance) keeps
      // connected clients fresh without exposing hidden changes or any counts.
      if ((watermark != s.watermark || eligible != s.hadEligible || eligible)
          && (eligible || s.hadEligible)) {
        s.enqueue(
            "event: notifications.changed\nid: "
                + UUID.randomUUID()
                + "\ndata: {\"type\":\"notifications.changed\"}\n\n");
      }
      s.watermark = watermark;
      s.hadEligible = eligible;
      s.enqueue(": heartbeat\n\n");
      s.lastCheck = System.nanoTime();
    } catch (RuntimeException e) {
      s.close("AUTH_DENIED");
    } finally {
      s.pending = false;
    }
  }

  private final class Stream {
    final UUID id;
    final StreamAuthentication.Proof proof;
    final AsyncContext async;
    final ServletOutputStream output;
    final Runnable abort;
    final ArrayDeque<byte[]> frames = new ArrayDeque<>(2);
    final AtomicBoolean closed = new AtomicBoolean();
    volatile boolean pending;
    boolean backpressured;
    volatile boolean hadEligible = true;
    volatile long checkStarted, queuedAt, lastCheck = System.nanoTime(), watermark = -1;

    Stream(
        UUID id,
        StreamAuthentication.Proof proof,
        AsyncContext async,
        ServletOutputStream output,
        Runnable abort) {
      this.id = id;
      this.proof = proof;
      this.async = async;
      this.output = output;
      this.abort = abort;
    }

    synchronized void enqueue(String frame) {
      if (closed.get()) return;
      if (frames.size() == 2) {
        close("BACKPRESSURE");
        return;
      }
      if (queuedAt == 0) queuedAt = System.nanoTime();
      frames.add(frame.getBytes(StandardCharsets.UTF_8));
      drain();
    }

    synchronized void drain() {
      try {
        while (!closed.get() && !frames.isEmpty()) {
          backpressured = !output.isReady();
          if (backpressured) break;
          if (!clock.instant().isBefore(proof.expiresAt())) {
            close("EXPIRY");
            return;
          }
          output.write(frames.removeFirst());
          if (output.isReady()) output.flush();
          backpressured = !output.isReady();
        }
        // A frame handed to the native nonblocking buffer is still unsent work.
        // Keep its deadline even when the application queue itself is empty.
        if (backpressured) backpressured = !output.isReady();
        if (frames.isEmpty() && !backpressured) queuedAt = 0;
      } catch (IOException | RuntimeException e) {
        close("DISCONNECT");
      }
    }

    synchronized boolean release(String reason) {
      if (!closed.compareAndSet(false, true)) return false;
      streams.remove(id, this);
      metrics.counter("social.notification.sse.closed", "reason", reason).increment();
      return true;
    }

    synchronized void close(String reason) {
      // A final write can fail after logical release and re-enter onError.
      // Complete that native error cycle too, otherwise the container may keep
      // an async request although the application reservation is already gone.
      try {
        if (release(reason) && (backpressured || "BACKPRESSURE".equals(reason))) {
          // Abort before complete: otherwise the container tries to flush bytes
          // beyond our deadline. Serialize terminal callbacks so this hook cannot
          // run after native completion/recycling. Never let a native failure
          // cancel the shared expiry/check timer.
          try {
            abort.run();
          } catch (RuntimeException e) {
            metrics.counter("social.notification.sse.abort.failures").increment();
          }
        }
        async.complete();
      } catch (IllegalStateException ignored) {
      }
    }
  }

  public int active() {
    return streams.size();
  }

  @PreDestroy
  public synchronized void stop() {
    running = false;
    for (var s : streams.values()) s.close("SHUTDOWN");
    timer.shutdownNow();
    checks.shutdownNow();
  }

  public void start() {
    running = true;
  }

  public boolean isRunning() {
    return running;
  }

  public int getPhase() {
    // Stop sockets before Tomcat waits for active async requests; @PreDestroy
    // alone runs after that drain and cannot terminate a live stream in time.
    return org.springframework.boot.web.server.context.WebServerApplicationContext
            .GRACEFUL_SHUTDOWN_PHASE
        + 1;
  }
}
