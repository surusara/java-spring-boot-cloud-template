# Production Architecture Review: Kafka Streams Circuit Breaker for Trade Processing

**Date:** March 2026  
**System:** Financial Trade Processing with Circuit Breaker Pattern  
**Scale:** 3M messages/day (104.17 msg/s average), 200ms per-message processing latency

---

## Executive Summary

✅ **Overall Assessment: PRODUCTION-READY with minor enhancements**

The architecture implements a **conservative, low-risk circuit breaker pattern** specifically designed for financial workloads. The key insight—**stopping the stream entirely rather than using KafkaStreams.pause()** for extended periods—prevents message queue buildup and avoids the "draining" problem mentioned in requirements.

**Critical strengths:**
- Soft failures are captured and persisted, not lost
- Hard failures trigger immediate pod restart via AKS
- Stream stop/restart prevents message buffer exhaustion
- Idempotent producer eliminates output duplicates
- Configurable recovery backoff (1m → 10m → 20m) allows dependencies to recover
- Comprehensive exception classification (deserialization vs. business vs. fatal)

---

## Architecture Overview

### High-Level Flow

```
Input Topic (3M msgs/day)
    ↓
[Custom Deserializer] ← Logs malformed JSON/CSFLE errors
    ↓
[PaymentsRecordProcessor]
    ↓
[Circuit Breaker Gate] ← Checks at_least_once soft-failure rate
    ├─ OK → [Business Process] → [Idempotent Producer] → Output Topic
    ├─ SOFT FAILURE → [Audit Store] → Breaker counts
    └─ HARD FAILURE → [Fatal Handler] → SHUTDOWN_CLIENT → AKS restart
```

### Processing Guarantee: at_least_once

**Why: Financial context requires replaying if pod crashes**
- Soft failures safely logged to DB before output produced
- Exactly_once_v2 reserved for future when Kafka-to-Kafka semantics proven
- Consumer offset advanced only after fault-tolerant log entry
- At-least-once acceptable because idempotent producer key prevents duplicates on retry

### Thread & Partition Sizing

| Parameter | Value | Rationale |
|-----------|-------|-----------|
| Msgs/day | 3,000,000 | ~104.17 msgs/sec average |
| Processing latency | 200ms/msg | Synchronous business logic |
| Throughput/thread | ~5 msgs/sec | 1 thread cannot handle average |
| Target throughput | 21+ concurrent slots | 104.17 ÷ 5 = 20.83 → round up |
| Stream threads/pod | 4 | Safe starting point; 4 threads × 6 pods = 24 slots > 21 needed |
| Input partitions | ≥24 | Parallelism ceiling = thread count |
| State replica copies | 1 standby | Enables fast failover w/o full rebalance |

**Recommended initial k8s deployment:**
- **6 pods** × 4 stream threads = 24 concurrent processing slots
- **24+ input topic partitions** (avoid partition-per-pod; use 2-3:1 ratio)
- Replication factor = 3 (broker high-availability)

---

## Detailed Component Review

### 1. Exception Classification (3-Tier Model) ✅

| Type | Example | Handler | Stream Action | Audit | Breaker Impact |
|------|---------|---------|---|---|---|
| **Deserialization Failure** | Malformed JSON, invalid schema, CSFLE incompatible | `CustomDeserializationExceptionHandler` | CONTINUE | Logged to audit DB | ❌ None (separate concern) |
| **Soft Business Failure** | Validation rule failure, enrichment unavailable | Business processor returns `SUCCESS_WITH_EXCEPTION_LOGGED` | CONTINUE | Logged to audit DB | ✅ **Counted** |
| **Hard Failure** | Null pointer bug, DB connection pool exhausted, OOM | Uncaught exception → `StreamFatalExceptionHandler` | SHUTDOWN_CLIENT | Log to stderr | ❌ None (immediate restart) |

**Strength:** Separates **data quality issues** (deserialization) from **business logic issues** (soft failures) from **infrastructure issues** (hard failures). Circuit breaker tracks only business soft-failures, preventing false positives from schema evolution.

---

### 2. Circuit Breaker Configuration ✅

