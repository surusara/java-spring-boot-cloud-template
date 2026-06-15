# CSFLE / KMS Caching and Low-Cost Tracing Recommendation for Kafka Streams on AKS

## 1. Purpose

This document provides a practical recommendation for:

- What to cache and what not to cache in a Java Spring Boot Kafka Streams service using CSFLE.
- How to verify whether KMS / Azure Key Vault calls are happening per message.
- How to measure the exact latency breakdown of message processing.
- How to enable low-cost observability using Log Analytics, Prometheus, Grafana, Micrometer, and OpenTelemetry.
- How to explain KEK, DEK, key rotation, and why manually caching raw key material can be risky.

The main objective is to prove whether  performance issue is caused by CSFLE, KMS/key lookup, DB, outbox, Kafka publish, GC, schema registry, or business logic.

---

## 2. Executive Recommendation

Do not introduce a custom raw KEK/DEK in-memory cache unless security, cryptography, and risk teams explicitly approve it.

The preferred approach is:

1. Reuse serializer/deserializer, Schema Registry client, Azure credential, and Key Vault client as singleton/safely reused objects.
2. Let Confluent CSFLE / DEK Registry / provider library manage key lifecycle and caching.
3. Warm up the CSFLE path during pod startup.
4. Use Azure Key Vault logs to prove whether KMS operations happen per message.
5. Instrument the code to measure each stage of message processing.
6. Use low-cost metrics/tracing instead of sending high-cardinality custom metrics to App Insights for every transaction.
7. Log only slow transactions and errors in production.

---

## 3. KEK and DEK Explained

### 3.1 What is KEK?

KEK means **Key Encryption Key**.

In CSFLE, the KEK is usually stored and protected in an external KMS such as:

- Azure Key Vault
- AWS KMS
- GCP KMS
- HSM-backed enterprise key management system

The KEK is used to protect or wrap lower-level data encryption keys.

### 3.2 What is DEK?

DEK means **Data Encryption Key**.

The DEK is used to encrypt and decrypt the actual field-level data, for example a confidential customer identifier field in an Avro schema.

In simplified form:

```text
Confidential field
    ↓ encrypted using
DEK
    ↓ protected/wrapped using
KEK in KMS
```

### 3.3 Why two layers?

This model allows:

- KEK to stay inside managed KMS/HSM.
- DEK to be managed/versioned for data encryption.
- Better key governance.
- Ability to rotate keys without directly exposing master key material.
- Better separation between key management and data encryption.

---

## 4. Key Rotation and Cache Risk

### 4.1 Can KEK rotate?

Yes. The KEK can rotate.

A KEK may have versions, for example:

```text
customer-csfle-kek/version-1
customer-csfle-kek/version-2
customer-csfle-kek/version-3
```

Old data remains decryptable only if the system keeps enough metadata to identify the correct KEK version and the old key version remains available according to retention/security policy.

### 4.2 Can DEK rotate?

Yes. The DEK can also rotate depending on CSFLE policy and configuration.

Different records or schema versions may be associated with different DEK versions.

### 4.3 Why is manual raw key caching risky?

Manual caching of raw DEK or raw key material is risky because:

- Pod memory now contains data-decryption capability.
- Memory dumps can expose sensitive material.
- Rotation may not take effect until cache expiry or pod restart.
- Revocation may not be immediate.
- Audit evidence becomes harder.
- Security review burden increases.
- Incorrect cache invalidation can break decryption.
- Historic data may require multiple key versions, not only the latest key.

### 4.4 Important clarification

It is not correct to assume:

```text
KEK and DEK are one-time setup and will never change.
```

A safer statement is:

```text
KEK and DEK may be long-lived, but they must be treated as versioned and rotatable.
Historic data remains decryptable because old key versions/metadata are retained and governed, not because keys can never rotate.
```

---

## 5. What to Cache and What Not to Cache

| Item | Cache? | Recommendation | Reason |
|---|---:|---|---|
| Schema Registry client | Yes | Reuse singleton | Avoid repeated client creation and network overhead |
| Kafka serializer/deserializer | Yes | Reuse framework-managed instances | Avoid repeated initialization and rule loading |
| Schema metadata | Yes | Let Schema Registry client cache it | Safe and expected |
| CSFLE rule metadata | Yes | Let CSFLE/provider library manage it | Avoid repeated rule fetch |
| Azure credential object | Yes | Create once as Spring bean | Avoid repeated token chain setup |
| Azure Key Vault client object | Yes | Create once as Spring bean | Avoid per-message client construction |
| Key identifier / key URI | Yes | Cache as config | Not secret by itself |
| KEK raw key material | No | Do not cache manually | Should remain in KMS/HSM |
| Plaintext DEK bytes | Avoid | Only if library handles it securely | Highly sensitive |
| Wrapped/encrypted DEK metadata | Yes, if library-managed | Prefer built-in DEK Registry handling | Needed for decryption and versioning |
| Access token | Prefer SDK-managed | Do not manually implement unless required | Azure SDK handles token acquisition/caching |
| Decrypted confidential business value | No | Do not cache | Sensitive data and high-cardinality |
| Per-message result | No | Not useful | Creates memory pressure |

