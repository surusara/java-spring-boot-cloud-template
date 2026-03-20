# Reusable Prompt: Migrate Kafka Streams Circuit Breaker from pause() to stop() Architecture

> Copy the prompt below (everything between the `---` lines) and paste it into a new Copilot chat in your other project.
> Replace the `[PLACEHOLDERS]` with your project-specific values before running.

---

## PROMPT START — Copy from here ↓

I need you to **replace** my existing Kafka Streams circuit breaker implementation that uses `pause()`/`resume()` with a production-grade **`stop()`/`start()`** architecture. 

### CRITICAL RULES
1. **DO NOT modify any business logic code** — processors, transformers, serdes, domain models, services, repositories, and any class that handles business data processing must remain untouched.
2. **Only replace the circuit breaker infrastructure** — the mechanism that decides when to stop/start the stream, how recovery works, how health is reported, and how metrics are exported.
3. **Remove all pause()/resume() based circuit breaker code** and replace it with the stop()/start() architecture described below.
4. Keep existing Kafka Streams topology configuration (`KStream`, `KTable`, processors, serdes) as-is.
5. Keep existing `application.properties`/`application.yml` Kafka connection settings as-is; only add the new circuit breaker properties.

### WHY stop() INSTEAD OF pause()

| Aspect | `pause()` (current, problematic) | `stop()` (target) |
|---|---|---|
| Consumer fetching | **Continues** — consumer keeps polling broker | **Halts** — consumer goes offline entirely |
| Message buffering | Unfetched records accumulate in client-side buffers | Records stay in broker untouched |
| Memory pressure | Buffer buildup → OOM risk at scale | Minimal — no active task threads or buffers |
| Recovery | Automatic when resume is called | Explicit `start()` after backoff |

**At high throughput, `pause()` silently accumulates GBs of buffered data → OOM kills.**

### ARCHITECTURE TO IMPLEMENT

Create/replace the following components. Adapt package names to match my project structure at `[YOUR_BASE_PACKAGE]` (e.g., `com.example.myapp`).

---

#### 1. `StreamLifecycleController` (Interface)

```java
package [YOUR_BASE_PACKAGE].circuit;

public interface StreamLifecycleController {
    void stopStream();
    void startStream();
    boolean isStoppedByBreaker();
}
```

**Purpose:** Abstraction layer between circuit breaker logic and Kafka Streams infrastructure.

---

#### 2. `KafkaStreamsLifecycleController` (Implementation)

```java
package [YOUR_BASE_PACKAGE].circuit;

@Component
public class KafkaStreamsLifecycleController implements StreamLifecycleController {

    private final StreamsBuilderFactoryBean streamsBuilderFactoryBean;
    private final AtomicBoolean stoppedByBreaker = new AtomicBoolean(false);

    // Inject using: @Qualifier("&[YOUR_STREAMS_BUILDER_BEAN_NAME]")
    // The "&" prefix gets the FactoryBean itself, not its product.
    // Replace [YOUR_STREAMS_BUILDER_BEAN_NAME] with your StreamsBuilderFactoryBean bean name.

    @Override
    public synchronized void stopStream() {
        if (stoppedByBreaker.compareAndSet(false, true)) {
            streamsBuilderFactoryBean.stop();
            // On error: reset stoppedByBreaker to false, rethrow
        }
    }

    @Override
    public synchronized void startStream() {
        if (stoppedByBreaker.compareAndSet(true, false)) {
            streamsBuilderFactoryBean.start();
            // On error: reset stoppedByBreaker to true, rethrow
        }
    }

    @Override
    public boolean isStoppedByBreaker() {
        return stoppedByBreaker.get();
    }
}
```

**Key details:**
- `synchronized` methods prevent concurrent start/stop races.
- `AtomicBoolean` + `compareAndSet` ensures idempotent stop/start.
- On error during stop/start, the boolean state is rolled back and exception is rethrown.
- Inject the `StreamsBuilderFactoryBean` using `@Qualifier("&beanName")` — the `&` prefix is required to get the factory bean itself.

