<<<<<<< HEAD
# Circuit Breaker Template — Adoption Guide

> **Purpose:** Step-by-step guide for adopting the Kafka Streams circuit breaker pattern into any project.  
> **Target projects:** Any Kafka Streams consumer.  
> **Stack:** Spring Boot 3.x, Kafka Streams 3.9.x, Confluent Cloud (CSFLE), PostgreSQL, Resilience4j.

---

## How This Template Works — 60-Second Overview

```
                    ┌─────────────────────────────────────────────────────┐
                    │                YOUR APPLICATION                     │
                    │                                                     │
  payments.input    │  ┌──────────────────────────────────────────────┐  │
  ──────────────────┼─▶│ ProcessorSupplier (Circuit Breaker Gate)     │  │
                    │  │                                              │  │
                    │  │  1. breaker.tryAcquirePermission()           │  │
                    │  │     ├─ OPEN? → throw → stream dies → pod ok │  │
                    │  │     └─ CLOSED/HALF_OPEN? → proceed ↓        │  │
                    │  │                                              │  │
                    │  │  2. businessProcessorService.process()       │  │
                    │  │     ├─ ✅ SUCCESS → output topic / DB save   │  │
                    │  │     ├─ ⚠️ SOFT FAILURE → log + count        │  │
                    │  │     └─ ❌ FATAL → throw → pod restart       │  │
                    │  │                                              │  │
                    │  │  3. breaker.record(result)                   │  │
                    │  │     └─ If soft failures > 20% → OPEN        │  │
                    │  │        → stop stream → recovery scheduler   │  │
                    │  └──────────────────────────────────────────────┘  │
                    │                                                     │
                    │  ┌────────────────────────────────────┐            │
                    │  │ RecoveryScheduler (every 5s)       │            │
                    │  │  OPEN? → wait backoff → HALF_OPEN  │            │
                    │  │  → restart stream → test 20 records│            │
                    │  │  → pass? CLOSED : OPEN again       │            │
                    │  └────────────────────────────────────┘            │
                    └─────────────────────────────────────────────────────┘
```

**Key design decision:** When the breaker opens, the stream is **stopped** (`stop()` → `KafkaStreams.close()`), not paused (`pause()`).
- `pause()` = StreamThreads stay alive and keep calling `poll()` to hold group membership → records keep being fetched until Kafka Streams' backpressure caps the buffer at `input.buffer.max.bytes` (**default 512 MB**). Bounded, but a sustained ~512 MB working set for a long hold → real memory pressure / GC on a tight pod.
- `stop()` = consumer goes **offline** → nothing is fetched, buffered records are released → memory held during the hold is **≈ 0**. This is why this template uses `stop()`.