---

## 6. Preferred Caching Strategy

### 6.1 Recommended approach

```text
Application startup
    ↓
Create reusable Kafka SerDe / Schema Registry / Azure clients
    ↓
Warm up schema + CSFLE rule + key access path
    ↓
Process Kafka messages
    ↓
Measure encryption/decryption latency
    ↓
Verify KMS calls are not happening per message
```

### 6.2 Avoid this anti-pattern

```java
public void processMessage(ConsumerRecord<String, byte[]> record) {
    DefaultAzureCredential credential = new DefaultAzureCredentialBuilder().build();
    CryptographyClient cryptoClient = new CryptographyClientBuilder()
        .keyIdentifier(keyId)
        .credential(credential)
        .buildClient();

    // This is bad if done per message.
}
```

### 6.3 Prefer this pattern

```java
@Configuration
public class KeyVaultClientConfig {

    @Bean
    public DefaultAzureCredential defaultAzureCredential() {
        return new DefaultAzureCredentialBuilder().build();
    }

    @Bean
    public CryptographyClient cryptographyClient(DefaultAzureCredential credential) {
        return new CryptographyClientBuilder()
                .keyIdentifier("https://<vault-name>.vault.azure.net/keys/<key-name>/<key-version>")
                .credential(credential)
                .buildClient();
    }
}
```

---

## 7. How to Check Whether KMS Calls Are Cached

### 7.1 Use Azure Key Vault logs

Enable Key Vault diagnostic logs to Log Analytics.

Look for operations such as:

```text
WrapKey
UnwrapKey
Encrypt
Decrypt
GetKey
```

Expected healthy behavior:

```text
Pod restart / first few records → some KMS calls
Normal processing → very low KMS calls
```

Bad behavior:

```text
Every Kafka message → KMS call
```

For 5 million messages/day, per-message KMS call is a major issue.

### 7.2 Kusto query for Key Vault operation count

```kusto
AzureDiagnostics
| where ResourceProvider == "MICROSOFT.KEYVAULT"
| where OperationName in ("Wrap Key", "Unwrap Key", "Encrypt", "Decrypt", "Get Key")
| summarize Count=count() by OperationName, bin(TimeGenerated, 5m)
| order by TimeGenerated desc
```

Depending on diagnostic table configuration, the table may also be `AzureDiagnostics` or a resource-specific Key Vault table. Adjust table/column names based on your workspace.

### 7.3 Compare Key Vault calls with Kafka volume

```kusto
AzureDiagnostics
| where ResourceProvider == "MICROSOFT.KEYVAULT"
| where OperationName in ("Wrap Key", "Unwrap Key", "Encrypt", "Decrypt", "Get Key")
| summarize KeyVaultCalls=count() by bin(TimeGenerated, 1h)
| order by TimeGenerated desc
```

Then compare with Kafka processed records for the same hour.

Interpretation:

| Observation | Meaning |
|---|---|
| 100,000 messages and 10 KMS calls | Cache likely working |
| 100,000 messages and 100,000 KMS calls | Cache likely not working |
| High KMS calls only after restart | Warm-up/startup behavior |
| KMS calls spike during redeploy | Per-pod cache warming |

---

## 8. CSFLE Warm-Up Recommendation

Add a startup warm-up so first production records do not pay all initialization cost.

```java
@Component
@RequiredArgsConstructor
@Slf4j
public class CsfleWarmupRunner implements ApplicationRunner {

    private final CsfleWarmupService csfleWarmupService;

    @Override
    public void run(ApplicationArguments args) {
        try {
            log.info("CSFLE warm-up started");

            csfleWarmupService.warmup();

            log.info("CSFLE warm-up completed");
        } catch (Exception ex) {
            log.warn("CSFLE warm-up failed. Service will continue, but first records may be slower.", ex);
        }
    }
}
```

Example warm-up service:

```java
@Service
@RequiredArgsConstructor
public class CsfleWarmupService {

    private final MeterRegistry meterRegistry;

    public void warmup() {
        Timer.Sample sample = Timer.start(meterRegistry);

        try {
            // Use a synthetic non-production payload that follows the same schema path.
            // Trigger:
            // - Schema load
            // - CSFLE rule load
            // - Serializer/deserializer initialization
            // - Key access path
            //
            // Example placeholder:
            // byte[] encryptedPayload = serializeSyntheticRecord();
            // Object record = deserializeSyntheticRecord(encryptedPayload);

        } finally {
            sample.stop(Timer.builder("csfle.warmup.ms")
                    .description("CSFLE warm-up duration")
                    .register(meterRegistry));
        }
    }
}
```

