# 01 — Kafka Configuration

> **Assignee:** _______________  
> **Scope:** Kafka Streams consumer configuration, Kafka plain producer configuration, exception handling, graceful shutdown  
> **No circuit breaker in this stage** — only stream processing pipeline  

---

## What This Service Does

A Spring Boot Kafka Streams application that reads financial events from `payments.input`, processes them, and writes results to `payments.output`. Designed for 3M trades/day on Confluent Cloud via AKS.

There are **two distinct Kafka configurations** in this service:

| # | Config | Class | Purpose |
|---|--------|-------|---------|
| 1 | **Kafka Streams (Consumer)** | `KafkaStreamsConfig.java` | Reads from `payments.input`, runs stream topology, manages consumer group, offsets, and internal producer for exactly-once |
| 2 | **Kafka Plain Producer** | `KafkaProducerConfig.java` | Standalone `KafkaTemplate<String, OutputEvent>` for writing processed results to `payments.output` |

They are configured independently — different YAML paths, different Java classes, different concerns.

---

## Tech Stack

| Component | Version | Why |
|-----------|---------|-----|
| Java | 21 | LTS, virtual threads, deprecated SecurityManager (DNS TTL default = 30s) |
| Spring Boot | 3.5.9 | Latest LTS, manages Kafka + actuator versions |
| Kafka Streams | 3.9.x | Pulled via `spring-kafka` BOM — don't pin separately |
| Jackson | (BOM) | JSON serde for `InputEvent` / `OutputEvent` |

---

## Maven Dependencies

```xml
<!-- pom.xml — only Kafka-related deps shown -->
<parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>3.5.9</version>
</parent>

<properties>
    <java.version>21</java.version>
</properties>

<dependencies>
    <!-- Core Spring Boot -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-web</artifactId>
    </dependency>

    <!-- Kafka Streams — Spring manages version via BOM -->
    <dependency>
        <groupId>org.springframework.kafka</groupId>
        <artifactId>spring-kafka</artifactId>
    </dependency>
    <dependency>
        <groupId>org.apache.kafka</groupId>
        <artifactId>kafka-streams</artifactId>
    </dependency>

    <!-- JSON serialization for stream records -->
    <dependency>
        <groupId>com.fasterxml.jackson.core</groupId>
        <artifactId>jackson-databind</artifactId>
    </dependency>
</dependencies>
```

---

---

## ⚠️ Critical: Exclude Spring Boot Kafka Auto-Configuration

When you manually configure `StreamsBuilderFactoryBean` (as this project does in `KafkaStreamsConfig.java`), you **must** exclude Spring Boot's `KafkaAutoConfiguration`. Without this, Spring Boot silently creates a **second** `StreamsBuilderFactoryBean` (`defaultKafkaStreamsBuilder`) using `spring.application.name` as its `application.id` — a completely separate consumer group that subscribes to the same input topic.

**Symptom:** Same message consumed twice — once by your manually configured Streams instance, once by the auto-configured phantom instance. Appears when running with multiple threads or multiple pods.

**When does the phantom actually appear?** The phantom `defaultKafkaStreamsBuilder` is only created when **one of these conditions** is true:
1. A `@Configuration` class uses `@EnableKafkaStreams`
2. A bean named `defaultKafkaStreamsConfig` exists in the ApplicationContext

If your project uses `@EnableKafkaStreams` on any `@Configuration` class — **that is the trigger**. Spring Boot sees it and auto-creates a second `StreamsBuilderFactoryBean` with `application.id = spring.application.name`, forming a separate consumer group that subscribes to the same input topic. Remove `@EnableKafkaStreams` and use manual `StreamsBuilderFactoryBean` creation (as this project does in `KafkaStreamsConfig.java`) to avoid the phantom.

**The `exclude` is still recommended as defensive best practice** — even without `@EnableKafkaStreams`, it prevents auto-configured `ConsumerFactory`/`ProducerFactory` beans from conflicting with your manual ones.

**Fix in `@SpringBootApplication`:**

```java
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;

@SpringBootApplication(exclude = KafkaAutoConfiguration.class)
public class YourApplication {
    public static void main(String[] args) {
        SpringApplication.run(YourApplication.class, args);
    }
}
```

**Why this is safe:** The exclusion only removes Spring Boot's auto-created beans. Your manual `@Configuration` classes (`KafkaStreamsConfig.java`, `KafkaProducerConfig.java`) are unaffected — they are component-scanned normally.

**How to verify in production:**

```bash
# List all consumer groups — you should see exactly ONE for your app
kafka-consumer-groups --bootstrap-server <broker> --list

# If you see TWO groups (your application.id AND spring.application.name), the phantom exists
# Check pod logs for multiple "StreamThread started" lines with different client IDs
kubectl logs <pod> | grep "stream-client"
```

---

# SECTION 1: Kafka Streams Consumer Configuration

> **Config class:** `KafkaStreamsConfig.java`  
> **YAML path:** `app.stream.*`  
> **What it does:** Creates a `StreamsBuilderFactoryBean` that manages the entire stream lifecycle — consumer group, partition assignment, offset commits, internal producer (for exactly-once), and the processing topology.

---

## 1.1 application.yml — Kafka Streams Properties

```yaml
app:
  input:
    topic: payments.input
  output:
    topic: payments.output
  stream:
    application-id: payments-stream-v1
    processing-guarantee: at_least_once
    num-stream-threads: 1
    commit-interval-ms: 1000
    state-dir: /tmp/kafka-streams
    replication-factor: 3
    num-standby-replicas: 0
    max-task-idle-ms: 1000

    # --- Static Membership (prevents rebalance storms with KEDA) ---
    group-instance-id: ${HOSTNAME:local-dev-instance}
    internal-leave-group-on-close: false

    # --- Consumer Overrides ---
    consumer:
      auto-offset-reset: latest
      isolation-level: read_committed
      max-poll-records: 250
      max-poll-interval-ms: 300000
      session-timeout-ms: 300000
      heartbeat-interval-ms: 10000
      request-timeout-ms: 30000
      retry-backoff-ms: 500
      fetch-max-bytes: 52428800
      max-partition-fetch-bytes: 1048576

    # --- Internal Producer Overrides (inside streams topology) ---
    producer:
      acks: all
      linger-ms: 5
      batch-size: 65536
      buffer-memory: 67108864
```

---

## 1.2 Streams Core Properties — Explained

