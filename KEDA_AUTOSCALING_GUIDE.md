# KEDA Kafka Lag-Based Autoscaling — Implementation Guide

**Application:** Kafka Streams Payment Processing (`payments-stream`)  
**Cluster:** AKS (Azure Kubernetes Service)  
**Kafka Version:** 3.9.x | **KEDA Version:** 2.15+  
**Date:** March 2026

---

## Table of Contents

1. [Overview](#1-overview)
2. [Architecture](#2-architecture)
3. [Prerequisites — AKS KEDA Setup](#3-prerequisites--aks-keda-setup)
4. [How KEDA Kafka Scaling Works](#4-how-keda-kafka-scaling-works)
5. [Cooperative Rebalancing & Static Membership](#5-cooperative-rebalancing--static-membership)
6. [Application Configuration (application.yml)](#6-application-configuration-applicationyml)
7. [Java Code Changes (KafkaStreamsConfig.java)](#7-java-code-changes-kafkastreamsconfigjava)
8. [Kubernetes Manifest (k8s-manifest.yaml)](#8-kubernetes-manifest-k8s-manifestyaml)
9. [KEDA ScaledObject Configuration](#9-keda-scaledobject-configuration)
10. [Scaling Behavior](#10-scaling-behavior)
11. [Multi-Topic Scaling Logic](#11-multi-topic-scaling-logic)
12. [Preventing the Yo-Yo Scaling Problem](#12-preventing-the-yo-yo-scaling-problem)
13. [Scale-Down Speed Tuning](#13-scale-down-speed-tuning)
14. [Heartbeat, Session Timeout & Dead Detection Deep Dive](#14-heartbeat-session-timeout--dead-detection-deep-dive)
15. [Exactly-Once v2 Compatibility](#15-exactly-once-v2-compatibility)
16. [Cooperative Rebalancing — Visual Walkthrough](#16-cooperative-rebalancing--visual-walkthrough)
17. [KEDA Kafka Authentication (SASL/TLS)](#17-keda-kafka-authentication-sasltls)
18. [Monitoring & Verification](#18-monitoring--verification)
19. [Troubleshooting](#19-troubleshooting)
20. [FAQ](#20-faq)

---

## 1. Overview

We are replacing the existing CPU/memory-based HPA with **KEDA (Kubernetes Event-Driven Autoscaler)** to scale our Kafka Streams application based on **actual Kafka consumer lag** rather than resource utilization.

### Key Design Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Scaling metric | Kafka consumer lag | Directly reflects processing backlog, not indirect resource signals |
| Max replica detection | Automatic via `limitToPartitionsWithLag` | KEDA discovers partition count from broker — no hardcoded max needed |
| Rebalance strategy | Cooperative + Static Membership | Near-zero processing disruption during scale events |
| Scale-up speed | 2 pods/minute | Gentle scale-up to minimize rebalances |
| Scale-down speed | 4 pods every 5 minutes (~1 hour full drain) | Conservative to avoid yo-yo scaling |

### What Changed

| Component | Before | After |
|-----------|--------|-------|
| Autoscaler | HPA (CPU/Memory) | KEDA ScaledObject (Kafka lag) |
| `session.timeout.ms` | 45,000 (45 sec) | 300,000 (5 min) |
| `heartbeat.interval.ms` | 3,000 (3 sec) | 10,000 (10 sec) |
| `max.poll.interval.ms` | 600,000 (10 min) | 300,000 (5 min) |
| Static membership | Not configured | `group.instance.id: ${HOSTNAME}` |
| Leave group on close | Default (true) | `false` |
| `terminationGracePeriodSeconds` | 70 | 120 |
| `maxSurge` / `maxUnavailable` | 1 / 1 | 2 / 0 |
| PDB `minAvailable` | 3 | 2 |

---

## 2. Architecture

```
                    ┌─────────────┐
                    │   KEDA       │
                    │  Operator    │
                    └──────┬──────┘
                           │ polls every 15 sec
                           ▼
                    ┌─────────────┐
                    │ Kafka Broker │◄── queries consumer group lag
                    │ (3.9.x)     │    per partition per topic
                    └──────┬──────┘
                           │
            ┌──────────────┼──────────────┐
            ▼              ▼              ▼
     ┌────────────┐ ┌────────────┐ ┌────────────┐
     │ Trade Topic │ │ Exception  │ │   Cron     │
     │ 48 partns   │ │ 6 partns   │ │ Trigger    │
     └──────┬─────┘ └──────┬─────┘ └──────┬─────┘
            │              │              │
            └──────────────┼──────────────┘
                           │
                    MAX(all triggers)
                           │
                           ▼
                    ┌─────────────┐
                    │ Deployment  │
                    │ payments-   │
                    │ stream      │
                    │ (2–48 pods) │
                    └─────────────┘
```

---

## 3. Prerequisites — AKS KEDA Setup

### 3.1 Install KEDA on AKS

```bash
# Add KEDA Helm repo
helm repo add kedacore https://kedacore.github.io/charts
helm repo update

# Install KEDA in keda namespace
helm install keda kedacore/keda \
  --namespace keda \
  --create-namespace \
  --set image.tag=2.15.0
```

### 3.2 Verify KEDA Installation

```bash
kubectl get pods -n keda
# Expected output:
# keda-operator-...        1/1  Running
# keda-metrics-...         1/1  Running
# keda-admission-webhooks  1/1  Running
```

### 3.3 RBAC (If Required)

KEDA needs permission to scale deployments. The Helm chart handles this by default. If using custom RBAC, ensure the KEDA operator service account has `get`, `list`, `watch`, `patch` on `deployments` and `statefulsets` in the `financial-streams` namespace.

---

## 4. How KEDA Kafka Scaling Works

### 4.1 Scaling Formula

KEDA connects to the Kafka broker, discovers all partitions, and computes lag per partition.

**With `limitToPartitionsWithLag: "true"` (our configuration):**

```
desired_replicas = count(partitions where consumer_lag > 0)
```

This formula **automatically caps** at the partition count. No `maxReplicaCount` hardcoding needed.

**With `limitToPartitionsWithLag: "false"` (default — NOT recommended):**

```
desired_replicas = ceil(total_lag_across_all_partitions / lagThreshold)
```

This can **overshoot** beyond partition count.

### 4.2 Partition Auto-Discovery

KEDA queries the broker on every `pollingInterval` (15 sec). If partitions are added to a topic, KEDA detects them automatically — no config change required.

### 4.3 Multi-Trigger Decision

When multiple triggers are configured, KEDA takes the **MAX** across all triggers:

```
Final replicas = MAX(trade_trigger, exception_trigger, cron_trigger, minReplicaCount)
```

---

## 5. Cooperative Rebalancing & Static Membership

### 5.1 The Rebalance Problem

Every time KEDA adds or removes a pod, Kafka triggers a consumer group rebalance. Without mitigation:

- **Eager rebalancing (old default):** ALL consumers STOP processing for 10–30 seconds
- Scale 2→8 = 3+ rebalances = 30–90 seconds of zero processing
- Scale 8→2 = 3+ rebalances = 30–90 seconds of zero processing

### 5.2 Cooperative Rebalancing (Kafka Streams 3.x Default)

Kafka Streams 3.9.x uses `StreamsPartitionAssignor` which supports cooperative rebalancing internally. Only **partitions that need to move** are revoked — remaining consumers keep processing uninterrupted.

```
Example: Scale from 4 → 6 pods (48 partitions)

EAGER:   ALL 48 partitions pause → reassign → resume (30 sec downtime)
COOPERATIVE: Only 16 partitions move → 32 keep processing (<1 sec disruption)
```

No explicit `partition.assignment.strategy` configuration is needed for Kafka Streams — it's built-in.

### 5.3 Static Group Membership

Each pod gets a persistent identity via `group.instance.id = ${HOSTNAME}`. Benefits:

| Scenario | Without Static Membership | With Static Membership |
|----------|--------------------------|----------------------|
| Pod removed by KEDA | Immediate rebalance of ALL partitions | No rebalance for 5 min (`session.timeout.ms`) |
| Pod crash | Immediate rebalance | Kafka waits 5 min, then reassigns only that pod's partitions |
| Rolling restart | Full rebalance per pod | Zero rebalances if pod returns within 5 min |
| Pod added by KEDA | Full rebalance | Only new partitions assigned (cooperative) |

### 5.4 `internal.leave.group.on.close: false`

When a consumer shuts down gracefully, it normally sends a `LeaveGroup` request which triggers an immediate rebalance. Setting this to `false` means:
- Consumer closes silently
- Coordinator waits `session.timeout.ms` (5 min) before reassigning
- KEDA scale-down doesn't cause immediate rebalances

### 5.5 Combined Effect

```
Scale-up:  2→4→6→8 over 3 minutes
           32 out of 48 partitions NEVER stop processing
           Only newly assigned partitions have brief assignment delay

Scale-down: 8→4→2 over 20 minutes
            Removed pods don't trigger rebalance for 5 min
            Remaining pods keep processing all their partitions
```

---

## 6. Application Configuration (application.yml)

### 6.1 New/Changed Properties

```yaml
app:
  stream:
    # --- NEW: KEDA Cooperative Rebalancing & Static Membership ---
    
    # Static group membership: use pod hostname as member identity.
    # Prevents rebalance storms during KEDA scale-up/down.
    # Set via env var HOSTNAME in K8s; local dev uses fallback.
    group-instance-id: ${HOSTNAME:local-dev-instance}
    
    # Don't trigger rebalance when a consumer closes gracefully.
    # Combined with session-timeout-ms, KEDA can remove pods without rebalance.
    internal-leave-group-on-close: false

    consumer:
      # CHANGED from 600000 → 300000
      # Max time between poll() calls before Kafka kicks the consumer.
      # 5 min matches session-timeout-ms for consistent behavior.
      max-poll-interval-ms: 300000
      
      # CHANGED from 45000 → 300000
      # How long Kafka waits with no heartbeat before declaring consumer dead.
      # 5 min gives KEDA time to scale down without triggering rebalance.
      session-timeout-ms: 300000
      
      # CHANGED from 3000 → 10000
      # How often the consumer sends "I'm alive" to the group coordinator.
      # Must be < session-timeout-ms / 3. 10 sec = 30 heartbeats per session window.
      heartbeat-interval-ms: 10000
```

### 6.2 Full application.yml

```yaml
spring:
  application:
    name: kafka-streams-cb
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
management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics,prometheus
  endpoint:
    health:
      probes:
        enabled: true

app:
  input:
    topic: payments.input
  output:
    topic: payments.output
  stream:
    application-id: payments-stream-v1
    processing-guarantee: exactly_once_v2
    num-stream-threads: 1
    commit-interval-ms: 1000
    # State dir still required by Kafka Streams for task metadata, even stateless.
    state-dir: /tmp/kafka-streams/payments
    replication-factor: 3
    # No standby replicas — stateless topology.
    num-standby-replicas: 0
    max-task-idle-ms: 1000

    # --- KEDA Cooperative Rebalancing & Static Membership ---
    group-instance-id: ${HOSTNAME:local-dev-instance}
    internal-leave-group-on-close: false

    consumer:
      auto-offset-reset: latest
      max-poll-records: 250
      max-poll-interval-ms: 300000
      session-timeout-ms: 300000
      heartbeat-interval-ms: 10000
      request-timeout-ms: 30000
      retry-backoff-ms: 500
      fetch-max-bytes: 52428800
      max-partition-fetch-bytes: 1048576
    producer:
      acks: all
      linger-ms: 5
      batch-size: 65536
      buffer-memory: 67108864
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

### 6.3 Property Reference Table

| Property | Old Value | New Value | Kafka Property | Purpose |
|----------|-----------|-----------|---------------|---------|
| `group-instance-id` | _(not set)_ | `${HOSTNAME}` | `group.instance.id` | Static membership — prevents rebalance storms |
| `internal-leave-group-on-close` | _(not set)_ | `false` | `internal.leave.group.on.close` | No LeaveGroup on shutdown |
| `session-timeout-ms` | 45000 (45s) | 300000 (5m) | `session.timeout.ms` | Dead detection window — gives KEDA time |
| `heartbeat-interval-ms` | 3000 (3s) | 10000 (10s) | `heartbeat.interval.ms` | Alive signal frequency |
| `max-poll-interval-ms` | 600000 (10m) | 300000 (5m) | `max.poll.interval.ms` | Matches session timeout |

### 6.4 Heartbeat/Session Relationship

```
Rule: heartbeat.interval.ms  <  session.timeout.ms / 3

Our config: 10,000  <  300,000 / 3  =  100,000  ✅

What this means:
- Consumer sends heartbeat every 10 seconds
- Kafka waits 5 minutes with no heartbeat before declaring dead
- 30 heartbeats fit in one session window — plenty of margin
- Pod can be dead for up to 5 minutes before Kafka notices
- This is intentional: gives KEDA scaling time to settle
```

### 6.5 Exactly-Once v2 Compatibility

These settings are **fully independent** from `processing.guarantee`. Whether you use `at_least_once` or `exactly_once_v2`:

- `heartbeat.interval.ms` — configurable, not locked by EOS
- `session.timeout.ms` — configurable, not locked by EOS
- `group.instance.id` — works with EOS v2 (used as fencing token)
- `internal.leave.group.on.close` — configurable independently

---

## 7. Java Code Changes (KafkaStreamsConfig.java)

### 7.1 New Parameters Added

```java
// Static membership: pod hostname as member ID. Prevents rebalance storms during KEDA scaling.
@Value("${app.stream.group-instance-id:#{null}}") String groupInstanceId,

// Don't trigger rebalance on graceful close. KEDA can remove pods safely.
@Value("${app.stream.internal-leave-group-on-close:false}") boolean internalLeaveGroupOnClose,
```

### 7.2 New Properties Wired into Kafka Streams

```java
// Static membership: prevents rebalance storms during KEDA scale-up/down.
// Each pod gets a unique group.instance.id (its hostname), so Kafka recognizes
// returning pods and skips rebalance if they rejoin within session.timeout.ms.
if (groupInstanceId != null && !groupInstanceId.isBlank()) {
    props.put("group.instance.id", groupInstanceId);
}

// Don't trigger rebalance when consumer shuts down gracefully.
// Kafka Streams will NOT send a LeaveGroup request, so the coordinator
// waits session.timeout.ms before reassigning partitions.
props.put("internal.leave.group.on.close", internalLeaveGroupOnClose);
```

### 7.3 Updated Default Values

| Parameter | Old Default | New Default |
|-----------|-------------|-------------|
| `max-poll-interval-ms` | 600000 | 300000 |
| `session-timeout-ms` | 45000 | 300000 |
| `heartbeat-interval-ms` | 3000 | 10000 |

---

## 8. Kubernetes Manifest (k8s-manifest.yaml)

### 8.1 Deployment Changes

```yaml
spec:
  replicas: 2                        # KEDA will override this based on Kafka lag
  strategy:
    type: RollingUpdate
    rollingUpdate:
      maxSurge: 2                    # Add 2 new pods before removing old ones
      maxUnavailable: 0              # Never kill a pod until replacement is ready
```

**Why `maxSurge: 2, maxUnavailable: 0`:** During rolling updates, new pods come up first. No pod is killed until its replacement is ready. This prevents double-rebalance (remove old + add new).

### 8.2 Static Membership Environment Variable

```yaml
env:
  - name: HOSTNAME
    valueFrom:
      fieldRef:
        fieldPath: metadata.name     # Pod name becomes group.instance.id
```

Each pod gets a unique, stable identity (e.g., `payments-stream-7b9c4-xk2q9`). Kafka uses this to recognize returning consumers and skip rebalancing.

### 8.3 Graceful Shutdown

```yaml
terminationGracePeriodSeconds: 120   # 2 min for clean Kafka Streams shutdown
lifecycle:
  preStop:
    exec:
      command: ["/bin/sh", "-c", "sleep 90"]  # Wait 90 sec before SIGTERM
```

**Timeline:**
1. K8s sends preStop → pod sleeps 90 sec (drains from load balancer)
2. K8s sends SIGTERM → Kafka Streams begins graceful shutdown
3. 30 sec remaining in termination grace period for cleanup
4. K8s sends SIGKILL if still running after 120 sec total

### 8.4 Pod Disruption Budget

```yaml
spec:
  minAvailable: 2    # At least 2 pods always running during disruptions
```

Changed from 3→2 to match `minReplicaCount: 2` in KEDA.

---

## 9. KEDA ScaledObject Configuration

### 9.1 Complete KEDA ScaledObject

```yaml
apiVersion: keda.sh/v1alpha1
kind: ScaledObject
metadata:
  name: payments-stream-scaler
  namespace: financial-streams
spec:
  scaleTargetRef:
    apiVersion: apps/v1
    kind: Deployment
    name: payments-stream

  # Minimum pods — never go below 2 for high availability
  minReplicaCount: 2
  # Wait 5 min after last trigger activation before allowing scale-to-min
  cooldownPeriod: 300
  # Check Kafka lag every 15 seconds
  pollingInterval: 15

  advanced:
    horizontalPodAutoscalerConfig:
      behavior:
        # --- Scale-Up: fast but controlled ---
        scaleUp:
          # Wait 1 min to batch rapid signals before acting
          stabilizationWindowSeconds: 60
          policies:
            # Add 2 pods per step (gentle, fewer rebalances)
            - type: Pods
              value: 2
              # Evaluate every 1 minute
              periodSeconds: 60

        # --- Scale-Down: slow and cautious ---
        scaleDown:
          # Wait 5 min of sustained zero-lag before starting scale-down
          stabilizationWindowSeconds: 300
          policies:
            # Remove 4 pods at a time (48 → 2 in ~1 hour)
            - type: Pods
              value: 4
              # Evaluate every 5 minutes
              periodSeconds: 300
          # If multiple policies, pick the one that removes fewest pods
          selectPolicy: Min

  triggers:
    # --- Trigger 1: Trade topic (48 partitions) ---
    - type: kafka
      metadata:
        bootstrapServers: kafka-broker-0.kafka-broker:9092,...
        consumerGroup: payments-stream-v1
        topic: payments.input
        lagThreshold: "500"
        activationLagThreshold: "10"
        limitToPartitionsWithLag: "true"
        offsetResetPolicy: "latest"
        allowIdleConsumers: "false"
        excludePersistentLag: "false"

    # --- Trigger 2: Exception topic (6 partitions) ---
    - type: kafka
      metadata:
        bootstrapServers: kafka-broker-0.kafka-broker:9092,...
        consumerGroup: payments-exception-v1
        topic: payments.exception
        lagThreshold: "500"
        activationLagThreshold: "10"
        limitToPartitionsWithLag: "true"
        offsetResetPolicy: "latest"
        allowIdleConsumers: "false"
        excludePersistentLag: "false"

    # --- Trigger 3: Cron floor during market hours ---
    - type: cron
      metadata:
        timezone: Europe/Zurich
        start: "0 8 * * 1-5"          # Mon-Fri 8 AM CET/CEST
        end: "30 16 * * 1-5"           # Mon-Fri 4:30 PM CET/CEST
        desiredReplicas: "4"           # Floor, not ceiling
```

### 9.2 KEDA Parameter Reference

| Parameter | Value | Purpose |
|-----------|-------|---------|
| `minReplicaCount` | 2 | HA baseline — never go below this |
| `cooldownPeriod` | 300 (5 min) | Wait before allowing scale-to-min |
| `pollingInterval` | 15 (15 sec) | How often KEDA checks Kafka lag |
| `lagThreshold` | 500 | Per-partition lag threshold for scaling |
| `activationLagThreshold` | 10 | Min lag to activate from zero replicas |
| `limitToPartitionsWithLag` | true | **KEY:** replicas = count of lagging partitions |
| `stabilizationWindowSeconds` (up) | 60 | Batch scale-up signals for 1 min |
| `stabilizationWindowSeconds` (down) | 300 | Wait 5 min of zero-lag before scale-down |
| `scaleUp.value` | 2 pods | Add 2 pods per step |
| `scaleDown.value` | 4 pods | Remove 4 pods per step |

---

## 10. Scaling Behavior

### 10.1 Scale-Up Timeline

```
Time 0:00  → 2 pods  (lag detected on trade topic)
Time 1:00  → 4 pods  (cooperative: only new partitions assigned)
Time 2:00  → 6 pods  (remaining partitions keep processing)
Time 3:00  → 8 pods  (current max needed for our load)
```

If production requires more: KEDA auto-scales up to 48 (trade topic partition count).

### 10.2 Scale-Down Timeline

```
Time 0:00    → 8 pods, lag = 0
Time 0-5:00  → 8 pods (stabilization window — no action)
Time 5:00    → 4 pods (remove 4, static membership: no rebalance for 5 min)
Time 10:00   → 2 pods (remove 2, at minReplicaCount — stop)
```

### 10.3 Scale-Down from 48 (Worst Case)

```
Time 0:00    → 48 pods, lag = 0
Time 5:00    → 44 pods (remove 4)
Time 10:00   → 40 pods
Time 15:00   → 36 pods
...
Time 55:00   → 4 pods
Time 60:00   → 2 pods (at minReplicaCount — stop)

Total: ~1 hour for full scale-down
```

### 10.4 Rebalance Impact During Scaling

| Event | Processing Disruption |
|-------|--------------------|
| Add 2 pods (scale-up) | Near-zero — cooperative assigns only new partitions |
| Remove 4 pods (scale-down) | Zero for 5 min, then only orphaned partitions reassigned |
| Pod crash | Zero for 5 min (static membership wait), then partial reassign |
| Rolling restart | Zero if pod returns within 5 min |

---

## 11. Multi-Topic Scaling Logic

### 11.1 Decision Formula

```
Final replicas = MAX(trade_trigger, exception_trigger, cron_trigger, minReplicaCount)
```

### 11.2 Scenario Examples

| Scenario | Trade (48 parts) | Exception (6 parts) | Cron | Min | Result |
|----------|-----------------|---------------------|------|-----|--------|
| Trade lag on 48 partitions, market hours | 48 | 0 | 4 | 2 | **48** |
| Trade lag on 8 partitions, market hours | 8 | 0 | 4 | 2 | **8** |
| No trade lag, exception lag on 6, market hours | 0 | 6 | 4 | 2 | **6** |
| No trade lag, exception lag on 3, market hours | 0 | 3 | 4 | 2 | **4** |
| No lag anywhere, market hours | 0 | 0 | 4 | 2 | **4** |
| No lag anywhere, after hours | 0 | 0 | 0 | 2 | **2** |
| Trade lag on 10, exception lag on 6, after hours | 10 | 6 | 0 | 2 | **10** |

### 11.3 Critical Points

- Exception topic (6 partitions) will **never** cause scaling beyond 6 replicas by itself
- Trade topic (48 partitions) can scale up to 48 replicas if all partitions have lag
- Topics with different consumer groups need **separate triggers** (already configured)
- KEDA evaluates each trigger independently, then takes MAX

---

## 12. Preventing the Yo-Yo Scaling Problem

### 12.1 The Problem

You scale to 48 pods, lag clears, KEDA scales down to 2, lag returns immediately, scale up again — this wastes resources, causes constant rebalances, and destabilizes the system.

### 12.2 Solution 1: HPA Behavior Policy (Primary — Already Configured)

Control **how fast** scale-down happens via `advanced.horizontalPodAutoscalerConfig.behavior`:

```yaml
scaleDown:
  stabilizationWindowSeconds: 300   # 5 min of zero-lag before ANY scale-down
  policies:
    - type: Pods
      value: 4                      # Remove only 4 pods at a time
      periodSeconds: 300            # Re-evaluate every 5 min
  selectPolicy: Min                 # Pick the policy that removes fewest pods
```

If lag reappears at **any point** during the gradual scale-down, scale-up kicks in immediately (30 sec stabilization).

### 12.3 Solution 2: Cron Trigger Floor (Already Configured)

Keep replicas high during market hours regardless of lag:

```yaml
- type: cron
  metadata:
    timezone: America/New_York
    start: "0 8 * * 1-5"       # Mon-Fri 8 AM ET
    end: "0 17 * * 1-5"        # Mon-Fri 5 PM ET
    desiredReplicas: "4"        # Floor during market hours
```

During market hours: `MAX(kafka_metric, 4)` — never below 4 pods even with zero lag.  
After market hours: pure Kafka lag-based — can go down to `minReplicaCount: 2`.

### 12.4 Solution 3: Higher `minReplicaCount`

If your steady-state load always needs N pods:

```yaml
minReplicaCount: 4    # Never go below 4, even off-hours
```

### 12.5 Solution 4: `cooldownPeriod`

Prevents scale-to-min for N seconds after last trigger activation:

```yaml
cooldownPeriod: 600   # 10 min after last lag event before allowing scale-to-min
```

This is a blunt instrument — it only delays the final scale-to-min event, not intermediate steps. Use HPA behavior policy (Solution 1) for granular control.

---

## 13. Scale-Down Speed Tuning

### 13.1 Tuning Table

Adjust `value` (pods per step), `periodSeconds`, and `stabilizationWindowSeconds` to control drain speed:

| Target Duration (48→2) | `value` (pods) | `periodSeconds` | `stabilizationWindowSeconds` | Result |
|------------------------|----------------|-----------------|------------------------------|--------|
| ~30 min | 8 | 300 | 300 | 6 steps × 5 min |
| **~1 hour (recommended)** | **4** | **300** | **300** | **12 steps × 5 min** |
| ~2 hours | 2 | 300 | 600 | 23 steps × 5 min + 10 min wait |
| ~3 hours | 2 | 600 | 600 | 23 steps × 10 min + 10 min wait |

**Faster scale-down = higher risk of lag returning. Slower = safer but uses more resources.**

### 13.2 Math Formula

```
steps_needed    = ceil((current_replicas - minReplicaCount) / pods_per_step)
drain_time      = stabilizationWindowSeconds + (steps_needed × periodSeconds)
```

Example for 48→2, removing 4 pods every 5 min:
```
steps = ceil((48 - 2) / 4) = 12 steps
drain = 300 sec + (12 × 300 sec) = 300 + 3600 = 3900 sec ≈ 65 min
```

### 13.3 Using Percentage-Based Policy

Instead of fixed pod count, scale down by percentage:

```yaml
scaleDown:
  policies:
    - type: Percent
      value: 10            # Remove 10% of current replicas per step
      periodSeconds: 300
```

48 pods × 10% = remove 4.8 → 4 pods per step (same as our fixed policy at 48, but adapts at smaller sizes).

### 13.4 Combining Multiple Policies

You can combine `Pods` and `Percent` policies with `selectPolicy`:

```yaml
scaleDown:
  policies:
    - type: Pods
      value: 4                 # Remove max 4 pods
      periodSeconds: 300
    - type: Percent
      value: 10                # Or max 10% of current count
      periodSeconds: 300
  selectPolicy: Min            # Use whichever removes FEWER pods
```

At 48 pods: `Min(4, ceil(48×0.10)=5)` → removes 4 pods  
At 10 pods: `Min(4, ceil(10×0.10)=1)` → removes 1 pod (gentler at small scale)

---

## 14. Heartbeat, Session Timeout & Dead Detection Deep Dive

### 14.1 Common Misconception

> **"heartbeat.interval.ms: 10000 means a pod can be dead for 10 seconds"**

**This is incorrect.** `heartbeat.interval.ms` only controls how often the consumer sends an "I'm alive" signal. It does NOT control when a pod is declared dead.

### 14.2 The Three Settings — What Each Controls

| Setting | Our Value | What It Controls | Analogy |
|---------|-----------|-----------------|---------|
| `heartbeat.interval.ms` | 10,000 (10 sec) | How often consumer says "I'm alive" | How often you check in at reception |
| `session.timeout.ms` | 300,000 (5 min) | How long Kafka waits before declaring consumer dead | How long reception waits before marking you absent |
| `max.poll.interval.ms` | 300,000 (5 min) | Max time between `poll()` calls before consumer is kicked | Max time between actually doing work |

### 14.3 Dead Detection Timeline

```
Time 0:00   → Heartbeat sent ✅
Time 0:10   → Heartbeat sent ✅
Time 0:20   → Pod crashes 💀
Time 0:30   → No heartbeat received (missed 1)
Time 0:40   → No heartbeat received (missed 2)
Time 0:50   → No heartbeat received (missed 3)
  ...         (continues missing heartbeats)
Time 5:20   → 5 min since last successful heartbeat
              → Kafka coordinator declares consumer DEAD
              → Triggers partition reassignment (rebalance)
```

**The pod can be dead for up to 5 minutes before Kafka notices** — this is intentional for KEDA scaling.

### 14.4 The Rule

```
heartbeat.interval.ms  <  session.timeout.ms / 3

Our config: 10,000  <  300,000 / 3  (= 100,000)  ✅

This gives 30 heartbeats per session window — plenty of margin
for network blips or GC pauses to not trigger false dead detection.
```

### 14.5 Trade-off Matrix

| `session.timeout.ms` | Dead Detection Speed | KEDA Scaling Safety | Use Case |
|-----------------------|---------------------|--------------------|----|
| 10,000 (10 sec) | Fast — 10 sec | Poor — every KEDA scale event triggers rebalance | Not recommended |
| 45,000 (45 sec) | Medium — 45 sec | Poor — tight for KEDA operations | Old config (before KEDA) |
| **300,000 (5 min)** | **Slow — 5 min** | **Good — KEDA can scale freely** | **Our choice** |
| 600,000 (10 min) | Very slow — 10 min | Excellent — but crashed pods sit idle too long | Overly conservative |

### 14.6 Why 5 Minutes Is the Right Balance

- **Scale-down:** KEDA removes a pod → Kafka waits 5 min → no rebalance if a new pod takes over
- **Scale-up:** New pod joins → cooperative assignment → existing pods unaffected
- **Pod crash:** Worst case = 5 min of idle partitions, but this is rare
- **Rolling restart:** Pod restarts within 2 min (`terminationGracePeriodSeconds: 120`) → well within 5 min window → **zero rebalance**

---

## 15. Exactly-Once v2 Compatibility

### 15.1 Independence from Heartbeat/Session Settings

Exactly-once v2 (`processing.guarantee: exactly_once_v2`) controls **transactional processing** — it has nothing to do with heartbeating. They are completely independent subsystems:

```
Heartbeat subsystem:
  Thread:    Background heartbeat thread
  Purpose:   Sends "I'm alive" to group coordinator
  Controls:  Membership detection, rebalance triggering
  Config:    heartbeat.interval.ms, session.timeout.ms
  
EOS v2 subsystem:
  Thread:    Transaction thread
  Purpose:   Commits offsets + output atomically
  Controls:  Read-process-write atomicity, exactly-once delivery
  Config:    processing.guarantee, transaction.timeout.ms
```

### 15.2 All These Settings Are Independently Configurable

Whether you use `at_least_once` or `exactly_once_v2`, these are **fully under your control**:

| Setting | Locked by EOS v2? | Configurable? |
|---------|-------------------|---------------|
| `heartbeat.interval.ms` | No | Yes — set whatever you want |
| `session.timeout.ms` | No | Yes — set whatever you want |
| `max.poll.interval.ms` | No | Yes — set whatever you want |
| `group.instance.id` | No | Yes — works with EOS v2 |
| `internal.leave.group.on.close` | No | Yes — independent |
| `commit.interval.ms` | Ignored by EOS v2 | EOS commits per transaction, not by interval |

### 15.3 EOS v2 + Static Membership — How Fencing Works

When using `exactly_once_v2` with `group.instance.id` (static membership), transaction fencing uses the group instance ID:

```
Scenario 1: Pod restarts with SAME group.instance.id (same pod name)
  → New instance fences the old zombie producer automatically ✅
  → Old uncommitted transactions are aborted
  → No duplicate processing

Scenario 2: KEDA removes pod, new pod gets old pod's partitions
  → New pod has a DIFFERENT group.instance.id
  → New transaction.id fences the old one ✅
  → Still safe — no duplicates

Scenario 3: Network partition — old pod thinks it's alive, new pod takes over
  → EOS v2 fencing ensures only ONE producer can write per partition ✅
  → Old pod's writes are rejected with FencedException
```

### 15.4 Configuration for EOS v2 (If/When You Switch)

```yaml
app:
  stream:
    processing-guarantee: exactly_once_v2
    # transaction.timeout.ms should be > session.timeout.ms for safety
    # Default is 10000 (10 sec) — increase if using long session timeout
    
    # These remain UNCHANGED regardless of processing guarantee:
    group-instance-id: ${HOSTNAME:local-dev-instance}
    internal-leave-group-on-close: false
    consumer:
      session-timeout-ms: 300000
      heartbeat-interval-ms: 10000
      max-poll-interval-ms: 300000
```

---

## 16. Cooperative Rebalancing — Visual Walkthrough

### 16.1 How Kafka Streams Handles Assignment

Kafka Streams 3.9.x uses `StreamsPartitionAssignor` which supports cooperative rebalancing internally. **No explicit `partition.assignment.strategy` configuration is needed** — it's built-in.

> **Important:** `CooperativeStickyAssignor` is for plain Kafka consumers only. Kafka Streams has its own assignor that already supports cooperative mode.

### 16.2 Visual: Scale from 4 → 6 Pods (48 Partitions)

#### Eager Rebalancing (Old Behavior — NOT Our Config)

```
BEFORE:
  Pod1 [P0-P11]  ──processing──►
  Pod2 [P12-P23] ──processing──►
  Pod3 [P24-P35] ──processing──►
  Pod4 [P36-P47] ──processing──►

REBALANCE TRIGGERED (2 new pods join):
  Pod1 [P0-P11]  ──STOP── ✋ ALL partitions revoked
  Pod2 [P12-P23] ──STOP── ✋ 
  Pod3 [P24-P35] ──STOP── ✋ 15-30 seconds of ZERO processing
  Pod4 [P36-P47] ──STOP── ✋ 
  Pod5 []        ──waiting──
  Pod6 []        ──waiting──

AFTER REASSIGNMENT:
  Pod1 [P0-P7]   ──START──► 
  Pod2 [P8-P15]  ──START──►
  Pod3 [P16-P23] ──START──►
  Pod4 [P24-P31] ──START──►
  Pod5 [P32-P39] ──START──►  (new)
  Pod6 [P40-P47] ──START──►  (new)

Impact: 100% of partitions stopped for 15-30 seconds
```

#### Cooperative Rebalancing (Our Config)

```
BEFORE:
  Pod1 [P0-P11]  ──processing──►
  Pod2 [P12-P23] ──processing──►
  Pod3 [P24-P35] ──processing──►
  Pod4 [P36-P47] ──processing──►

REBALANCE TRIGGERED (2 new pods join):
  Pod1 [P0-P11]  ──keeps P0-P7──►  revokes P8-P11 only
  Pod2 [P12-P23] ──keeps P12-P15──► revokes P16-P23 only
  Pod3 [P24-P35] ──keeps P24-P31──► revokes P32-P35 only
  Pod4 [P36-P47] ──keeps P36-P39──► revokes P40-P47 only
                    │
                    │  32 partitions NEVER stopped processing
                    │  Only 16 partitions briefly paused (<1 sec)
                    ▼
AFTER REASSIGNMENT:
  Pod1 [P0-P7]   ──continues──►  (never stopped)
  Pod2 [P12-P15] ──continues──►  (never stopped)
  Pod3 [P24-P31] ──continues──►  (never stopped)
  Pod4 [P36-P39] ──continues──►  (never stopped)
  Pod5 [P8-P11, P16-P19]   ──START──►  (new — gets revoked partitions)
  Pod6 [P20-P23, P32-P35, P40-P47] ──START──►  (new)

Impact: 67% of partitions (32/48) never stopped processing
```

### 16.3 Scale-Down: 6 → 4 Pods

#### Without Static Membership

```
KEDA removes Pod5 and Pod6:
  Pod5 sends LeaveGroup → IMMEDIATE rebalance
  ALL 6 pods stop → reassign ALL 48 partitions → 4 pods start
  Impact: 15-30 sec full stop
```

#### With Static Membership (`group.instance.id` + `internal.leave.group.on.close: false`)

```
KEDA removes Pod5 and Pod6:
  Pod5 shuts down silently (no LeaveGroup sent)
  Pod6 shuts down silently (no LeaveGroup sent)
  
  Kafka coordinator: "Pod5 and Pod6 haven't heartbeated..."
  
  Time 0:00-5:00 → Coordinator waiting (session.timeout.ms = 5 min)
                    Pod1-Pod4 keep processing their partitions normally ✅
                    Pod5's partitions (P8-P11, P16-P19) idle but no rebalance
                    Pod6's partitions (P20-P23, P32-P35, P40-P47) idle
  
  Time 5:00     → Coordinator: "Pod5 and Pod6 are dead"
                   Cooperative reassignment: only orphaned partitions move
                   Pod1-Pod4 get the orphaned partitions assigned
                   Pod1-Pod4 NEVER stopped their existing partitions ✅

Impact: Zero disruption for 32 partitions. 16 partitions idle for 5 min.
```

---

## 17. KEDA Kafka Authentication (SASL/TLS)

If your Kafka brokers require authentication, configure KEDA to connect securely.

### 17.1 Create Kubernetes Secret

```yaml
apiVersion: v1
kind: Secret
metadata:
  name: kafka-credentials
  namespace: financial-streams
type: Opaque
stringData:
  sasl: "plaintext"           # or "scram_sha256", "scram_sha512"
  username: "keda-scaler"
  password: "your-secure-password"
  # For TLS (optional):
  # tls: "enable"
  # ca: |
  #   -----BEGIN CERTIFICATE-----
  #   ...your CA cert...
  #   -----END CERTIFICATE-----
```

### 17.2 Reference in KEDA TriggerAuthentication

```yaml
apiVersion: keda.sh/v1alpha1
kind: TriggerAuthentication
metadata:
  name: kafka-trigger-auth
  namespace: financial-streams
spec:
  secretTargetRef:
    - parameter: sasl
      name: kafka-credentials
      key: sasl
    - parameter: username
      name: kafka-credentials
      key: username
    - parameter: password
      name: kafka-credentials
      key: password
```

### 17.3 Reference in ScaledObject Triggers

```yaml
triggers:
  - type: kafka
    metadata:
      bootstrapServers: kafka-broker:9092
      consumerGroup: payments-stream-v1
      topic: payments.input
      lagThreshold: "500"
      limitToPartitionsWithLag: "true"
    authenticationRef:
      name: kafka-trigger-auth        # Reference the TriggerAuthentication
```

---

## 18. Monitoring & Verification

### 18.1 Verify KEDA ScaledObject

```bash
# Check ScaledObject status
kubectl describe scaledobject payments-stream-scaler -n financial-streams

# Watch HPA created by KEDA
kubectl get hpa -n financial-streams -o wide -w

# Expected output:
# NAME                              REFERENCE                TARGETS    MINPODS  MAXPODS  REPLICAS
# keda-hpa-payments-stream-scaler   Deployment/payments-stream  1/1       2        100      8
```

### 18.2 Monitor Scaling Events

```bash
# Watch scaling events in real-time
kubectl get events -n financial-streams --sort-by='.lastTimestamp' -w

# Watch pod count changes
kubectl get deployment payments-stream -n financial-streams --watch

# Check KEDA operator logs
kubectl logs -n keda -l app.kubernetes.io/name=keda-operator -f
```

### 18.3 Verify Static Membership

```bash
# Check consumer group members (from a Kafka broker pod)
kafka-consumer-groups --bootstrap-server kafka-broker:9092 \
  --describe --group payments-stream-v1

# Expected: Each member has a group.instance.id matching the pod name
# GROUP            TOPIC            PARTITION  CURRENT-OFFSET  LOG-END-OFFSET  LAG   CONSUMER-ID                          HOST           CLIENT-ID
# payments-stream  payments.input   0          1000            1000            0     payments-stream-7b9c4-xk2q9-...      /10.244.0.5    payments-stream-v1-...
```

### 18.4 Verify Cooperative Rebalancing

```bash
# Watch for rebalance events in application logs
kubectl logs -n financial-streams -l app=payments-stream -f | grep -i "rebalance\|assigned\|revoked"

# Healthy output during scale-up:
# "Assigned partitions: [payments.input-12, payments.input-13]"   ← only new partitions
# NOT: "Revoked all partitions ... Assigned all partitions"       ← this would indicate eager rebalance
```

### 18.5 Prometheus Metrics

The following metrics are available at `/actuator/prometheus`:

```
# KEDA-related
keda_scaler_is_active{scaler="kafka"}
keda_metrics_adapter_scaler_metrics_value{scaler="kafka"}

# Kafka Streams consumer lag (via Micrometer)
kafka_consumer_fetch_manager_records_lag{topic="payments.input"}
kafka_consumer_coordinator_rebalance_total
kafka_consumer_coordinator_last_rebalance_seconds_ago
```

---

## 19. Troubleshooting

### 19.1 KEDA Not Scaling

```bash
# Check if KEDA can reach Kafka
kubectl describe scaledobject payments-stream-scaler -n financial-streams
# Look for: "Error getting kafka consumer lag: ..."

# Verify consumer group exists
kafka-consumer-groups --bootstrap-server kafka-broker:9092 --list | grep payments
```

### 19.2 Too Many Rebalances

```bash
# Check if static membership is working
kafka-consumer-groups --bootstrap-server kafka-broker:9092 \
  --describe --group payments-stream-v1 --members

# If group.instance.id column is empty → static membership not configured
# Fix: Verify HOSTNAME env var is set in Deployment
```

### 19.3 Scaling Too Aggressively

Increase stabilization windows:
```yaml
scaleUp:
  stabilizationWindowSeconds: 120    # Wait 2 min instead of 1
scaleDown:
  stabilizationWindowSeconds: 600    # Wait 10 min instead of 5
```

### 19.4 Scaling Not Fast Enough

Increase pods per step:
```yaml
scaleUp:
  policies:
    - type: Pods
      value: 4          # Add 4 pods per step instead of 2
      periodSeconds: 30  # Evaluate every 30 sec instead of 60
```

### 19.5 Pods Stuck in Terminating

If `terminationGracePeriodSeconds` is too short:
```yaml
terminationGracePeriodSeconds: 180   # Increase to 3 min
lifecycle:
  preStop:
    exec:
      command: ["/bin/sh", "-c", "sleep 120"]  # Increase preStop sleep
```

---

## Dev Team Checklist

- [ ] Review `application.yml` changes (Section 6)
- [ ] Review `KafkaStreamsConfig.java` changes (Section 7)
- [ ] Verify Kafka Streams uses cooperative rebalancing (Section 5.2)
- [ ] Test static membership locally with multiple instances
- [ ] Verify circuit breaker still works with new timeout values
- [ ] Run integration tests with updated consumer config

## DevOps Team Checklist

- [ ] Install KEDA on AKS cluster (Section 3)
- [ ] Remove existing HPA (`payments-stream-hpa`)
- [ ] Apply updated `k8s-manifest.yaml` (Section 8)
- [ ] Apply KEDA ScaledObject (Section 9)
- [ ] Update topic names and bootstrap servers in ScaledObject triggers
- [ ] Update consumer group names in ScaledObject triggers
- [ ] Update cron trigger timezone and hours for your market
- [ ] Verify KEDA can connect to Kafka brokers
- [ ] Set up Prometheus alerting for `keda_scaler_is_active`
- [ ] Test scale-up by producing high-volume test messages
- [ ] Test scale-down by stopping producers and watching drain
- [ ] Verify PDB allows KEDA to scale down (minAvailable: 2)
- [ ] Document runbook for KEDA ScaledObject troubleshooting

---

## 20. FAQ

### Q1: Do we need to hardcode `maxReplicaCount` to match partition count?

**No.** With `limitToPartitionsWithLag: "true"`, KEDA auto-discovers partition count from the broker on every `pollingInterval` (15 sec). The desired replica count = number of partitions with lag, which physically cannot exceed the total partition count. If you add partitions to a topic, KEDA detects them automatically — no config change needed.

If you want a safety ceiling to prevent runaway scaling due to misconfiguration, set it generously:
```yaml
maxReplicaCount: 50   # Safety ceiling, not the actual limit
```

### Q2: If trade topic has 48 partitions and exception topic has 6, can exception topic cause 48 replicas?

**No.** Each topic/trigger is evaluated independently. With `limitToPartitionsWithLag: "true"`:
- Exception topic can cause at most **6 replicas** (its partition count)
- Trade topic can cause at most **48 replicas**
- KEDA takes `MAX(trade, exception, cron, min)` — exception alone cannot push beyond 6

### Q3: What happens if we use comma-separated topics in one trigger instead of separate triggers?

With one trigger: `topic: payments.input,payments.exception`
- All topics must share the **same consumer group** — if they don't, lag won't be measured correctly
- You can't set different `lagThreshold` per topic
- KEDA still takes MAX across topics

**Recommendation:** Use separate triggers per topic when topics have different consumer groups or need different thresholds.

### Q4: Does `heartbeat.interval.ms: 10000` mean a pod is declared dead after 10 seconds?

**No.** `heartbeat.interval.ms` controls how often the consumer sends "I'm alive." Dead detection is controlled by `session.timeout.ms` (5 min in our config). A pod can be dead for up to 5 minutes before Kafka notices. See [Section 14](#14-heartbeat-session-timeout--dead-detection-deep-dive) for full details.

### Q5: Is `heartbeat.interval.ms` locked by Exactly-Once v2?

**No.** All heartbeat/session settings are fully configurable regardless of `processing.guarantee`. EOS v2 controls transactional processing — a completely separate subsystem. See [Section 15](#15-exactly-once-v2-compatibility) for details.

### Q6: What version of Kafka supports partition count discovery by KEDA?

**All modern Kafka versions** (0.10+, including our 3.9.x). KEDA uses the Kafka AdminClient API to call `DescribeTopics` which returns partition count. This has been available since Kafka 0.10.

### Q7: When KEDA scales down, why don't we immediately get a rebalance?

Because of **static membership** (`group.instance.id`) + `internal.leave.group.on.close: false`:
1. Pod shuts down silently — no `LeaveGroup` request sent
2. Kafka coordinator doesn't know the pod is gone
3. Coordinator waits `session.timeout.ms` (5 min) for the member to heartbeat
4. After 5 min with no heartbeat → coordinator reassigns only that member's partitions
5. Other pods keep processing uninterrupted the entire time

### Q8: What if we need to scale faster than 2 pods/minute?

Adjust the scale-up policy:
```yaml
scaleUp:
  policies:
    - type: Pods
      value: 4            # 4 pods per step instead of 2
      periodSeconds: 30    # Every 30 sec instead of 60
```
Trade-off: more pods per step = more partitions moved in each cooperative rebalance.

### Q9: How does the cron trigger interact with Kafka triggers?

KEDA takes `MAX(all triggers)`. The cron trigger sets a **floor**, not a ceiling:
- If Kafka lag needs 8 pods and cron says 4 → KEDA scales to 8
- If Kafka lag needs 0 pods and cron says 4 → KEDA keeps 4
- If cron is inactive (after hours) and lag needs 0 → KEDA scales to `minReplicaCount` (2)

### Q10: Can we have different scale-down speeds for different times of day?

**Not directly with KEDA.** The HPA behavior policy is static. Workarounds:
- Use a higher cron `desiredReplicas` during volatile hours (keeps pods high)
- Adjust `cooldownPeriod` via a CronJob that patches the ScaledObject
- Use multiple ScaledObjects with different behaviors (advanced)