---

## 9. Low-Cost Observability Options

## 9.1 Option 1 — Minimum cost: structured logs + Log Analytics

Use this when budget is tight.

### What to do

- Emit structured JSON logs.
- Log only:
  - errors
  - slow transactions
  - circuit breaker open
  - DB timeout
  - Kafka publish failure
  - outbox backlog warning
- Do not log every successful message in production.

### Pros

- Simple.
- Uses existing Log Analytics.
- Good for incident investigation.

### Cons

- Not ideal for p95/p99 metrics unless logs are structured and sampled carefully.
- Can become expensive if every message is logged.

---

## 9.2 Option 2 — Recommended low-cost: Micrometer + Prometheus + Grafana

Use this for continuous metrics.

### Flow

```text
Spring Boot Actuator /actuator/prometheus
        ↓
Prometheus scrape
        ↓
Grafana dashboard
```

### Pros

- Cheap if self-hosted.
- Good for p95/p99 latency.
- Excellent dashboards.
- Low overhead.
- No per-message log ingestion cost.

### Cons

- Need Prometheus/Grafana setup.
- Need retention planning.

---

## 9.3 Option 3 — Azure Managed Prometheus + Azure Managed Grafana

Use this if your organization prefers managed Azure services.

### Flow

```text
Spring Boot Actuator /actuator/prometheus
        ↓
Azure Monitor managed Prometheus
        ↓
Azure Managed Grafana
```

### Pros

- Managed Azure option.
- Easier platform governance.
- Less operational overhead.

### Cons

- Not free.
- Need Azure Monitor workspace and Grafana workspace.
- Cost still needs monitoring.

---

## 9.4 Option 4 — Deep tracing: OpenTelemetry + Tempo/Jaeger

Use this for lower environment and performance testing.

### Flow

```text
Spring Boot / OpenTelemetry Java Agent
        ↓
OpenTelemetry Collector
        ↓
Tempo or Jaeger
        ↓
Grafana
```

### Pros

- Good for tracing full message path.
- Helps find DB/API/Kafka/serialization bottlenecks.

### Cons

- More setup.
- In production, use sampling to avoid cost and overhead.

---

## 10. Recommended Observability Strategy

### Production

```text
Metrics:
- Micrometer + Prometheus

Logs:
- Log Analytics only for errors and slow transactions

Traces:
- Sampled only, or disabled unless incident/performance window
```

### Performance test environment

```text
Metrics:
- Micrometer + Prometheus

Logs:
- Structured logs for all test messages if volume is controlled

Traces:
- OpenTelemetry enabled with higher sampling
```

### Local developer environment

```text
Metrics:
- Actuator endpoints

Tracing:
- Optional Jaeger/Tempo using Docker Compose
```

---

## 11. Spring Boot Dependencies

### Maven dependencies

```xml
<dependencies>
    <!-- Spring Boot Actuator -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-actuator</artifactId>
    </dependency>

    <!-- Micrometer Prometheus registry -->
    <dependency>
        <groupId>io.micrometer</groupId>
        <artifactId>micrometer-registry-prometheus</artifactId>
    </dependency>

    <!-- Optional: Resilience4j metrics if using circuit breaker -->
    <dependency>
        <groupId>io.github.resilience4j</groupId>
        <artifactId>resilience4j-micrometer</artifactId>
    </dependency>

    <!-- Optional: JDBC / Hikari metrics are auto-bound by Spring Boot when configured -->
</dependencies>
```

---

## 12. Spring Boot Configuration

### application.yml

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info,prometheus,metrics
  endpoint:
    health:
      show-details: when_authorized
  metrics:
    tags:
      application: settlement-service
      environment: ${ENVIRONMENT:local}
      service: ${SERVICE_NAME:settlement-service}
    distribution:
      percentiles-histogram:
        settlement.processing.total: true
        settlement.processing.stage: true
        settlement.db.operation: true
        settlement.kafka.publish: true
      percentiles:
        settlement.processing.total: 0.5,0.95,0.99
        settlement.processing.stage: 0.5,0.95,0.99

logging:
  level:
    root: INFO
    com.yourbank.settlement: INFO
    # Enable only in lower env temporarily
    # io.confluent.kafka.schemaregistry: DEBUG
    # io.confluent.kafka.serializers: DEBUG
    # io.confluent.dekregistry: DEBUG
    # com.azure.security.keyvault.keys: DEBUG