| Property | Value | Why |
|----------|-------|-----|
| `application-id` | `payments-stream-v1` | Consumer group ID for Kafka Streams. All pods with the same ID share partitions. Append version suffix (`-v1`) to force fresh offsets during schema/topology changes |
| `processing-guarantee` | `at_least_once` | Offsets are committed periodically after processing. If the app crashes or rebalances before the next commit, the same input records can be replayed. Use idempotent downstream handling or dedupe keys for financial data |
| `num-stream-threads` | `1` | One stream thread per pod. KEDA scales pods horizontally, not threads. More threads per pod = more rebalance complexity for no benefit |
| `commit-interval-ms` | `1000` | Offset commit frequency. Lower = less re-processing on crash. Higher = better throughput. 1s is ideal for financial data |
| `state-dir` | `/tmp/kafka-streams` | Kafka Streams creates subdirs per `application.id`. In K8s, `emptyDir` is mounted here because `readOnlyRootFilesystem: true` blocks `/tmp` writes |
| `replication-factor` | `3` | Replication for internal topics (changelogs, repartition). Match your broker's replication factor |
| `num-standby-replicas` | `0` | No standby replicas needed — this topology is stateless (no state stores / KTables). Set to `1+` if you add stateful operations later |
| `max-task-idle-ms` | `1000` | When a task has multiple input partitions, wait up to 1s for data from all partitions before processing. Prevents lopsided processing across partitions |

---

## 1.3 Static Membership — Explained

### Kafka 3.9 Rebalancing Mode — Enforce Cooperative, Ban `upgrade.from`

In Kafka Streams 3.9, cooperative rebalancing is the normal path **as long as `upgrade.from` is not set**. The `upgrade.from` config is a **temporary compatibility flag for rolling upgrades** from older Kafka Streams versions. If it is left behind after an upgrade, Kafka Streams may stay on the older eager rebalance path, which revokes more work up front and is much more disruptive during scale events.

This service enforces cooperative behavior defensively:

- `KafkaStreamsConfig.java` builds the Streams config map manually and does **not** bind any `upgrade.from` property
- Startup now **fails fast** if any of these properties are present: `upgrade.from`, `spring.kafka.streams.properties.upgrade.from`, `app.stream.upgrade-from`, or `app.stream.upgrade.from`
- This means there is no supported way in this service to opt back into the compatibility path by accident

> **Important:** There is no separate `partition.assignment.strategy` switch you need to set for Kafka Streams here. In this codebase, the only practical way to regress away from cooperative rebalancing is to reintroduce `upgrade.from`.

These three properties work together to prevent rebalance storms during KEDA scale-up/down:

| Property | Value | Why |
|----------|-------|-----|
| `group-instance-id` | `${HOSTNAME:local-dev-instance}` | Static group membership — each pod's hostname becomes its permanent member identity. When a pod restarts with the same ID, Kafka skips rebalance and instantly reassigns its old partitions. **Set via `HOSTNAME` env var in K8s.** Falls back to `local-dev-instance` locally |
| `internal-leave-group-on-close` | `false` | Don't send a `LeaveGroup` request when the consumer shuts down gracefully. The group coordinator waits `session-timeout-ms` (5 min) before reassigning partitions. This gives KEDA time to scale down without triggering unnecessary rebalances |
| `session-timeout-ms` | `300000` (5 min) | How long Kafka waits with no heartbeat before declaring the consumer dead and reassigning partitions. 5 min matches KEDA's cooldown period — giving a replacement pod time to start and reclaim the same `group.instance.id` without partition shuffling |

**K8s note:** With **Deployments** (not StatefulSets), pod names change on restart. This means the `group.instance.id` changes too, and the consumer gets a new identity. The old identity times out after `session-timeout-ms`. If you need guaranteed same-hostname-on-restart, use `StatefulSet` instead.

---

## 1.4 Consumer Overrides — Explained

These properties configure the Kafka Streams **internal consumer** that reads from `payments.input`:

| Property | Value | Why |
|----------|-------|-----|
| `auto-offset-reset` | `latest` | When the consumer group has no committed offset (first join or reset), start from the latest message. Don't replay historical data. Use `earliest` only if you need full replay on fresh deployment |
| `isolation-level` | `read_committed` | If upstream producers use Kafka transactions, hide aborted writes so the stream does not process records that were never committed. This does **not** stop replay duplicates from `at_least_once`; it only filters aborted transactional input |
| `max-poll-records` | `250` | Max records returned per `poll()` call. 250 keeps processing loops responsive and gives meaningful progress between commits. Too high = long processing gaps between heartbeats |
| `max-poll-interval-ms` | `300000` (5 min) | Max time between `poll()` calls before Kafka kicks the consumer from the group. Must be greater than the longest possible batch processing time. 5 min matches `session-timeout-ms` for consistent behavior |
| `session-timeout-ms` | `300000` (5 min) | How long Kafka waits without a heartbeat before declaring the consumer dead. Coordinated with KEDA cooldown — prevents premature rebalance during scale events |
| `heartbeat-interval-ms` | `10000` (10 sec) | How often the consumer sends "I'm alive" to the group coordinator. **Rule: must be < `session-timeout-ms` / 3.** 10s < 100s ✓ — gives 30 heartbeat opportunities per session window |
| `request-timeout-ms` | `30000` (30 sec) | Timeout for individual fetch requests to the broker. Most fetches return in <100ms. 30s handles slow brokers |
| `retry-backoff-ms` | `500` | Wait 500ms between retry attempts for failed requests. Prevents hammering a struggling broker |
| `fetch-max-bytes` | `52428800` (50 MB) | Max data returned per fetch across all partitions. 50MB is generous for batch processing |
| `max-partition-fetch-bytes` | `1048576` (1 MB) | Max data per partition per fetch. 1MB aligns with Kafka's default `message.max.bytes` |

---

## 1.5 Internal Producer Overrides — Explained

Kafka Streams has an **internal producer** for repartition/changelog/output work inside the topology. With `at_least_once`, it is a normal producer, not a transactional exactly-once boundary. These overrides tune that internal producer:

| Property | Value | Why |
|----------|-------|-----|
| `producer.acks` | `all` | All in-sync replicas must acknowledge. Zero data loss for financial data |
| `producer.linger-ms` | `5` | Batch records for 5ms before sending. Improves throughput with negligible latency |
| `producer.batch-size` | `65536` (64 KB) | Max batch size. Good balance for 1-2KB financial events. Larger = fewer network calls |
| `producer.buffer-memory` | `67108864` (64 MB) | Total buffer for unsent records. 64MB provides ample headroom for 3M trades/day |