```yaml
Sliding Window Type: TIME_BASED
├─ Window Duration: 30 minutes (1800 seconds)
├─ Minimum Calls: 100 (avoids tripping on startup hiccups)
├─ Failure Threshold: 20% (e.g., 20+ failures of 100 calls)
├─ Half-Open Calls: 20 (cautious recovery test, not immediate resume)
└─ Max Wait in Half-Open: 2 minutes
```

**Why 30 minutes?**
- Covers typical regional outages or DB maintenance windows
- Longer than pod restart + dependency warm-up time
- Wide enough to absorb transient spikes

**Why 20% threshold?**
- Conservative: allows up to 1/5 of traffic to fail gracefully
- Prevents hair-triggered trips from normal variance
- Financial workloads can tolerate brief degradation if audited

**Why 100 minimum calls?**
- At 104 msgs/sec and 30-min window: even 1 call/sec = 1800 min samples
- 100 is trivial volume (< 1 sec at full rate)
- Prevents false positives on startup

---

### 3. Stream Stop/Start Pattern (NOT Pause) ✅✅

**Key Innovation:**
```java
// INSTEAD OF:
stream.pause();  // ← Returns CONTINUE, consumer keeps fetching, 
                    // buffers fill, memory pressure builds

// USES:
streamsBuilderFactoryBean.stop();  // ← Actually stops the underlying 
                                      // Kafka consumer; no fetching occurs
```

**Benefits for high-volume scenarios:**
| Scenario | Pause() | Stop() |
|----------|---------|--------|
| What happens to unfetched records? | Sit in Kafka broker buffers | Stay in broker untouched |
| Consumer session timeout? | Rebalance triggers after 45s | None (consumer offline) |
| Memory foot print | Grows with buffered batch | Minimal (no active task threads) |
| Recovery signal | Automatic when ↑ opens | Explicit `start()` after backoff |
| Pod resource usage | High (holding threads + buffers) | Low (consumers paused) |

**For 3M messages/day scenario:**
- If soft-failure rate spikes to 21%+, breaker opens
- Stream **stops immediately**—no new messages fetched or buffered
- Recovery scheduler waits 1 min, then transitions to half-open and restarts
- If fixed, half-open tests 20 msgs, gradual resume; otherwise opens again
- Backoff escalates: 1m → 10m → 20m (dependency recovery time)
- **No message loss or buffer exhaustion**

---

### 4. Idempotent Producer (Output Safety) ✅

```yaml
Producer Config:
├─ enable-idempotence: true
├─ max-in-flight-requests-per-connection: 5 (required with idempotence)
├─ retries: 2147483647
├─ delivery-timeout-ms: 120000 (2 minutes)
├─ acks: all (acknowledge after all replicas)
└─ linger-ms: 5 (batch up to 5ms for throughput)
```

**Why idempotent?**
- At-least-once semantics can produce output twice if producer retries
- Idempotent producer deduplicates server-side using sequence numbers
- Kafka handles ordering per partition + producer instance

**Why `max-in-flight ≤ 5`?**
- Idempotence safety requires < 2^31 cumulative sequence numbers
- Setting to 5 is conservative; prevents reordering across retries

**Why `acks: all` + high retries?**
- Financial outputs cannot be silently dropped
- All broker replicas must acknowledge before returning success
- Default 60s delivery timeout might be too short; increased to 120s

---

### 5. Consumer Batch & Polling Configuration ✅

```yaml
Consumer:
├─ max-poll-records: 250 (batch size)
├─ max-poll-interval-ms: 600000 (10 minutes)
├─ session-timeout-ms: 45000 (45 seconds)
├─ heartbeat-interval-ms: 3000 (3 seconds)
├─ request-timeout-ms: 30000 (30 seconds)
└─ max-partition-fetch-bytes: 1048576 (1 MB per partition)
```

**Rationale for 250 batch size:**
- Average payload: 14 KB
- 250 × 14 KB = 3.5 MB per batch
- Processing: 250 × 200ms = 50 seconds per batch
- Poll interval is 600s; plenty of headroom before rebalance timeout
- Tune down if processing > 200ms; up if much faster

**Rationale for 600s poll interval:**
- Longest possible business processing window per batch
- Prevents mid-batch rebalance, which loses offset progress
- Much longer than default 300s; required for financial synchronous work