```

### Important production note

Avoid high-cardinality metric tags such as:

```text
tradeId
eventId
customerId
accountId
```

Use those in logs only for slow/error records, not as metric labels.

---

## 13. Custom Metrics Model

### 13.1 Metric names

| Metric | Purpose |
|---|---|
| `settlement.processing.total` | Total message processing time |
| `settlement.processing.stage` | Stage-level processing duration |
| `settlement.db.operation` | DB read/write latency |
| `settlement.kafka.publish` | Kafka publish latency |
| `settlement.outbox.operation` | Outbox insert/publish latency |
| `settlement.exception.write` | Exception persistence latency |
| `settlement.circuitbreaker.open` | Circuit breaker open count |
| `settlement.message.count` | Processed/success/failure count |

### 13.2 Stage names

Use low-cardinality stage names:

```text
consume_to_start
avro_deserialize
csfle_decrypt
business_validation
db_read
db_write
outbox_write
kafka_publish
exception_write
total
```

---

## 14. Java Sample: Stage Timer Utility

```java
@Component
@RequiredArgsConstructor
public class ProcessingMetrics {

    private final MeterRegistry meterRegistry;

    public Timer.Sample start() {
        return Timer.start(meterRegistry);
    }

    public void recordStage(Timer.Sample sample, String service, String stage, String result) {
        sample.stop(Timer.builder("settlement.processing.stage")
                .description("Stage-level processing duration")
                .tag("service", service)
                .tag("stage", stage)
                .tag("result", result)
                .publishPercentileHistogram()
                .register(meterRegistry));
    }

    public void recordTotal(long durationMs, String service, String result) {
        Timer.builder("settlement.processing.total")
                .description("Total message processing duration")
                .tag("service", service)
                .tag("result", result)
                .publishPercentileHistogram()
                .register(meterRegistry)
                .record(durationMs, TimeUnit.MILLISECONDS);
    }

    public void incrementMessage(String service, String status) {
        Counter.builder("settlement.message.count")
                .description("Message count by status")
                .tag("service", service)
                .tag("status", status)
                .register(meterRegistry)
                .increment();
    }
}
```

---

## 15. Java Sample: Kafka Streams Processor Instrumentation

```java
@Service
@RequiredArgsConstructor
@Slf4j
public class SettlementProcessor {

    private static final String SERVICE = "settlement-consumer";

    private final ProcessingMetrics metrics;
    private final SettlementRepository settlementRepository;
    private final OutboxRepository outboxRepository;
    private final KafkaPublisher kafkaPublisher;

    public void process(ConsumerRecord<String, byte[]> record) {
        long totalStartNs = System.nanoTime();

        String tradeId = null;

        try {
            Timer.Sample consumeStartSample = metrics.start();
            // If event timestamp is producer timestamp:
            long consumeToStartMs = System.currentTimeMillis() - record.timestamp();
            consumeStartSample.stop(Timer.builder("settlement.processing.stage")
                    .tag("service", SERVICE)
                    .tag("stage", "consume_to_start")
                    .tag("result", "success")
                    .register(metricsRegistry()));

            Timer.Sample avroSample = metrics.start();
            SettlementEvent event = deserializeAvro(record.value());
            metrics.recordStage(avroSample, SERVICE, "avro_deserialize", "success");

            tradeId = event.getTradeId();

            Timer.Sample csfleSample = metrics.start();
            String cid = decryptOrAccessCsfleField(event);
            metrics.recordStage(csfleSample, SERVICE, "csfle_decrypt", "success");

            Timer.Sample validationSample = metrics.start();
            validateBusinessRules(event);
            metrics.recordStage(validationSample, SERVICE, "business_validation", "success");

            Timer.Sample dbReadSample = metrics.start();
            Optional<SettlementEntity> existing = settlementRepository.findByTradeId(event.getTradeId());
            metrics.recordStage(dbReadSample, SERVICE, "db_read", "success");

            Timer.Sample dbWriteSample = metrics.start();
            SettlementEntity entity = saveSettlement(event, existing);
            metrics.recordStage(dbWriteSample, SERVICE, "db_write", "success");

            Timer.Sample outboxSample = metrics.start();
            outboxRepository.save(createOutboxRecord(entity));
            metrics.recordStage(outboxSample, SERVICE, "outbox_write", "success");

            Timer.Sample kafkaPublishSample = metrics.start();
            kafkaPublisher.publish(entity);
            metrics.recordStage(kafkaPublishSample, SERVICE, "kafka_publish", "success");

            long totalMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - totalStartNs);
            metrics.recordTotal(totalMs, SERVICE, "success");
            metrics.incrementMessage(SERVICE, "success");

            if (totalMs > 1000) {
                log.warn("SLOW_PROCESSING service={} tradeId={} totalMs={}",
                        SERVICE, tradeId, totalMs);
            }

        } catch (Exception ex) {
            long totalMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - totalStartNs);

            metrics.recordTotal(totalMs, SERVICE, "failure");
            metrics.incrementMessage(SERVICE, "failure");

            log.error("PROCESSING_FAILED service={} tradeId={} totalMs={} errorType={} message={}",
                    SERVICE, tradeId, totalMs, ex.getClass().getSimpleName(), ex.getMessage(), ex);