---

#### 3. `BreakerControlProperties` (Externalized Configuration)

```java
package [YOUR_BASE_PACKAGE].circuit;

@ConfigurationProperties(prefix = "app.circuit-breaker")
public class BreakerControlProperties {
    private float failureRateThreshold = 20.0f;          // percentage
    private int timeWindowSeconds = 1800;                  // 30 minutes, TIME_BASED
    private int minimumNumberOfCalls = 100;                // prevent false positives
    private int permittedCallsInHalfOpenState = 20;        // cautious recovery test
    private Duration maxWaitInHalfOpenState = Duration.ofMinutes(2);
    private List<Duration> restartDelays = List.of(
        Duration.ofMinutes(1),   // 1st breach: quick retry
        Duration.ofMinutes(10),  // 2nd breach: dependency likely restarting
        Duration.ofMinutes(20)   // 3rd+ breach: persistent issue
    );
    // Generate getters and setters for all fields
}
```

**Add to `application.yml`:**
```yaml
app:
  circuit-breaker:
    failure-rate-threshold: 20
    time-window-seconds: 1800
    minimum-number-of-calls: 100
    permitted-calls-in-half-open-state: 20
    max-wait-in-half-open-state: 2m
    restart-delays:
      - 1m
      - 10m
      - 20m
    scheduler-delay-ms: 5000
```

---

#### 4. `BusinessOutcomeCircuitBreaker` (Core Logic)

```java
package [YOUR_BASE_PACKAGE].circuit;

@Component
public class BusinessOutcomeCircuitBreaker {

    private final BreakerControlProperties properties;
    private final StreamLifecycleController lifecycleController;
    private final AtomicInteger openCount = new AtomicInteger(0);
    
    @Autowired(required = false)
    private CircuitBreakerMetrics metrics;  // optional dependency
    
    private CircuitBreaker circuitBreaker;
    private volatile Duration nextRestartDelay;
}
```

**Initialization (`@PostConstruct`):**
- Build `CircuitBreakerConfig` with:
  - `slidingWindowType(TIME_BASED)`, `slidingWindowSize(timeWindowSeconds)`
  - `failureRateThreshold`, `minimumNumberOfCalls`, `permittedNumberOfCallsInHalfOpenState`
  - `maxWaitDurationInHalfOpenState`
  - `automaticTransitionFromOpenToHalfOpenEnabled(false)` — **critical: recovery is manual**
  - `recordException(ex -> ex instanceof SoftBusinessFailureException)` — only count soft business failures
  - `waitDurationInOpenState(restartDelays.getFirst())`

**State transition event handler:**
- **→ OPEN:** increment `openCount`, compute `nextRestartDelay` from `restartDelays` list (index = `min(openCount-1, list.size()-1)`), record metrics, call **`lifecycleController.stopStream()`**
- **→ CLOSED:** reset `openCount` to 0, reset `nextRestartDelay` to first delay
- **→ HALF_OPEN:** log warning

**Methods:**
- `record(ProcessingResult result)` — calls `circuitBreaker.onSuccess()` for SUCCESS, `circuitBreaker.onError()` with `SoftBusinessFailureException` for failures
- `tryAcquirePermission()` — gate check before processing each record
- `moveToHalfOpenAndRestart()` — transitions OPEN→HALF_OPEN, then calls `lifecycleController.startStream()`
- `getState()`, `getNextRestartDelay()` — accessors

**Inner class:** `SoftBusinessFailureException extends RuntimeException`

**IMPORTANT:** Adapt `record()` and `tryAcquirePermission()` to match your existing processor's result model. The breaker needs to know: was this record a success or a soft business failure?

---

#### 5. `CircuitBreakerRecoveryScheduler`