> **Note:** This internal producer is NOT the same as the standalone `KafkaTemplate` producer in Section 2. The internal producer is managed entirely by Kafka Streams — you don't interact with it directly.

---

## 1.6 KafkaStreamsConfig.java — Full Code

This is the central config class. It creates the `StreamsBuilderFactoryBean` with all properties above and wires the stream topology.

```java
@Configuration
@EnableConfigurationProperties(BreakerControlProperties.class)
public class KafkaStreamsConfig {

    @Bean(name = "paymentsStreamsConfiguration")
    public KafkaStreamsConfiguration paymentsStreamsConfiguration(
            @Value("${spring.kafka.bootstrap-servers:localhost:9092}") String bootstrapServers,
            @Value("${app.stream.application-id:payments-stream-v1}") String applicationId,
            @Value("${app.stream.processing-guarantee:at_least_once}") String processingGuarantee,
            @Value("${app.stream.num-stream-threads:1}") int numStreamThreads,
            @Value("${app.stream.commit-interval-ms:1000}") int commitIntervalMs,
            @Value("${app.stream.state-dir:/tmp/kafka-streams}") String stateDir,
            @Value("${app.stream.replication-factor:3}") int replicationFactor,
            @Value("${app.stream.num-standby-replicas:0}") int numStandbyReplicas,
            @Value("${app.stream.max-task-idle-ms:1000}") long maxTaskIdleMs,
            @Value("${app.stream.group-instance-id:#{null}}") String groupInstanceId,
            @Value("${app.stream.internal-leave-group-on-close:false}") boolean internalLeaveGroupOnClose,
            @Value("${app.stream.consumer.auto-offset-reset:latest}") String autoOffsetReset,
            @Value("${app.stream.consumer.max-poll-records:250}") int maxPollRecords,
            @Value("${app.stream.consumer.max-poll-interval-ms:300000}") int maxPollIntervalMs,
            @Value("${app.stream.consumer.session-timeout-ms:300000}") int sessionTimeoutMs,
            @Value("${app.stream.consumer.heartbeat-interval-ms:10000}") int heartbeatIntervalMs,
            @Value("${app.stream.consumer.request-timeout-ms:30000}") int requestTimeoutMs,
            @Value("${app.stream.consumer.retry-backoff-ms:500}") int retryBackoffMs,
            @Value("${app.stream.consumer.fetch-max-bytes:52428800}") int fetchMaxBytes,
            @Value("${app.stream.consumer.max-partition-fetch-bytes:1048576}") int maxPartitionFetchBytes,
            @Value("${app.stream.producer.acks:all}") String producerAcks,
            @Value("${app.stream.producer.linger-ms:5}") int producerLingerMs,
            @Value("${app.stream.producer.batch-size:65536}") int producerBatchSize,
            @Value("${app.stream.producer.buffer-memory:67108864}") long producerBufferMemory) {

        Map<String, Object> props = new HashMap<>();

        // --- Core Streams Config ---
        props.put(StreamsConfig.APPLICATION_ID_CONFIG, applicationId);
        props.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(StreamsConfig.PROCESSING_GUARANTEE_CONFIG, processingGuarantee);
        props.put(StreamsConfig.NUM_STREAM_THREADS_CONFIG, numStreamThreads);
        props.put(StreamsConfig.COMMIT_INTERVAL_MS_CONFIG, commitIntervalMs);
        props.put(StreamsConfig.STATE_DIR_CONFIG, stateDir);
        props.put(StreamsConfig.REPLICATION_FACTOR_CONFIG, replicationFactor);
        props.put(StreamsConfig.NUM_STANDBY_REPLICAS_CONFIG, numStandbyReplicas);
        props.put(StreamsConfig.MAX_TASK_IDLE_MS_CONFIG, maxTaskIdleMs);
        props.put(StreamsConfig.DEFAULT_DESERIALIZATION_EXCEPTION_HANDLER_CLASS_CONFIG,
                CustomDeserializationExceptionHandler.class);

        // --- Static Membership ---
        if (groupInstanceId != null && !groupInstanceId.isBlank()) {
            props.put("group.instance.id", groupInstanceId);
        }
        props.put("internal.leave.group.on.close", internalLeaveGroupOnClose);

        // --- Consumer Overrides ---
        props.put("main.consumer.auto.offset.reset", autoOffsetReset);
        props.put("consumer.max.poll.records", maxPollRecords);
        props.put("consumer.max.poll.interval.ms", maxPollIntervalMs);
        props.put("consumer.session.timeout.ms", sessionTimeoutMs);
        props.put("consumer.heartbeat.interval.ms", heartbeatIntervalMs);
        props.put("consumer.request.timeout.ms", requestTimeoutMs);
        props.put("consumer.retry.backoff.ms", retryBackoffMs);
        props.put("consumer.fetch.max.bytes", fetchMaxBytes);
        props.put("consumer.max.partition.fetch.bytes", maxPartitionFetchBytes);

        // --- Internal Producer Overrides ---
        props.put("producer.acks", producerAcks);
        props.put("producer.linger.ms", producerLingerMs);
        props.put("producer.batch.size", producerBatchSize);
        props.put("producer.buffer.memory", producerBufferMemory);

        return new KafkaStreamsConfiguration(props);
    }

    @Bean(name = "paymentsStreamsBuilder")
    public StreamsBuilderFactoryBean paymentsStreamsBuilder(
            KafkaStreamsConfiguration kafkaStreamsConfiguration,
            StreamFatalExceptionHandler uncaughtExceptionHandler) {
        StreamsBuilderFactoryBean factoryBean = new StreamsBuilderFactoryBean(kafkaStreamsConfiguration);
        factoryBean.setStreamsUncaughtExceptionHandler(uncaughtExceptionHandler);
        return factoryBean;
    }

    @Bean
    public KStream<String, InputEvent> paymentsStream(
            StreamsBuilder streamsBuilder,
            PaymentsRecordProcessorSupplier processorSupplier,
            @Value("${app.input.topic:payments.input}") String inputTopic) {
        JsonSerde<InputEvent> inputSerde = new JsonSerde<>(InputEvent.class);
        KStream<String, InputEvent> stream = streamsBuilder.stream(
                inputTopic, Consumed.with(Serdes.String(), inputSerde));
        stream.process(processorSupplier::get);
        return stream;
    }
}
```

---

## 1.7 Stream Topology Diagram