            throw ex;
        }
    }

    private MeterRegistry metricsRegistry() {
        // Replace with injected registry if needed.
        throw new UnsupportedOperationException("Inject MeterRegistry directly in real implementation");
    }

    private SettlementEvent deserializeAvro(byte[] payload) {
        // Plug in actual Avro/Confluent deserializer.
        return new SettlementEvent();
    }

    private String decryptOrAccessCsfleField(SettlementEvent event) {
        // Access encrypted field through CSFLE-aware deserialized object.
        return event.getCid();
    }

    private void validateBusinessRules(SettlementEvent event) {
        // Business validation.
    }

    private SettlementEntity saveSettlement(SettlementEvent event, Optional<SettlementEntity> existing) {
        // Persist business entity.
        return new SettlementEntity();
    }

    private OutboxRecord createOutboxRecord(SettlementEntity entity) {
        return new OutboxRecord();
    }
}
```

Note: In real code, inject `MeterRegistry` directly instead of using the placeholder `metricsRegistry()` method. The placeholder is included only to keep the example compact.

---

## 16. Improved Java Sample: Reusable Stage Timing Context

This is cleaner for production code.

```java
@RequiredArgsConstructor
public class ProcessingTimerContext {

    private final MeterRegistry meterRegistry;
    private final String service;
    private final long startNs = System.nanoTime();

    public <T> T time(String stage, Supplier<T> supplier) {
        long stageStartNs = System.nanoTime();
        try {
            T result = supplier.get();
            record(stage, "success", stageStartNs);
            return result;
        } catch (RuntimeException ex) {
            record(stage, "failure", stageStartNs);
            throw ex;
        }
    }

    public void time(String stage, Runnable runnable) {
        long stageStartNs = System.nanoTime();
        try {
            runnable.run();
            record(stage, "success", stageStartNs);
        } catch (RuntimeException ex) {
            record(stage, "failure", stageStartNs);
            throw ex;
        }
    }

    public long totalMs() {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs);
    }

    public void recordTotal(String result) {
        Timer.builder("settlement.processing.total")
                .tag("service", service)
                .tag("result", result)
                .publishPercentileHistogram()
                .register(meterRegistry)
                .record(totalMs(), TimeUnit.MILLISECONDS);
    }

    private void record(String stage, String result, long stageStartNs) {
        long durationNs = System.nanoTime() - stageStartNs;

        Timer.builder("settlement.processing.stage")
                .tag("service", service)
                .tag("stage", stage)
                .tag("result", result)
                .publishPercentileHistogram()
                .register(meterRegistry)
                .record(durationNs, TimeUnit.NANOSECONDS);
    }
}
```

Factory:

```java
@Component
@RequiredArgsConstructor
public class ProcessingTimerFactory {

    private final MeterRegistry meterRegistry;

    public ProcessingTimerContext create(String service) {
        return new ProcessingTimerContext(meterRegistry, service);
    }
}
```

Usage:

```java
public void process(ConsumerRecord<String, byte[]> record) {
    ProcessingTimerContext timer = timerFactory.create("settlement-consumer");

    String tradeId = null;

    try {
        SettlementEvent event = timer.time("avro_deserialize",
                () -> deserializeAvro(record.value()));

        tradeId = event.getTradeId();

        String cid = timer.time("csfle_decrypt",
                () -> decryptOrAccessCsfleField(event));

        timer.time("business_validation",
                () -> validateBusinessRules(event));

        SettlementEntity entity = timer.time("db_write",
                () -> saveSettlement(event));

        timer.time("outbox_write",
                () -> outboxRepository.save(createOutboxRecord(entity)));

        timer.time("kafka_publish",
                () -> kafkaPublisher.publish(entity));

        timer.recordTotal("success");

        if (timer.totalMs() > 1000) {
            log.warn("SLOW_PROCESSING service={} tradeId={} totalMs={}",
                    "settlement-consumer", tradeId, timer.totalMs());
        }

    } catch (Exception ex) {
        timer.recordTotal("failure");

        log.error("PROCESSING_FAILED service={} tradeId={} totalMs={} errorType={} message={}",
                "settlement-consumer", tradeId, timer.totalMs(),
                ex.getClass().getSimpleName(), ex.getMessage(), ex);

        throw ex;
    }
}
```

---

## 17. Structured Logging for Slow Transactions

### Log only slow path

```java
if (totalMs > 1000) {
    log.warn("SLOW_PROCESSING service={} tradeId={} eventId={} totalMs={} csfleMs={} dbMs={} outboxMs={} kafkaPublishMs={}",
            service, tradeId, eventId, totalMs, csfleMs, dbMs, outboxMs, kafkaPublishMs);
}
```

### Better JSON logging pattern

Use Logback JSON encoder if allowed:

```xml
<dependency>
    <groupId>net.logstash.logback</groupId>
    <artifactId>logstash-logback-encoder</artifactId>
    <version>7.4</version>