```java
package [YOUR_BASE_PACKAGE].circuit;

@Component
public class CircuitBreakerRecoveryScheduler {

    private final BusinessOutcomeCircuitBreaker breaker;
    private final StreamLifecycleController lifecycleController;
    private final AtomicReference<Instant> nextAttempt = new AtomicReference<>();

    @Scheduled(fixedDelayString = "${app.circuit-breaker.scheduler-delay-ms:5000}")
    public void scheduleRecovery() {
        // 1. When OPEN && stopped: schedule nextAttempt = now + nextRestartDelay (once, via compareAndSet)
        // 2. When nextAttempt is due: clear it, call breaker.moveToHalfOpenAndRestart()
        // 3. Wrap everything in try-catch to prevent scheduler thread death
    }
}
```

**Requires `@EnableScheduling` on your main application class.**

---

#### 6. `CircuitBreakerHealthIndicator`

```java
package [YOUR_BASE_PACKAGE].health;

@Component("circuitBreakerHealth")
public class CircuitBreakerHealthIndicator implements HealthIndicator {
    // ALL states return Health.up() — breaker state is a BUSINESS concern, not pod health
    // CLOSED  → UP + detail "Normal — processing active"
    // HALF_OPEN → UP + detail "Testing — recovery in progress"
    // OPEN → UP + detail "Paused — waiting for recovery" + next_restart_delay
    // Default → Health.unknown()
}
```

**Rationale:** K8s liveness/readiness probes should NOT restart the pod when breaker opens. The pod is alive and the recovery scheduler is running inside it.

---

#### 7. `CircuitBreakerMetrics` (Prometheus/Micrometer)

```java
package [YOUR_BASE_PACKAGE].metrics;

@Component
public class CircuitBreakerMetrics {
    // Register 3 Micrometer gauges:
    // 1. circuit_breaker_current_state (0=CLOSED, 1=OPEN, 2=HALF_OPEN)
    // 2. circuit_breaker_next_restart_delay_seconds
    // 3. circuit_breaker_open_count_total
    
    // Use ObjectProvider<BusinessOutcomeCircuitBreaker> to break circular dependency
    // Tag all gauges with: breaker=[YOUR_BREAKER_NAME]
}
```

---

#### 8. `KafkaStreamsShutdownHook` (Graceful Shutdown)

```java
package [YOUR_BASE_PACKAGE].shutdown;

@Component
public class KafkaStreamsShutdownHook {
    // In constructor: Runtime.getRuntime().addShutdownHook(...)
    // gracefulShutdown(): stop StreamsBuilderFactoryBean, sleep 60s grace period
    // Handle InterruptedException by re-setting interrupt flag
}
```

**Match with K8s deployment:** `terminationGracePeriodSeconds: 70` and `preStop: sleep 60`

---

#### 9. `BreakerConfigurationValidator` (Fail-Fast Validation)

```java
package [YOUR_BASE_PACKAGE].config.validation;

@Component
public class BreakerConfigurationValidator implements InitializingBean {
    // Validates at startup (prevents app from starting with bad config):
    // 1. failureRateThreshold ∈ [1, 100]
    // 2. minimumNumberOfCalls ≥ 10
    // 3. restartDelays non-empty, first entry ≥ 10 seconds
    // 4. timeWindowSeconds ∈ [60, 7200]
    // Throws IllegalArgumentException on violation
}
```

---

### 3-TIER EXCEPTION CLASSIFICATION

This architecture uses a 3-tier exception model. **Map your existing exceptions to these tiers:**

| Tier | What | Stream Action | Breaker Impact |
|---|---|---|---|
| **1. Deserialization errors** | Malformed JSON, bad schema, encoding | `CONTINUE` (skip record) | **NO** breaker count |
| **2. Soft business failures** | Validation fail, enrichment unavailable, business rule violation | `CONTINUE` (log to audit) | **YES** counted toward threshold |
| **3. Hard infrastructure failures** | NPE, DB down, OOM | `SHUTDOWN_CLIENT` → pod restart | **NO** breaker count (immediate restart) |