```
payments.input (48 partitions)
        │
        ▼
┌──────────────────────────┐
│  Kafka Streams Consumer  │  ← auto-offset-reset: latest
│  (internal, managed)     │  ← max-poll-records: 250
│                          │  ← session-timeout: 5 min
│  Deserialization ────────│──→ Bad data? → CONTINUE (log + audit)
│                          │
│  ┌────────────────────┐  │
│  │ PaymentsRecord     │  │
│  │ ProcessorSupplier  │  │  ← Your business logic
│  │                    │  │
│  │ success → output   │  │
│  │ failure → log/DLT  │  │
│  └────────────────────┘  │
│                          │
│  Internal Producer ──────│──→ payments.output
│  (exactly_once_v2)       │  ← acks: all, linger: 5ms, batch: 64KB
└──────────────────────────┘
        │
        ▼
  Uncaught exception?
        │
   ┌────┴────┐
   │Recover? │
   ├─YES─────┤→ REPLACE_THREAD (RetriableException, TimeoutException, DisconnectException)
   └─NO──────┘→ SHUTDOWN_CLIENT + LivenessState.BROKEN → pod restart
```

---

---

# SECTION 2: Kafka Plain Producer Configuration

> **Config class:** `KafkaProducerConfig.java`  
> **YAML path:** `spring.kafka.producer.*`  
> **What it does:** Creates a standalone `KafkaTemplate<String, OutputEvent>` for writing processed results. This is a **separate transactional producer** — not the Kafka Streams internal producer.

---

## 2.1 application.yml — Plain Producer Properties

```yaml
spring:
  kafka:
    bootstrap-servers: localhost:9092
    producer:
      acks: all
      enable-idempotence: true
      max-in-flight-requests-per-connection: 5
      retries: 2147483647
      linger-ms: 5
      batch-size: 65536
      buffer-memory: 67108864
      delivery-timeout-ms: 120000
      request-timeout-ms: 30000
      max-block-ms: 60000
      compression-type: lz4
      transactional-id-prefix: payments-producer-${random.uuid}-
      transaction-timeout-ms: 60000
```

---

## 2.2 Durability & Reliability Properties — Explained

| Property | Value | Why |
|----------|-------|-----|
| `acks` | `all` | Message acknowledged by ALL in-sync replicas before the producer considers it committed. Guarantees zero data loss even if a broker dies immediately after ack. **Always `all` for financial data.** Alternative: `1` = leader-only ack (faster, but risks data loss on leader failure) |
| `enable-idempotence` | `true` | Broker assigns a unique Producer ID (PID) and sequence number to each message. If the producer retries a send (e.g., network timeout), the broker detects the duplicate sequence number and silently discards it. **Prevents retry-duplicates.** Default since Kafka 3.0. Requires: `acks=all`, `retries>0`, `max-in-flight<=5` |
| `max-in-flight-requests-per-connection` | `5` | Max unacknowledged requests per broker connection. With idempotence, Kafka guarantees ordering even with up to 5 in-flight — it rejects out-of-order sequences. `5` is the maximum allowed with idempotence and provides good throughput |
| `retries` | `2147483647` | Effectively infinite retries. The producer retries until `delivery-timeout-ms` expires. Combined with idempotence, retries are safe — broker deduplicates via sequence numbers |

---

## 2.3 Timeout Properties — Explained

| Property | Value | Why |
|----------|-------|-----|
| `delivery-timeout-ms` | `120000` (2 min) | Total time allowed for a message to be delivered, including all retries. If a message cannot be acknowledged within this window, `send()` fails with `TimeoutException`. 2 min gives headroom for transient broker issues. Must be `>= request-timeout-ms + linger-ms` |
| `request-timeout-ms` | `30000` (30 sec) | Timeout for a single produce request to the broker. Most acks return in <100ms. If a broker is unresponsive beyond 30s, the request fails and is retried |
| `max-block-ms` | `60000` (60 sec) | Max time `send()` blocks when the buffer is full or metadata is unavailable. If still blocked after 60s, throws `TimeoutException` |

---

## 2.4 Batching & Throughput Properties — Explained

| Property | Value | Why |
|----------|-------|-----|
| `linger-ms` | `5` | Wait 5ms to accumulate records into batches before sending. Increases throughput (more records per batch) with negligible latency impact. Default is `0` (send immediately) — wasteful for high-throughput streams |
| `batch-size` | `65536` (64 KB) | Max size of a single batch. 64KB is a good balance for financial events (~1-2KB each). Default is 16KB — too small for 3M trades/day. Larger batches = fewer network calls |
| `buffer-memory` | `67108864` (64 MB) | Total memory for all unsent records. If the buffer fills up, `send()` blocks for up to `max-block-ms`. 64MB provides ample headroom. Increase if you see `BufferExhaustedException` |
| `compression-type` | `lz4` | Compression applied to each batch. Reduces network bandwidth and broker disk usage by ~40-60%. **lz4 = fastest, lowest CPU overhead.** Options: `none`, `gzip` (best ratio, slow), `snappy` (fast, good ratio), `lz4` (fastest), `zstd` (best overall) |

---

## 2.5 Transactional Producer Properties — Explained

| Property | Value | Why |
|----------|-------|-----|
| `transactional-id-prefix` | `payments-producer-${random.uuid}-` | Enables Kafka transactions for the **standalone `KafkaTemplate` producer only**. Spring appends a unique suffix per producer instance. `${random.uuid}` guarantees globally unique IDs across all pods/restarts |
| `transaction-timeout-ms` | `60000` (60 sec) | How long broker waits before force-aborting a pending transaction for that standalone producer. Must be `<=` broker's `transaction.max.timeout.ms` (default 900s) |

**Why `${random.uuid}` instead of `${HOSTNAME}`:**
- With **Deployments** (not StatefulSets), pod names change on every restart
- Zombie fencing requires the **same** `transactional.id` to be reused — broken with Deployment
- UUID is honest: guaranteed uniqueness, no false sense of fencing
- If you switch to StatefulSet, consider `${HOSTNAME}` for real zombie fencing

**What these transactions do and do NOT do:**
- They apply only to the separate plain producer in `KafkaProducerConfig.java`
- They do **not** control Kafka Streams consumer offset commits
- They do **not** prevent input replay when the Streams app runs with `at_least_once`
- They only guarantee that this producer's own writes are committed or aborted as a unit

**Impact in this architecture:**
- If the producer transaction commits and Kafka Streams has not yet committed the input offset, a restart or rebalance can replay the same input record
- That replay can call the producer again and emit a duplicate output record unless downstream processing is idempotent or deduplicated

**Orphan cleanup:** Orphaned transactional IDs from crashed pods are auto-cleaned by the broker after `transactional.id.expiration.ms` (default 7 days). No manual intervention needed.