**Heartbeat vs. Session Timeout:**
- Heartbeat sent every 3s
- Session expires after 45s of no heartbeat
- Prevents "phantom" consumer causing rebalance delays
- 3/45 = 6.7%, safe margin

---

### 6. Fatal Exception Handling ✅

```java
@Override
public StreamThreadExceptionResponse handle(Throwable exception) {
    log.error("Fatal uncaught exception. Shutting down client.", exception);
    return StreamThreadExceptionResponse.SHUTDOWN_CLIENT;
}
```

**Why SHUTDOWN_CLIENT?**
- Uncaught exceptions (bugs, OOM, DB pool exhaustion) should **fail fast**
- Circuit breaker is **only for recoverable business failures**
- Kubernetes automatically restarts the pod
- Prevents "zombie" pod that looks healthy but silently fails

**When does this trigger?**
- NPE in custom business logic
- Database connection exhaustion
- OutOfMemoryError
- Unexpected runtime exceptions not caught in business processor

---

### 7. Exception Audit Trail ✅

**Deserialization failures → Separate audit log**
```
Topic: payments.input
Partition: 3, Offset: 45201
Error: json_decode_error
Key: <binary>
Value: <corrupted bytes>
Timestamp: 2026-03-08T14:23:45Z
Root Cause: Invalid UTF-8 sequence
```

**Soft business failures → Audit log**
```
StreamName: payments-stream
Topic: payments.input
Error: BUSINESS_SOFT_FAILURE
Reason: Counterparty not found in enrichment DB
Payload Hash: abc123...
Key: TRADE_XYZ
Correlation ID: 67890-abcde
```

**Hard failures → Stderr log (picked up by k8s)**
```
ERROR kafka-streams: Fatal uncaught exception in Kafka Streams.
java.lang.NullPointerException: at com.example...PaymentProcessor.calculateFee(Line 102)
```

**Purpose:** Enable:
- Root cause analysis for hard failures
- Audit compliance for soft failures
- Data quality dashboards for deserialization errors
- Separate metrics for each category

---

## Production-Readiness Assessment

### ✅ What Is Production-Ready

| Concern | Status | Evidence |
|---------|--------|----------|
| High-volume throughput (3M/day) | ✅ | 4 threads × 6 pods = 24 concurrent slots > 21 required |
| No message loss on circuit break | ✅ | Stop stream entirely; offset only committed after audit log written |
| No duplicates on output | ✅ | Idempotent producer + 5 max-in-flight |
| Graceful degradation | ✅ | Circuit breaker opens, stream stops, recovery scheduler retries |
| Hard failure isolation | ✅ | Uncaught exceptions trigger immediate pod restart, not hung process |
| Soft failure tracking | ✅ | Every soft failure logged with full context before processing continues |
| Config flexibility | ✅ | All thresholds/delays configurable via application.yml |
| Metrics & monitoring | ✅ | Actuator + Prometheus integration enabled; breaker state exportable |

### ⚠️ What Needs Pre-Production Verification

| Item | Requirement | Action |
|------|-------------|--------|
| **Business processor latency** | Verify actual P50/P95/P99 times | Load test with 200ms synthetic delay |
| **Soft failure rate baseline** | Measure normal rate & variance | Run 24hr baseline in pre-prod |
| **Dependency recover times** | How long until DB/enrichment recovers? | Document SLAs; adjust breaker delays if needed |
| **Audit table scaling** | Can DB handle soft failure writes at scale? | Benchmark: 1 write per soft failure, if 21% of traffic = 22k/day baseline, could spike to 50k/day |
| **Pod restart impact** | Does application warm up quickly? | Measure cold-start latency, connection pool time |
| **State store restore** | If stateful topology added, restore time? | Plan for >= 60s restore window |
| **Partition assignment strategy** | Default sticky assignment okay? | Avoid rebalance storms; monitor lag |

---

## Operational Considerations

### 1. Kubernetes & AKS Deployment

**Liveness & Readiness Probes**
```yaml
livenessProbe:
  httpGet:
    path: /actuator/health
    port: 8080
  initialDelaySeconds: 60
  periodSeconds: 10
  timeoutSeconds: 5
  failureThreshold: 3

readinessProbe:
  httpGet:
    path: /actuator/health/liveness  # Only fails on actual unrecoverable state
    port: 8080
  initialDelaySeconds: 30
  periodSeconds: 5
  failureThreshold: 2
```

