# plain-consumer-outbox

A production-shaped **plain Spring Kafka consumer** (no Kafka Streams) that:

- consumes **Avro** payment records from **Confluent Cloud** (SASL_SSL + Schema Registry),
- runs a **configurable number of worker threads per pod**,
- persists to **PostgreSQL** and emits **two output topics** via the **Transactional Outbox** pattern,
- **pauses/resumes** consumption with a **Resilience4j circuit breaker** when a downstream dependency is down,
- **autoscales on consumer-group lag** with **KEDA**.

It is the plain-consumer counterpart to the Kafka Streams app: same correctness goals (no visible duplicates, dependency-outage handling), built without the Streams runtime.

---

## 1. Why a plain consumer here (vs Kafka Streams)

You don't use windowing/joins/aggregations, so the main thing Streams would buy you is *easy* exactly-once. This app reaches the same place a different way:

| Concern | Kafka Streams (EOS v2) | This app (plain consumer + outbox) |
|---|---|---|
| Atomic "process + emit" | Kafka transaction (offset+output) | **DB transaction** (business row + outbox rows), then relay publishes |
| DB write + Kafka write consistency | Not native (needs care) | **Native** — one DB commit; no dual-write |
| No visible duplicates | `read_committed` hides aborted output | **Deterministic `eventId` + downstream dedupe** |
| Dependency outage | stop stream / long `session.timeout.ms` parking | **`pause()`/`resume()`** — member stays in group, no rebalance, no long timeout |
| Duplicate on replay | rereads, hidden by EOS | **`processed_message` dedupe** makes DB effect once |

**Honest caveat:** a plain consumer does **not** reduce duplicates for free. This design achieves it with the outbox + idempotency below. Publishing is **at-least-once**; downstream must dedupe on `eventId`.

---

## 2. Architecture / data flow

```
                  ┌──────────────────────── one DB transaction ───────────────────────┐
 payments.input   │  1. dedupe check (processed_message)                               │
   (Avro) ──▶ @KafkaListener ──▶ 2. enrichment call  ──[circuit breaker]──▶ static-data │
             (N threads/pod)     3. INSERT payment                                      │
                                 4. INSERT outbox_event  (PAYMENT_APPROVED)             │
                                 5. INSERT outbox_event  (PAYMENT_AUDIT)                │
                  └──────────────────── commit → offset acked ────────────────────────┘
                                              │
                       OutboxRelay (@Scheduled, FOR UPDATE SKIP LOCKED)
                                              │
                        ┌─────────────────────┴──────────────────────┐
                        ▼                                             ▼
               payments.approved (Avro)                       payments.audit (Avro)
```

### Guarantees
- **Effectively-once DB effect.** Listener is at-least-once (offset committed *after* the DB commit, `AckMode.RECORD`). A replayed record is a no-op because `processed_message` already has the business key.
- **No dual-write.** The DB row and the "intent to publish" (outbox rows) commit together. Kafka is written *later* by the relay — never in the same breath as the DB.
- **At-least-once publish, deduped downstream.** If the relay dies between the broker ack and marking `SENT`, the row is re-published. `eventId` is deterministic (`<paymentId>::APPROVED` / `::AUDIT`), so downstream dedupe collapses it.
- **Multi-pod safe relay.** `SELECT … FOR UPDATE SKIP LOCKED` lets every pod run the relay against disjoint rows — no leader election needed.

### Circuit breaker → pause/resume
When the enrichment dependency fails past the threshold, the breaker OPENs. The coordinator **pauses all listener containers**: the consumers stay in the group and keep heartbeating, they just stop fetching. The in-flight record that hit the open breaker is **withheld (not acked)** and re-delivered after recovery — no record is skipped. Resilience4j auto-transitions OPEN→HALF_OPEN after the wait duration; the coordinator then resumes so a few trial records probe recovery (success → CLOSED, failure → OPEN again).

> This is the key structural win over Streams for the outage case: `pause()` needs **no** stable identity and **no** long `session.timeout.ms` parking — the same pod holds its partitions and resumes exactly where it left off.

---

## 3. Layout

```
src/main/avro/            PaymentInput / PaymentApproved / PaymentAudit  (.avsc → generated at build)
config/                   Kafka consumer & producer factories, AppProperties
listener/                 @KafkaListener entry point
service/                  PaymentProcessingService (@Transactional), EnrichmentClient (breaker-guarded)
outbox/                   OutboxEvent + repo + AvroOutboxCodec + OutboxRelay
dedupe/                   ProcessedMessage + repo
circuit/                  CircuitBreakerConfiguration, ConsumerPauseController, PauseCoordinator
resources/db/migration/   Flyway V1__init.sql (payment, processed_message, outbox_event)
k8s-manifest.yaml         Deployment + Service + PDB + KEDA ScaledObject
```

---

## 4. Build & run