---

## 2.6 KafkaProducerConfig.java — Full Code

```java
@Configuration
public class KafkaProducerConfig {

    private static final Logger log = LoggerFactory.getLogger(KafkaProducerConfig.class);

    @Bean
    public ProducerFactory<String, OutputEvent> producerFactory(
            @Value("${spring.kafka.bootstrap-servers:localhost:9092}") String bootstrapServers,
            @Value("${spring.kafka.producer.acks:all}") String acks,
            @Value("${spring.kafka.producer.enable-idempotence:true}") boolean enableIdempotence,
            @Value("${spring.kafka.producer.max-in-flight-requests-per-connection:5}") int maxInFlight,
            @Value("${spring.kafka.producer.retries:2147483647}") int retries,
            @Value("${spring.kafka.producer.delivery-timeout-ms:120000}") int deliveryTimeoutMs,
            @Value("${spring.kafka.producer.request-timeout-ms:30000}") int requestTimeoutMs,
            @Value("${spring.kafka.producer.max-block-ms:60000}") long maxBlockMs,
            @Value("${spring.kafka.producer.linger-ms:5}") int lingerMs,
            @Value("${spring.kafka.producer.batch-size:65536}") int batchSize,
            @Value("${spring.kafka.producer.buffer-memory:67108864}") long bufferMemory,
            @Value("${spring.kafka.producer.compression-type:snappy}") String compressionType,
            @Value("${spring.kafka.producer.transaction-timeout-ms:60000}") int transactionTimeoutMs,
            @Value("${spring.kafka.producer.transactional-id-prefix:payments-producer-${random.uuid}-}") String transactionalIdPrefix) {

        Map<String, Object> config = new HashMap<>();

        // --- Connection ---
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);

        // --- Serialization ---
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);

        // --- Durability ---
        config.put(ProducerConfig.ACKS_CONFIG, acks);
        config.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, enableIdempotence);
        config.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, maxInFlight);
        config.put(ProducerConfig.RETRIES_CONFIG, retries);

        // --- Timeouts ---
        config.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, deliveryTimeoutMs);
        config.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, requestTimeoutMs);
        config.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, maxBlockMs);

        // --- Batching & Throughput ---
        config.put(ProducerConfig.LINGER_MS_CONFIG, lingerMs);
        config.put(ProducerConfig.BATCH_SIZE_CONFIG, batchSize);
        config.put(ProducerConfig.BUFFER_MEMORY_CONFIG, bufferMemory);
        config.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, compressionType);

        // --- Transactions ---
        config.put(ProducerConfig.TRANSACTION_TIMEOUT_CONFIG, transactionTimeoutMs);

        DefaultKafkaProducerFactory<String, OutputEvent> factory =
                new DefaultKafkaProducerFactory<>(config);
        factory.setTransactionIdPrefix(transactionalIdPrefix);

        log.info("Transactional producer factory created with prefix '{}', acks={}, compression={}",
                transactionalIdPrefix, acks, compressionType);

        return factory;
    }

    @Bean
    public KafkaTemplate<String, OutputEvent> kafkaTemplate(
            ProducerFactory<String, OutputEvent> producerFactory) {
        return new KafkaTemplate<>(producerFactory);
    }
}
```

---

---

# SECTION 3: Exception Handling (Shared)

Both the Kafka Streams consumer and the plain producer rely on the same 3-tier exception strategy.

---

## 3.1 Tier 1: Deserialization Errors → Log + Continue

```java
// CustomDeserializationExceptionHandler.java
// NOT a Spring @Component — Kafka Streams instantiates it via class reference.
// Uses ApplicationContextProvider to get Spring beans.
public class CustomDeserializationExceptionHandler implements DeserializationExceptionHandler {

    @Override
    public DeserializationHandlerResponse handle(ProcessorContext context,
                                                 ConsumerRecord<byte[], byte[]> record,
                                                 Exception exception) {
        log.warn("Deserialization error - topic={}, partition={}, offset={}, error={}",
            record.topic(), record.partition(), record.offset(), exception.getMessage());

        auditService.logDeserializationFailure(...);

        // CONTINUE — don't crash the stream for bad data.
        // This does NOT count toward circuit breaker (different concern).
        return DeserializationHandlerResponse.CONTINUE;
    }
}
```

## 3.2 Tier 2: Business Logic Errors → Processor Handles

Your `PaymentsRecordProcessorSupplier` catches business exceptions inside `process()`. In stage 1 (no circuit breaker), just log and send to exception topic.

## 3.3 Tier 3: Uncaught/Fatal Errors → Classify + Restart

```java
// StreamFatalExceptionHandler.java
@Component
public class StreamFatalExceptionHandler implements StreamsUncaughtExceptionHandler {

    private final ApplicationEventPublisher eventPublisher;

    private static final Set<Class<? extends Throwable>> RECOVERABLE_TYPES = Set.of(
            TimeoutException.class,
            DisconnectException.class
    );

    @Override
    public StreamThreadExceptionResponse handle(Throwable exception) {
        Throwable root = getRootCause(exception);

        if (isRecoverable(root)) {
            // REPLACE_THREAD: kills failed thread, spins up replacement.
            // Partitions stay assigned — no consumer group rebalance.
            log.warn("Recoverable — replacing thread. Root: {}", root.getMessage(), exception);
            return StreamThreadExceptionResponse.REPLACE_THREAD;
        }

        // SHUTDOWN_CLIENT: stops all stream threads. JVM stays alive but broken.
        log.error("Fatal — SHUTDOWN_CLIENT. Root: {}", root.getMessage(), exception);

        // Publish BROKEN so /actuator/health/liveness returns 503 → K8s restarts pod
        AvailabilityChangeEvent.publish(eventPublisher, this, LivenessState.BROKEN);

        return StreamThreadExceptionResponse.SHUTDOWN_CLIENT;
    }

    private boolean isRecoverable(Throwable root) {
        if (root instanceof RetriableException) return true;
        return RECOVERABLE_TYPES.contains(root.getClass());
    }

    private Throwable getRootCause(Throwable throwable) {
        Throwable root = throwable;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root;
    }
}
```

---

# SECTION 4: Graceful Shutdown

