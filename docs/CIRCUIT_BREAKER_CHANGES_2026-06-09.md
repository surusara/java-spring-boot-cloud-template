# Circuit Breaker — Changes Log (2026-06-09)

> **Purpose:** Retrofit guide for the changes made today. Apply these to your production codebase.
> **Scope:** One code fix (lifecycle threading), timing/config corrections, and new alerting rules.
> **Stack:** Spring Boot 3.x, Kafka Streams 3.9.x, Resilience4j, Confluent Cloud.

---

## Summary

| # | Change | Type | File |
|---|--------|------|------|
| 1 | Offload `stop()`/`start()` to a dedicated executor (fix self-join deadlock) | **Code** | `circuit/KafkaStreamsLifecycleController.java` |
| 2 | Align `session-timeout-ms` / `max-poll-interval-ms` / `restart-delays` to the 30-min broker ceiling | **Config** | `application.yml` |
| 3 | Add Prometheus alert rules for breaker OPEN / stuck-open / flapping | **Infra** | `PrometheusRule` (new) |
| 4 | _(Optional)_ Convert `open_count` gauge → true Micrometer `Counter` | **Optional** | `metrics/CircuitBreakerMetrics.java` |

---

## Change 1 — Lifecycle controller: offload blocking close to an executor (CODE)

### Problem