**Your existing deserialization exception handler should return `CONTINUE` and NOT count toward the breaker.**
**Your existing fatal exception handler should return `SHUTDOWN_CLIENT`.**
**Only soft business failures feed into `BusinessOutcomeCircuitBreaker.record()`.**

---

### STATE MACHINE

```
CLOSED (normal processing)
  ↓ failure_rate > 20% in 30-min window (min 100 calls)
OPEN  → stopStream() → consumer goes offline, records stay in broker
  ↓ RecoveryScheduler waits nextRestartDelay (1m → 10m → 20m)
HALF_OPEN → startStream() → test 20 messages
  ├─ pass (< 20% fail): → CLOSED, openCount reset to 0
  └─ fail (≥ 20% fail): → OPEN, openCount++, delay escalated
```

---

### REQUIRED DEPENDENCIES (pom.xml / build.gradle)

Ensure these are present:
```xml
<!-- Resilience4j Circuit Breaker -->
<dependency>
    <groupId>io.github.resilience4j</groupId>
    <artifactId>resilience4j-circuitbreaker</artifactId>
    <version>2.2.0</version>
</dependency>

<!-- Spring Boot Actuator (for HealthIndicator) -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-actuator</artifactId>
</dependency>

<!-- Micrometer Prometheus (for metrics export) -->
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-registry-prometheus</artifactId>
</dependency>
```

---

### INTEGRATION POINTS — What to wire in your existing code

**In your record processor (the class that processes each Kafka message):**

1. **Before processing:** Call `breaker.tryAcquirePermission()`. If `false`, skip (breaker is open).
2. **After processing:** Call `breaker.record(result)` with the processing outcome.

Example pattern (adapt to your code):
```java
// In your processor's process() method:
if (!circuitBreaker.tryAcquirePermission()) {
    // Breaker is open — skip processing (stream will be stopped shortly anyway)
    return;
}

// ... your existing business logic (DO NOT CHANGE) ...

// After business logic completes:
circuitBreaker.record(new ProcessingResult(status, code, message));
```

**You need a `ProcessingResult` model** (or adapt to your existing result type):
```java
public record ProcessingResult(ProcessingStatus status, String code, String message) {}
public enum ProcessingStatus { SUCCESS, SOFT_FAILURE }
```

---

### WHAT TO DELETE (from old pause-based implementation)

Search for and remove:
- Any code calling `KafkaStreams.pause()` or `KafkaStreams.resume()`
- Any `TopicPartition` tracking for pause/resume
- Any pause-based circuit breaker classes
- Any pause-based recovery/scheduler logic
- Any health indicators that return DOWN/OUT_OF_SERVICE when breaker opens

---

### STEP-BY-STEP EXECUTION PLAN

1. **Scan** my project and identify all existing circuit breaker / pause / resume code
2. **List** the files you will modify and the files you will create — get my approval before proceeding
3. **Add** the required dependencies to pom.xml/build.gradle if missing
4. **Create** the 9 new classes listed above, adapting package names and bean qualifiers to my project
5. **Wire** the `tryAcquirePermission()` and `record()` calls into my existing processor — minimal changes only
6. **Remove** all old pause/resume circuit breaker code
7. **Add** the configuration properties to application.yml
8. **Add** `@EnableScheduling` to the main application class if not already present
9. **Add** `@EnableConfigurationProperties(BreakerControlProperties.class)` to the relevant config class
10. **Verify** no business logic code was modified
11. **Show me** a summary of all changes made

### PROJECT-SPECIFIC VALUES TO FILL IN

Replace these placeholders before running:
- `[YOUR_BASE_PACKAGE]` = your base Java package (e.g., `com.mycompany.payments`)
- `[YOUR_STREAMS_BUILDER_BEAN_NAME]` = your `StreamsBuilderFactoryBean` bean name
- `[YOUR_BREAKER_NAME]` = name tag for metrics (e.g., `payments-business-soft-failure`)

## PROMPT END — Copy up to here ↑

---