```java
// KafkaStreamsShutdownHook.java
@Component
public class KafkaStreamsShutdownHook {
    private static final long SHUTDOWN_GRACE_PERIOD_MS = 60_000;  // 60 seconds

    public KafkaStreamsShutdownHook(
            @Qualifier("&paymentsStreamsBuilder") StreamsBuilderFactoryBean streamsBuilderFactoryBean) {
        this.streamsBuilderFactoryBean = streamsBuilderFactoryBean;
        Runtime.getRuntime().addShutdownHook(new Thread(this::gracefulShutdown, "kafka-shutdown-hook"));
    }

    private void gracefulShutdown() {
        streamsBuilderFactoryBean.stop();
        Thread.sleep(SHUTDOWN_GRACE_PERIOD_MS);
    }
}
```

**K8s shutdown sequence:**
1. Kubelet sends `preStop` → `sleep 90s` (drain in-flight)
2. After 90s, `SIGTERM` → JVM shutdown hook → `stop()` + 60s wait
3. If still alive at 120s → `SIGKILL`

---

# SECTION 5: Application Entry Point

```java
@SpringBootApplication
@EnableScheduling    // Required for @Scheduled recovery tasks (future circuit breaker)
public class FinancialStreamApplication {

    // Java 21: DNS TTL defaults to 30s (SecurityManager deprecated, not active).
    // No Security.setProperty() needed. DNS refreshes every 30s for Confluent Cloud failover.

    public static void main(String[] args) {
        SpringApplication.run(FinancialStreamApplication.class, args);
    }
}
```

---

## Key Design Decisions Summary

> **Current project default:** `processing-guarantee=at_least_once`. If any older row below still mentions `exactly_once_v2`, treat it as historical guidance rather than the active runtime setting.

| Decision | Choice | Why |
|----------|--------|-----|
| `processing-guarantee` | `exactly_once_v2` | Financial data — no duplicates, no loss |
| `compression-type` | `lz4` (YAML) / `snappy` (KafkaProducerConfig default) | Fastest for streams throughput / best balance for standalone |
| `num-stream-threads` | `1` | KEDA scales pods, not threads. Simpler rebalancing |
| `session-timeout-ms` | `300000` (5 min) | Matches KEDA cooldown — no premature rebalance |
| `group.instance.id` | `${HOSTNAME}` | Static membership — returning pods skip rebalance |
| `auto-offset-reset` | `latest` | Don't replay history on fresh consumer join |
| Exception strategy | Classify → REPLACE_THREAD or SHUTDOWN_CLIENT | Transient errors don't trigger full rebalance |
| Transactional ID | `${random.uuid}` | Deployment (not StatefulSet) — UUID avoids collision |

---

## Files to Create

```
src/main/java/com/example/financialstream/
├── FinancialStreamApplication.java          # Entry point
├── config/
│   ├── KafkaStreamsConfig.java              # Kafka Streams consumer + internal producer config
│   └── KafkaProducerConfig.java             # Standalone KafkaTemplate producer config
├── kafka/
│   ├── StreamFatalExceptionHandler.java     # Uncaught exception classifier
│   ├── CustomDeserializationExceptionHandler.java  # Bad data handler
│   └── PaymentsRecordProcessorSupplier.java # Your business logic (implement this)
├── model/
│   ├── InputEvent.java                      # Input record POJO
│   └── OutputEvent.java                     # Output record POJO
├── shutdown/
│   └── KafkaStreamsShutdownHook.java         # Graceful shutdown
└── util/
    └── ApplicationContextProvider.java      # Spring context for non-bean classes
```

---

---

# SECTION 6: Performance Tuning for Stateless Topologies

> **Applies when:** Your Kafka Streams topology does NOT use windowing, aggregation, joins, or state stores.  
> **Pattern:** Read from input topic → process each record → write to output (Kafka or DB).

---

## 6.1 What to Disable — No Windowing / No State Stores

When your topology is stateless (no `KTable`, no `.aggregate()`, no `.windowedBy()`, no `StateStore`), several Kafka Streams features add **zero value** but still consume CPU, memory, and I/O.

| Setting | Stateless Value | Default | Why Disable |
|---------|----------------|---------|-------------|
| `statestore.cache.max.bytes` | `0` | `10485760` (10 MB) | The record cache buffers state store updates before flushing to RocksDB. No state stores = cache is never used but **memory is still reserved**. Setting to `0` eliminates the allocation |
| `num.standby.replicas` | `0` | `0` | Standby replicas maintain shadow copies of state stores on other pods so failover is fast. No state stores = nothing to replicate |
| `max.task.idle.ms` | `0` | `0` | Controls how long a task waits for data on all co-partitioned inputs before processing. Only relevant with joins across multiple input topics. `0` = process immediately, don't wait |
| `topology.optimization` | `all` | `none` | Lets Kafka Streams merge unnecessary repartition topics and reduce internal topic overhead. Small win for stateless, but **no downside** to enabling |
| `rocksdb.config.setter` | Don't set | `null` | RocksDB is only initialized when you have persistent state stores. Without state stores, RocksDB is never loaded — don't add a config setter |
| `state.cleanup.delay.ms` | Don't change | `600000` (10 min) | How long to wait before deleting abandoned state directories. With no state stores, nothing accumulates — leave as default |

### application.properties — Stateless Overrides

```properties
# ── Disable unused stateful features ──
spring.kafka.streams.properties.statestore.cache.max.bytes=0
spring.kafka.streams.properties.num.standby.replicas=0
spring.kafka.streams.properties.max.task.idle.ms=0
spring.kafka.streams.properties.topology.optimization=all
```

### application.yml — Stateless Overrides

```yaml
app:
  stream:
    num-standby-replicas: 0
    max-task-idle-ms: 0

# These must go under spring.kafka.streams.properties (no app.stream shortcut):
spring:
  kafka:
    streams:
      properties:
        statestore.cache.max.bytes: 0
        topology.optimization: all
```

---

## 6.2 Processing Guarantee — `exactly_once_v2` vs `at_least_once`

This is the **single biggest performance lever** for a stateless topology.

### What `exactly_once_v2` Actually Does

| Operation | Per-Task Overhead | When It Happens |
|-----------|------------------|-----------------|
| `initTransactions()` | ~200ms | Once per partition assignment (on startup + every rebalance) |
| `beginTransaction()` | ~1-5ms | Every `commit.interval.ms` (default: 30,000ms for EOS) |
| `commitTransaction()` (2PC) | ~50-100ms | Every `commit.interval.ms` — two-phase commit to brokers |
| Fencing producers | ~100-500ms | Every rebalance — old producer instances are fenced out |
| Per-task producer | Memory + connections | One transactional producer per input partition |

**Total overhead:** With 48 partitions, EOS maintains 48 separate transactional producers, each doing 2PC commits every 30 seconds.

### When to Use Which