> **Threading note (critical):** Resilience4j fires its state-transition listener **synchronously on the StreamThread** that crossed the threshold. Calling `close()` directly on that thread makes it try to join itself → blocks for the full close timeout → unclean shutdown. The lifecycle controller therefore **offloads `stop()`/`start()` to a dedicated single-thread executor** so the blocking call never runs on a StreamThread. See the [Lifecycle Controller — Executor Offload Pattern](#lifecycle-controller--executor-offload-pattern-critical) section below for the full code and retrofit rules.

---

## Maximum Safe Stop Duration & Long Outages

> **TL;DR:** A single stop must stay **under `session.timeout.ms`**, and `session.timeout.ms` is capped by the broker at **30 minutes** (Confluent Cloud, fixed). So the **absolute max for one uninterrupted stop is ~30 min**. For longer outages, loop in sub-30-min cycles **and alert**, or let ops/KEDA take the pod down.

### Why the limit exists

While stopped, `KafkaStreams.close()` shuts the consumer down completely — **no heartbeats are sent**. Because `internal.leave.group.on.close=false` suppresses the `LeaveGroup` request, the group coordinator keeps this member's partitions **reserved** — but only for `session.timeout.ms` after the last heartbeat. Exceed that and the member is evicted → **rebalance** on both the stop and the eventual resume.

`session.timeout.ms` itself cannot exceed the broker's `group.max.session.timeout.ms`, whose default is **1,800,000 ms = 30 min**. On Confluent Cloud this ceiling is **fixed** — you cannot raise it.

| Bound | Value |
|---|---|
| Max single stop without rebalance | **< `session.timeout.ms`** |
| Max `session.timeout.ms` (broker ceiling) | **30 min** (Confluent Cloud, fixed) |
| **Absolute max for one uninterrupted stop** | **~30 min** |

**Rules of thumb**
- Keep **every** `restart-delay` a margin under `session.timeout.ms` (e.g. a 25 min stop with a 30 min session timeout — leaves room for the resume heartbeat to land).
- Keep `session.timeout.ms ≤ 30 min` (the broker won't accept more on Confluent Cloud).
- Keep `max.poll.interval.ms ≥ session.timeout.ms`. It does **not** tick while stopped (no poll loop is running), but it guards the first batch after resume.

### Long outages (hours) — do NOT hold it in the app

For a downstream outage lasting **2–3 hours**, holding the stream stopped in-process is the wrong tool:
- You **cannot** set a single stop > 30 min (broker ceiling), so one long stop is impossible on Confluent Cloud.
- Even if you could, parking partitions for hours **blocks failover, grows lag silently, and hides the incident**.

Two correct patterns:

1. **Loop in sub-session-timeout cycles + alert (recommended automated fallback).** Keep each stop **< `session.timeout.ms`** (e.g. 25–30 min). After the backoff, the scheduler briefly resumes (HALF-OPEN probe); if the downstream is still failing, the breaker re-opens and stops for **another** cycle. This repeats indefinitely (e.g. 30 + 30 + 30 …) until the dependency recovers or a human intervenes — partitions stay reserved the whole time **because each individual stop is under the ceiling**. The brief resume between cycles re-establishes heartbeats and resets the session timer. **Pair every OPEN with an alert.**
2. **Alert + ops/KEDA decision for very long outages.** Beyond ~1 hour, fire a high-severity alert and let production support stop the pod — or scale the deployment to zero via KEDA — until the dependency is healthy. This frees partitions cleanly and makes the outage **visible**, instead of a pod silently looping for hours.

> **Recommendation:** “Max 30 min, then loop 30 + 30 until someone restarts” is the right automated bridge **provided** (a) each cycle stays under `session.timeout.ms`, and (b) every OPEN emits an alert. Use the loop for short-to-medium outages; for multi-hour outages, treat the **alert** as the primary mechanism and let humans/KEDA take the pod down.

### Alerting on Breaker OPEN (required)

The loop-and-alert strategy only works if OPEN actually pages someone. The app **already emits** the signals you need:

| Signal | Source | Use for |
|---|---|---|
| ERROR log `🔴 BREAKER OPEN (attempt #n)` | `BusinessOutcomeCircuitBreaker` state listener | Log-based alerting |
| Gauge `circuit_breaker_current_state` (0=CLOSED, 1=OPEN, 2=HALF_OPEN) | `CircuitBreakerMetrics` | **Primary** metric alert |
| Gauge `circuit_breaker_next_restart_delay_seconds` | `CircuitBreakerMetrics` | Dashboards |
| Gauge `circuit_breaker_open_count_total` | `CircuitBreakerMetrics` | Flapping detection |

What's missing out-of-the-box is the **alert rule**. Add this `PrometheusRule` (or the equivalent in your alerting stack):

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
        # Breaker opened — processing is paused. Page on-call.
        - alert: CircuitBreakerOpen
          expr: circuit_breaker_current_state{breaker="payments-business-soft-failure"} == 1
          for: 1m
          labels: { severity: warning }
          annotations:
            summary: "Circuit breaker OPEN on {{ $labels.pod }}"
            description: "Soft-failure rate tripped the breaker. Stream is stopped and looping recovery."

        # Still OPEN past one full max cycle (~30m stop + probe) — likely a sustained outage.
        # Escalate: production support should decide whether to take the pod down / scale to zero.
        - alert: CircuitBreakerStuckOpen
          expr: circuit_breaker_current_state{breaker="payments-business-soft-failure"} == 1
          for: 35m
          labels: { severity: critical }
          annotations:
            summary: "Circuit breaker STUCK OPEN > 35m on {{ $labels.pod }}"
            description: "Breaker has looped past a full max cycle. Treat as a multi-hour outage: page ops, consider KEDA scale-to-zero until the dependency recovers."

        # Opened repeatedly in a short window — flapping dependency.
        - alert: CircuitBreakerFlapping
          expr: changes(circuit_breaker_open_count_total{breaker="payments-business-soft-failure"}[1h]) > 3
          for: 5m
          labels: { severity: warning }
          annotations:
            summary: "Circuit breaker flapping on {{ $labels.pod }}"
            description: "Breaker opened more than 3 times in the last hour."
```

> **Why no code change for alerting?** Breaker OPEN already produces an ERROR log and updates `circuit_breaker_current_state`. Alerting is a **rule** over those signals, not application logic — keeping it in Prometheus means ops can tune thresholds without a redeploy. The `CircuitBreakerStuckOpen` rule (`for: 35m`) is the operational trigger that turns "loop forever" into "a human/KEDA decides" for multi-hour outages.

---

## Lifecycle Controller — Executor Offload Pattern (Critical)

> **TL;DR:** The lifecycle controller **must** offload `StreamsBuilderFactoryBean.stop()` / `start()` to a dedicated single-thread executor. Doing it inline causes the StreamThread to join itself and shut down uncleanly. This section gives you the exact code, plus the rules a reviewer will check during a retrofit.

### Why this matters

Resilience4j fires its `onStateTransition(OPEN)` listener **synchronously on the StreamThread** that crossed the failure threshold (the OPEN transition happens inside `circuitBreaker.onError(...)`, which runs inside the processor's `record(...)` call).

The listener calls `lifecycleController.stopStream()` → `StreamsBuilderFactoryBean.stop()` → `KafkaStreams.close()`. `close()` **blocks until all StreamThreads terminate — including the calling one**. A thread cannot join itself, so the call blocks for the full close timeout and shuts down **uncleanly**.

### The pattern

Flip the breaker-intent flag **synchronously** (so `isStoppedByBreaker()` is instantly correct), but run the **blocking** `stop()`/`start()` on a dedicated single-thread daemon executor so it never runs on a StreamThread. Revert intent on failure (don't rethrow from the executor — the recovery scheduler retries). Shut the executor down in `@PreDestroy`.

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

### What an inline (broken) version looks like — and why it fails

```java
// ❌ DO NOT DO THIS — runs on the StreamThread → self-join → unclean shutdown
@Override
public synchronized void stopStream() {
    if (stoppedByBreaker.compareAndSet(false, true)) {
        try {
            streamsBuilderFactoryBean.stop();          // blocks for full close timeout
        } catch (Exception ex) {
            stoppedByBreaker.set(false);
            throw new IllegalStateException(ex);       // ❌ propagates back onto the StreamThread
        }
    }
}
```

The two failure modes of the inline version:
1. **Self-join deadlock.** `close()` waits for all StreamThreads to terminate, but one of those threads *is* the caller → it blocks the full close timeout, then force-terminates.
2. **Exception propagates onto the StreamThread.** Throwing back from the listener turns a breaker OPEN event into an uncaught exception on the StreamThread, which the Streams uncaught-exception handler then re-classifies (usually as fatal). A breaker trip should never look like a stream crash.

### Retrofit rules (reviewer checklist)

- [ ] `stopStream()` / `startStream()` are **not `synchronized`** — the single-thread executor already serializes them.
- [ ] Both methods **do not throw** — failures revert the `AtomicBoolean` flag instead, so the recovery scheduler can retry on its next tick.
- [ ] The executor is a **single-thread daemon executor** (named for log diagnostics, e.g. `cb-stream-lifecycle`). Single-thread guarantees stop/start run in submission order.
- [ ] The intent flag flips **synchronously** (`compareAndSet` before `submit`) so `isStoppedByBreaker()` is correct the instant the listener returns — the recovery scheduler reads this flag.
- [ ] `@PreDestroy shutdown()` is present and bounded (e.g. 30s timeout, then `shutdownNow()`) so the executor drains cleanly on pod stop.
- [ ] No changes are needed in `BusinessOutcomeCircuitBreaker` or `CircuitBreakerRecoveryScheduler` — they call the same `StreamLifecycleController` interface.

---

## File Map — What to Copy, What to Customize

### Copy As-Is (Template Layer — Don't Modify)

These files contain zero business logic. Copy them directly into your project and change only the package name.

| # | File | What It Does |
|---|------|--------------|
| 1 | `circuit/StreamLifecycleController.java` | Interface: `stopStream()`, `startStream()`, `isStoppedByBreaker()` |
| 2 | `circuit/KafkaStreamsLifecycleController.java` | Implementation: calls `StreamsBuilderFactoryBean.stop()/start()` |
| 3 | `circuit/BusinessOutcomeCircuitBreaker.java` | Resilience4j wrapper: permission gate, result recording, state transitions, exponential backoff |
| 4 | `circuit/BreakerControlProperties.java` | `@ConfigurationProperties` for threshold, window, delays (configurable via YAML) |
| 5 | `circuit/CircuitBreakerRecoveryScheduler.java` | `@Scheduled` bean: watches breaker state, transitions OPEN → HALF_OPEN after backoff delay |
| 6 | `config/validation/BreakerConfigurationValidator.java` | Fail-fast validation on startup (threshold bounds, window bounds, minimum calls) |
| 7 | `health/CircuitBreakerHealthIndicator.java` | Exposes breaker state via `/actuator/health` — all states return UP (observability, not K8s probes) |
| 8 | `metrics/CircuitBreakerMetrics.java` | Prometheus metrics: `circuit_breaker_open_total`, `circuit_breaker_state` gauge |
| 9 | `kafka/StreamFatalExceptionHandler.java` | Uncaught exception classifier: REPLACE_THREAD (transient) vs SHUTDOWN_CLIENT (fatal) |
| 10 | `kafka/CustomDeserializationExceptionHandler.java` | Bad data handler: logs + continues stream. Does NOT count toward circuit breaker |
| 11 | `shutdown/KafkaStreamsShutdownHook.java` | `@PreDestroy` graceful shutdown with configurable timeout |
| 12 | `util/ApplicationContextProvider.java` | Spring context accessor for non-bean classes (used by deserialization handler) |
| 13 | `model/ProcessingResult.java` | Record: `status(SUCCESS|SUCCESS_WITH_EXCEPTION_LOGGED)`, `code`, `message` |
| 14 | `model/ProcessingStatus.java` | Enum: `SUCCESS`, `SUCCESS_WITH_EXCEPTION_LOGGED` |
| 15 | `model/SoftFailureRecord.java` | Record: captures soft failure details for audit logging |

### Customize Per Project (You Provide the Implementation)

| # | File | What to Change | Your Action |
|---|------|----------------|-------------|
| 16 | `model/InputEvent.java` | Replace with your domain's input message model | Define your trade/order/payment record structure |
| 17 | `model/OutputEvent.java` | Replace with your domain's output message model | Define what you send downstream |
| 18 | `model/SoftFailureReason.java` | Replace enum values with your domain's failure types | Define YOUR soft failure codes (e.g., `INVALID_TRADE_ID`, `MISSING_COUNTERPARTY`) |
| 19 | `service/BusinessProcessorService.java` | Keep interface, update `InputEvent` type parameter | No change if you keep the same method signature |
| 20 | `service/DefaultBusinessProcessorService.java` | **Replace entire implementation** with your business logic | This is where YOUR code goes — DB save, validation, outbox, downstream publish |
| 21 | `service/ExceptionAuditService.java` | Keep interface | No change |
| 22 | `service/InMemoryExceptionAuditService.java` | **Replace** with PostgreSQL-backed implementation | Write soft failures to your exception audit table |
| 23 | `service/CsfleCryptoService.java` | Keep interface or remove if CSFLE handled by Confluent config | See CSFLE section below |
| 24 | `service/NoopCsfleCryptoService.java` | Remove — not needed in production | Confluent deserializer handles decryption |
| 25 | `service/OutputProducerService.java` | Keep interface | No change |
| 26 | `service/KafkaOutputProducerService.java` | Update `OutputEvent` type, adjust topic name | Change output topic to your downstream topic |
| 27 | `kafka/PaymentsRecordProcessorSupplier.java` | Rename to match your domain. Change `InputEvent` type | Rename class: `TradeRecordProcessorSupplier` |
| 28 | `config/KafkaStreamsConfig.java` | Update topology: input topic, serde, processor name | Rename beans, change `application-id`, update serde |
| 29 | `config/KafkaProducerConfig.java` | Update `OutputEvent` type | Change to your output model class |
| 30 | `FinancialStreamApplication.java` | Rename class | `TradeIngestionApplication.java` |

---

## Step-by-Step Adoption

### Step 1: Copy Template Files (15 minutes)

Create this package structure in your project:

```
src/main/java/com/yourcompany/tradeingestion/
├── TradeIngestionApplication.java                    ← rename from FinancialStreamApplication
├── circuit/
│   ├── StreamLifecycleController.java                ← copy as-is
│   ├── KafkaStreamsLifecycleController.java           ← copy as-is
│   ├── BusinessOutcomeCircuitBreaker.java             ← copy as-is
│   ├── BreakerControlProperties.java                  ← copy as-is
│   └── CircuitBreakerRecoveryScheduler.java           ← copy as-is
├── config/
│   ├── KafkaStreamsConfig.java                        ← customize Step 4
│   ├── KafkaProducerConfig.java                      ← customize Step 5
│   └── validation/
│       └── BreakerConfigurationValidator.java         ← copy as-is
├── controller/
│   └── HealthController.java                          ← optional, customize
├── health/
│   └── CircuitBreakerHealthIndicator.java             ← copy as-is
├── kafka/
│   ├── TradeRecordProcessorSupplier.java              ← customize Step 3
│   ├── StreamFatalExceptionHandler.java               ← copy as-is
│   └── CustomDeserializationExceptionHandler.java     ← copy as-is
├── metrics/
│   └── CircuitBreakerMetrics.java                     ← copy as-is
├── model/
│   ├── TradeInput.java                                ← YOUR model (Step 2)
│   ├── TradeOutput.java                               ← YOUR model (Step 2)
│   ├── ProcessingResult.java                          ← copy as-is
│   ├── ProcessingStatus.java                          ← copy as-is
│   ├── SoftFailureReason.java                         ← customize Step 2
│   └── SoftFailureRecord.java                         ← copy as-is
├── service/
│   ├── BusinessProcessorService.java                  ← update generic type
│   ├── TradeBusinessProcessorService.java             ← YOUR logic (Step 6)
│   ├── ExceptionAuditService.java                     ← copy as-is
│   ├── PostgresExceptionAuditService.java             ← YOUR impl (Step 7)
│   ├── OutputProducerService.java                     ← copy as-is
│   └── KafkaOutputProducerService.java                ← customize Step 5
├── shutdown/
│   └── KafkaStreamsShutdownHook.java                   ← copy as-is
└── util/
    └── ApplicationContextProvider.java                ← copy as-is
```

### Step 2: Define Your Domain Models (30 minutes)

Replace `InputEvent` and `OutputEvent` with your domain models.

**Example for `trade-processing-service`:**

```java
// model/TradeInput.java — YOUR input record from Kafka
public record TradeInput(
    String tradeId,
    String counterpartyId,
    String instrumentId,
    String tradeType,        // BUY, SELL
    BigDecimal amount,
    String currency,
    String settlementDate,
    String payload,          // Full JSON payload for hashing/audit
    // These two flags drive circuit breaker classification:
    boolean softBusinessFailure,   // ← set by your validation logic
    boolean fatalBusinessFailure   // ← set when unrecoverable (e.g., corrupt data format)
) {}

// model/TradeOutput.java — What you send downstream
public record TradeOutput(
    String tradeId,
    String counterpartyId,
    String status,           // PROCESSED, ENRICHED, FAILED
    Instant processedAt
) {}
```

**Define your soft failure reasons:**

```java
// model/SoftFailureReason.java — YOUR domain failure codes
public enum SoftFailureReason {
    INVALID_TRADE_ID(
        "INVALID_TRADE_ID",
        "Trade ID format is invalid or missing",
        "VALIDATION", null,
        "Verify source system trade ID generation"
    ),
    COUNTERPARTY_NOT_FOUND(
        "COUNTERPARTY_NOT_FOUND",
        "Counterparty not found in reference data",
        "ENRICHMENT", "reference-data-service",
        "Check reference data DB; counterparty may need onboarding"
    ),
    SETTLEMENT_DATE_PAST(
        "SETTLEMENT_DATE_PAST",
        "Settlement date is in the past",
        "BUSINESS_RULE", null,
        "Reject or escalate to operations"
    ),
    DB_CONSTRAINT_VIOLATION(
        "DB_CONSTRAINT_VIOLATION",
        "Duplicate trade ID or constraint violation on insert",
        "DATABASE", null,
        "Check for duplicate submissions; verify upsert logic"
    );
    // ... constructor, getters (same pattern as template)
}
```

### Step 3: Wire the Processor Supplier (15 minutes)

Rename `PaymentsRecordProcessorSupplier` to your domain name. The circuit breaker integration stays identical:

```java
@Component
public class TradeRecordProcessorSupplier implements ProcessorSupplier<String, TradeInput> {

    private final BusinessProcessorService businessProcessorService;
    private final BusinessOutcomeCircuitBreaker breaker;

    // Constructor injection — same as template

    @Override
    public Processor<String, TradeInput> get() {
        return new AbstractProcessor<>() {
            @Override
            public void process(String key, TradeInput value) {
                // ┌──────────────────────────────────────────────────┐
                // │ This method is IDENTICAL to the template.        │
                // │ Don't modify it. All business logic belongs in   │
                // │ BusinessProcessorService.process().              │
                // └──────────────────────────────────────────────────┘
                try {
                    if (!breaker.tryAcquirePermission()) {
                        throw new IllegalStateException(
                            "Circuit breaker does not permit processing");
                    }

                    ProcessorContext context = context();
                    ProcessingResult result = businessProcessorService.process(
                        "trade-processing-stream",   // ← change stream name
                        context.topic(),
                        context.partition(),
                        context.offset(),
                        key,
                        value
                    );

                    breaker.record(result);

                } catch (IllegalStateException cbException) {
                    throw cbException;
                } catch (Exception ex) {
                    throw new IllegalStateException("Unexpected processor error", ex);
                }
            }
        };
    }
}
```

### Step 4: Configure Kafka Streams Topology (20 minutes)

Update `KafkaStreamsConfig.java`:

```java
@Bean
public KStream<String, TradeInput> tradeStream(
        StreamsBuilder streamsBuilder,
        TradeRecordProcessorSupplier processorSupplier,
        @Value("${app.input.topic:trades.raw}") String inputTopic) {

    // ── YOUR SERDE ──
    // If using Confluent CSFLE, use the Confluent KafkaJsonSchemaDeserializer
    // with schema registry + CSFLE config. The deserializer handles decryption.
    // If using plain JSON:
    JsonSerde<TradeInput> inputSerde = new JsonSerde<>(TradeInput.class);

    KStream<String, TradeInput> stream = streamsBuilder.stream(
        inputTopic, Consumed.with(Serdes.String(), inputSerde));
    stream.process(processorSupplier::get);
    return stream;
}
```

**Update `application.yml`:**

```yaml
app:
  input:
    topic: trades.raw                              # ← YOUR input topic
  output:
    topic: trades.processed                        # ← YOUR output topic
  stream:
    application-id: trade-processing-service-v1     # ← YOUR application ID (= consumer group)
```

### Step 5: Configure Output Producer (15 minutes)

Update `KafkaOutputProducerService` and `KafkaProducerConfig` to use your output model:

```java
// Replace OutputEvent with TradeOutput everywhere:
@Service
public class KafkaOutputProducerService implements OutputProducerService {

    private final KafkaTemplate<String, TradeOutput> kafkaTemplate;
    private final String outputTopic;

    // ... same transactional send logic
}
```

### Step 6: Implement Your Business Logic (This Is YOUR Code)

This is the only file where you write actual business logic. Everything else is infrastructure.

```java
@Service
public class TradeBusinessProcessorService implements BusinessProcessorService {

    private final TradeRepository tradeRepository;          // ← YOUR PostgreSQL repo
    private final ExceptionAuditService exceptionAuditService;
    private final OutputProducerService outputProducerService;

    @Override
    public ProcessingResult process(String streamName, String topic,
                                     int partition, long offset,
                                     String key, TradeInput trade) {
        try {
            // ════════════════════════════════════════════════════
            // YOUR BUSINESS LOGIC GOES HERE
            // ════════════════════════════════════════════════════

            // 1. Validate
            if (trade.tradeId() == null || trade.tradeId().isBlank()) {
                return logSoftFailure(streamName, topic, partition, offset, key,
                    trade, SoftFailureReason.INVALID_TRADE_ID);
            }

            // 2. Enrich (lookup counterparty, instrument, etc.)
            // Counterparty counterparty = referenceDataService.find(trade.counterpartyId());
            // if (counterparty == null) {
            //     return logSoftFailure(..., SoftFailureReason.COUNTERPARTY_NOT_FOUND);
            // }

            // 3. Save to PostgreSQL (outbox pattern: save trade + outbox event in one TX)
            // tradeRepository.saveTradeWithOutbox(trade, counterparty);

            // 4. Publish downstream (to output topic or via outbox polling)
            outputProducerService.send(new TradeOutput(
                trade.tradeId(),
                trade.counterpartyId(),
                "PROCESSED",
                Instant.now()
            ));

            return ProcessingResult.success("OK");

            // ════════════════════════════════════════════════════
            // END OF YOUR BUSINESS LOGIC
            // ════════════════════════════════════════════════════

        } catch (DataIntegrityViolationException dbEx) {
            // DB constraint violation → soft failure (duplicate trade, FK violation)
            return logSoftFailure(streamName, topic, partition, offset, key,
                trade, SoftFailureReason.DB_CONSTRAINT_VIOLATION);

        } catch (Exception ex) {
            log.error("Unexpected error processing trade {}", trade.tradeId(), ex);
            return ProcessingResult.successWithExceptionLogged(
                "INTERNAL_ERROR", ex.getMessage());
        }
    }

    private ProcessingResult logSoftFailure(String stream, String topic,
            int partition, long offset, String key,
            TradeInput trade, SoftFailureReason reason) {
        exceptionAuditService.logSoftFailure(
            SoftFailureRecord.of(stream, topic, partition, offset, key,
                trade.tradeId(), reason, sha256(trade.payload())));
        return ProcessingResult.successWithExceptionLogged(
            reason.code(), reason.remediation());
    }
}
```

**Key rules for your business logic:**
1. **Return `ProcessingResult.success("OK")`** for happy path → breaker counts as success
2. **Return `ProcessingResult.successWithExceptionLogged(code, msg)`** for soft failures → breaker counts as failure, stream continues
3. **Throw `IllegalStateException`** for fatal/unrecoverable failures → stream dies → pod restarts
4. **Never swallow exceptions silently** — always return a `ProcessingResult` so the breaker can count it

### Step 7: Implement Exception Audit with PostgreSQL (30 minutes)

Replace `InMemoryExceptionAuditService` with a real database-backed implementation:

```java
@Service
@Primary  // Takes precedence over InMemoryExceptionAuditService if both exist
public class PostgresExceptionAuditService implements ExceptionAuditService {

    private final JdbcTemplate jdbcTemplate;

    public PostgresExceptionAuditService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void logSoftFailure(SoftFailureRecord record) {
        jdbcTemplate.update("""
            INSERT INTO exception_audit (
                stream_name, topic, partition_id, offset_id, record_key,
                correlation_id, failure_code, failure_category, payload_hash,
                logged_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, NOW())
            ON CONFLICT (topic, partition_id, offset_id) DO NOTHING
            """,
            record.streamName(), record.topic(), record.partition(),
            record.offset(), record.key(), record.correlationId(),
            record.reason().code(), record.reason().category(),
            record.payloadHash()
        );
    }

    @Override
    public void logDeserializationFailure(String streamName, String topic,
            int partition, long offset, byte[] keyBytes, byte[] valueBytes,
            String errorCode, String errorMessage) {
        jdbcTemplate.update("""
            INSERT INTO deserialization_errors (
                stream_name, topic, partition_id, offset_id,
                error_code, error_message, logged_at
            ) VALUES (?, ?, ?, ?, ?, ?, NOW())
            ON CONFLICT (topic, partition_id, offset_id) DO NOTHING
            """,
            streamName, topic, partition, offset, errorCode, errorMessage
        );
    }
}
```

**PostgreSQL DDL:**

```sql
CREATE TABLE exception_audit (
    id              BIGSERIAL PRIMARY KEY,
    stream_name     VARCHAR(100) NOT NULL,
    topic           VARCHAR(255) NOT NULL,
    partition_id    INT NOT NULL,
    offset_id       BIGINT NOT NULL,
    record_key      VARCHAR(255),
    correlation_id  VARCHAR(255),
    failure_code    VARCHAR(50) NOT NULL,
    failure_category VARCHAR(50),
    payload_hash    VARCHAR(64),
    logged_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (topic, partition_id, offset_id)
);

CREATE TABLE deserialization_errors (
    id              BIGSERIAL PRIMARY KEY,
    stream_name     VARCHAR(100) NOT NULL,
    topic           VARCHAR(255) NOT NULL,
    partition_id    INT NOT NULL,
    offset_id       BIGINT NOT NULL,
    error_code      VARCHAR(50) NOT NULL,
    error_message   TEXT,
    logged_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (topic, partition_id, offset_id)
);

CREATE INDEX idx_exception_audit_code ON exception_audit(failure_code);
CREATE INDEX idx_exception_audit_logged ON exception_audit(logged_at);
```

### Step 8: Configure application.yml (20 minutes)

Add to your `application.yml`:

```yaml
app:
  # ── Topics ──
  input:
    topic: trades.raw                                    # YOUR input topic
  output:
    topic: trades.processed                              # YOUR output topic

  # ── Kafka Streams Config ──
  stream:
    application-id: trade-processing-service-v1           # YOUR consumer group
    processing-guarantee: exactly_once_v2                # or at_least_once (see SECTION 6)
    num-stream-threads: 1
    commit-interval-ms: 1000
    state-dir: /tmp/kafka-streams
    replication-factor: 3
    num-standby-replicas: 0
    max-task-idle-ms: 0
    group-instance-id: ${HOSTNAME:local-dev-instance}    # Static membership for KEDA
    internal-leave-group-on-close: false

    consumer:
      auto-offset-reset: latest
      max-poll-records: 250
      # max-poll-interval-ms MUST be >= session-timeout-ms. Guards the first batch after resume.
      max-poll-interval-ms: 1800000                      # 30 min
      # session-timeout-ms = how long partitions stay reserved while the stream is STOPPED.
      # This is the hard ceiling for a single breaker stop. Broker max (group.max.session.timeout.ms)
      # is 30 min on Confluent Cloud and cannot be raised. Every restart-delay MUST stay under this.
      session-timeout-ms: 1800000                        # 30 min (broker ceiling)
      heartbeat-interval-ms: 10000
      request-timeout-ms: 30000
      retry-backoff-ms: 500
      fetch-max-bytes: 52428800
      fetch-min-bytes: 16384
      fetch-max-wait-ms: 500
      max-partition-fetch-bytes: 1048576

    producer:
      acks: all
      linger-ms: 5
      batch-size: 65536
      buffer-memory: 67108864

  # ── Circuit Breaker ──
  circuit-breaker:
    failure-rate-threshold: 20                           # Trip at 20% soft failures
    time-window-seconds: 1800                            # 30-minute sliding window
    minimum-number-of-calls: 100                         # Minimum sample size
    permitted-calls-in-half-open-state: 20               # Test records during recovery
    max-wait-in-half-open-state: 2m
    # Exponential backoff. Each delay MUST be < session-timeout-ms (30 min) or the stop
    # exceeds the broker ceiling → member evicted → rebalance. Last delay repeats for
    # subsequent opens, so the breaker loops in ~25m cycles during a long outage.
    restart-delays: 1m, 5m, 25m
    scheduler-delay-ms: 5000                             # Recovery check interval

# ── PostgreSQL ──
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/tradedb
    username: ${DB_USERNAME}
    password: ${DB_PASSWORD}
```

### Step 9: Add Maven Dependencies (10 minutes)

Add to your `pom.xml` (in addition to what you already have):

```xml
<!-- Circuit Breaker -->
<dependency>
    <groupId>io.github.resilience4j</groupId>
    <artifactId>resilience4j-circuitbreaker</artifactId>
    <version>2.2.0</version>
</dependency>

<!-- Metrics (Prometheus export) -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-actuator</artifactId>
</dependency>
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-registry-prometheus</artifactId>
</dependency>

<!-- PostgreSQL (for exception audit) -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-jdbc</artifactId>
</dependency>
<dependency>
    <groupId>org.postgresql</groupId>
    <artifactId>postgresql</artifactId>
    <scope>runtime</scope>
</dependency>
```

### Step 10: Verify Wiring (5 minutes)

After copying all files, compile and verify the Spring context loads:

```bash
mvn clean compile
```

Then run locally. You should see these log lines on startup:

```
Initializing circuit breaker: threshold=20.0%, window=1800s, min-calls=100
✓ Streams started successfully
```

And on `/actuator/health`:

```json
{
  "circuitBreakerHealth": {
    "status": "UP",
    "details": {
      "breaker_state": "CLOSED",
      "status": "Normal — processing active"
    }
  }
}
```

---

## Confluent Cloud CSFLE — What Changes

CSFLE (Client-Side Field Level Encryption) is handled entirely at the **serializer/deserializer layer** — no code changes inside the processor.

### Consumer Side (Kafka Streams)

Confluent's `KafkaJsonSchemaDeserializer` with CSFLE config automatically decrypts encrypted fields before your processor sees the data. Your `TradeInput` record receives **already-decrypted** fields.

**What to configure in `KafkaStreamsConfig.java`:**

```java
// In paymentsStreamsConfiguration method, add consumer-level CSFLE properties:
props.put("consumer.schema.registry.url", schemaRegistryUrl);
props.put("consumer.basic.auth.credentials.source", "USER_INFO");
props.put("consumer.basic.auth.user.info", schemaRegistryCredentials);

// CSFLE-specific:
props.put("consumer.value.deserializer.csfle.kek.name", kekName);
props.put("consumer.value.deserializer.csfle.kms.type", "azure-kms");  // or aws-kms
props.put("consumer.value.deserializer.csfle.kms.key.id", kmsKeyId);
props.put("consumer.value.deserializer.csfle.tenant.id", tenantId);
// ... additional KMS credentials via env vars
```

### Producer Side (KafkaTemplate)

Confluent's `KafkaJsonSchemaSerializer` with CSFLE config automatically encrypts tagged fields before sending. Your code sends **plaintext** `TradeOutput` — the serializer handles encryption.

**What to configure in `KafkaProducerConfig.java`:**

```java
// Producer-level CSFLE properties in the producer config map:
props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
    "io.confluent.kafka.serializers.json.KafkaJsonSchemaSerializer");
props.put("schema.registry.url", schemaRegistryUrl);
props.put("auto.register.schemas", false);  // Use pre-registered schemas in prod
props.put("json.fail.invalid.schema", true);

// CSFLE-specific:
props.put("value.serializer.csfle.kek.name", kekName);
props.put("value.serializer.csfle.kms.type", "azure-kms");
props.put("value.serializer.csfle.kms.key.id", kmsKeyId);
props.put("value.serializer.csfle.tenant.id", tenantId);
```

### `CsfleCryptoService` Interface — Remove It

In the template, `CsfleCryptoService.normalizeAfterSerde()` exists as a placeholder for post-deserialization normalization. With Confluent CSFLE:

- **Decryption happens in the deserializer** → your processor receives plaintext
- **No post-serde normalization needed** → remove `CsfleCryptoService` and `NoopCsfleCryptoService`
- **In `DefaultBusinessProcessorService`**: remove the `csfleCryptoService.normalizeAfterSerde(event)` call; use the input event directly

---

## Outbox Pattern — Where It Fits

If you use the transactional outbox pattern (save trade + outbox event in one PostgreSQL transaction), the circuit breaker still works the same way:

```java
// In your BusinessProcessorService.process():
@Transactional  // Single DB transaction
public ProcessingResult process(..., TradeInput trade) {

    // 1. Validate → soft failure if invalid
    // 2. Save trade to trades table
    tradeRepository.save(trade);

    // 3. Save outbox event (same TX — all or nothing)
    outboxRepository.save(new OutboxEvent(
        trade.tradeId(), "trades.processed", toJson(tradeOutput)));

    // 4. Return success → circuit breaker counts it
    return ProcessingResult.success("OK");

    // The outbox poller (separate process) reads outbox table and publishes to Kafka.
    // If DB save fails → exception → soft failure → circuit breaker counts it.
    // If outbox poller fails → records stay in outbox → retried automatically.
}
```

**Important:** With outbox, you do NOT need `KafkaOutputProducerService`. The outbox poller handles publishing. Remove the direct Kafka send from the processor.

---

## Exception-to-Internal-Topic Pattern

If you catch exceptions and send them to an internal retry/DLT topic:

```java
// In your BusinessProcessorService:
catch (SomeRetriableException ex) {
    // Send to internal retry topic for later reprocessing
    retryProducerService.send("trades.retry", trade.tradeId(), trade);

    // Log as soft failure so circuit breaker tracks the rate
    return logSoftFailure(stream, topic, partition, offset, key,
        trade, SoftFailureReason.SENT_TO_RETRY);
}
```

The circuit breaker monitors the **rate** of these. If > 20% of records go to retry within 30 minutes, the breaker opens and stops the stream (prevents flooding the retry topic when the root cause is systemic).

---

## Optional — `open_count` as a true Micrometer `Counter`

> **Status:** Optional. Apply only if you want breaker-open counts to support `rate()` / `increase()` queries on dashboards. **Not required for alerting** — the `CircuitBreakerFlapping` rule uses `changes(...)[1h]`, which tolerates resets.

### Why

By default, `circuit_breaker_open_count_total` is registered as a **gauge** backed by an in-memory `AtomicInteger`. Two consequences:

- It **resets to 0 on every pod restart** (the `AtomicInteger` is re-created).
- A `_total` suffix on a gauge is misleading — Prometheus convention reserves `_total` for monotonic counters.

This is **not a correctness bug for alerting**, but it matters if you want accurate `rate(...)` / `increase(...)` queries on dashboards.

### Fix — register a Micrometer `Counter` instead of a gauge

**Before** (in `CircuitBreakerMetrics`):

```java
private final AtomicInteger openCount = new AtomicInteger(0);

// in registerMetrics():
meterRegistry.gauge(
    "circuit_breaker_open_count_total",
    Tags.of("breaker", "payments-business-soft-failure"),
    openCount,
    AtomicInteger::get
);

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

> **Exported name is unchanged.** Micrometer's Prometheus registry automatically appends `_total` to counter names, so the metric is still exported as `circuit_breaker_open_count_total` — the existing `CircuitBreakerFlapping` alert keeps working. The count still resets on pod restart (counters are per-process), but it is now a proper monotonic counter within a pod's lifetime, so `rate()` / `increase()` behave correctly.

### Retrofit checklist

- [ ] Replace the `AtomicInteger` gauge with a `Counter` as shown.
- [ ] Confirm the exported metric name stays `circuit_breaker_open_count_total` at `/actuator/prometheus`.
- [ ] No alert-rule change needed.
- [ ] Optional: switch dashboards from raw value to `increase(circuit_breaker_open_count_total[1h])`.

---

## Applying to Both Projects

### `trade-processing-service` (200ms/trade — Light Processing)

| Config | Value | Rationale |
|--------|-------|-----------|
| `application-id` | `trade-processing-service-v1` | Consumer group identity |
| `processing-guarantee` | `at_least_once` | DB is the sink → idempotent upserts |
| `max-poll-records` | `500` | Light processing can handle larger batches |
| `failure-rate-threshold` | `20` | 20% soft failures → breaker opens |
| `minimum-number-of-calls` | `100` | Need 100 records to evaluate |
| `time-window-seconds` | `1800` | 30-min window |


---

## Verification Checklist

After adoption, verify these work end-to-end:

- [ ] **Compile cleanly:** `mvn clean compile` with no errors
- [ ] **Spring context loads:** app starts, streams connect to Kafka
- [ ] **Health endpoint:** `GET /actuator/health` shows `circuitBreakerHealth: UP, CLOSED`
- [ ] **Happy path:** send a valid message → processed successfully → output topic / DB
- [ ] **Soft failure:** send an invalid message → logged to `exception_audit` → stream continues
- [ ] **Circuit breaker trip:** send 100+ records with > 20% soft failures → breaker opens → stream stops
- [ ] **Recovery:** wait for backoff delay → breaker transitions to HALF_OPEN → stream restarts → send valid records → breaker closes
- [ ] **Fatal failure:** throw unrecoverable exception → SHUTDOWN_CLIENT → liveness probe fails → pod restarts
- [ ] **Metrics:** `GET /actuator/prometheus` shows `circuit_breaker_state`, `circuit_breaker_open_total`
- [ ] **Deserialization error:** send malformed JSON → logged → stream continues → breaker NOT affected

---

## Timeline Estimate

| Step | Effort | Who |
|------|--------|-----|
| Copy template files (Step 1) | 15 min | Any developer |
| Define domain models (Step 2) | 30 min | Domain developer |
| Wire processor supplier (Step 3) | 15 min | Any developer |
| Configure topology (Steps 4-5) | 30 min | Kafka developer |
| Implement business logic (Step 6) | **Already done** — your existing code | Domain developer |
| PostgreSQL audit service (Step 7) | 30 min | Any developer |
| Configure YAML (Step 8) | 20 min | Any developer |
| Add Maven deps (Step 9) | 10 min | Any developer |
| Verify + test (Step 10) | 1-2 hours | QA / developer |
| **Total new work** | **~3-4 hours** (excluding business logic which already exists) | |
=======
# Circuit Breaker Template — Adoption Guide

> **Purpose:** Step-by-step guide for adopting the Kafka Streams circuit breaker pattern into any project.  
> **Target projects:** Any Kafka Streams consumer.  
> **Stack:** Spring Boot 3.x, Kafka Streams 3.9.x, Confluent Cloud (CSFLE), PostgreSQL, Resilience4j.

---

## How This Template Works — 60-Second Overview

```
                    ┌─────────────────────────────────────────────────────┐
                    │                YOUR APPLICATION                     │
                    │                                                     │
  payments.input    │  ┌──────────────────────────────────────────────┐  │
  ──────────────────┼─▶│ ProcessorSupplier (Circuit Breaker Gate)     │  │
                    │  │                                              │  │
                    │  │  1. breaker.tryAcquirePermission()           │  │
                    │  │     ├─ OPEN? → throw → stream dies → pod ok │  │
                    │  │     └─ CLOSED/HALF_OPEN? → proceed ↓        │  │
                    │  │                                              │  │
                    │  │  2. businessProcessorService.process()       │  │
                    │  │     ├─ ✅ SUCCESS → output topic / DB save   │  │
                    │  │     ├─ ⚠️ SOFT FAILURE → log + count        │  │
                    │  │     └─ ❌ FATAL → throw → pod restart       │  │
                    │  │                                              │  │
                    │  │  3. breaker.record(result)                   │  │
                    │  │     └─ If soft failures > 20% → OPEN        │  │
                    │  │        → stop stream → recovery scheduler   │  │
                    │  └──────────────────────────────────────────────┘  │
                    │                                                     │
                    │  ┌────────────────────────────────────┐            │
                    │  │ RecoveryScheduler (every 5s)       │            │
                    │  │  OPEN? → wait backoff → HALF_OPEN  │            │
                    │  │  → restart stream → test 20 records│            │
                    │  │  → pass? CLOSED : OPEN again       │            │
                    │  └────────────────────────────────────┘            │
                    └─────────────────────────────────────────────────────┘
```

**Key design decision:** When the breaker opens, the stream is **stopped** (`stop()` → `KafkaStreams.close()`), not paused (`pause()`).
- `pause()` = StreamThreads stay alive and keep calling `poll()` to hold group membership → records keep being fetched until Kafka Streams' backpressure caps the buffer at `input.buffer.max.bytes` (**default 512 MB**). Bounded, but a sustained ~512 MB working set for a long hold → real memory pressure / GC on a tight pod.
- `stop()` = consumer goes **offline** → nothing is fetched, buffered records are released → memory held during the hold is **≈ 0**. This is why this template uses `stop()`.

> **Threading note (critical):** Resilience4j fires its state-transition listener **synchronously on the StreamThread** that crossed the threshold. Calling `close()` directly on that thread makes it try to join itself → blocks for the full close timeout → unclean shutdown. The lifecycle controller therefore **offloads `stop()`/`start()` to a dedicated single-thread executor** so the blocking call never runs on a StreamThread. See the [Lifecycle Controller — Executor Offload Pattern](#lifecycle-controller--executor-offload-pattern-critical) section below for the full code and retrofit rules.

---

## Maximum Safe Stop Duration & Long Outages

> **TL;DR:** A single stop must stay **under `session.timeout.ms`**, and `session.timeout.ms` is capped by the broker at **30 minutes** (Confluent Cloud, fixed). So the **absolute max for one uninterrupted stop is ~30 min**. For longer outages, loop in sub-30-min cycles **and alert**, or let ops/KEDA take the pod down.

### Why the limit exists

While stopped, `KafkaStreams.close()` shuts the consumer down completely — **no heartbeats are sent**. Because `internal.leave.group.on.close=false` suppresses the `LeaveGroup` request, the group coordinator keeps this member's partitions **reserved** — but only for `session.timeout.ms` after the last heartbeat. Exceed that and the member is evicted → **rebalance** on both the stop and the eventual resume.

`session.timeout.ms` itself cannot exceed the broker's `group.max.session.timeout.ms`, whose default is **1,800,000 ms = 30 min**. On Confluent Cloud this ceiling is **fixed** — you cannot raise it.

| Bound | Value |
|---|---|
| Max single stop without rebalance | **< `session.timeout.ms`** |
| Max `session.timeout.ms` (broker ceiling) | **30 min** (Confluent Cloud, fixed) |
| **Absolute max for one uninterrupted stop** | **~30 min** |

**Rules of thumb**
- Keep **every** `restart-delay` a margin under `session.timeout.ms` (e.g. a 25 min stop with a 30 min session timeout — leaves room for the resume heartbeat to land).
- Keep `session.timeout.ms ≤ 30 min` (the broker won't accept more on Confluent Cloud).
- Keep `max.poll.interval.ms ≥ session.timeout.ms`. It does **not** tick while stopped (no poll loop is running), but it guards the first batch after resume.

### Long outages (hours) — do NOT hold it in the app

For a downstream outage lasting **2–3 hours**, holding the stream stopped in-process is the wrong tool:
- You **cannot** set a single stop > 30 min (broker ceiling), so one long stop is impossible on Confluent Cloud.
- Even if you could, parking partitions for hours **blocks failover, grows lag silently, and hides the incident**.

Two correct patterns:

1. **Loop in sub-session-timeout cycles + alert (recommended automated fallback).** Keep each stop **< `session.timeout.ms`** (e.g. 25–30 min). After the backoff, the scheduler briefly resumes (HALF-OPEN probe); if the downstream is still failing, the breaker re-opens and stops for **another** cycle. This repeats indefinitely (e.g. 30 + 30 + 30 …) until the dependency recovers or a human intervenes — partitions stay reserved the whole time **because each individual stop is under the ceiling**. The brief resume between cycles re-establishes heartbeats and resets the session timer. **Pair every OPEN with an alert.**
2. **Alert + ops/KEDA decision for very long outages.** Beyond ~1 hour, fire a high-severity alert and let production support stop the pod — or scale the deployment to zero via KEDA — until the dependency is healthy. This frees partitions cleanly and makes the outage **visible**, instead of a pod silently looping for hours.

> **Recommendation:** “Max 30 min, then loop 30 + 30 until someone restarts” is the right automated bridge **provided** (a) each cycle stays under `session.timeout.ms`, and (b) every OPEN emits an alert. Use the loop for short-to-medium outages; for multi-hour outages, treat the **alert** as the primary mechanism and let humans/KEDA take the pod down.

### Alerting on Breaker OPEN (required)

The loop-and-alert strategy only works if OPEN actually pages someone. The app **already emits** the signals you need:

| Signal | Source | Use for |
|---|---|---|
| ERROR log `🔴 BREAKER OPEN (attempt #n)` | `BusinessOutcomeCircuitBreaker` state listener | Log-based alerting |
| Gauge `circuit_breaker_current_state` (0=CLOSED, 1=OPEN, 2=HALF_OPEN) | `CircuitBreakerMetrics` | **Primary** metric alert |
| Gauge `circuit_breaker_next_restart_delay_seconds` | `CircuitBreakerMetrics` | Dashboards |
| Gauge `circuit_breaker_open_count_total` | `CircuitBreakerMetrics` | Flapping detection |

What's missing out-of-the-box is the **alert rule**. Add this `PrometheusRule` (or the equivalent in your alerting stack):

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
        # Breaker opened — processing is paused. Page on-call.
        - alert: CircuitBreakerOpen
          expr: circuit_breaker_current_state{breaker="payments-business-soft-failure"} == 1
          for: 1m
          labels: { severity: warning }
          annotations:
            summary: "Circuit breaker OPEN on {{ $labels.pod }}"
            description: "Soft-failure rate tripped the breaker. Stream is stopped and looping recovery."

        # Still OPEN past one full max cycle (~30m stop + probe) — likely a sustained outage.
        # Escalate: production support should decide whether to take the pod down / scale to zero.
        - alert: CircuitBreakerStuckOpen
          expr: circuit_breaker_current_state{breaker="payments-business-soft-failure"} == 1
          for: 35m
          labels: { severity: critical }
          annotations:
            summary: "Circuit breaker STUCK OPEN > 35m on {{ $labels.pod }}"
            description: "Breaker has looped past a full max cycle. Treat as a multi-hour outage: page ops, consider KEDA scale-to-zero until the dependency recovers."

        # Opened repeatedly in a short window — flapping dependency.
        - alert: CircuitBreakerFlapping
          expr: changes(circuit_breaker_open_count_total{breaker="payments-business-soft-failure"}[1h]) > 3
          for: 5m
          labels: { severity: warning }
          annotations:
            summary: "Circuit breaker flapping on {{ $labels.pod }}"
            description: "Breaker opened more than 3 times in the last hour."
```

> **Why no code change for alerting?** Breaker OPEN already produces an ERROR log and updates `circuit_breaker_current_state`. Alerting is a **rule** over those signals, not application logic — keeping it in Prometheus means ops can tune thresholds without a redeploy. The `CircuitBreakerStuckOpen` rule (`for: 35m`) is the operational trigger that turns "loop forever" into "a human/KEDA decides" for multi-hour outages.

---

## Lifecycle Controller — Executor Offload Pattern (Critical)

> **TL;DR:** The lifecycle controller **must** offload `StreamsBuilderFactoryBean.stop()` / `start()` to a dedicated single-thread executor. Doing it inline causes the StreamThread to join itself and shut down uncleanly. This section gives you the exact code, plus the rules a reviewer will check during a retrofit.

### Why this matters

Resilience4j fires its `onStateTransition(OPEN)` listener **synchronously on the StreamThread** that crossed the failure threshold (the OPEN transition happens inside `circuitBreaker.onError(...)`, which runs inside the processor's `record(...)` call).

The listener calls `lifecycleController.stopStream()` → `StreamsBuilderFactoryBean.stop()` → `KafkaStreams.close()`. `close()` **blocks until all StreamThreads terminate — including the calling one**. A thread cannot join itself, so the call blocks for the full close timeout and shuts down **uncleanly**.

### The pattern

Flip the breaker-intent flag **synchronously** (so `isStoppedByBreaker()` is instantly correct), but run the **blocking** `stop()`/`start()` on a dedicated single-thread daemon executor so it never runs on a StreamThread. Revert intent on failure (don't rethrow from the executor — the recovery scheduler retries). Shut the executor down in `@PreDestroy`.

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

### What an inline (broken) version looks like — and why it fails

```java
// ❌ DO NOT DO THIS — runs on the StreamThread → self-join → unclean shutdown
@Override
public synchronized void stopStream() {
    if (stoppedByBreaker.compareAndSet(false, true)) {
        try {
            streamsBuilderFactoryBean.stop();          // blocks for full close timeout
        } catch (Exception ex) {
            stoppedByBreaker.set(false);
            throw new IllegalStateException(ex);       // ❌ propagates back onto the StreamThread
        }
    }
}
```

The two failure modes of the inline version:
1. **Self-join deadlock.** `close()` waits for all StreamThreads to terminate, but one of those threads *is* the caller → it blocks the full close timeout, then force-terminates.
2. **Exception propagates onto the StreamThread.** Throwing back from the listener turns a breaker OPEN event into an uncaught exception on the StreamThread, which the Streams uncaught-exception handler then re-classifies (usually as fatal). A breaker trip should never look like a stream crash.

### Retrofit rules (reviewer checklist)

- [ ] `stopStream()` / `startStream()` are **not `synchronized`** — the single-thread executor already serializes them.
- [ ] Both methods **do not throw** — failures revert the `AtomicBoolean` flag instead, so the recovery scheduler can retry on its next tick.
- [ ] The executor is a **single-thread daemon executor** (named for log diagnostics, e.g. `cb-stream-lifecycle`). Single-thread guarantees stop/start run in submission order.
- [ ] The intent flag flips **synchronously** (`compareAndSet` before `submit`) so `isStoppedByBreaker()` is correct the instant the listener returns — the recovery scheduler reads this flag.
- [ ] `@PreDestroy shutdown()` is present and bounded (e.g. 30s timeout, then `shutdownNow()`) so the executor drains cleanly on pod stop.
- [ ] No changes are needed in `BusinessOutcomeCircuitBreaker` or `CircuitBreakerRecoveryScheduler` — they call the same `StreamLifecycleController` interface.

---

## File Map — What to Copy, What to Customize

### Copy As-Is (Template Layer — Don't Modify)

These files contain zero business logic. Copy them directly into your project and change only the package name.

| # | File | What It Does |
|---|------|--------------|
| 1 | `circuit/StreamLifecycleController.java` | Interface: `stopStream()`, `startStream()`, `isStoppedByBreaker()` |
| 2 | `circuit/KafkaStreamsLifecycleController.java` | Implementation: calls `StreamsBuilderFactoryBean.stop()/start()` |
| 3 | `circuit/BusinessOutcomeCircuitBreaker.java` | Resilience4j wrapper: permission gate, result recording, state transitions, exponential backoff |
| 4 | `circuit/BreakerControlProperties.java` | `@ConfigurationProperties` for threshold, window, delays (configurable via YAML) |
| 5 | `circuit/CircuitBreakerRecoveryScheduler.java` | `@Scheduled` bean: watches breaker state, transitions OPEN → HALF_OPEN after backoff delay |
| 6 | `config/validation/BreakerConfigurationValidator.java` | Fail-fast validation on startup (threshold bounds, window bounds, minimum calls) |
| 7 | `health/CircuitBreakerHealthIndicator.java` | Exposes breaker state via `/actuator/health` — all states return UP (observability, not K8s probes) |
| 8 | `metrics/CircuitBreakerMetrics.java` | Prometheus metrics: `circuit_breaker_open_total`, `circuit_breaker_state` gauge |
| 9 | `kafka/StreamFatalExceptionHandler.java` | Uncaught exception classifier: REPLACE_THREAD (transient) vs SHUTDOWN_CLIENT (fatal) |
| 10 | `kafka/CustomDeserializationExceptionHandler.java` | Bad data handler: logs + continues stream. Does NOT count toward circuit breaker |
| 11 | `shutdown/KafkaStreamsShutdownHook.java` | `@PreDestroy` graceful shutdown with configurable timeout |
| 12 | `util/ApplicationContextProvider.java` | Spring context accessor for non-bean classes (used by deserialization handler) |
| 13 | `model/ProcessingResult.java` | Record: `status(SUCCESS|SUCCESS_WITH_EXCEPTION_LOGGED)`, `code`, `message` |
| 14 | `model/ProcessingStatus.java` | Enum: `SUCCESS`, `SUCCESS_WITH_EXCEPTION_LOGGED` |
| 15 | `model/SoftFailureRecord.java` | Record: captures soft failure details for audit logging |

### Customize Per Project (You Provide the Implementation)

| # | File | What to Change | Your Action |
|---|------|----------------|-------------|
| 16 | `model/InputEvent.java` | Replace with your domain's input message model | Define your trade/order/payment record structure |
| 17 | `model/OutputEvent.java` | Replace with your domain's output message model | Define what you send downstream |
| 18 | `model/SoftFailureReason.java` | Replace enum values with your domain's failure types | Define YOUR soft failure codes (e.g., `INVALID_TRADE_ID`, `MISSING_COUNTERPARTY`) |
| 19 | `service/BusinessProcessorService.java` | Keep interface, update `InputEvent` type parameter | No change if you keep the same method signature |
| 20 | `service/DefaultBusinessProcessorService.java` | **Replace entire implementation** with your business logic | This is where YOUR code goes — DB save, validation, outbox, downstream publish |
| 21 | `service/ExceptionAuditService.java` | Keep interface | No change |
| 22 | `service/InMemoryExceptionAuditService.java` | **Replace** with PostgreSQL-backed implementation | Write soft failures to your exception audit table |
| 23 | `service/CsfleCryptoService.java` | Keep interface or remove if CSFLE handled by Confluent config | See CSFLE section below |
| 24 | `service/NoopCsfleCryptoService.java` | Remove — not needed in production | Confluent deserializer handles decryption |
| 25 | `service/OutputProducerService.java` | Keep interface | No change |
| 26 | `service/KafkaOutputProducerService.java` | Update `OutputEvent` type, adjust topic name | Change output topic to your downstream topic |
| 27 | `kafka/PaymentsRecordProcessorSupplier.java` | Rename to match your domain. Change `InputEvent` type | Rename class: `TradeRecordProcessorSupplier` |
| 28 | `config/KafkaStreamsConfig.java` | Update topology: input topic, serde, processor name | Rename beans, change `application-id`, update serde |
| 29 | `config/KafkaProducerConfig.java` | Update `OutputEvent` type | Change to your output model class |
| 30 | `FinancialStreamApplication.java` | Rename class | `TradeIngestionApplication.java` |

---

## Step-by-Step Adoption

### Step 1: Copy Template Files (15 minutes)

Create this package structure in your project:

```
src/main/java/com/yourcompany/tradeingestion/
├── TradeIngestionApplication.java                    ← rename from FinancialStreamApplication
├── circuit/
│   ├── StreamLifecycleController.java                ← copy as-is
│   ├── KafkaStreamsLifecycleController.java           ← copy as-is
│   ├── BusinessOutcomeCircuitBreaker.java             ← copy as-is
│   ├── BreakerControlProperties.java                  ← copy as-is
│   └── CircuitBreakerRecoveryScheduler.java           ← copy as-is
├── config/
│   ├── KafkaStreamsConfig.java                        ← customize Step 4
│   ├── KafkaProducerConfig.java                      ← customize Step 5
│   └── validation/
│       └── BreakerConfigurationValidator.java         ← copy as-is
├── controller/
│   └── HealthController.java                          ← optional, customize
├── health/
│   └── CircuitBreakerHealthIndicator.java             ← copy as-is
├── kafka/
│   ├── TradeRecordProcessorSupplier.java              ← customize Step 3
│   ├── StreamFatalExceptionHandler.java               ← copy as-is
│   └── CustomDeserializationExceptionHandler.java     ← copy as-is
├── metrics/
│   └── CircuitBreakerMetrics.java                     ← copy as-is
├── model/
│   ├── TradeInput.java                                ← YOUR model (Step 2)
│   ├── TradeOutput.java                               ← YOUR model (Step 2)
│   ├── ProcessingResult.java                          ← copy as-is
│   ├── ProcessingStatus.java                          ← copy as-is
│   ├── SoftFailureReason.java                         ← customize Step 2
│   └── SoftFailureRecord.java                         ← copy as-is
├── service/
│   ├── BusinessProcessorService.java                  ← update generic type
│   ├── TradeBusinessProcessorService.java             ← YOUR logic (Step 6)
│   ├── ExceptionAuditService.java                     ← copy as-is
│   ├── PostgresExceptionAuditService.java             ← YOUR impl (Step 7)
│   ├── OutputProducerService.java                     ← copy as-is
│   └── KafkaOutputProducerService.java                ← customize Step 5
├── shutdown/
│   └── KafkaStreamsShutdownHook.java                   ← copy as-is
└── util/
    └── ApplicationContextProvider.java                ← copy as-is
```

### Step 2: Define Your Domain Models (30 minutes)

Replace `InputEvent` and `OutputEvent` with your domain models.

**Example for `trade-processing-service`:**

```java
// model/TradeInput.java — YOUR input record from Kafka
public record TradeInput(
    String tradeId,
    String counterpartyId,
    String instrumentId,
    String tradeType,        // BUY, SELL
    BigDecimal amount,
    String currency,
    String settlementDate,
    String payload,          // Full JSON payload for hashing/audit
    // These two flags drive circuit breaker classification:
    boolean softBusinessFailure,   // ← set by your validation logic
    boolean fatalBusinessFailure   // ← set when unrecoverable (e.g., corrupt data format)
) {}

// model/TradeOutput.java — What you send downstream
public record TradeOutput(
    String tradeId,
    String counterpartyId,
    String status,           // PROCESSED, ENRICHED, FAILED
    Instant processedAt
) {}
```

**Define your soft failure reasons:**

```java
// model/SoftFailureReason.java — YOUR domain failure codes
public enum SoftFailureReason {
    INVALID_TRADE_ID(
        "INVALID_TRADE_ID",
        "Trade ID format is invalid or missing",
        "VALIDATION", null,
        "Verify source system trade ID generation"
    ),
    COUNTERPARTY_NOT_FOUND(
        "COUNTERPARTY_NOT_FOUND",
        "Counterparty not found in reference data",
        "ENRICHMENT", "reference-data-service",
        "Check reference data DB; counterparty may need onboarding"
    ),
    SETTLEMENT_DATE_PAST(
        "SETTLEMENT_DATE_PAST",
        "Settlement date is in the past",
        "BUSINESS_RULE", null,
        "Reject or escalate to operations"
    ),
    DB_CONSTRAINT_VIOLATION(
        "DB_CONSTRAINT_VIOLATION",
        "Duplicate trade ID or constraint violation on insert",
        "DATABASE", null,
        "Check for duplicate submissions; verify upsert logic"
    );
    // ... constructor, getters (same pattern as template)
}
```

### Step 3: Wire the Processor Supplier (15 minutes)

Rename `PaymentsRecordProcessorSupplier` to your domain name. The circuit breaker integration stays identical:

```java
@Component
public class TradeRecordProcessorSupplier implements ProcessorSupplier<String, TradeInput> {

    private final BusinessProcessorService businessProcessorService;
    private final BusinessOutcomeCircuitBreaker breaker;

    // Constructor injection — same as template

    @Override
    public Processor<String, TradeInput> get() {
        return new AbstractProcessor<>() {
            @Override
            public void process(String key, TradeInput value) {
                // ┌──────────────────────────────────────────────────┐
                // │ This method is IDENTICAL to the template.        │
                // │ Don't modify it. All business logic belongs in   │
                // │ BusinessProcessorService.process().              │
                // └──────────────────────────────────────────────────┘
                try {
                    if (!breaker.tryAcquirePermission()) {
                        throw new IllegalStateException(
                            "Circuit breaker does not permit processing");
                    }

                    ProcessorContext context = context();
                    ProcessingResult result = businessProcessorService.process(
                        "trade-processing-stream",   // ← change stream name
                        context.topic(),
                        context.partition(),
                        context.offset(),
                        key,
                        value
                    );

                    breaker.record(result);

                } catch (IllegalStateException cbException) {
                    throw cbException;
                } catch (Exception ex) {
                    throw new IllegalStateException("Unexpected processor error", ex);
                }
            }
        };
    }
}
```

### Step 4: Configure Kafka Streams Topology (20 minutes)

Update `KafkaStreamsConfig.java`:

```java
@Bean
public KStream<String, TradeInput> tradeStream(
        StreamsBuilder streamsBuilder,
        TradeRecordProcessorSupplier processorSupplier,
        @Value("${app.input.topic:trades.raw}") String inputTopic) {

    // ── YOUR SERDE ──
    // If using Confluent CSFLE, use the Confluent KafkaJsonSchemaDeserializer
    // with schema registry + CSFLE config. The deserializer handles decryption.
    // If using plain JSON:
    JsonSerde<TradeInput> inputSerde = new JsonSerde<>(TradeInput.class);

    KStream<String, TradeInput> stream = streamsBuilder.stream(
        inputTopic, Consumed.with(Serdes.String(), inputSerde));
    stream.process(processorSupplier::get);
    return stream;
}
```

**Update `application.yml`:**

```yaml
app:
  input:
    topic: trades.raw                              # ← YOUR input topic
  output:
    topic: trades.processed                        # ← YOUR output topic
  stream:
    application-id: trade-processing-service-v1     # ← YOUR application ID (= consumer group)
```

### Step 5: Configure Output Producer (15 minutes)

Update `KafkaOutputProducerService` and `KafkaProducerConfig` to use your output model:

```java
// Replace OutputEvent with TradeOutput everywhere:
@Service
public class KafkaOutputProducerService implements OutputProducerService {

    private final KafkaTemplate<String, TradeOutput> kafkaTemplate;
    private final String outputTopic;

    // ... same transactional send logic
}
```

### Step 6: Implement Your Business Logic (This Is YOUR Code)

This is the only file where you write actual business logic. Everything else is infrastructure.

```java
@Service
public class TradeBusinessProcessorService implements BusinessProcessorService {

    private final TradeRepository tradeRepository;          // ← YOUR PostgreSQL repo
    private final ExceptionAuditService exceptionAuditService;
    private final OutputProducerService outputProducerService;

    @Override
    public ProcessingResult process(String streamName, String topic,
                                     int partition, long offset,
                                     String key, TradeInput trade) {
        try {
            // ════════════════════════════════════════════════════
            // YOUR BUSINESS LOGIC GOES HERE
            // ════════════════════════════════════════════════════

            // 1. Validate
            if (trade.tradeId() == null || trade.tradeId().isBlank()) {
                return logSoftFailure(streamName, topic, partition, offset, key,
                    trade, SoftFailureReason.INVALID_TRADE_ID);
            }

            // 2. Enrich (lookup counterparty, instrument, etc.)
            // Counterparty counterparty = referenceDataService.find(trade.counterpartyId());
            // if (counterparty == null) {
            //     return logSoftFailure(..., SoftFailureReason.COUNTERPARTY_NOT_FOUND);
            // }

            // 3. Save to PostgreSQL (outbox pattern: save trade + outbox event in one TX)
            // tradeRepository.saveTradeWithOutbox(trade, counterparty);

            // 4. Publish downstream (to output topic or via outbox polling)
            outputProducerService.send(new TradeOutput(
                trade.tradeId(),
                trade.counterpartyId(),
                "PROCESSED",
                Instant.now()
            ));

            return ProcessingResult.success("OK");

            // ════════════════════════════════════════════════════
            // END OF YOUR BUSINESS LOGIC
            // ════════════════════════════════════════════════════

        } catch (DataIntegrityViolationException dbEx) {
            // DB constraint violation → soft failure (duplicate trade, FK violation)
            return logSoftFailure(streamName, topic, partition, offset, key,
                trade, SoftFailureReason.DB_CONSTRAINT_VIOLATION);

        } catch (Exception ex) {
            log.error("Unexpected error processing trade {}", trade.tradeId(), ex);
            return ProcessingResult.successWithExceptionLogged(
                "INTERNAL_ERROR", ex.getMessage());
        }
    }

    private ProcessingResult logSoftFailure(String stream, String topic,
            int partition, long offset, String key,
            TradeInput trade, SoftFailureReason reason) {
        exceptionAuditService.logSoftFailure(
            SoftFailureRecord.of(stream, topic, partition, offset, key,
                trade.tradeId(), reason, sha256(trade.payload())));
        return ProcessingResult.successWithExceptionLogged(
            reason.code(), reason.remediation());
    }
}
```

**Key rules for your business logic:**
1. **Return `ProcessingResult.success("OK")`** for happy path → breaker counts as success
2. **Return `ProcessingResult.successWithExceptionLogged(code, msg)`** for soft failures → breaker counts as failure, stream continues
3. **Throw `IllegalStateException`** for fatal/unrecoverable failures → stream dies → pod restarts
4. **Never swallow exceptions silently** — always return a `ProcessingResult` so the breaker can count it

### Step 7: Implement Exception Audit with PostgreSQL (30 minutes)

Replace `InMemoryExceptionAuditService` with a real database-backed implementation:

```java
@Service
@Primary  // Takes precedence over InMemoryExceptionAuditService if both exist
public class PostgresExceptionAuditService implements ExceptionAuditService {

    private final JdbcTemplate jdbcTemplate;

    public PostgresExceptionAuditService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void logSoftFailure(SoftFailureRecord record) {
        jdbcTemplate.update("""
            INSERT INTO exception_audit (
                stream_name, topic, partition_id, offset_id, record_key,
                correlation_id, failure_code, failure_category, payload_hash,
                logged_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, NOW())
            ON CONFLICT (topic, partition_id, offset_id) DO NOTHING
            """,
            record.streamName(), record.topic(), record.partition(),
            record.offset(), record.key(), record.correlationId(),
            record.reason().code(), record.reason().category(),
            record.payloadHash()
        );
    }

    @Override
    public void logDeserializationFailure(String streamName, String topic,
            int partition, long offset, byte[] keyBytes, byte[] valueBytes,
            String errorCode, String errorMessage) {
        jdbcTemplate.update("""
            INSERT INTO deserialization_errors (
                stream_name, topic, partition_id, offset_id,
                error_code, error_message, logged_at
            ) VALUES (?, ?, ?, ?, ?, ?, NOW())
            ON CONFLICT (topic, partition_id, offset_id) DO NOTHING
            """,
            streamName, topic, partition, offset, errorCode, errorMessage
        );
    }
}
```

**PostgreSQL DDL:**

```sql
CREATE TABLE exception_audit (
    id              BIGSERIAL PRIMARY KEY,
    stream_name     VARCHAR(100) NOT NULL,
    topic           VARCHAR(255) NOT NULL,
    partition_id    INT NOT NULL,
    offset_id       BIGINT NOT NULL,
    record_key      VARCHAR(255),
    correlation_id  VARCHAR(255),
    failure_code    VARCHAR(50) NOT NULL,
    failure_category VARCHAR(50),
    payload_hash    VARCHAR(64),
    logged_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (topic, partition_id, offset_id)
);

CREATE TABLE deserialization_errors (
    id              BIGSERIAL PRIMARY KEY,
    stream_name     VARCHAR(100) NOT NULL,
    topic           VARCHAR(255) NOT NULL,
    partition_id    INT NOT NULL,
    offset_id       BIGINT NOT NULL,
    error_code      VARCHAR(50) NOT NULL,
    error_message   TEXT,
    logged_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (topic, partition_id, offset_id)
);

CREATE INDEX idx_exception_audit_code ON exception_audit(failure_code);
CREATE INDEX idx_exception_audit_logged ON exception_audit(logged_at);
```

### Step 8: Configure application.yml (20 minutes)

Add to your `application.yml`:

```yaml
app:
  # ── Topics ──
  input:
    topic: trades.raw                                    # YOUR input topic
  output:
    topic: trades.processed                              # YOUR output topic

  # ── Kafka Streams Config ──
  stream:
    application-id: trade-processing-service-v1           # YOUR consumer group
    processing-guarantee: exactly_once_v2                # or at_least_once (see SECTION 6)
    num-stream-threads: 1
    commit-interval-ms: 1000
    state-dir: /tmp/kafka-streams
    replication-factor: 3
    num-standby-replicas: 0
    max-task-idle-ms: 0
    group-instance-id: ${HOSTNAME:local-dev-instance}    # Static membership for KEDA
    internal-leave-group-on-close: false

    consumer:
      auto-offset-reset: latest
      max-poll-records: 250
      # max-poll-interval-ms MUST be >= session-timeout-ms. Guards the first batch after resume.
      max-poll-interval-ms: 1800000                      # 30 min
      # session-timeout-ms = how long partitions stay reserved while the stream is STOPPED.
      # This is the hard ceiling for a single breaker stop. Broker max (group.max.session.timeout.ms)
      # is 30 min on Confluent Cloud and cannot be raised. Every restart-delay MUST stay under this.
      session-timeout-ms: 1800000                        # 30 min (broker ceiling)
      heartbeat-interval-ms: 10000
      request-timeout-ms: 30000
      retry-backoff-ms: 500
      fetch-max-bytes: 52428800
      fetch-min-bytes: 16384
      fetch-max-wait-ms: 500
      max-partition-fetch-bytes: 1048576

    producer:
      acks: all
      linger-ms: 5
      batch-size: 65536
      buffer-memory: 67108864

  # ── Circuit Breaker ──
  circuit-breaker:
    failure-rate-threshold: 20                           # Trip at 20% soft failures
    time-window-seconds: 1800                            # 30-minute sliding window
    minimum-number-of-calls: 100                         # Minimum sample size
    permitted-calls-in-half-open-state: 20               # Test records during recovery
    max-wait-in-half-open-state: 2m
    # Exponential backoff. Each delay MUST be < session-timeout-ms (30 min) or the stop
    # exceeds the broker ceiling → member evicted → rebalance. Last delay repeats for
    # subsequent opens, so the breaker loops in ~25m cycles during a long outage.
    restart-delays: 1m, 5m, 25m
    scheduler-delay-ms: 5000                             # Recovery check interval

# ── PostgreSQL ──
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/tradedb
    username: ${DB_USERNAME}
    password: ${DB_PASSWORD}
```

### Step 9: Add Maven Dependencies (10 minutes)

Add to your `pom.xml` (in addition to what you already have):

```xml
<!-- Circuit Breaker -->
<dependency>
    <groupId>io.github.resilience4j</groupId>
    <artifactId>resilience4j-circuitbreaker</artifactId>
    <version>2.2.0</version>
</dependency>

<!-- Metrics (Prometheus export) -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-actuator</artifactId>
</dependency>
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-registry-prometheus</artifactId>
</dependency>

<!-- PostgreSQL (for exception audit) -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-jdbc</artifactId>
</dependency>
<dependency>
    <groupId>org.postgresql</groupId>
    <artifactId>postgresql</artifactId>
    <scope>runtime</scope>
</dependency>
```

### Step 10: Verify Wiring (5 minutes)

After copying all files, compile and verify the Spring context loads:

```bash
mvn clean compile
```

Then run locally. You should see these log lines on startup:

```
Initializing circuit breaker: threshold=20.0%, window=1800s, min-calls=100
✓ Streams started successfully
```

And on `/actuator/health`:

```json
{
  "circuitBreakerHealth": {
    "status": "UP",
    "details": {
      "breaker_state": "CLOSED",
      "status": "Normal — processing active"
    }
  }
}
```

---

## Confluent Cloud CSFLE — What Changes

CSFLE (Client-Side Field Level Encryption) is handled entirely at the **serializer/deserializer layer** — no code changes inside the processor.

### Consumer Side (Kafka Streams)

Confluent's `KafkaJsonSchemaDeserializer` with CSFLE config automatically decrypts encrypted fields before your processor sees the data. Your `TradeInput` record receives **already-decrypted** fields.

**What to configure in `KafkaStreamsConfig.java`:**

```java
// In paymentsStreamsConfiguration method, add consumer-level CSFLE properties:
props.put("consumer.schema.registry.url", schemaRegistryUrl);
props.put("consumer.basic.auth.credentials.source", "USER_INFO");
props.put("consumer.basic.auth.user.info", schemaRegistryCredentials);

// CSFLE-specific:
props.put("consumer.value.deserializer.csfle.kek.name", kekName);
props.put("consumer.value.deserializer.csfle.kms.type", "azure-kms");  // or aws-kms
props.put("consumer.value.deserializer.csfle.kms.key.id", kmsKeyId);
props.put("consumer.value.deserializer.csfle.tenant.id", tenantId);
// ... additional KMS credentials via env vars
```

### Producer Side (KafkaTemplate)

Confluent's `KafkaJsonSchemaSerializer` with CSFLE config automatically encrypts tagged fields before sending. Your code sends **plaintext** `TradeOutput` — the serializer handles encryption.

**What to configure in `KafkaProducerConfig.java`:**

```java
// Producer-level CSFLE properties in the producer config map:
props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
    "io.confluent.kafka.serializers.json.KafkaJsonSchemaSerializer");
props.put("schema.registry.url", schemaRegistryUrl);
props.put("auto.register.schemas", false);  // Use pre-registered schemas in prod
props.put("json.fail.invalid.schema", true);

// CSFLE-specific:
props.put("value.serializer.csfle.kek.name", kekName);
props.put("value.serializer.csfle.kms.type", "azure-kms");
props.put("value.serializer.csfle.kms.key.id", kmsKeyId);
props.put("value.serializer.csfle.tenant.id", tenantId);
```

### `CsfleCryptoService` Interface — Remove It

In the template, `CsfleCryptoService.normalizeAfterSerde()` exists as a placeholder for post-deserialization normalization. With Confluent CSFLE:

- **Decryption happens in the deserializer** → your processor receives plaintext
- **No post-serde normalization needed** → remove `CsfleCryptoService` and `NoopCsfleCryptoService`
- **In `DefaultBusinessProcessorService`**: remove the `csfleCryptoService.normalizeAfterSerde(event)` call; use the input event directly

---

## Outbox Pattern — Where It Fits

If you use the transactional outbox pattern (save trade + outbox event in one PostgreSQL transaction), the circuit breaker still works the same way:

```java
// In your BusinessProcessorService.process():
@Transactional  // Single DB transaction
public ProcessingResult process(..., TradeInput trade) {

    // 1. Validate → soft failure if invalid
    // 2. Save trade to trades table
    tradeRepository.save(trade);

    // 3. Save outbox event (same TX — all or nothing)
    outboxRepository.save(new OutboxEvent(
        trade.tradeId(), "trades.processed", toJson(tradeOutput)));

    // 4. Return success → circuit breaker counts it
    return ProcessingResult.success("OK");

    // The outbox poller (separate process) reads outbox table and publishes to Kafka.
    // If DB save fails → exception → soft failure → circuit breaker counts it.
    // If outbox poller fails → records stay in outbox → retried automatically.
}
```

**Important:** With outbox, you do NOT need `KafkaOutputProducerService`. The outbox poller handles publishing. Remove the direct Kafka send from the processor.

---

## Exception-to-Internal-Topic Pattern

If you catch exceptions and send them to an internal retry/DLT topic:

```java
// In your BusinessProcessorService:
catch (SomeRetriableException ex) {
    // Send to internal retry topic for later reprocessing
    retryProducerService.send("trades.retry", trade.tradeId(), trade);

    // Log as soft failure so circuit breaker tracks the rate
    return logSoftFailure(stream, topic, partition, offset, key,
        trade, SoftFailureReason.SENT_TO_RETRY);
}
```

The circuit breaker monitors the **rate** of these. If > 20% of records go to retry within 30 minutes, the breaker opens and stops the stream (prevents flooding the retry topic when the root cause is systemic).

---

## Optional — `open_count` as a true Micrometer `Counter`

> **Status:** Optional. Apply only if you want breaker-open counts to support `rate()` / `increase()` queries on dashboards. **Not required for alerting** — the `CircuitBreakerFlapping` rule uses `changes(...)[1h]`, which tolerates resets.

### Why

By default, `circuit_breaker_open_count_total` is registered as a **gauge** backed by an in-memory `AtomicInteger`. Two consequences:

- It **resets to 0 on every pod restart** (the `AtomicInteger` is re-created).
- A `_total` suffix on a gauge is misleading — Prometheus convention reserves `_total` for monotonic counters.

This is **not a correctness bug for alerting**, but it matters if you want accurate `rate(...)` / `increase(...)` queries on dashboards.

### Fix — register a Micrometer `Counter` instead of a gauge

**Before** (in `CircuitBreakerMetrics`):

```java
private final AtomicInteger openCount = new AtomicInteger(0);

// in registerMetrics():
meterRegistry.gauge(
    "circuit_breaker_open_count_total",
    Tags.of("breaker", "payments-business-soft-failure"),
    openCount,
    AtomicInteger::get
);

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

> **Exported name is unchanged.** Micrometer's Prometheus registry automatically appends `_total` to counter names, so the metric is still exported as `circuit_breaker_open_count_total` — the existing `CircuitBreakerFlapping` alert keeps working. The count still resets on pod restart (counters are per-process), but it is now a proper monotonic counter within a pod's lifetime, so `rate()` / `increase()` behave correctly.

### Retrofit checklist

- [ ] Replace the `AtomicInteger` gauge with a `Counter` as shown.
- [ ] Confirm the exported metric name stays `circuit_breaker_open_count_total` at `/actuator/prometheus`.
- [ ] No alert-rule change needed.
- [ ] Optional: switch dashboards from raw value to `increase(circuit_breaker_open_count_total[1h])`.

---

## Applying to Both Projects

### `trade-processing-service` (200ms/trade — Light Processing)

| Config | Value | Rationale |
|--------|-------|-----------|
| `application-id` | `trade-processing-service-v1` | Consumer group identity |
| `processing-guarantee` | `at_least_once` | DB is the sink → idempotent upserts |
| `max-poll-records` | `500` | Light processing can handle larger batches |
| `failure-rate-threshold` | `20` | 20% soft failures → breaker opens |
| `minimum-number-of-calls` | `100` | Need 100 records to evaluate |
| `time-window-seconds` | `1800` | 30-min window |


---

## Verification Checklist

After adoption, verify these work end-to-end:

- [ ] **Compile cleanly:** `mvn clean compile` with no errors
- [ ] **Spring context loads:** app starts, streams connect to Kafka
- [ ] **Health endpoint:** `GET /actuator/health` shows `circuitBreakerHealth: UP, CLOSED`
- [ ] **Happy path:** send a valid message → processed successfully → output topic / DB
- [ ] **Soft failure:** send an invalid message → logged to `exception_audit` → stream continues
- [ ] **Circuit breaker trip:** send 100+ records with > 20% soft failures → breaker opens → stream stops
- [ ] **Recovery:** wait for backoff delay → breaker transitions to HALF_OPEN → stream restarts → send valid records → breaker closes
- [ ] **Fatal failure:** throw unrecoverable exception → SHUTDOWN_CLIENT → liveness probe fails → pod restarts
- [ ] **Metrics:** `GET /actuator/prometheus` shows `circuit_breaker_state`, `circuit_breaker_open_total`
- [ ] **Deserialization error:** send malformed JSON → logged → stream continues → breaker NOT affected

---

## Timeline Estimate

| Step | Effort | Who |
|------|--------|-----|
| Copy template files (Step 1) | 15 min | Any developer |
| Define domain models (Step 2) | 30 min | Domain developer |
| Wire processor supplier (Step 3) | 15 min | Any developer |
| Configure topology (Steps 4-5) | 30 min | Kafka developer |
| Implement business logic (Step 6) | **Already done** — your existing code | Domain developer |
| PostgreSQL audit service (Step 7) | 30 min | Any developer |
| Configure YAML (Step 8) | 20 min | Any developer |
| Add Maven deps (Step 9) | 10 min | Any developer |
| Verify + test (Step 10) | 1-2 hours | QA / developer |
| **Total new work** | **~3-4 hours** (excluding business logic which already exists) | |
>>>>>>> d22254f050bbcf0c09f85d4a47feef009219aca8