⚠️ **Caution:** Ensure `/actuator/health` does **NOT** fail when breaker is in OPEN state. Breaker opening is intentional; pod should remain ready to accept restart command.

### 2. Scaling Strategy

**Horizontal scaling (add pods):**
- Do NOT scale beyond 24 pods if input has only 24 partitions
- Excess pods sit idle, consuming resources
- Scale up partitions **first** (Kafka cluster reshard), then scale pods

**Scaling formula:**
```
Pods Needed = ceil(Required Concurrent Slots / Threads Per Pod)
Partitions Needed = Pods × 2  (conservative; enables future growth)
```

For 3M msgs/day with 200ms latency:
- Required slots = 21
- Threads/pod = 4
- Pods = ceil(21/4) = 6
- Partitions = 6 × 2 = 12 (minimum); recommend 24 for bursts

### 3. Metrics to Export

**Circuit Breaker:**
- `circuit_breaker_state` (0=CLOSED, 1=OPEN, 2=HALF_OPEN)
- `circuit_breaker_calls_total` (labels: outcome=success|soft_failure)
- `circuit_breaker_open_timestamp` (when breaker last opened)
- `circuit_breaker_restart_backoff_minutes` (current recovery delay)

**Consumer:**
- `kafka_consumer_lag_sum` (total lag across partitions)
- `kafka_consumer_records_consumed_total`
- `kafka_consumer_fetch_wait_seconds`

**Exceptions:**
- `deserialization_errors_total` (labeled by error_code)
- `soft_failures_total` (by error_code)
- `hard_failures_total` (should be near-zero; each is a pod restart event)

### 4. Alerting

| Alert | Condition | Action |
|-------|-----------|--------|
| Breaker Opened | State transitions to OPEN | Page on-call; check dependency health |
| Breaker Stuck Open | OPEN for > 20 min | Usually indicates persistent dependency issue; escalate |
| Lag Growing | Consumer lag > 100k messages | Pod may be struggling; check CPU/memory; consider scale-up |
| Hard Failures | > 1 pod restart in 5 min | Code bug in business processor; rollback immediately |
| Soft Failure Spike | Soft failures > 21% in 30-min window | Normal operation; breaker handles it; monitor for underlying issue |

---

## Risk Assessment & Mitigations

### 🔴 High Risk Areas

| Risk | Impact | Mitigation |
|------|--------|-----------|
| **Deserialization errors not caught** | Malformed messages crash processor | Covered: custom handler returns CONTINUE + audits |
| **Soft failure rate misestimated** | Breaker trips when should stay open | Tune threshold in canary; adjust 20% if needed |
| **Idempotence disabled accidentally** | Output duplicates on producer retry | Config validation test; fail-fast if not enabled |
| **DB audit table fills up** | Soft failures can't be logged | Plan capacity: 14 KB/error × (failures/day) × retention days |

### 🟡 Medium Risk Areas

| Risk | Impact | Mitigation |
|------|--------|-----------|
| **Partition rebalancing during soft-failure surge** | Lag spikes, processing delays | High heartbeat interval (3s); long session timeout (45s); design for it |
| **State directory not persisted** | Stateless okay now; issues if state added later | Pre-create PVC; mount on /var/lib/kafka-streams/payments |
| **Recovery scheduler misconfiguration** | Breaker stuck in OPEN; no retry attempts | Test schedule in pre-prod; alert if OPEN > 20 min |
| **Producer max-in-flight changed** | Could break idempotence safety | Document as immutable; test before changing |

### 🟢 Low Risk Areas

| Risk | Impact | Mitigation |
|------|--------|-----------|
| **Consumer poll timeout** | Rebalance during long batch | 600s poll interval; batch = 50s max → safe |
| **Network jitter to Kafka brokers** | Sporadic request timeouts | 30s request timeout; Kafka handles retries |
| **Pod rollout during processing** | Graceful shutdown | Use preStop hook; allow 60s drain time |

---

## Recommendations for First Production Release

### ✅ Approved As-Is

1. **Exception classification model** → Use for all three tiers
2. **Circuit breaker with stop/start** → Prevents message draining
3. **Idempotent producer** → Financial-grade safety
4. **Consumer batch sizing** → 250 records × 14 KB is reasonable starting point
5. **Recovery backoff schedule** → 1m, 10m, 20m is conservative and appropriate