| Scenario | Guarantee | Why |
|----------|-----------|-----|
| Kafka-to-Kafka with state stores (joins, aggregations) | `exactly_once_v2` | EOS atomically commits offsets + state store changelogs + output records. **No other way to get consistency.** |
| Kafka-to-Kafka, stateless, duplicates are unacceptable | `exactly_once_v2` | Output topic consumers get no duplicates. Acceptable if throughput is sufficient |
| Kafka → Database (stateless) | `at_least_once` | EOS only protects Kafka output topics. **Your DB doesn't participate in Kafka transactions.** Use idempotent DB upserts (e.g., `INSERT ... ON CONFLICT DO UPDATE`) instead |
| Kafka → Database, high throughput required | `at_least_once` | **2-5x throughput improvement** over EOS. DB idempotency handles duplicates |

### Switching to `at_least_once`

```properties
# ── Switch from exactly_once_v2 to at_least_once ──
spring.kafka.streams.properties.processing.guarantee=at_least_once

# With at_least_once, commit.interval.ms defaults to 100ms (not 30s).
# Tune based on your throughput needs:
#   100ms  = low latency, frequent commits (more broker overhead)
#   1000ms = good balance for 3M trades/day  
#   5000ms = high throughput, but 5s of uncommitted work at risk on crash
spring.kafka.streams.properties.commit.interval.ms=1000
```

> **Important:** If you switch to `at_least_once`, your downstream consumer/DB **must handle duplicates**. For databases, use upserts or deduplication keys. For Kafka consumers, use `isolation.level=read_uncommitted` (default) and deduplicate at the application level.

---

## 6.3 Consumer Fetch Tuning — Reduce Empty Polls

By default, the consumer returns immediately even if no data is available, causing **busy-wait polling** that wastes CPU.

| Setting | Recommended | Default | Why |
|---------|-------------|---------|-----|
| `fetch.min.bytes` | `1` (for low latency) or `1024`-`16384` (for throughput) | `1` | Minimum data the broker accumulates before responding to a fetch. Higher = fewer fetches, better batching, but higher latency |
| `fetch.max.wait.ms` | `500` | `500` | Max time broker waits to fill `fetch.min.bytes` before responding anyway. Only matters if `fetch.min.bytes > 1` |

### For Maximum Throughput (3M trades/day)

```properties
# Tell broker: "don't respond until you have at least 16KB of data OR 500ms has passed"
spring.kafka.streams.properties.consumer.fetch.min.bytes=16384
spring.kafka.streams.properties.consumer.fetch.max.wait.ms=500
```

### For Low Latency (sub-second)

```properties
# Return immediately when any data is available (default behavior)
spring.kafka.streams.properties.consumer.fetch.min.bytes=1
```

---

## 6.4 Thread Scaling — `num.stream.threads` vs Pod Scaling

| Approach | When to Use | Trade-off |
|----------|-------------|-----------|
| `num.stream.threads=1` + KEDA pod scaling | **Recommended for AKS** | Each pod = 1 thread = simple rebalancing. KEDA adds pods based on consumer lag. Predictable resource usage per pod |
| `num.stream.threads=N` (N > 1) | High partition count, fewer pods | More partitions per pod. Reduces pod count but complicates resource limits. A single slow thread blocks all threads in the same `poll()` call |

**Rule of thumb:** `num.stream.threads × num_pods ≤ num_partitions`. More threads than partitions = idle threads wasting memory.

If KEDA scaling is too slow and you need immediate throughput:
```properties
# 2-4 threads per pod, let each thread handle separate partitions
spring.kafka.streams.properties.num.stream.threads=2
```

---

## 6.5 `commit.interval.ms` — The Hidden Bottleneck

| Processing Guarantee | Default `commit.interval.ms` | Impact |
|---------------------|------------------------------|--------|
| `exactly_once_v2` | `30000` (30 sec) | Each commit = full 2-phase commit. 30s means large transaction batches — good for throughput, bad for latency |
| `at_least_once` | `100` (100 ms) | Lightweight offset commit. 100ms = frequent commits, low data-at-risk, but high broker overhead |

### Tuning Guide

```properties
# ── For at_least_once with high throughput ──
# Commit every 1 second. Balances throughput with data-at-risk:
#   - On crash, at most 1 second of records are reprocessed
#   - Reduces commit overhead 10x vs default 100ms
spring.kafka.streams.properties.commit.interval.ms=1000

# ── For exactly_once_v2 (if you must keep EOS) ──
# Default 30s is already optimized. Don't go lower than 5000ms — 
# each 2PC commit has fixed overhead (~50-100ms).
spring.kafka.streams.properties.commit.interval.ms=30000
```

---

## 6.6 Output Producer — Blocking Send Anti-Pattern

If your processor sends to an output topic using a separate `KafkaTemplate` (not the Kafka Streams internal producer), check for this pattern:

```java
// ❌ ANTI-PATTERN: Blocking send per record inside processor
kafkaTemplate.executeInTransaction(ops -> {
    return ops.send(topic, key, value).get(30, TimeUnit.SECONDS);  // BLOCKS 30s per record!
});
```

**Why it's slow:**  Each record blocks until the broker acks. With 50ms avg send latency → max **20 records/sec per thread**. For 3M trades/day (104/sec), this alone makes the topology too slow.

### Fix Options

**Option A: Use Kafka Streams Internal Producer (Recommended)**

Let Kafka Streams handle the output. No separate `KafkaTemplate` needed:

```java
// In your Processor:
context.forward(new Record<>(key, outputEvent, context.currentSystemTimeMs()));
```

With a sink in your topology:
```java
stream.process(processorSupplier::get)
      .to("payments.output", Produced.with(Serdes.String(), outputSerde));
```

This uses the Kafka Streams internal producer, which:
- Batches records across the commit interval
- Commits atomically with consumer offsets (if EOS)
- Handles transactions automatically
- **No per-record blocking**

**Option B: Fire-and-Forget with Callback (If Separate Producer Required)**

```java
// ✅ Non-blocking — callback handles errors
kafkaTemplate.send(topic, key, value).whenComplete((result, ex) -> {
    if (ex != null) {
        log.error("Send failed for {}", key, ex);
        // Record failure in circuit breaker
    }
});
```

**Option C: Batch Collect + Flush**

Accumulate records in a batch, then flush:
```java
List<CompletableFuture<?>> futures = new ArrayList<>();
for (OutputEvent event : batch) {
    futures.add(kafkaTemplate.send(topic, event.eventId(), event));
}
kafkaTemplate.flush();  // Force send all batched records
CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get(30, SECONDS);
```