</dependency>
```

Example log fields:

```json
{
  "event": "SLOW_PROCESSING",
  "service": "settlement-consumer",
  "tradeId": "T123",
  "eventId": "E456",
  "totalMs": 3021,
  "csfleMs": 40,
  "dbReadMs": 1200,
  "dbWriteMs": 700,
  "outboxMs": 300,
  "kafkaPublishMs": 50
}
```

---

## 18. Log Analytics KQL for Slow Processing

```kusto
ContainerLogV2
| where TimeGenerated > ago(24h)
| where LogMessage contains "SLOW_PROCESSING"
| project TimeGenerated, PodName, ContainerName, LogMessage
| order by TimeGenerated desc
```

If JSON logs are parsed into fields:

```kusto
ContainerLogV2
| where TimeGenerated > ago(24h)
| where LogMessage has "SLOW_PROCESSING"
| extend totalMs = todouble(extract(@"totalMs=(\d+)", 1, LogMessage))
| summarize count(), avg(totalMs), percentile(totalMs, 95), percentile(totalMs, 99) by bin(TimeGenerated, 15m), PodName
| order by TimeGenerated desc
```

---

## 19. Prometheus Queries

### 19.1 p95 total processing time

```promql
histogram_quantile(
  0.95,
  sum by (le, service) (
    rate(settlement_processing_total_seconds_bucket[5m])
  )
)
```

### 19.2 p99 total processing time

```promql
histogram_quantile(
  0.99,
  sum by (le, service) (
    rate(settlement_processing_total_seconds_bucket[5m])
  )
)
```

### 19.3 p95 by processing stage

```promql
histogram_quantile(
  0.95,
  sum by (le, service, stage) (
    rate(settlement_processing_stage_seconds_bucket[5m])
  )
)
```

### 19.4 Compare CSFLE vs DB vs Kafka publish

```promql
histogram_quantile(
  0.95,
  sum by (le, stage) (
    rate(settlement_processing_stage_seconds_bucket{stage=~"csfle_decrypt|db_read|db_write|outbox_write|kafka_publish"}[5m])
  )
)
```

### 19.5 Message failure rate

```promql
sum by (service, status) (
  rate(settlement_message_count_total[5m])
)
```

---

## 20. AKS Setup: Self-Hosted Prometheus and Grafana

### 20.1 Add Helm repositories

```bash
helm repo add prometheus-community https://prometheus-community.github.io/helm-charts
helm repo add grafana https://grafana.github.io/helm-charts
helm repo update
```

### 20.2 Install kube-prometheus-stack

```bash
kubectl create namespace monitoring

helm install kube-prometheus-stack prometheus-community/kube-prometheus-stack \
  --namespace monitoring
```

### 20.3 Expose Prometheus/Grafana internally

For internal access, use port-forward first:

```bash
kubectl port-forward -n monitoring svc/kube-prometheus-stack-grafana 3000:80
```

Then open:

```text
http://localhost:3000
```

Get Grafana password:

```bash
kubectl get secret -n monitoring kube-prometheus-stack-grafana \
  -o jsonpath="{.data.admin-password}" | base64 --decode
```

---

## 21. Kubernetes ServiceMonitor for Spring Boot

If Prometheus Operator is used:

```yaml
apiVersion: monitoring.coreos.com/v1
kind: ServiceMonitor
metadata:
  name: settlement-service-monitor
  namespace: monitoring
  labels:
    release: kube-prometheus-stack
spec:
  namespaceSelector:
    matchNames:
      - settlement
  selector:
    matchLabels:
      app: settlement-service
  endpoints:
    - port: http
      path: /actuator/prometheus
      interval: 30s
```

Service:

```yaml
apiVersion: v1
kind: Service
metadata:
  name: settlement-service
  namespace: settlement
  labels:
    app: settlement-service
spec:
  selector:
    app: settlement-service
  ports:
    - name: http
      port: 8080
      targetPort: 8080
```

---

## 22. OpenTelemetry Option

### 22.1 Use OpenTelemetry Java agent

Download the OpenTelemetry Java agent and mount it into the container or bake it into the image.

Example JVM args:

```bash
-javaagent:/otel/opentelemetry-javaagent.jar
-Dotel.service.name=settlement-service
-Dotel.traces.exporter=otlp
-Dotel.metrics.exporter=none
-Dotel.logs.exporter=none
-Dotel.exporter.otlp.endpoint=http://otel-collector.monitoring:4318
-Dotel.traces.sampler=parentbased_traceidratio
-Dotel.traces.sampler.arg=0.05
```

### 22.2 Use high sampling only in performance environment

Production:

```text
1% to 5% sampling, or slow/error traces only where supported
```

Performance test:

```text
50% to 100% sampling for controlled test windows
```

---

## 23. DB Performance Checks

### 23.1 Enable PostgreSQL slow query logging

Recommended lower environment setting:

```sql
ALTER SYSTEM SET log_min_duration_statement = '500ms';
SELECT pg_reload_conf();
```

### 23.2 Check table sizes

```sql
SELECT
    schemaname,
    relname AS table_name,
    pg_size_pretty(pg_total_relation_size(relid)) AS total_size,
    pg_size_pretty(pg_relation_size(relid)) AS table_size,
    pg_size_pretty(pg_indexes_size(relid)) AS index_size
