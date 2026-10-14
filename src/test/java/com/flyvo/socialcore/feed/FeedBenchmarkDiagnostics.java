package com.flyvo.socialcore.feed;

import com.zaxxer.hikari.HikariDataSource;
import java.lang.management.ManagementFactory;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/** Test-only, bounded telemetry. Never retains messages, SQL text/bindings or actor IDs. */
final class FeedBenchmarkDiagnostics implements AutoCloseable {
  record Cause(String type, String sqlState, Integer vendorCode) {}

  record Pool(int active, int idle, int pending, int total, int maximum) {}

  record Counters(
      long acquired,
      long acquisitionFailures,
      double meanAcquireMs,
      double meanFailedAcquireMs,
      double maxAcquireMs,
      long returned,
      double meanHeldMs,
      double meanOutsideExecuteMs,
      double maxHeldMs,
      double oldestHeldMs) {}

  record Resource(
      double cpuLoad,
      double processCpuLoad,
      long heapBytes,
      long freeMemoryBytes,
      long gcCollections,
      long gcMillis) {}

  record Sample(
      Pool pool,
      Resource resource,
      Map<String, Long> dbWaits,
      double oldestDbTransactionMs,
      List<Cause> diagnosticFailure) {}

  record Failure(
      int pool,
      String scenario,
      int attempt,
      double failedElapsedMs,
      List<Cause> causes,
      Pool atFailure,
      Counters connection,
      Sample recentSample) {}

  record Summary(
      long samples,
      long unavailableDbSamples,
      int peakPending,
      int peakActive,
      double peakCpuLoad,
      long peakHeapBytes,
      Map<String, Long> peakDbWaits,
      Counters connection) {}

  static final AtomicLong acquired = new AtomicLong(),
      acquisitionFailures = new AtomicLong(),
      acquireNs = new AtomicLong(),
      failedAcquireNs = new AtomicLong(),
      maxAcquireNs = new AtomicLong(),
      returned = new AtomicLong(),
      heldNs = new AtomicLong(),
      outsideNs = new AtomicLong(),
      maxHeldNs = new AtomicLong(),
      nextLease = new AtomicLong();
  private static final ConcurrentHashMap<Long, Lease> leases = new ConcurrentHashMap<>();
  private static final ThreadLocal<Lease> localLease = new ThreadLocal<>();

  private static final class Lease {
    final long id = nextLease.incrementAndGet(), start = System.nanoTime();
    long executedNs;
  }

  static long borrowed(long elapsedNs) {
    acquired.incrementAndGet();
    acquireNs.addAndGet(elapsedNs);
    maxAcquireNs.accumulateAndGet(elapsedNs, Math::max);
    var lease = new Lease();
    leases.put(lease.id, lease);
    localLease.set(lease);
    return lease.id;
  }

  static void failedAcquire(long elapsedNs) {
    acquisitionFailures.incrementAndGet();
    failedAcquireNs.addAndGet(elapsedNs);
    maxAcquireNs.accumulateAndGet(elapsedNs, Math::max);
  }

  static void executed(long elapsedNs) {
    var lease = localLease.get();
    if (lease != null) lease.executedNs += elapsedNs;
  }

  static void released(long id) {
    var lease = leases.remove(id);
    if (lease == null) return;
    long elapsed = System.nanoTime() - lease.start;
    returned.incrementAndGet();
    heldNs.addAndGet(elapsed);
    outsideNs.addAndGet(Math.max(0, elapsed - lease.executedNs));
    maxHeldNs.accumulateAndGet(elapsed, Math::max);
    if (localLease.get() == lease) localLease.remove();
  }

  static void reset() {
    if (!leases.isEmpty()) throw new IllegalStateException("DIAGNOSTIC_LEASE_STILL_OPEN");
    for (var value :
        List.of(
            acquired,
            acquisitionFailures,
            acquireNs,
            failedAcquireNs,
            maxAcquireNs,
            returned,
            heldNs,
            outsideNs,
            maxHeldNs)) value.set(0);
  }

  static Counters counters() {
    long oldest = 0, now = System.nanoTime();
    for (var lease : leases.values()) oldest = Math.max(oldest, now - lease.start);
    return new Counters(
        acquired.get(),
        acquisitionFailures.get(),
        acquireNs.get() / 1e6 / Math.max(1, acquired.get()),
        failedAcquireNs.get() / 1e6 / Math.max(1, acquisitionFailures.get()),
        maxAcquireNs.get() / 1e6,
        returned.get(),
        heldNs.get() / 1e6 / Math.max(1, returned.get()),
        outsideNs.get() / 1e6 / Math.max(1, returned.get()),
        maxHeldNs.get() / 1e6,
        oldest / 1e6);
  }