```bash
# Java 21 + Maven. Avro classes are generated from src/main/avro during the build.
mvn -q clean verify

# Local run needs: Postgres, and Confluent Cloud (or a local Kafka + Schema Registry).
KAFKA_BOOTSTRAP_SERVERS=... KAFKA_SASL_JAAS_CONFIG='...' \
SCHEMA_REGISTRY_URL=... SCHEMA_REGISTRY_AUTH=key:secret \
DB_URL=jdbc:postgresql://localhost:5432/payments DB_USERNAME=payments DB_PASSWORD=payments \
mvn spring-boot:run
```

Create the topics in Confluent Cloud (input + 2 outputs) with a partition count that matches your target parallelism (e.g. 48).

---

## 5. Fine-tuning guide

All knobs are environment variables (see `k8s-manifest.yaml` ConfigMap) mapping to `AppProperties`.

### 5.1 Throughput & parallelism
- **`CONSUMER_CONCURRENCY`** — worker threads per pod. Each thread owns a share of partitions. Effective parallelism = `pods × concurrency`, capped at the **partition count**. For 48 partitions, `6 pods × 4 threads = 24` consumers = 2 partitions/thread (room to grow to 48). Never exceed partitions — extra threads idle.
- **`MAX_POLL_RECORDS`** — records fetched per poll. Keep `MAX_POLL_RECORDS × per-record-time` well under `MAX_POLL_INTERVAL_MS`. At ~3 s/record, **10** → ~30 s/poll cycle (safe). 100 → ~300 s (too coarse; risks eviction).
- Size the DB pool (`DB_POOL_SIZE`) ≥ `CONSUMER_CONCURRENCY` + 1 (for the relay).

### 5.2 Liveness / rebalancing
- **`MAX_POLL_INTERVAL_MS`** (default 300000 / 5 min) — must exceed worst-case poll-cycle time with margin. This detects a genuinely stuck consumer.
- **`SESSION_TIMEOUT_MS`** (default 45000) / **`HEARTBEAT_INTERVAL_MS`** (default 10000) — fast dead-pod detection. Heartbeat ≈ ⅓ of session timeout.
- Assignment strategy is **CooperativeStickyAssignor** — incremental rebalances, no stop-the-world revocation on scale events.
- **You do NOT need the 12-min `session.timeout.ms` from the Streams app.** Outage handling here is `pause()`, which keeps the member alive without holding partitions via a long timeout.

### 5.3 Offsets / delivery
- Auto-commit is **off**; the container commits after the listener returns (`AckMode.RECORD`) → at-least-once, made effectively-once by `processed_message`.
- `auto.offset.reset=earliest` (no data loss on a new/expired group). Switch to `latest` if skipping is acceptable.
- `isolation.level=read_committed` — only read committed data from upstream transactional producers.

### 5.4 Outbox relay
- **`OUTBOX_BATCH_SIZE`** — rows drained per cycle. Larger = higher throughput, longer lock hold. 200 is a good default.
- **`OUTBOX_POLL_DELAY_MS`** — delay between cycles; the main lever on end-to-end publish latency (500 ms ≈ sub-second). Lower for latency, higher to reduce DB load.
- Producer is **idempotent, `acks=all`** (dedupes producer retries; does not dedupe outbox re-publishes — that's downstream on `eventId`).
- The relay preserves per-key order by processing `id ASC` and stopping a batch at the first failure.

### 5.5 Circuit breaker
- **`CB_FAILURE_RATE`** (%) over a **`CB_WINDOW_SECONDS`** time window, requiring **`CB_MIN_CALLS`** before it can trip.
- **`CB_WAIT_OPEN_SECONDS`** — how long to stay OPEN (paused) before probing (HALF_OPEN). Set to your expected dependency-recovery time. Because we `pause()` instead of parking partitions, this can be as long as you like **without** touching `session.timeout.ms`.
- **`CB_HALF_OPEN_CALLS`** — trial records allowed through when probing.

### 5.6 KEDA autoscaling
- Scales the Deployment on **consumer-group lag** (`lagThreshold` ≈ target lag per pod).
- Keep **`maxReplicaCount` ≤ partition count**.
- `cooldownPeriod` / `stabilizationWindowSeconds` damp scale-thrash → fewer rebalances → fewer duplicate windows.

### 5.7 What you can NOT avoid tuning
A plain consumer has the **same** knobs as Streams plus more responsibility. There is no config that eliminates duplicates on its own; correctness comes from the **outbox + dedupe + deterministic `eventId`** here, not from a magic setting. Relying on defaults is still a decision — validate them against your volume.

---

## 6. Downstream contract

Consumers of `payments.approved` / `payments.audit` **must**:
- set `isolation.level=read_committed`, and
- **dedupe on `eventId`** (`<paymentId>::APPROVED` / `::AUDIT`) — publishing is at-least-once.

---

## 7. Tests

```bash
mvn test
```
Covers: Avro outbox encode/decode round-trip, transactional processing (dedupe + 2 outbox rows), relay publish/mark-sent and failure-leaves-pending, and breaker OPEN/HALF_OPEN/CLOSED → pause/resume wiring. Broker/DB-free (mocked), so it runs in CI without infra.