FROM pg_catalog.pg_statio_user_tables
ORDER BY pg_total_relation_size(relid) DESC;
```

### 23.3 Check index usage

```sql
SELECT
    schemaname,
    relname AS table_name,
    indexrelname AS index_name,
    idx_scan,
    idx_tup_read,
    idx_tup_fetch
FROM pg_stat_user_indexes
ORDER BY idx_scan ASC;
```

### 23.4 Check dead tuples / bloat signal

```sql
SELECT
    schemaname,
    relname,
    n_live_tup,
    n_dead_tup,
    last_vacuum,
    last_autovacuum,
    last_analyze,
    last_autoanalyze
FROM pg_stat_user_tables
ORDER BY n_dead_tup DESC;
```

---

## 24. Hikari Connection Pool Metrics

Spring Boot can expose Hikari metrics through Micrometer.

Important metrics:

```text
hikaricp_connections_active
hikaricp_connections_idle
hikaricp_connections_pending
hikaricp_connections_timeout_total
```

PromQL example:

```promql
hikaricp_connections_pending
```

If pending connection count is high, message processing may be waiting for DB connections.

---

## 25. Kafka Streams Metrics to Watch

Important metrics:

```text
consumer lag
records consumed rate
process latency
commit latency
poll latency
rebalance count
skipped records
deserialization errors
```

Also watch:

```text
Kafka Streams thread state
rebalance frequency
task assignment movement
transaction commit latency if EOS is enabled
```

---

## 26. Circuit Breaker Metrics

If using Resilience4j:

```yaml
management:
  health:
    circuitbreakers:
      enabled: true
  endpoints:
    web:
      exposure:
        include: health,prometheus,metrics
```

Useful metrics:

```text
resilience4j_circuitbreaker_calls
resilience4j_circuitbreaker_state
resilience4j_circuitbreaker_slow_calls
resilience4j_circuitbreaker_failure_rate
```

PromQL:

```promql
resilience4j_circuitbreaker_state
```

---

## 27. CSFLE Measurement Plan

### 27.1 Test matrix

| Test | CSFLE | DB | Kafka publish | Purpose |
|---|---:|---:|---:|---|
| A | Off | Off | Off | Baseline deserialization/business cost |
| B | On | Off | Off | CSFLE overhead |
| C | On | On | Off | DB overhead |
| D | On | On | On | Full path |
| E | On | On | On + outbox | Production-like path |

### 27.2 What to compare

```text
total_processing_ms
csfle_decrypt_ms
avro_deserialize_ms
db_read_ms
db_write_ms
outbox_write_ms
kafka_publish_ms
gc_pause_ms
cpu_usage
heap_usage
```

### 27.3 Expected interpretation

| Result | Interpretation |
|---|---|
| CSFLE adds 30ms, total is 3000ms | CSFLE is not main bottleneck |
| CSFLE adds 1000ms and KMS calls per message | KMS/cache issue |
| DB write is 1500ms | DB/index/transaction issue |
| Outbox write is 700ms | Outbox table/index/bloat issue |
| Kafka publish is 500ms | Producer config/network/broker issue |
| GC pauses high | Memory/object allocation issue |

---


```

---

## 29. Final Recommendation

### Cache / reuse

- Schema Registry client
- Kafka serializer/deserializer instances
- Azure credential/client objects
- Key URI/config
- CSFLE metadata through library-managed caching
- Schema/rule metadata

### Do not manually cache

- Raw KEK
- Raw/plaintext DEK
- Decrypted confidential business value
- Per-message confidential data

### Enable tracing/metrics using

- Spring Boot Actuator
- Micrometer
- Prometheus
- Grafana
- Log Analytics for slow/error logs
- OpenTelemetry for controlled deep tracing

### Prove before changing architecture

Do not move confidential field access to per-message API lookup unless measurements prove CSFLE/KMS is the dominant bottleneck and no safe event-based mitigation is possible.

---

## 30. References