  static List<Cause> causes(Throwable failure) {
    var result = new ArrayList<Cause>();
    var seen = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
    var queue = new ArrayDeque<Throwable>();
    queue.add(failure);
    while (!queue.isEmpty() && result.size() < 16) {
      var current = queue.removeFirst();
      if (!seen.add(current)) continue;
      String state = null;
      Integer code = null;
      if (current instanceof SQLException sql) {
        if (sql.getSQLState() != null && sql.getSQLState().matches("[0-9A-Z]{5}"))
          state = sql.getSQLState();
        code = sql.getErrorCode();
        if (sql.getNextException() != null) queue.add(sql.getNextException());
      }
      result.add(new Cause(current.getClass().getName(), state, code));
      if (current.getCause() != null) queue.add(current.getCause());
    }
    return List.copyOf(result);
  }

  static Pool pool(HikariDataSource source) {
    var bean = source.getHikariPoolMXBean();
    return new Pool(
        bean.getActiveConnections(),
        bean.getIdleConnections(),
        bean.getThreadsAwaitingConnection(),
        bean.getTotalConnections(),
        source.getMaximumPoolSize());
  }

  private final HikariDataSource source;
  private final Connection observer;
  private final ScheduledExecutorService executor;
  private volatile Sample recent;
  private long samples, unavailableDbSamples;
  private int peakPending, peakActive;
  private double peakCpuLoad;
  private long peakHeapBytes;
  private final Map<String, Long> peakDbWaits = new TreeMap<>();

  FeedBenchmarkDiagnostics(HikariDataSource source, Connection observer) throws SQLException {
    this.source = source;
    this.observer = observer;
    try (var st = observer.createStatement()) {
      st.execute("SET statement_timeout='250ms'");
    }
    executor =
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              var thread = new Thread(r, "feed-benchmark-observer");
              thread.setDaemon(true);
              return thread;
            });
    sample();
    executor.scheduleWithFixedDelay(this::sample, 200, 200, TimeUnit.MILLISECONDS);
  }

  private Resource resources() {
    var os = ManagementFactory.getOperatingSystemMXBean();
    double cpu = -1, process = -1;
    long free = -1;
    if (os instanceof com.sun.management.OperatingSystemMXBean extended) {
      cpu = extended.getCpuLoad();
      process = extended.getProcessCpuLoad();
      free = extended.getFreeMemorySize();
    }
    long collections = 0, gcMillis = 0;
    for (var bean : ManagementFactory.getGarbageCollectorMXBeans()) {
      collections += Math.max(0, bean.getCollectionCount());
      gcMillis += Math.max(0, bean.getCollectionTime());
    }
    return new Resource(
        cpu,
        process,
        ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed(),
        free,
        collections,
        gcMillis);
  }

  private void sample() {
    var waits = new TreeMap<String, Long>();
    var errors = List.<Cause>of();
    double oldest = 0;
    try (var st = observer.createStatement()) {
      st.setQueryTimeout(1);
      try (var rows =
          st.executeQuery(
              """
          SELECT state,COALESCE(wait_event_type,'CPU_OR_RUNNABLE') kind,
            COALESCE(wait_event,'NONE') event,count(*) n,
            COALESCE(max(extract(epoch FROM clock_timestamp()-xact_start))*1000,0) oldest
          FROM pg_stat_activity WHERE datname=current_database() AND backend_type='client backend'
            AND pid<>pg_backend_pid() GROUP BY state,wait_event_type,wait_event
          """)) {
        while (rows.next()) {
          waits.put(
              rows.getString("state")
                  + ":"
                  + rows.getString("kind")
                  + ":"
                  + rows.getString("event"),
              rows.getLong("n"));
          oldest = Math.max(oldest, rows.getDouble("oldest"));
        }
      }
    } catch (SQLException failure) {
      errors = causes(failure);
    }
    var next = new Sample(pool(source), resources(), Map.copyOf(waits), oldest, errors);
    recent = next;
    synchronized (this) {
      samples++;
      if (!errors.isEmpty()) unavailableDbSamples++;
      peakPending = Math.max(peakPending, next.pool().pending());
      peakActive = Math.max(peakActive, next.pool().active());
      peakCpuLoad = Math.max(peakCpuLoad, next.resource().cpuLoad());
      peakHeapBytes = Math.max(peakHeapBytes, next.resource().heapBytes());
      waits.forEach((key, count) -> peakDbWaits.merge(key, count, Math::max));
    }
  }

  Failure failure(int poolSize, String scenario, int attempt, long started, Throwable failure) {
    return new Failure(
        poolSize,
        scenario,
        attempt,
        (System.nanoTime() - started) / 1e6,
        causes(failure),
        pool(source),
        counters(),
        recent);
  }

  synchronized Summary summary() {
    return new Summary(
        samples,
        unavailableDbSamples,
        peakPending,
        peakActive,
        peakCpuLoad,
        peakHeapBytes,
        Map.copyOf(peakDbWaits),
        counters());
  }

  public void close() throws Exception {
    executor.shutdownNow();
    if (!executor.awaitTermination(3, TimeUnit.SECONDS))
      throw new IllegalStateException("DIAGNOSTIC_OBSERVER_NOT_STOPPED");
    observer.close();
  }
}