---

## 6.7 JSON Serde Optimization

Jackson JSON serialization adds ~0.5-2ms per record for 1-2KB payloads. For high throughput:

| Optimization | Effort | Impact |
|-------------|--------|--------|
| Reuse `ObjectMapper` | Low | Avoid creating new instances per record (Spring's `JsonSerde` already does this) |
| Disable unused Jackson features | Low | `mapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)` — avoids exception overhead on unknown fields |
| Pre-compiled `JavaType` | Low | Avoids reflection on every deser call. `JsonSerde` already handles this |
| Switch to Avro/Protobuf | High | 5-10x faster serialization. Requires schema registry. Only if JSON is a proven bottleneck |

---

## 6.8 Metrics Recording Level

```properties
# Default is INFO — records per-thread, per-task metrics. Fine for production.
# Set to DEBUG only for deep troubleshooting (adds per-node metrics — significant overhead).
spring.kafka.streams.properties.metrics.recording.level=INFO
```

---

## 6.9 Recommended Optimized Config — Stateless Trade Processor

Here's a complete optimized config for a stateless `trade-db-processor` that reads trades › processes › writes to DB or output topic:

```properties
# ═══════════════════════════════════════════════════════════════
# OPTIMIZED CONFIG — Stateless Kafka Streams (No Windowing/State)
# ═══════════════════════════════════════════════════════════════

# ── Core ──
spring.kafka.streams.application-id=trade-db-processor-v1
spring.kafka.bootstrap-servers=broker1:9092,broker2:9092,broker3:9092
spring.kafka.streams.properties.client.id=trade-db-processor

# ── Processing Guarantee ──
# at_least_once: 2-5x faster than exactly_once_v2 for stateless topologies.
# Use idempotent DB upserts to handle duplicates on crash recovery.
spring.kafka.streams.properties.processing.guarantee=at_least_once

# ── Commit Interval ──
# 1 second: good balance. On crash, at most 1s of records reprocessed.
spring.kafka.streams.properties.commit.interval.ms=1000

# ── Threads ──
# 1 thread per pod. KEDA handles horizontal scaling.
spring.kafka.streams.properties.num.stream.threads=1

# ── Disable Stateful Features ──
spring.kafka.streams.properties.statestore.cache.max.bytes=0
spring.kafka.streams.properties.num.standby.replicas=0
spring.kafka.streams.properties.max.task.idle.ms=0
spring.kafka.streams.properties.topology.optimization=all

# ── Deserialization ──
spring.kafka.streams.properties.default.deserialization.exception.handler=\
  org.apache.kafka.streams.errors.LogAndFailExceptionHandler

# ── Consumer Overrides ──
spring.kafka.streams.properties.consumer.max.poll.records=500
spring.kafka.streams.properties.consumer.auto.offset.reset=earliest
spring.kafka.streams.properties.consumer.fetch.min.bytes=16384
spring.kafka.streams.properties.consumer.fetch.max.wait.ms=500
spring.kafka.streams.properties.allow.auto.create.topics=false

# ── Static Membership (KEDA) ──
spring.kafka.streams.properties.group.instance.id=${HOSTNAME:local-dev-instance}
spring.kafka.streams.properties.internal.leave.group.on.close=false
spring.kafka.streams.properties.consumer.session.timeout.ms=300000
spring.kafka.streams.properties.consumer.max.poll.interval.ms=300000
spring.kafka.streams.properties.consumer.heartbeat.interval.ms=10000

# ── State Dir ──
spring.kafka.streams.state-dir=/var/lib/app/kafka-streams

# ── Serdes ──
spring.kafka.streams.properties.default.key.serde=\
  org.apache.kafka.common.serialization.Serdes$StringSerde
spring.kafka.streams.properties.default.value.serde=\
  com.example.kafka.TradeMessageSerde

# ── Metrics ──
spring.kafka.streams.properties.metrics.recording.level=INFO

# ── Security ──
spring.kafka.properties.security.protocol=SASL_SSL
spring.kafka.properties.sasl.mechanism=PLAIN
```

### What Changed vs Your Current Config

| Setting | Before | After | Impact |
|---------|--------|-------|--------|
| `processing.guarantee` | `exactly_once_v2` | `at_least_once` | **2-5x throughput**. No 2PC overhead. Requires idempotent DB writes |
| `commit.interval.ms` | Not set (default 30s for EOS) | `1000` | Explicit. 1s of data-at-risk on crash |
| `max.poll.records` | `100` | `500` | 5x more records per poll(). Less polling overhead |
| `session.timeout.ms` | `45000` | `300000` | Prevents rebalance storms with KEDA |
| `max.poll.interval.ms` | `900000` | `300000` | Matches session timeout. Consistent dead-consumer detection |
| `heartbeat.interval.ms` | Not set (default 3s) | `10000` | Explicit. < session.timeout / 3 |
| `statestore.cache.max.bytes` | `0` ✅ | `0` | Already correct |
| `max.task.idle.ms` | Not set (default 0) | `0` | Explicit. No waiting on co-partitioned inputs |
| `topology.optimization` | Not set (`none`) | `all` | Merges unnecessary internal topics |
| `fetch.min.bytes` | Not set (default 1) | `16384` | Reduces empty polls. Broker batches 16KB before responding |
| `fetch.max.wait.ms` | Not set (default 500) | `500` | Explicit. Max wait even if fetch.min.bytes not met |
| `metrics.recording.level` | Not set (`INFO`) | `INFO` | Explicit. Avoid accidental `DEBUG` overhead |

---

## 6.10 Performance Checklist

Before deploying, verify:

- [ ] **Processing guarantee matches your sink:** `at_least_once` if DB sink with idempotent writes; `exactly_once_v2` only if Kafka sink AND duplicates are unacceptable
- [ ] **No blocking `.get()` calls** in your processor. Use `context.forward()` or async sends
- [ ] **`statestore.cache.max.bytes=0`** — no memory wasted on unused cache
- [ ] **`num.standby.replicas=0`** — no standby overhead for stateless
- [ ] **`topology.optimization=all`** — let Kafka Streams optimize
- [ ] **`max.poll.records` ≥ 250** — don't starve the processor with tiny batches
- [ ] **Session timeout = 300s** and matches KEDA cooldown
- [ ] **Static membership** (`group.instance.id`) configured
- [ ] **`commit.interval.ms`** explicitly set (not relying on guarantee-specific defaults)
- [ ] **Serde is efficient** — Jackson JSON is fine for 1-2KB. Consider Avro/Protobuf if > 10KB or > 10M/day