### 🟡 Before Production Launch

| Item | Owner | Effort | Deadline |
|------|-------|--------|----------|
| Load test at 2× expected rate | QA | 1 week | Week 1 |
| Soft failure rate baseline measurement | DevOps | 3 days | Week 1 |
| Audit table capacity planning | DBA | 2 days | Week 1 |
| Liveness/readiness probe validation | DevOps | 1 day | Week 1 |
| Metrics dashboard setup | DevOps | 3 days | Week 2 |
| Alert rule configuration & testing | SRE | 2 days | Week 2 |
| Runbook: "Breaker Stuck Open" | Ops | 1 day | Week 2 |
| Go-live rehearsal (shadow traffic) | QA+DevOps | 2 days | Week 3 |

### 🔮 Post-Launch Enhancements

1. **Kafka Streams DLQ (Dead Letter Queue)** → Add after 2 weeks of stable production for hard failures
2. **Distributed tracing (Jaeger)** → Correlate across services; helpful for soft failure root-cause analysis
3. **Exactly-once-v2 migration** → After proving exactly_once_v2 Kafka-to-Kafka transactions work in stage
4. **Stateful topology** → If future requirements demand aggregations; ensure state directory persisted
5. **Custom metrics dashboard** → Grafana dashboard with breaker state, lag, error rates

---

## Testing Scenarios

### Unit Tests (Already Present)

See `/test/java/` for:
- ✅ `BusinessOutcomeCircuitBreakerTest` — Breaker state transitions
- ✅ `CustomDeserializationExceptionHandlerTest` — Malformed JSON handling
- ✅ `DefaultBusinessProcessorServiceTest` — Business logic paths
- ✅ `TopologyProcessingTest` — End-to-end stream topology

### Pre-Production Load Tests

```bash
# Scenario 1: Normal throughput
jmeter -n -t payments_load.jmx -l results.jtl -j jmeter.log \
  -Jthreads=50 -Jrampup=60 -Jduration=3600 -Jmsg_rate=150

# Scenario 2: Soft failure spike (simulate bad enrichment data)
# Inject 25% soft failures for 5 minutes; verify breaker opens & recovers

# Scenario 3: Deserialization error burst
# Produce 100 malformed messages; verify handler logs & stream continues

# Scenario 4: Hard failure injection (deploy buggy code)
# Throw NPE in business processor; verify pod restarts & breaker not affected
```

---

## Conclusion

This architecture is **well-suited for production processing of millions of daily trades** with the following key strengths:

✅ **Prevents message draining** via stream stop/start instead of pause  
✅ **Captures all failures safely** before processing continues  
✅ **Prevents output duplicates** with idempotent producer  
✅ **Graceful degradation path** with configurable circuit breaker strategy  
✅ **Fast hard-failure recovery** via explicit pod restart  
✅ **Auditable & compliant** with comprehensive exception logging  

**Risk level:** 🟢 **Low** (assuming pre-production validation checklist completed)

**Recommendation:** **APPROVED for canary production launch** with full monitoring and runbooks in place.

---

## Appendix: Configuration Reference

### Key Properties for Tuning

| Property | Default | Min | Max | Impact | Tuning Guide |
|----------|---------|-----|-----|--------|--------------|
| `num-stream-threads` | 4 | 1 | cores | Throughput | Increase if lag grows; decrease if CPU maxed |
| `max-poll-records` | 250 | 1 | 10000 | Batch size | Increase if latency <<200ms; decrease if >>200ms |
| `failure-rate-threshold` | 20% | 5% | 100% | Breaker trip point | Decrease for strict SLAs; increase if noisy |
| `time-window-seconds` | 1800 | 60 | 3600 | Evaluation window | Longer window = slower to detect sustained issues |
| `restart-delays` | [1m,10m,20m] | — | — | Recovery backoff | Match dependency SLAs; escalate if recovery slow |
| `processing-guarantee` | at_least_once | — | — | Semantics | Keep as-is unless prove exactly_once_v2 needed |

---

**Document Version:** 1.0  
**Last Updated:** March 2026  
**Next Review:** Post-production stability window (2 weeks)