- Confluent CSFLE and key management documentation: https://docs.confluent.io/
- Spring Boot Actuator metrics and observability documentation: https://docs.spring.io/spring-boot/reference/actuator/
- Micrometer Prometheus documentation: https://docs.micrometer.io/
- Azure AKS monitoring documentation: https://learn.microsoft.com/en-us/azure/aks/monitor-aks
- Azure Managed Prometheus and Grafana documentation: https://learn.microsoft.com/en-us/azure/azure-monitor/metrics/prometheus-grafana
- Azure Key Vault Java SDK cryptography documentation: https://learn.microsoft.com/en-us/java/api/com.azure.security.keyvault.keys.cryptography


additinal check
pattern:
KafkaJsonSchemaSerde<MessageWrapper> tradeMessageSerde = new KafkaJsonSchemaSerde<>();
Map<String, Object> serdeConfig = new HashMap<>();
tradeMessageSerde.configure(serdeConfig, false);

is okay only if this SerDe is created once when the Spring bean is created.

Make sure you are not doing this inside mapValues, peek, process, transformer, or per-message logic.

Bad:
stream.mapValues(value -> {
    KafkaJsonSchemaSerde<MessageWrapper> serde = new KafkaJsonSchemaSerde<>();
    serde.configure(config, false);
    ...
});

Good

@Bean
public KStream<String, MessageWrapper> tradeMessage(
        @Qualifier("tradeMessageStreamBuilderFactory") StreamsBuilder builder) {

    KafkaJsonSchemaSerde<MessageWrapper> tradeMessageSerde = new KafkaJsonSchemaSerde<>();
    tradeMessageSerde.configure(serdeConfig(), false);

    KStream<String, MessageWrapper> stream =
            builder.stream(inputTopic, Consumed.with(Serdes.String(), tradeMessageSerde));

    return stream;
}

config
serdeConfig.put("schema.registry.url", schemaRegistryUrl);
serdeConfig.put("basic.auth.credentials.source", "USER_INFO");
serdeConfig.put("basic.auth.user.info", schemaRegistryUserInfo);

serdeConfig.put("json.value.type", MessageWrapper.class.getName());

serdeConfig.put("auto.register.schemas", false);
serdeConfig.put("use.latest.version", true);

serdeConfig.put("rule.executors._default_.param.tenant.id", tenantId);
serdeConfig.put("rule.executors._default_.param.client.id", clientId);
serdeConfig.put("rule.executors._default_.param.client.secret", clientSecret);

Performance-related CSFLE settings

In the encryption rule, check:

"params": {
  "encrypt.kek.name": "<kekName>",
  "encrypt.dek.algorithm": "AES256_GCM",
  "preserve.source.fields": "false"
}


Important:

preserve.source.fields=false

Confluent says field-level transforms update fields in-place for performance; preserving source fields adds overhead.

Be careful with:

encrypt.dek.expiry.days

If set, automatic DEK rotation happens when the DEK exceeds expiry age; old DEKs remain available for old messages, and Confluent warns there is a 10,000 DEK retention limit. Do not set aggressive rotation like daily unless security explicitly requires it

1. Enable AKV logs to Log Analytics
Azure CLI

az monitor diagnostic-settings create \
  --name kv-audit-to-law \
  --resource <KEY_VAULT_RESOURCE_ID> \
  --workspace <LOG_ANALYTICS_WORKSPACE_ID> \
  --logs '[{"categoryGroup":"audit","enabled":true}]' \
  --metrics '[{"category":"AllMetrics","enabled":true}]'

  Microsoft’s Key Vault docs say to enable Diagnostic settings and select audit / allLogs, then route to Log Analytics, Storage, or Event Hubs.

  2. Check if already enabled

  az monitor diagnostic-settings list \
  --resource <KEY_VAULT_RESOURCE_ID>

  3. KQL to check KMS calls per minute

Run in Log Analytics:
AzureDiagnostics
| where ResourceProvider == "MICROSOFT.KEYVAULT"
| where OperationName in ("KeyDecrypt", "KeyEncrypt", "KeyUnwrap", "KeyWrap", "KeyGet")
| summarize count() by OperationName, bin(TimeGenerated, 1m)
| order by TimeGenerated desc

If your workspace uses resource-specific tables, try:
AKVKeyOperation
| where OperationName in ("KeyDecrypt", "KeyEncrypt", "KeyUnwrap", "KeyWrap", "KeyGet")
| summarize count() by OperationName, bin(TimeGenerated, 1m)
| order by TimeGenerated desc

5. What result means

Healthy:

Few KeyUnwrap / KeyDecrypt calls after pod start
Not proportional to message count

Problem:

1 message ≈ 1 AKV KeyDecrypt / KeyUnwrap call


6. Optional Java log level only for lower env

This is separate from AKV audit logs:

logging:
  level:
    io.confluent.kafka.serializers: DEBUG
    io.confluent.kafka.schemaregistry: DEBUG
    io.confluent.dekregistry: DEBUG
    com.azure.security.keyvault.keys: DEBUG
    com.azure.core.http.policy: DEBUG

Use Java DEBUG only temporarily. For proof, AKV Diagnostic Logs are better.