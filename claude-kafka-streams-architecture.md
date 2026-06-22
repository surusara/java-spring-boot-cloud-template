# Kafka Streams Architecture
## Trade Processing Platform — AKS / Java 21 / Spring Boot

---

## Table of Contents

1. [Why Kafka Streams Over Plain Kafka Consumer](#1-why-kafka-streams-over-plain-kafka-consumer)
2. [How Kafka Streams Works Internally](#2-how-kafka-streams-works-internally)
3. [EOS v2 — Exactly Once Semantics](#3-eos-v2--exactly-once-semantics)
4. [Architecture Overview](#4-architecture-overview)
5. [Processing Status Lineage Design](#5-processing-status-lineage-design)
6. [Topology Design — Conditional Branching](#6-topology-design--conditional-branching)
7. [Full Spring Boot Configuration](#7-full-spring-boot-configuration)
8. [State Store — Do You Need It?](#8-state-store--do-you-need-it)
9. [Performance Tuning — Disable What You Don't Need](#9-performance-tuning--disable-what-you-dont-need)
10. [Deployment on AKS](#10-deployment-on-aks)
11. [Operational Checklist](#11-operational-checklist)

---

## 1. Why Kafka Streams Over Plain Kafka Consumer

### The Core Problem With Plain KafkaConsumer + Manual Threading

Plain `KafkaConsumer` is **not thread-safe**. Every consumer instance must be owned and polled by exactly one thread. When you need parallel processing, you face a hard design problem:

```
Plain KafkaConsumer — Threading Problem:

Thread-1 (poll thread)    → polls records → hands off to worker pool
Thread-2..N (workers)     → process records

Problem: max.poll.interval.ms starts ticking from last poll().
         If workers are slow, poll() is late → broker thinks
         consumer is dead → REBALANCE → all partitions reassigned
         → duplicate processing of in-flight records
```

To avoid this, you must tune `max.poll.interval.ms` to be longer than your worst-case processing time AND batch size. This creates fragile coupling between your processing SLA and your Kafka config.

### Why Kafka Streams Solves This

Kafka Streams uses a **decoupled architecture**:

```
Kafka Streams Internal Threading:

┌─────────────────────────────────────────────┐
│  StreamThread-1                              │
│  ├── Internal KafkaConsumer (poll loop)      │  ← Always polling, never blocked
│  ├── Task-1 (partition-0 processing)         │
│  └── Task-2 (partition-1 processing)         │
│                                              │
│  StreamThread-2                              │
│  ├── Internal KafkaConsumer (poll loop)      │  ← Always polling, never blocked
│  ├── Task-3 (partition-2 processing)         │
│  └── Task-4 (partition-3 processing)         │
└─────────────────────────────────────────────┘
```

Each StreamThread owns its consumer AND its processing. The poll loop is never blocked by slow processing because processing happens inline within the same thread — Streams controls the pace. There is no separate worker pool that can lag behind polling.

### Comparison Table

| Concern | Plain KafkaConsumer | Kafka Streams |
|---|---|---|
| Thread safety | Manual — error-prone | Built-in per StreamThread |
| Rebalance on slow processing | Yes — if poll interval exceeded | No — poll is internal and controlled |
| Offset commit management | Manual commitSync() / commitAsync() | Automatic, tied to commit.interval.ms |
| EOS across output topics | DIY with transactional producer | Built-in with processing.guarantee=exactly_once_v2 |
| Multi-topic fan-out | Manual producer management | Topology .to() — all in one transaction |
| Scaling | Increase partitions + consumer instances | Increase num.stream.threads or pod replicas |
| Static membership | Manual group.instance.id wiring | First-class StreamsConfig support |

### Your Specific Justification

- **5 threads × 6 pods = 30 parallel stream tasks** across input topic partitions
- **~1M records/day (~12 records/sec average, burst higher)** — fits well within Streams throughput
- **No windowing or aggregation** — stateless topology, zero RocksDB overhead
- **Multi-topic output (4+1 success, 1 failure)** — all in one Kafka transaction via EOS v2
- **Static membership already in place** — pod restart reclaims same partitions without full rebalance

---

## 2. How Kafka Streams Works Internally

### Task and Partition Assignment

```
Input Topic: 30 partitions
Pods: 6  ×  Threads per pod: 5  =  30 StreamThreads total
Each StreamThread owns: 1 Task = 1 Partition

Pod-1 (5 threads)         Pod-2 (5 threads)   ...  Pod-6 (5 threads)
├── Thread-1 → Task-0     ├── Thread-1 → Task-5    ├── Thread-1 → Task-25
├── Thread-2 → Task-1     ├── Thread-2 → Task-6    ├── Thread-2 → Task-26
├── Thread-3 → Task-2     ├── Thread-3 → Task-7    ├── Thread-3 → Task-27
├── Thread-4 → Task-3     ├── Thread-4 → Task-8    ├── Thread-4 → Task-28
└── Thread-5 → Task-4     └── Thread-5 → Task-9    └── Thread-5 → Task-29
```

### Record Processing Lifecycle

```
1. KafkaConsumer.poll()          — fetches batch of records
2. For each record:
   a. Deserialize key/value
   b. Execute topology processors (your mapValues, flatMapValues, branch)
   c. Write output records to internal RecordCollector (buffered)
3. At commit.interval.ms (100ms):
   a. Flush RecordCollector → send buffered output to Kafka producer
   b. Producer.commitTransaction() — atomically commits:
      - All output records to output-topic-1, output-topic-2, exception-topic
      - Input offset for this partition
   c. Transaction marker written to all partitioned topics
4. Next poll() — fetches new records
```

### What Happens on Pod Restart

```
Without Static Membership:
  Pod restarts → broker sees consumer leave group → REBALANCE
  All 30 partitions redistributed across remaining 5 pods
  New assignment takes 30-60 seconds
  In-flight uncommitted transactions rolled back → replay from last committed offset

With Static Membership (group.instance.id set):
  Pod restarts → broker waits session.timeout.ms (45s)
  Pod rejoins → broker assigns same 5 partitions back
  No other pods disturbed
  In-flight transactions rolled back → replay only from this pod's last commit
  With 100ms commit interval → replay window ≤ 100ms of records
```

---

## 3. EOS v2 — Exactly Once Semantics

### What EOS v2 Guarantees

EOS v2 (`exactly_once_v2`) guarantees that for every input record:

- Output records are written to Kafka output topics **exactly once**
- Input offset is committed **atomically** with output records
- On failure and replay, the transaction is fenced — duplicate output is impossible

### What EOS v2 Does NOT Guarantee

- **Database writes** — Postgres is outside the Kafka transaction
- **External HTTP calls** — outside transaction scope
- Idempotency of side effects beyond Kafka topics

### Transaction Flow

```
EOS v2 Transaction per commit.interval.ms:

BEGIN TRANSACTION (producer)
  │
  ├── Write to output-topic-1 (4 records)     ┐
  ├── Write to output-topic-2 (1 record)      ├── Buffered in RecordCollector
  ├── Write to exception-topic (if failure)   ┘
  │
  ├── Write to processing_status topic        ← Your new lineage topic
  │
  └── Commit offset for input-topic partition ← Atomic with all above
COMMIT TRANSACTION
```

If pod crashes before COMMIT → broker rolls back entire transaction → consumer replays from last committed offset → your Postgres dedup key prevents duplicate DB write.

### Why Your Previous Setup Had 50k Duplicates on Restart

Before adding any output topic, your topology had no Kafka producer writes. EOS v2 had no transaction to commit — offset commits fell back to consumer group coordinator commits which are **not** transactionally fenced. On restart, the broker had no transaction marker to verify → replayed all records since last clean shutdown.

**Adding `processing_status` as output topic solves this** — every commit interval now produces a real Kafka transaction with a durable commit marker.

---

## 4. Architecture Overview

```
┌─────────────────────────────────────────────────────────────────────┐
│                        AKS Cluster                                   │
│                                                                      │
│  ┌──────────────────────────────────────────────────────────────┐   │
│  │  Trade Processing Pod (×6)                                    │   │
│  │                                                               │   │
│  │  ┌─────────────────────────────────────────────────────┐     │   │
│  │  │  Kafka Streams Application                           │     │   │
│  │  │                                                      │     │   │
│  │  │  StreamThread-1..5                                   │     │   │
│  │  │  ┌──────────────────────────────────────────────┐   │     │   │
│  │  │  │  Topology                                     │   │     │   │
│  │  │  │                                               │   │     │   │
│  │  │  │  input-topic                                  │   │     │   │
│  │  │  │      │                                        │   │     │   │
│  │  │  │      ▼                                        │   │     │   │
│  │  │  │  safeProcess()                                │   │     │   │
│  │  │  │  ├── TradeProcessor.process()                 │   │     │   │
│  │  │  │  └── catch → ProcessResult.Failure            │   │     │   │
│  │  │  │      │                                        │   │     │   │
│  │  │  │      ▼                                        │   │     │   │
│  │  │  │  split().branch()                             │   │     │   │
│  │  │  │  ├── SUCCESS ──→ output-topic-1 (4 msgs)      │   │     │   │
│  │  │  │  │           ──→ output-topic-2 (1 msg)       │   │     │   │
│  │  │  │  └── FAILURE ──→ exception-topic (1 msg)      │   │     │   │
│  │  │  │      │                                        │   │     │   │
│  │  │  │      ▼  (always, success or failure)          │   │     │   │
│  │  │  │  processing_status topic                      │   │     │   │
│  │  │  │  (correlationId, eventId, status, lineage)    │   │     │   │
│  │  │  └──────────────────────────────────────────────┘   │     │   │
│  │  └─────────────────────────────────────────────────────┘     │   │
│  │                                                               │   │
│  │  TradeProcessor (existing framework untouched)                │   │
│  │  ├── OutboxService → Postgres TX (data + outbox table)        │   │
│  │  ├── OutboxRetryService → publishes outbox messages           │   │
│  │  └── ExceptionManager → builds exception events               │   │
│  └──────────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────┘
         │                    │                    │
         ▼                    ▼                    ▼
   output-topic-1       output-topic-2      exception-topic
   processing_status    (all in one EOS v2 transaction)
```

---

## 5. Processing Status Lineage Design

### Schema

```java
public record ProcessingStatus(
    String  correlationId,      // business correlation ID (from message header or key)
    String  eventId,            // unique event identifier
    int     eventVersion,       // event schema version
    String  eventType,          // e.g. TRADE_CREATED, TRADE_AMENDED
    long    inputOffset,        // input topic offset
    int     inputPartition,     // input topic partition
    String  inputTopic,         // source topic name
    Instant processingTime,     // wall clock time of processing
    long    processingLatencyMs,// time from event timestamp to processing time
    String  status,             // SUCCESS | FAILURE
    String  failureCode,        // null on success, error code on failure
    String  failureMessage,     // null on success, error detail on failure
    String  serviceId,          // this microservice identifier
    String  podId               // AKS pod name (from env POD_NAME)
) {}
```

### Why This Design Works for Lineage

- `correlationId` links events across microservices as a trade traverses systems
- `inputOffset` + `inputPartition` gives exact replay position if needed
- `status` anchors the EOS transaction — this topic write forces Kafka to commit
- `podId` helps diagnose which pod processed which partition on rebalance events
- The topic is **compacted by `correlationId`** — retains latest status per trade

### Topic Configuration

```bash
kafka-topics.sh --create \
  --bootstrap-server <broker> \
  --topic processing_status \
  --partitions 30 \
  --replication-factor 3 \
  --config cleanup.policy=compact \
  --config min.cleanable.dirty.ratio=0.1 \
  --config segment.ms=60000 \
  --config delete.retention.ms=300000
```

---

## 6. Topology Design — Conditional Branching

### ProcessResult Sealed Interface

```java
// ProcessResult.java
public sealed interface ProcessResult permits ProcessResult.Success, ProcessResult.Failure {

    record Success(
        List<OutboxEvent> primaryEvents,   // 2 or 4 messages → output-topic-1
        OutboxEvent summaryEvent           // 1 message → output-topic-2
    ) implements ProcessResult {}

    record Failure(
        String errorCode,
        String errorMessage,
        String originalPayload
    ) implements ProcessResult {}
}
```

### Topology

```java
// TradeStreamTopology.java
@Configuration
@RequiredArgsConstructor
public class TradeStreamTopology {

    private final TradeProcessor tradeProcessor;
    private final ProcessingStatusMapper statusMapper;
    private final TopicProperties topics;

    @Bean
    public Topology buildTopology(StreamsBuilder builder) {

        KStream<String, TradeEvent> inputStream = builder.stream(
            topics.getInputTopic(),
            Consumed.with(Serdes.String(), tradeSerde())
                    .withOffsetResetPolicy(Topology.AutoOffsetReset.EARLIEST)
        );

        // ── Process: always returns ProcessResult, never throws ──────
        KStream<String, ProcessResult> resultStream = inputStream
            .mapValues((key, value) -> safeProcess(key, value));

        // ── Always publish lineage status (anchors EOS transaction) ──
        resultStream
            .mapValues((key, result) -> statusMapper.toStatus(key, result))
            .to(topics.getProcessingStatusTopic(),
                Produced.with(Serdes.String(), processingStatusSerde()));

        // ── Branch: success vs failure ────────────────────────────────
        Map<String, KStream<String, ProcessResult>> branches = resultStream
            .split(Named.as("branch-"))
            .branch(
                (key, result) -> result instanceof ProcessResult.Success,
                Branched.as("success")
            )
            .defaultBranch(Branched.as("failure"));

        KStream<String, ProcessResult> successStream = branches.get("branch-success");
        KStream<String, ProcessResult> failureStream = branches.get("branch-failure");

        // ── Success: N messages to output-topic-1 ────────────────────
        successStream
            .flatMapValues(result -> ((ProcessResult.Success) result).primaryEvents())
            .to(topics.getOutputTopic1(),
                Produced.with(Serdes.String(), outboxEventSerde()));

        // ── Success: 1 message to output-topic-2 ─────────────────────
        successStream
            .mapValues(result -> ((ProcessResult.Success) result).summaryEvent())
            .to(topics.getOutputTopic2(),
                Produced.with(Serdes.String(), outboxEventSerde()));

        // ── Failure: exception event ──────────────────────────────────
        failureStream
            .mapValues(result -> buildExceptionEvent((ProcessResult.Failure) result))
            .to(topics.getExceptionTopic(),
                Produced.with(Serdes.String(), exceptionEventSerde()));

        return builder.build();
    }

    private ProcessResult safeProcess(String key, TradeEvent event) {
        try {
            ProcessResult result = tradeProcessor.process(key, event);
            if (result == null) {
                return new ProcessResult.Failure(
                    "NULL_RESULT",
                    "Processor returned null for key: " + key,
                    event.toString()
                );
            }
            return result;
        } catch (Exception ex) {
            return new ProcessResult.Failure(
                "UNHANDLED_EXCEPTION",
                ex.getMessage(),
                event.toString()
            );
        }
    }
}
```

### Topology DAG

```
input-topic
    │
    ▼
mapValues(safeProcess)         ← all exceptions caught here
    │
    ├──→ mapValues(toStatus) ──→ processing_status   ← EOS anchor (always)
    │
    └──→ split()
         ├── SUCCESS
         │    ├── flatMapValues(primaryEvents) ──→ output-topic-1  (2 or 4 msgs)
         │    └── mapValues(summaryEvent)      ──→ output-topic-2  (1 msg)
         │
         └── FAILURE
              └── mapValues(exceptionEvent)    ──→ exception-topic (1 msg)
```

All `.to()` writes committed atomically in one EOS v2 transaction per `commit.interval.ms`.

---

## 7. Full Spring Boot Configuration

### application.yml

```yaml
spring:
  kafka:
    streams:
      application-id: trade-processing-service
      bootstrap-servers: ${KAFKA_BOOTSTRAP_SERVERS}
      properties:
        # ── EOS v2 ────────────────────────────────────────────────────
        processing.guarantee: exactly_once_v2

        # ── Commit interval ───────────────────────────────────────────
        commit.interval.ms: 100

        # ── Threading: 5 threads per pod ─────────────────────────────
        num.stream.threads: 5

        # ── Static membership: unique per pod ────────────────────────
        consumer.group.instance.id: ${POD_NAME}

        # ── Consumer tuning ──────────────────────────────────────────
        consumer.session.timeout.ms: 45000
        consumer.heartbeat.interval.ms: 3000
        consumer.max.poll.records: 1000
        consumer.fetch.min.bytes: 1
        consumer.fetch.max.wait.ms: 50

        # ── Producer tuning ──────────────────────────────────────────
        producer.acks: all
        producer.enable.idempotence: true
        producer.compression.type: lz4
        producer.linger.ms: 5
        producer.batch.size: 65536

        # ── Transaction timeout ───────────────────────────────────────
        transaction.timeout.ms: 60000

        # ── Deserialization error handling ────────────────────────────
        default.deserialization.exception.handler: >
          org.apache.kafka.streams.errors.LogAndContinueExceptionHandler

        # ── No state directory needed (stateless topology) ───────────
        state.dir: /tmp/kafka-streams

        # ── Disable standby replicas (no state store) ─────────────────
        num.standby.replicas: 0

        # ── Metadata age ─────────────────────────────────────────────
        metadata.max.age.ms: 60000

      # Serde defaults
      default-key-serde: org.apache.kafka.common.serialization.Serdes$StringSerde
      default-value-serde: org.apache.kafka.common.serialization.Serdes$ByteArraySerde
```

### KafkaStreamsConfig.java

```java
@Configuration
@EnableKafkaStreams
public class KafkaStreamsConfig {

    @Value("${POD_NAME:unknown-pod}")
    private String podName;

    @Value("${KAFKA_BOOTSTRAP_SERVERS}")
    private String bootstrapServers;

    @Bean(name = KafkaStreamsDefaultConfiguration.DEFAULT_STREAMS_CONFIG_BEAN_NAME)
    public KafkaStreamsConfiguration kafkaStreamsConfig() {
        Map<String, Object> props = new HashMap<>();

        // Core identity
        props.put(StreamsConfig.APPLICATION_ID_CONFIG, "trade-processing-service");
        props.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);

        // EOS v2
        props.put(StreamsConfig.PROCESSING_GUARANTEE_CONFIG,
                  StreamsConfig.EXACTLY_ONCE_V2);

        // Commit interval
        props.put(StreamsConfig.COMMIT_INTERVAL_MS_CONFIG, 100L);

        // Threading
        props.put(StreamsConfig.NUM_STREAM_THREADS_CONFIG, 5);

        // Static membership — unique and stable per pod
        props.put(
            StreamsConfig.consumerPrefix(ConsumerConfig.GROUP_INSTANCE_ID_CONFIG),
            "trade-processor-" + podName
        );

        // Consumer tuning
        props.put(StreamsConfig.consumerPrefix(
            ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG), 45000);
        props.put(StreamsConfig.consumerPrefix(
            ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG), 3000);
        props.put(StreamsConfig.consumerPrefix(
            ConsumerConfig.MAX_POLL_RECORDS_CONFIG), 1000);
        props.put(StreamsConfig.consumerPrefix(
            ConsumerConfig.FETCH_MIN_BYTES_CONFIG), 1);
        props.put(StreamsConfig.consumerPrefix(
            ConsumerConfig.FETCH_MAX_WAIT_MS_CONFIG), 50);

        // Producer tuning
        props.put(StreamsConfig.producerPrefix(
            ProducerConfig.ACKS_CONFIG), "all");
        props.put(StreamsConfig.producerPrefix(
            ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG), true);
        props.put(StreamsConfig.producerPrefix(
            ProducerConfig.COMPRESSION_TYPE_CONFIG), "lz4");
        props.put(StreamsConfig.producerPrefix(
            ProducerConfig.LINGER_MS_CONFIG), 5);
        props.put(StreamsConfig.producerPrefix(
            ProducerConfig.BATCH_SIZE_CONFIG), 65536);

        // Transaction timeout
        props.put(StreamsConfig.producerPrefix(
            ProducerConfig.TRANSACTION_TIMEOUT_CONFIG), 60000);

        // Error handling — log bad records and continue, don't crash the stream
        props.put(StreamsConfig.DEFAULT_DESERIALIZATION_EXCEPTION_HANDLER_CLASS_CONFIG,
                  LogAndContinueExceptionHandler.class);

        // State dir (even if stateless, Streams needs a temp dir)
        props.put(StreamsConfig.STATE_DIR_CONFIG, "/tmp/kafka-streams/" + podName);

        // No standby replicas needed — no state store
        props.put(StreamsConfig.NUM_STANDBY_REPLICAS_CONFIG, 0);

        // Serdes
        props.put(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG,
                  Serdes.String().getClass());
        props.put(StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG,
                  Serdes.ByteArray().getClass());

        return new KafkaStreamsConfiguration(props);
    }

    // Streams health check — exposes state via Actuator
    @Bean
    public KafkaStreamsStateListener kafkaStreamsStateListener(
            KafkaStreams kafkaStreams) {
        return (newState, oldState) -> {
            if (newState == KafkaStreams.State.ERROR) {
                // Alert / trigger pod restart
                log.error("KafkaStreams entered ERROR state from {}", oldState);
            }
        };
    }
}
```

### TopicProperties.java

```java
@ConfigurationProperties(prefix = "trade.topics")
@Validated
public record TopicProperties(
    @NotBlank String inputTopic,
    @NotBlank String outputTopic1,
    @NotBlank String outputTopic2,
    @NotBlank String exceptionTopic,
    @NotBlank String processingStatusTopic
) {}
```

```yaml
# application.yml
trade:
  topics:
    input-topic: trade-events
    output-topic-1: trade-legs
    output-topic-2: trade-summary
    exception-topic: trade-exceptions
    processing-status-topic: processing_status
```

### ProcessingStatusMapper.java

```java
@Component
public class ProcessingStatusMapper {

    @Value("${spring.application.name}")
    private String serviceId;

    @Value("${POD_NAME:unknown}")
    private String podId;

    public ProcessingStatus toStatus(
            String key,
            ProcessResult result,
            Headers headers,
            long offset,
            int partition,
            String topic,
            Instant eventTimestamp) {

        String status = result instanceof ProcessResult.Success ? "SUCCESS" : "FAILURE";
        Instant now = Instant.now();

        return new ProcessingStatus(
            extractCorrelationId(headers, key),
            extractEventId(headers),
            extractEventVersion(headers),
            extractEventType(headers),
            offset,
            partition,
            topic,
            now,
            Duration.between(eventTimestamp, now).toMillis(),
            status,
            result instanceof ProcessResult.Failure f ? f.errorCode() : null,
            result instanceof ProcessResult.Failure f ? f.errorMessage() : null,
            serviceId,
            podId
        );
    }
}
```

---

## 8. State Store — Do You Need It?

**No.** Your topology is fully stateless. Here is what you must NOT configure:

```java
// DO NOT add any of these — they create RocksDB state stores on disk

// ❌ No aggregations
stream.groupByKey().aggregate(...);

// ❌ No windowed operations
stream.windowedBy(TimeWindows.ofSizeWithNoGrace(Duration.ofMinutes(5)));

// ❌ No joins
stream.join(otherStream, ...);

// ❌ No explicit state store
builder.addStateStore(Stores.keyValueStoreBuilder(...));
```

Without state stores:

- No RocksDB — no disk I/O, no state directory to manage
- No changelog topics created on broker
- No standby replica tasks needed → `num.standby.replicas=0`
- Pod restarts are fast — no state restoration from changelog

The only thing Kafka Streams writes to `state.dir` is a small checkpoint file for offset tracking. Setting it to `/tmp` is fine.

---

## 9. Performance Tuning — Disable What You Don't Need

### Features to Disable or Minimize

| Feature | Config | Value | Reason |
|---|---|---|---|
| Standby replicas | `num.standby.replicas` | `0` | No state store — standby tasks are useless |
| State dir cleanup | `state.dir` | `/tmp/kafka-streams` | Minimal footprint; nothing to persist |
| Rocksdb cache | N/A | Not applicable | No state store = no RocksDB instantiated |
| Repartition topics | Avoid `groupBy` / `join` | Design choice | Each repartition = extra topic + network hop |
| Metrics recording level | `metrics.recording.level` | `INFO` (not `DEBUG`) | DEBUG records per-record metrics — CPU overhead |

### Configs That Improve Throughput for Your Pattern

```yaml
# Larger fetch batch = fewer round trips to broker
consumer.fetch.min.bytes: 1024           # wait until 1KB available
consumer.fetch.max.wait.ms: 50           # but no more than 50ms

# Producer batching — lz4 is fast compression, good for JSON payloads
producer.compression.type: lz4
producer.linger.ms: 5                    # batch for 5ms before sending
producer.batch.size: 65536              # 64KB batch size

# Commit interval — 100ms is already aggressive and correct for your SLA
commit.interval.ms: 100

# Poll records — tune to your processing speed
# Too high → more memory pressure per thread
# Too low → more round trips
consumer.max.poll.records: 500          # start here, tune up if CPU allows
```

### Memory Sizing Per Pod (No State Store)

```
Per StreamThread:
  - In-flight record buffer: ~max.poll.records × avg_record_size
  - RecordCollector output buffer: ~producer.batch.size × output topics count

5 threads × (500 records × 2KB avg × 5 output topics × 64KB batch) ≈ 500MB working set

Recommended pod memory: 2GB heap + 512MB overhead = 2.5GB container limit
JVM flags: -Xms1g -Xmx2g -XX:+UseG1GC -XX:MaxGCPauseMillis=50
```

---

## 10. Deployment on AKS

### Kubernetes Deployment Snippet

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: trade-processing-service
spec:
  replicas: 6
  template:
    spec:
      containers:
        - name: trade-processor
          image: trade-processing-service:latest
          env:
            - name: POD_NAME
              valueFrom:
                fieldRef:
                  fieldPath: metadata.name          # e.g. trade-processing-service-abc12
            - name: KAFKA_BOOTSTRAP_SERVERS
              valueFrom:
                secretKeyRef:
                  name: kafka-credentials
                  key: bootstrap-servers
          resources:
            requests:
              memory: "2Gi"
              cpu: "1000m"
            limits:
              memory: "2.5Gi"
              cpu: "2000m"
          readinessProbe:
            httpGet:
              path: /actuator/health/readiness
              port: 8080
            initialDelaySeconds: 30
            periodSeconds: 10
          livenessProbe:
            httpGet:
              path: /actuator/health/liveness
              port: 8080
            initialDelaySeconds: 60
            periodSeconds: 15
```

### Static Membership + AKS Pod Names

AKS pod names include a stable hash suffix when using Deployments. For StatefulSets the name is fully deterministic (`pod-0`, `pod-1`). Either works — what matters is that `POD_NAME` is unique per pod and **stable across restarts of the same pod**.

StatefulSet is preferred for Kafka Streams on AKS:

```yaml
apiVersion: apps/v1
kind: StatefulSet    # ← preferred over Deployment for Streams
metadata:
  name: trade-processor
spec:
  replicas: 6
  serviceName: trade-processor
  # Pod names will be: trade-processor-0 .. trade-processor-5
  # Stable across restarts — perfect for group.instance.id
```

### Health Check via Actuator

```java
@Component
public class KafkaStreamsHealthIndicator implements HealthIndicator {

    private final KafkaStreams kafkaStreams;

    @Override
    public Health health() {
        KafkaStreams.State state = kafkaStreams.state();
        if (state == KafkaStreams.State.RUNNING ||
            state == KafkaStreams.State.REBALANCING) {
            return Health.up()
                .withDetail("state", state.name())
                .build();
        }
        return Health.down()
            .withDetail("state", state.name())
            .build();
    }
}
```

---

## 11. Operational Checklist

### Before Go-Live

- [ ] Input topic has exactly 30 partitions (matches 6 pods × 5 threads)
- [ ] `processing_status` topic created as compacted with 30 partitions
- [ ] `output-topic-1`, `output-topic-2`, `exception-topic` created with matching partitions
- [ ] `POD_NAME` env var injected via Downward API in pod spec
- [ ] StatefulSet used (not Deployment) for stable pod names
- [ ] `num.standby.replicas=0` confirmed — no state store in topology
- [ ] Postgres dedup index on `(kafka_partition, kafka_offset)` in place
- [ ] Outbox retry service has idempotent publish logic

### Monitoring (Micrometer + Prometheus)

Key metrics to alert on:

| Metric | Alert Threshold | Meaning |
|---|---|---|
| `kafka.consumer.fetch.rate` | Drop > 50% | Consumer stalled |
| `kafka.streams.commit.rate` | < 1/sec per thread | Commits not happening |
| `kafka.producer.record.error.rate` | > 0 | Transaction failures |
| `kafka.streams.records.lag.max` | > 10000 | Processing falling behind |
| `processing_status` topic — FAILURE rate | > 1% | Business logic errors |

### Graceful Shutdown

Kafka Streams handles `SIGTERM` gracefully — in-flight transactions are committed before shutdown. Ensure your AKS pod termination grace period is generous:

```yaml
spec:
  terminationGracePeriodSeconds: 60   # Allow Streams to commit and close cleanly
```

---

*Document version: 1.0 | Stack: Java 21, Spring Boot, Kafka Streams 3.9.2, Postgres, AKS*