Resilience4j fires its `onStateTransition(OPEN)` listener **synchronously on the StreamThread** that
crossed the failure threshold (the OPEN transition happens inside `circuitBreaker.onError(...)`, which
runs inside the processor's `record(...)` call).

The listener calls `lifecycleController.stopStream()` → `StreamsBuilderFactoryBean.stop()` →
`KafkaStreams.close()`. `close()` **blocks until all StreamThreads terminate — including the calling
one**. A thread cannot join itself, so the call blocks for the full close timeout and shuts down
**uncleanly**.

### Fix

Flip the breaker-intent flag **synchronously** (so `isStoppedByBreaker()` is instantly correct), but
run the **blocking** `stop()`/`start()` on a dedicated single-thread daemon executor so it never runs
on a StreamThread. Revert intent on failure (don't rethrow from the executor). Shut the executor down
in `@PreDestroy`.

### Before

```java
@Component
public class KafkaStreamsLifecycleController implements StreamLifecycleController {

    private static final Logger log = LoggerFactory.getLogger(KafkaStreamsLifecycleController.class);

    private final StreamsBuilderFactoryBean streamsBuilderFactoryBean;
    private final AtomicBoolean stoppedByBreaker = new AtomicBoolean(false);

    public KafkaStreamsLifecycleController(@Qualifier("&paymentsStreamsBuilder") StreamsBuilderFactoryBean streamsBuilderFactoryBean) {
        this.streamsBuilderFactoryBean = streamsBuilderFactoryBean;
    }

    @Override
    public synchronized void stopStream() {
        if (stoppedByBreaker.compareAndSet(false, true)) {
            try {
                log.warn("⏸️  Stopping Kafka Streams - Circuit breaker triggered");
                streamsBuilderFactoryBean.stop();      // ❌ runs on the StreamThread → self-join → blocks
                log.info("✓ Streams stopped successfully");
            } catch (Exception ex) {
                log.error("Error stopping stream", ex);
                stoppedByBreaker.set(false);
                throw new IllegalStateException("Failed to stop stream", ex);  // ❌ propagates onto StreamThread
            }
        } else {
            log.debug("Stream already stopped by breaker");
        }
    }

    @Override
    public synchronized void startStream() {
        if (stoppedByBreaker.compareAndSet(true, false)) {
            try {
                log.info("▶️  Starting Kafka Streams - Recovery in progress");
                streamsBuilderFactoryBean.start();
                log.info("✓ Streams started successfully");
            } catch (Exception ex) {
                log.error("Error starting stream", ex);
                stoppedByBreaker.set(true);
                throw new IllegalStateException("Failed to start stream", ex);
            }
        } else {
            log.debug("Stream already running");
        }
    }

    @Override
    public boolean isStoppedByBreaker() {
        return stoppedByBreaker.get();
    }
}
```

### After

```java
package com.example.financialstream.circuit;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.kafka.config.StreamsBuilderFactoryBean;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class KafkaStreamsLifecycleController implements StreamLifecycleController {

    private static final Logger log = LoggerFactory.getLogger(KafkaStreamsLifecycleController.class);

    private final StreamsBuilderFactoryBean streamsBuilderFactoryBean;
    private final AtomicBoolean stoppedByBreaker = new AtomicBoolean(false);

    /** Single-thread executor: serializes stop/start and keeps the blocking close() off any StreamThread. */
    private final ExecutorService lifecycleExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "cb-stream-lifecycle");
        thread.setDaemon(true);
        return thread;
    });

    public KafkaStreamsLifecycleController(@Qualifier("&paymentsStreamsBuilder") StreamsBuilderFactoryBean streamsBuilderFactoryBean) {
        this.streamsBuilderFactoryBean = streamsBuilderFactoryBean;
    }

    @Override
    public void stopStream() {
        // Flip intent synchronously so isStoppedByBreaker() is correct immediately,
        // but run the blocking close() off the StreamThread to avoid a self-join deadlock.
        if (stoppedByBreaker.compareAndSet(false, true)) {
            lifecycleExecutor.submit(() -> {
                try {
                    log.warn("⏸️  Stopping Kafka Streams - circuit breaker OPEN (consumer offline, no fetching, no buffering)");
                    streamsBuilderFactoryBean.stop();
                    log.info("✓ Streams stopped successfully");
                } catch (Exception ex) {
                    log.error("Error stopping stream — reverting breaker-stop state so it can be retried", ex);
                    stoppedByBreaker.set(false);
                }
            });
        } else {
            log.debug("Stream already stopped by breaker — ignoring duplicate stop request");
        }
    }

    @Override
    public void startStream() {
        if (stoppedByBreaker.compareAndSet(true, false)) {
            lifecycleExecutor.submit(() -> {
                try {
                    log.info("▶️  Starting Kafka Streams - recovery in progress");
                    streamsBuilderFactoryBean.start();
                    log.info("✓ Streams started successfully");
                } catch (Exception ex) {
                    log.error("Error starting stream — reverting state so recovery can retry", ex);
                    stoppedByBreaker.set(true);
                }
            });
        } else {
            log.debug("Stream already running — ignoring duplicate start request");
        }
    }

    @Override
    public boolean isStoppedByBreaker() {
        return stoppedByBreaker.get();
    }

    @PreDestroy
    void shutdown() {
        lifecycleExecutor.shutdown();
        try {
            if (!lifecycleExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
                lifecycleExecutor.shutdownNow();
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            lifecycleExecutor.shutdownNow();
        }
    }
}
```

### Retrofit checklist

- [ ] Replace the body of your lifecycle controller with the **After** version (keep your own package/qualifier names).
- [ ] Confirm `stopStream()` / `startStream()` are **no longer `synchronized`** (the executor serializes them).
- [ ] Confirm they **no longer throw** — failures revert the flag instead, so the recovery scheduler can retry.
- [ ] Confirm `@PreDestroy shutdown()` is present so the executor drains on pod stop.
- [ ] No change needed in `BusinessOutcomeCircuitBreaker` or `CircuitBreakerRecoveryScheduler` — they call the same interface.

---

## Change 2 — Timing alignment to the 30-min broker ceiling (CONFIG)

### Why

While the stream is **stopped**, the consumer sends **no heartbeats**. With
`internal.leave.group.on.close=false`, the coordinator reserves this member's partitions only for
`session.timeout.ms` after the last heartbeat. Exceed it → member evicted → **rebalance** on both stop
and resume.

`session.timeout.ms` cannot exceed the broker's `group.max.session.timeout.ms` (**default 30 min**,
**fixed on Confluent Cloud**). Therefore:

| Bound | Value |
|---|---|
| Max single stop without rebalance | `< session.timeout.ms` |
| `session.timeout.ms` ceiling | **30 min** (Confluent Cloud, fixed) |
| **Absolute max single stop** | **~30 min** |

Every `restart-delay` **must** be under `session.timeout.ms`. The previous example mixed a 5-min
session timeout with 20-min delays — that would rebalance.

### Config change

```yaml
app:
  stream:
    consumer:
      # max-poll-interval-ms MUST be >= session-timeout-ms (guards first batch after resume).
      max-poll-interval-ms: 1800000      # was 300000  → now 30 min
      # session-timeout-ms = how long partitions stay reserved while STOPPED. Broker ceiling = 30 min.
      session-timeout-ms: 1800000        # was 300000  → now 30 min (broker ceiling)

  circuit-breaker:
    # Each delay MUST be < session-timeout-ms (30 min). Last delay repeats for subsequent opens,
    # so the breaker loops in ~25-min cycles during a long outage.
    restart-delays: 1m, 5m, 25m          # was 1m, 10m, 20m
```

### Retrofit checklist

- [ ] Set `session-timeout-ms` and `max-poll-interval-ms` to your chosen value, **≤ 30 min**.
- [ ] Ensure **every** `restart-delay` is **strictly less** than `session-timeout-ms`.
- [ ] If you need a margin for the resume heartbeat, cap the largest delay a few minutes under the timeout (e.g. 25m delay with 30m timeout).
- [ ] Keep `heartbeat-interval-ms < session-timeout-ms / 3`.

---

## Change 3 — Alerting on breaker OPEN (INFRA)

### Why

The loop-and-alert strategy for long outages only works if OPEN actually pages someone. The app
**already emits** the signals (no code change needed):

| Signal | Source |
|---|---|
| ERROR log `🔴 BREAKER OPEN (attempt #n)` | `BusinessOutcomeCircuitBreaker` state listener |
| Gauge `circuit_breaker_current_state` (0=CLOSED, 1=OPEN, 2=HALF_OPEN) | `CircuitBreakerMetrics` |
| Gauge `circuit_breaker_next_restart_delay_seconds` | `CircuitBreakerMetrics` |
| Gauge `circuit_breaker_open_count_total` | `CircuitBreakerMetrics` |

The missing piece is the **alert rule** that turns those signals into a page.

### New PrometheusRule

```yaml
apiVersion: monitoring.coreos.com/v1
kind: PrometheusRule
metadata:
  name: circuit-breaker-alerts
  labels:
    release: prometheus
spec:
  groups:
    - name: circuit-breaker
      rules:
        - alert: CircuitBreakerOpen
          expr: circuit_breaker_current_state{breaker="payments-business-soft-failure"} == 1
          for: 1m
          labels: { severity: warning }
          annotations:
            summary: "Circuit breaker OPEN on {{ $labels.pod }}"
            description: "Soft-failure rate tripped the breaker. Stream is stopped and looping recovery."

        - alert: CircuitBreakerStuckOpen
          expr: circuit_breaker_current_state{breaker="payments-business-soft-failure"} == 1
          for: 35m
          labels: { severity: critical }
          annotations:
            summary: "Circuit breaker STUCK OPEN > 35m on {{ $labels.pod }}"
            description: "Looped past a full max cycle. Treat as multi-hour outage: page ops, consider KEDA scale-to-zero."

        - alert: CircuitBreakerFlapping
          expr: changes(circuit_breaker_open_count_total{breaker="payments-business-soft-failure"}[1h]) > 3
          for: 5m
          labels: { severity: warning }
          annotations:
            summary: "Circuit breaker flapping on {{ $labels.pod }}"
            description: "Breaker opened more than 3 times in the last hour."
```

### Retrofit checklist

- [ ] Update the `breaker="..."` label to your breaker name if different.
- [ ] Confirm `CircuitBreakerMetrics` is a registered `@Component` and metrics appear at `/actuator/prometheus`.
- [ ] Deploy the `PrometheusRule` to your monitoring namespace.
- [ ] Route `CircuitBreakerStuckOpen` (critical) to the on-call pager — this is the human/KEDA decision trigger for multi-hour outages.

---

## Optional Change — `open_count` gauge → true Counter (INFRA/CODE)

> **Status:** Optional. Apply only if you want breaker-open counts to be reliable across pod restarts.

### Why

`circuit_breaker_open_count_total` is currently registered as a **gauge** backed by an in-memory
`AtomicInteger`. Two consequences:

- It **resets to 0 on every pod restart** (the `AtomicInteger` is re-created).
- A `_total` suffix on a gauge is misleading — Prometheus convention reserves `_total` for monotonic
  counters.

The `CircuitBreakerFlapping` alert uses `changes(...)[1h]`, which tolerates resets, so this is **not a
correctness bug for alerting**. It only matters if you want accurate cumulative counts on dashboards
or rate-based queries (`rate(...)`, `increase(...)`) that assume monotonic counters.

### Fix — register a Micrometer `Counter` instead of a gauge

**Before** (in `CircuitBreakerMetrics`):

```java
private final AtomicInteger openCount = new AtomicInteger(0);

// ...in registerMetrics():
// Total number of times breaker opened (cumulative)
meterRegistry.gauge(
    "circuit_breaker_open_count_total",
    Tags.of("breaker", "payments-business-soft-failure"),
    openCount,
    AtomicInteger::get
);

// ...
public void recordOpenEvent() {
    openCount.incrementAndGet();
}
```

**After:**

```java
import io.micrometer.core.instrument.Counter;

private final Counter openCounter;

// in the constructor, after meterRegistry is assigned:
this.openCounter = Counter.builder("circuit_breaker_open_count")   // Micrometer appends _total
        .tag("breaker", "payments-business-soft-failure")
        .description("Cumulative number of times the breaker opened")
        .register(meterRegistry);

// remove the AtomicInteger field and its gauge registration above.

public void recordOpenEvent() {
    openCounter.increment();
}
```

> **Note:** Micrometer's Prometheus registry automatically appends `_total` to counter names, so
> `circuit_breaker_open_count` is exported as `circuit_breaker_open_count_total` — your existing alert
> expression keeps working unchanged. The count still resets on restart (counters are per-process), but
> it is now a proper monotonic counter within a pod's lifetime, so `rate()` / `increase()` behave
> correctly.

### Retrofit checklist

- [ ] Replace the `AtomicInteger` gauge with a `Counter` as shown.
- [ ] Confirm the exported metric name stays `circuit_breaker_open_count_total` at `/actuator/prometheus`.
- [ ] No alert-rule change needed — the `CircuitBreakerFlapping` `changes(...)` expression still works.
- [ ] Optional: switch dashboards from raw value to `increase(circuit_breaker_open_count_total[1h])`.

---

## Long-outage decision (2–3 hours) — operating model

| Outage length | Mechanism |
|---|---|
| Seconds–minutes | Breaker backoff ladder (`1m, 5m, 25m`), auto-recovers |
| Tens of minutes | Loop in sub-30-min cycles; `CircuitBreakerOpen` alert fires each cycle |
| **Hours** | **Do not hold in app.** `CircuitBreakerStuckOpen` pages ops → stop the pod / KEDA scale-to-zero until the dependency recovers |

> A single stop cannot exceed ~30 min on Confluent Cloud (broker ceiling), so multi-hour holds are
> impossible in-process anyway. The loop is a **bridge**, not a substitute for the alert.

---

## Files touched today

| File | Change |
|------|--------|
| `src/main/java/com/example/financialstream/circuit/KafkaStreamsLifecycleController.java` | Executor offload + revert-on-failure + `@PreDestroy` (Change 1) |
| `docs/CIRCUIT_BREAKER_ADOPTION_GUIDE.md` | Stop-vs-pause rationale corrected; max-stop-duration section; alert rules; example YAML timing fixed |

> No changes were required in `BusinessOutcomeCircuitBreaker`, `CircuitBreakerRecoveryScheduler`,
> `BreakerControlProperties`, or `PaymentsRecordProcessorSupplier`.
>
> `CircuitBreakerMetrics` is touched **only if** you apply the optional gauge → counter change (Change 4).
