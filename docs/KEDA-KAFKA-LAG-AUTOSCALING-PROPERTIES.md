# KEDA Kafka Lag-Based AKS Pod Autoscaling — Properties Reference

**Application:** Kafka Streams Payment Processing (`payments-stream`)  
**Cluster:** AKS (Azure Kubernetes Service)  
**Stack:** Spring Boot 3.5.9 · Kafka Streams 3.9.x · KEDA 2.15+ · Resilience4j 2.2.0  
**Date:** March 2026

---

## Table of Contents

1. [Goal](#1-goal)
2. [How KEDA Kafka Scaling Works — 60 Second Summary](#2-how-keda-kafka-scaling-works--60-second-summary)
3. [Where Each Property Lives](#3-where-each-property-lives)
4. [Layer 1 — application.properties (Spring Boot)](#4-layer-1--applicationproperties-spring-boot)
5. [Layer 2 — Kubernetes Deployment (k8s-manifest.yaml)](#5-layer-2--kubernetes-deployment-k8s-manifestyaml)
6. [Layer 3 — KEDA ScaledObject (k8s-manifest.yaml)](#6-layer-3--keda-scaledobject-k8s-manifestyaml)
7. [Layer 4 — HPA Behavior (inside ScaledObject)](#7-layer-4--hpa-behavior-inside-scaledobject)
8. [Layer 5 — PodDisruptionBudget](#8-layer-5--poddisruptionbudget)
9. [How All Layers Coordinate — Timing Chain](#9-how-all-layers-coordinate--timing-chain)
10. [Tuning Formulas & Rules](#10-tuning-formulas--rules)
11. [Complete Copy-Paste Configuration](#11-complete-copy-paste-configuration)
12. [Tuning for Different Workloads](#12-tuning-for-different-workloads)
13. [Verification Commands](#13-verification-commands)
14. [Common Mistakes That Break Scaling](#14-common-mistakes-that-break-scaling)
15. [Production Diagnostic: "Pods Scale But Only One Gets Messages"](#15-production-diagnostic-pods-scale-but-only-one-gets-messages)
16. [What /actuator/runtime Already Covers](#16-what-actuatorruntime-already-covers)

---

## 1. Goal

Scale AKS pods **up and down automatically** based on Kafka Streams consumer lag, not CPU/memory. KEDA watches the gap between the latest message offset and the committed consumer offset per partition — if messages pile up, pods scale up; when lag clears, pods scale down.

**Why lag, not CPU?**

| Signal | Reacts When... | Lag Signal | CPU Signal |
|--------|---------------|------------|------------|
| Messages pile up | Immediately | Yes | Maybe (minutes later) |
| Messages stop | Immediately | Yes | No (CPU stays high during drain) |
| Processing is slow | Immediately | Yes | No (CPU might be low) |
| Downstream is blocking | Immediately | Yes | No |

---

## 2. How KEDA Kafka Scaling Works — 60 Second Summary

```
Every 15 seconds (pollingInterval):

1. KEDA connects to Kafka broker via AdminClient
2. Queries consumer group committed offsets vs log-end offsets
3. Per partition: lag = log_end_offset - committed_offset
4. With limitToPartitionsWithLag=true:
     desired_replicas = count(partitions where lag > lagThreshold)
5. Across multiple triggers: desired = MAX(trigger_1, trigger_2, ...)
6. HPA applies scale-up/scale-down behavior policies
7. K8s scheduler creates or removes pods
8. Kafka Streams cooperatively rebalances partitions
```

**Physical cap:** You can never have more *useful* pods than partitions. A topic with 48 partitions can have at most 48 actively consuming pods.

---

## 3. Where Each Property Lives

Five layers of configuration must work together. A mismatch in any layer breaks scaling.

```
┌─────────────────────────────────────────────────────────────────────┐
│ Layer 1: application.properties (Spring Boot)                       │
│   → Consumer behavior: session timeout, heartbeat, static membership│
│   → Controls HOW each pod behaves during scale events               │
├─────────────────────────────────────────────────────────────────────┤
│ Layer 2: Kubernetes Deployment (k8s-manifest.yaml)                  │
│   → Pod template: resources, probes, env vars, graceful shutdown    │
│   → Controls WHAT each pod looks like                               │
├─────────────────────────────────────────────────────────────────────┤
│ Layer 3: KEDA ScaledObject (k8s-manifest.yaml)                      │
│   → Kafka triggers: topic, consumerGroup, lagThreshold              │
│   → Controls WHEN scaling happens                                   │
├─────────────────────────────────────────────────────────────────────┤
│ Layer 4: HPA Behavior (inside ScaledObject.advanced)                │
│   → Scale-up/down rates, stabilization windows                      │
│   → Controls HOW FAST scaling happens                               │
├─────────────────────────────────────────────────────────────────────┤
│ Layer 5: PodDisruptionBudget                                        │
│   → minAvailable during voluntary disruptions                       │
│   → Controls the SAFETY FLOOR                                       │
└─────────────────────────────────────────────────────────────────────┘
```

---

## 4. Layer 1 — application.properties (Spring Boot)

These properties go in `src/main/resources/application.properties` (or equivalent `application.yml` in ConfigMap). They control how the Kafka Streams consumer behaves during KEDA scaling events.

### 4.1 Static Membership (Prevents Rebalance Storms)

| Property | Value | Kafka Property | Purpose |
|----------|-------|----------------|---------|
| `app.stream.group-instance-id` | `${HOSTNAME:local-dev-instance}` | `group.instance.id` | Each pod gets a unique, persistent identity. Kafka recognizes returning pods and skips rebalance. |
| `app.stream.internal-leave-group-on-close` | `false` | `internal.leave.group.on.close` | Pod shuts down silently — no `LeaveGroup` sent. Coordinator waits `session-timeout-ms` before reassigning. |

```properties
# Static membership — prevents rebalance storms during KEDA scale events.
# HOSTNAME is injected via Downward API in K8s Deployment env.
app.stream.group-instance-id=${HOSTNAME:local-dev-instance}

# Don't trigger rebalance on graceful shutdown. Kafka waits session-timeout-ms instead.
app.stream.internal-leave-group-on-close=false
```

**Why this matters for KEDA:** Without static membership, every pod termination triggers an immediate full rebalance (all partitions revoked, all consumers stop for 10-30 sec). With static membership + `internal-leave-group-on-close=false`, terminated pods exit silently and Kafka waits `session-timeout-ms` before reassigning — other pods keep processing uninterrupted.

### 4.2 Session Timeout & Heartbeat (Dead Detection Window)

| Property | Value | Kafka Property | Rule |
|----------|-------|----------------|------|
| `app.stream.consumer.session-timeout-ms` | `720000` (12 min) | `session.timeout.ms` | Must be long enough to cover: max circuit breaker delay (10m) + buffer (2m). This is the "dead detection window." |
| `app.stream.consumer.heartbeat-interval-ms` | `10000` (10 sec) | `heartbeat.interval.ms` | Must be < `session-timeout-ms / 3`. Alive signal frequency. |
| `app.stream.consumer.max-poll-interval-ms` | `720000` (12 min) | `max.poll.interval.ms` | Must be >= `session-timeout-ms`. Max time between `poll()` calls. |

```properties
# 12 min session timeout. Covers max circuit breaker delay (10m) + 2m buffer.
# During breaker OPEN, partitions stay reserved on this pod.
# Dead pods caught faster by K8s liveness probes (~30s).
app.stream.consumer.session-timeout-ms=720000

# 10 sec heartbeat. Must be < session-timeout-ms / 3 (720000/3 = 240s). Well within limit.
app.stream.consumer.heartbeat-interval-ms=10000

# 12 min max poll interval. Must be >= session-timeout-ms.
app.stream.consumer.max-poll-interval-ms=720000
```

**Timing relationship:**

```
heartbeat-interval-ms (10s)  <  session-timeout-ms / 3 (240s)  ✅
max-poll-interval-ms (720s)  >=  session-timeout-ms (720s)     ✅
session-timeout-ms (720s)    >=  max circuit breaker delay (600s) + buffer (120s)  ✅
```

### 4.3 Consumer Throughput Properties

| Property | Value | Kafka Property | Purpose |
|----------|-------|----------------|---------|
| `app.stream.consumer.max-poll-records` | `250` | `max.poll.records` | Records per poll batch. Directly affects `lagThreshold` calculation. |
| `app.stream.consumer.auto-offset-reset` | `latest` | `auto.offset.reset` | New consumer groups start from latest — no replay of entire topic. |
| `app.stream.consumer.fetch-max-bytes` | `52428800` (50 MB) | `fetch.max.bytes` | Max data per fetch across all partitions. |
| `app.stream.consumer.max-partition-fetch-bytes` | `1048576` (1 MB) | `max.partition.fetch.bytes` | Max data per fetch per partition. |
| `app.stream.consumer.request-timeout-ms` | `30000` (30 sec) | `request.timeout.ms` | Per-broker request timeout. |
| `app.stream.consumer.retry-backoff-ms` | `500` | `retry.backoff.ms` | Backoff between retries. |

```properties
app.stream.consumer.auto-offset-reset=latest
app.stream.consumer.max-poll-records=250
app.stream.consumer.max-poll-interval-ms=720000
app.stream.consumer.session-timeout-ms=720000
app.stream.consumer.heartbeat-interval-ms=10000
app.stream.consumer.request-timeout-ms=30000
app.stream.consumer.retry-backoff-ms=500
app.stream.consumer.fetch-max-bytes=52428800
app.stream.consumer.max-partition-fetch-bytes=1048576
```

### 4.4 Streams Core Properties

| Property | Value | Purpose |
|----------|-------|---------|
| `app.stream.application-id` | `payments-stream-v1` | Consumer group name. **Must match `consumerGroup` in KEDA trigger.** |
| `app.stream.processing-guarantee` | `exactly_once_v2` | Transactional processing. Independent from heartbeat/session settings. |
| `app.stream.num-stream-threads` | `1` | Stream threads per pod. 1 thread is standard; scale via pods, not threads. |
| `app.stream.commit-interval-ms` | `1000` | Offset commit frequency (ignored by EOS v2 — commits per transaction). |
| `app.stream.replication-factor` | `3` | Internal topic replication. |
| `app.stream.num-standby-replicas` | `0` | Stateless topology — no state stores to replicate. |

```properties
# CRITICAL: application-id = consumer group name.
# Must match consumerGroup in KEDA ScaledObject trigger.
app.stream.application-id=payments-stream-v1

app.stream.processing-guarantee=exactly_once_v2
app.stream.num-stream-threads=1
app.stream.commit-interval-ms=1000
app.stream.state-dir=/tmp/kafka-streams
app.stream.replication-factor=3
app.stream.num-standby-replicas=0
```

### 4.5 Producer Properties (Inside Streams Topology)

```properties
app.stream.producer.acks=all
app.stream.producer.linger-ms=5
app.stream.producer.batch-size=65536
app.stream.producer.buffer-memory=67108864
```

### 4.6 Circuit Breaker Configuration

The circuit breaker directly affects scaling: when the breaker trips, the stream stops polling. Session timeout must cover the max recovery delay so partitions stay reserved on the stopped pod.

| Property | Value | Constraint |
|----------|-------|------------|
| `app.circuit-breaker.restart-delays` | `1m, 2m, 3m, 5m, 7m, 10m` | Max delay (10m) must be < `session-timeout-ms` (12m) |
| `app.circuit-breaker.failure-rate-threshold` | `20` | 20% failures triggers breaker |
| `app.circuit-breaker.time-window-seconds` | `1800` | 30 min sliding window |
| `app.circuit-breaker.minimum-number-of-calls` | `100` | Prevents false positives |

```properties
app.circuit-breaker.failure-rate-threshold=20
app.circuit-breaker.time-window-seconds=1800
app.circuit-breaker.minimum-number-of-calls=100
app.circuit-breaker.permitted-calls-in-half-open-state=20
app.circuit-breaker.max-wait-in-half-open-state=2m
app.circuit-breaker.restart-delays[0]=1m
app.circuit-breaker.restart-delays[1]=2m
app.circuit-breaker.restart-delays[2]=3m
app.circuit-breaker.restart-delays[3]=5m
app.circuit-breaker.restart-delays[4]=7m
app.circuit-breaker.restart-delays[5]=10m
app.circuit-breaker.scheduler-delay-ms=5000
```

### 4.7 Management / Actuator (Health Probes for Scaling)

```properties
# Actuator on separate port — not behind mTLS, accessible to kubelet and KEDA.
management.server.port=9090
management.endpoints.web.exposure.include=health,info,metrics,prometheus
management.endpoint.health.probes.enabled=true
management.endpoint.health.show-details=always
management.health.livenessState.enabled=true
management.health.readinessState.enabled=true
management.health.kafka.enabled=true
```

---

## 5. Layer 2 — Kubernetes Deployment (k8s-manifest.yaml)

### 5.1 Replica Count

```yaml
spec:
  replicas: 2    # Initial only — KEDA overrides this via HPA
```

KEDA creates an HPA that controls replica count. The Deployment `replicas` is only used at first deploy before KEDA takes over.

### 5.2 Rolling Update Strategy

```yaml
strategy:
  type: RollingUpdate
  rollingUpdate:
    maxSurge: 2        # Add 2 new pods before removing old ones
    maxUnavailable: 0  # Never kill a pod until replacement is ready
```

**Why these values:** During rolling deploys, new pods come up first. Zero unavailable means no double-rebalance (remove old + add new simultaneously).

### 5.3 Environment Variables for Static Membership

```yaml
env:
  - name: HOSTNAME
    valueFrom:
      fieldRef:
        fieldPath: metadata.name    # e.g., "payments-stream-7b4f9-xk2m9"
  - name: SPRING_PROFILES_ACTIVE
    value: "prod"
  - name: JAVA_TOOL_OPTIONS
    valueFrom:
      configMapKeyRef:
        name: payments-stream-config
        key: JAVA_TOOL_OPTIONS      # "-Xmx1G -Xms1G -XX:+UseG1GC -XX:MaxGCPauseMillis=200"
```

`HOSTNAME` is consumed by `app.stream.group-instance-id=${HOSTNAME}` in application.properties.

### 5.4 Resource Requests & Limits

```yaml
resources:
  requests:
    cpu: 1000m      # 1 vCPU
    memory: 1.5Gi
  limits:
    cpu: 2000m      # 2 vCPU burst
    memory: 2Gi
```

**Why this matters for KEDA:** AKS node pool must have capacity for `maxReplicaCount` pods. At 48 pods × 1.5Gi = 72Gi minimum across the node pool.

### 5.5 Health Probes

```yaml
livenessProbe:
  httpGet:
    path: /actuator/health/liveness
    port: management          # Port 9090
  initialDelaySeconds: 60
  periodSeconds: 10
  timeoutSeconds: 5
  failureThreshold: 3         # 3 × 10s = 30s to detect dead pod

readinessProbe:
  httpGet:
    path: /actuator/health/readiness
    port: management          # Includes Kafka + circuit breaker health
  initialDelaySeconds: 30
  periodSeconds: 5
  timeoutSeconds: 3
  failureThreshold: 2         # 2 × 5s = 10s to mark not-ready

startupProbe:
  httpGet:
    path: /actuator/health/liveness
    port: management
  initialDelaySeconds: 10
  periodSeconds: 5
  failureThreshold: 20        # 10s + (20 × 5s) = 110s max startup
```

**Why probes matter for scaling:** The readiness probe must pass before a scaled-up pod receives partition assignments. The liveness probe catches truly dead pods faster (30s) than the Kafka session timeout (12m) — acting as a fast-path dead detection.

### 5.6 Graceful Shutdown

```yaml
terminationGracePeriodSeconds: 120   # 2 min total

lifecycle:
  preStop:
    exec:
      command: ["/bin/sh", "-c", "sleep 90"]  # 90s drain before SIGTERM
```

**Timeline on scale-down:**
```
T+0s     KEDA decides to remove pod
T+0s     K8s sends preStop → pod sleeps 90s (drains from load balancer)
T+90s    K8s sends SIGTERM → Kafka Streams begins shutdown
T+90-120s Kafka Streams flushes pending transactions, closes consumers
T+120s   K8s sends SIGKILL if still running
```

**Constraint:** `preStop sleep (90s) + shutdown time (~30s) <= terminationGracePeriodSeconds (120s)`

### 5.7 Pod Anti-Affinity

```yaml
affinity:
  podAntiAffinity:
    preferredDuringSchedulingIgnoredDuringExecution:
      - weight: 100
        podAffinityTerm:
          labelSelector:
            matchExpressions:
              - key: app
                operator: In
                values:
                  - payments-stream
          topologyKey: kubernetes.io/hostname
```

Spreads pods across nodes. Prevents all replicas landing on one node (node failure = total outage).

### 5.8 Istio / Service Mesh Exclusion

```yaml
annotations:
  traffic.istio.io/excludeInboundPorts: "9090"
```

Excludes the management port from mTLS interception. Without this, kubelet sends plain HTTP to a port expecting mTLS → probe fails → pod restarts in a loop.

---

## 6. Layer 3 — KEDA ScaledObject (k8s-manifest.yaml)

### 6.1 ScaledObject Top-Level Settings

| Parameter | Value | Purpose |
|-----------|-------|---------|
| `minReplicaCount` | `2` | Never go below 2 pods (HA baseline). Must match PDB `minAvailable`. |
| `maxReplicaCount` | `48` | Safety ceiling = largest topic's partition count. Prevents runaway scaling. |
| `cooldownPeriod` | `300` (5 min) | Wait 5 min after last trigger activation before allowing scale-to-min. Must be <= `session-timeout-ms`. |
| `pollingInterval` | `15` (15 sec) | How often KEDA queries Kafka lag. Don't go below 10s (broker load). |

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

  minReplicaCount: 2
  maxReplicaCount: 48
  cooldownPeriod: 300
  pollingInterval: 15
```

### 6.2 Kafka Trigger Parameters

Each Kafka trigger monitors one topic + consumer group combination.

| Parameter | Value | Purpose |
|-----------|-------|---------|
| `bootstrapServers` | broker addresses | KEDA's own AdminClient connection (separate from your app's). |
| `consumerGroup` | `payments-stream-v1` | **Must match `app.stream.application-id`** in application.properties. |
| `topic` | `payments.input` | Topic to monitor lag for. |
| `lagThreshold` | `"500"` | Per-partition lag to count as "lagging." With `max-poll-records=250`, this is ~2 poll cycles. |
| `activationLagThreshold` | `"10"` | Min lag before KEDA activates from idle. Prevents scaling on noise. |
| `limitToPartitionsWithLag` | `"true"` | **KEY SETTING.** `desired = count(lagging partitions)`. Caps at partition count. |
| `offsetResetPolicy` | `"latest"` | New consumer group = no false lag spike. |
| `allowIdleConsumers` | `"false"` | Don't count idle consumers as active. |
| `excludePersistentLag` | `"false"` | Include stuck partitions in lag calculation. |

```yaml
  triggers:
    - type: kafka
      metadata:
        bootstrapServers: kafka-broker-0.kafka-broker:9092,kafka-broker-1.kafka-broker:9092,kafka-broker-2.kafka-broker:9092
        consumerGroup: payments-stream-v1      # Must match app.stream.application-id
        topic: payments.input                  # 48 partitions
        lagThreshold: "500"
        activationLagThreshold: "10"
        limitToPartitionsWithLag: "true"
        offsetResetPolicy: "latest"
        allowIdleConsumers: "false"
        excludePersistentLag: "false"
```

### 6.3 Choosing `lagThreshold`

`lagThreshold` is the per-partition message count that tells KEDA "this partition is behind."

**Formula:**

$$lagThreshold = maxPollRecords \times pollCyclesBehind$$

| Scenario | max-poll-records | Cycles Behind | lagThreshold | Meaning |
|----------|-----------------|---------------|-------------|---------|
| Aggressive | 250 | 1 | 250 | Scale up if ~1 poll cycle behind |
| **Recommended** | **250** | **2** | **500** | **Scale up if ~2 poll cycles behind** |
| Conservative | 250 | 4 | 1000 | Scale up if ~4 poll cycles behind |

- **Too low (e.g., 50):** Scales on normal micro-batching fluctuations → constant up/down flapping.
- **Too high (e.g., 5000):** Only scales when badly behind → slow reaction.
- **500:** Reacts quickly but ignores transient 1-cycle delays.

### 6.4 Why `limitToPartitionsWithLag: "true"` Is Critical

| Setting | Formula | 100k lag, 48 partitions, threshold=500 | Risk |
|---------|---------|--------------------------------------|------|
| `"true"` | `count(partitions with lag > 500)` | Max 48 (partition count) | Safe — physically capped |
| `"false"` (default) | `ceil(totalLag / lagThreshold)` | `ceil(100000/500) = 200` → capped by `maxReplicaCount` | Dangerous — can overshoot |

**KEDA >= 2.8 required.** Older versions silently ignore this flag and fall back to the total-lag formula.

### 6.5 Multi-Trigger Logic

KEDA combines multiple triggers using **MAX** (not SUM):

$$desired = \min(maxReplicaCount, \max(trigger_1, trigger_2, ..., trigger_n))$$

```yaml
    # Trigger 2: Exception topic (24 partitions, separate consumer group)
    - type: kafka
      metadata:
        bootstrapServers: kafka-broker-0.kafka-broker:9092,...
        consumerGroup: payments-exception-v1   # Different application-id
        topic: payments.exception
        lagThreshold: "500"
        activationLagThreshold: "10"
        limitToPartitionsWithLag: "true"
        offsetResetPolicy: "latest"
        allowIdleConsumers: "false"
        excludePersistentLag: "false"

    # Trigger 3: Cron floor during market hours
    - type: cron
      metadata:
        timezone: Europe/Zurich
        start: "0 8 * * 1-5"           # Mon-Fri 8:00 AM
        end: "30 16 * * 1-5"           # Mon-Fri 4:30 PM
        desiredReplicas: "4"            # Floor, not ceiling
```

**Multi-trigger scenarios:**

| payments.input lag | payments.exception lag | Cron active | Result |
|---|---|---|---|
| 30 lagging partitions | 0 | Yes (floor=4) | **30 pods** |
| 0 | 24 lagging partitions | Yes (floor=4) | **24 pods** |
| 0 | 0 | Yes (floor=4) | **4 pods** |
| 0 | 0 | No | **2 pods** (minReplicaCount) |
| 40 lagging partitions | 20 lagging partitions | No | **40 pods** |

### 6.6 KEDA Kafka Authentication (SASL/TLS)

If brokers require authentication, KEDA needs its own credentials (separate from your app's):

```yaml
# Step 1: Secret with Kafka credentials
apiVersion: v1
kind: Secret
metadata:
  name: kafka-credentials
  namespace: financial-streams
type: Opaque
stringData:
  sasl: "plaintext"                    # or scram_sha256, scram_sha512
  username: "keda-scaler"
  password: "your-secure-password"

---
# Step 2: TriggerAuthentication referencing the secret
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

---
# Step 3: Reference in each trigger
triggers:
  - type: kafka
    metadata:
      bootstrapServers: kafka-broker:9092
      consumerGroup: payments-stream-v1
      topic: payments.input
      lagThreshold: "500"
      limitToPartitionsWithLag: "true"
    authenticationRef:
      name: kafka-trigger-auth
```

### 6.7 KEDA Version Requirements

| Feature | Min KEDA | If KEDA is older |
|---------|----------|-----------------|
| `limitToPartitionsWithLag` | **2.8** | Silently ignored → `ceil(totalLag/lagThreshold)` → pod count explodes |
| `excludePersistentLag` | **2.8** | Silently ignored → persistent lag always included |
| `activationLagThreshold` | **2.9** | Silently ignored → scales 0→1 on any lag |
| `allowIdleConsumers` | **2.10** | Silently ignored → idle consumers counted incorrectly |

**No warnings, no errors, no logs.** Unknown metadata fields are silently dropped. Always verify your KEDA version:

```bash
kubectl get deployment -n keda keda-operator -o jsonpath='{.spec.template.spec.containers[0].image}'
```

---

## 7. Layer 4 — HPA Behavior (inside ScaledObject)

KEDA creates an HPA behind the scenes. The `advanced.horizontalPodAutoscalerConfig.behavior` block controls the rate of scaling.

### 7.1 Scale-Up Policy

```yaml
advanced:
  horizontalPodAutoscalerConfig:
    behavior:
      scaleUp:
        stabilizationWindowSeconds: 60     # Wait 1 min before acting on lag spike
        policies:
          - type: Pods
            value: 2                        # Add max 2 pods per step
            periodSeconds: 60               # Evaluate every 1 min
```

| Parameter | Value | Effect |
|-----------|-------|--------|
| `stabilizationWindowSeconds` | `60` | After lag detected, wait 60s before adding pods. Batches rapid signal bursts. |
| `value` | `2` | Add at most 2 pods per evaluation period. Gentle = fewer rebalances. |
| `periodSeconds` | `60` | Re-evaluate every 60s. |

**Scale-up timeline (2 → 12 pods needed):**
```
T+0:00   Lag detected on 12 partitions
T+1:00   Stabilization passes → scale 2→4 (+2 pods)
T+2:00   Still lagging → scale 4→6
T+3:00   Still lagging → scale 6→8
T+4:00   Still lagging → scale 8→10
T+5:00   Still lagging → scale 10→12 → lag clears
```

### 7.2 Scale-Down Policy

```yaml
      scaleDown:
        stabilizationWindowSeconds: 300    # Wait 5 min of zero-lag before scaling down
        policies:
          - type: Pods
            value: 4                        # Remove max 4 pods per step
            periodSeconds: 300              # Evaluate every 5 min
        selectPolicy: Min                  # If multiple policies, pick fewest removals
```

| Parameter | Value | Effect |
|-----------|-------|--------|
| `stabilizationWindowSeconds` | `300` | Lag must be zero for 5 full minutes before any scale-down begins. |
| `value` | `4` | Remove at most 4 pods per evaluation period. |
| `periodSeconds` | `300` | Re-evaluate every 5 min. |
| `selectPolicy` | `Min` | If multiple policies, use the one that removes the fewest pods (most conservative). |

**Scale-down timeline (48 → 2 pods):**
```
T+0:00    48 pods, lag = 0
T+5:00    Stabilization passes → 48→44 (-4 pods)
T+10:00   Still zero lag → 44→40
T+15:00   40→36
...
T+55:00   8→4
T+60:00   4→2 (minReplicaCount reached)

Total: ~60 min for full scale-down (conservative and safe)
```

### 7.3 Scale-Down Speed Tuning Table

| Target 48→2 | `value` | `periodSeconds` | `stabilizationWindow` | Result |
|---|---|---|---|---|
| ~30 min (fast) | 8 | 300 | 300 | Risky — many rebalances |
| **~60 min (recommended)** | **4** | **300** | **300** | **Safe — gradual drain** |
| ~120 min (slow) | 2 | 300 | 600 | Very conservative |
| ~180 min (cautious) | 2 | 600 | 600 | For critical/sensitive workloads |

**Formula:**
$$drainTime = stabilizationWindow + \left\lceil \frac{currentReplicas - minReplicas}{podsPerStep} \right\rceil \times periodSeconds$$

### 7.4 Combining Pods + Percentage Policies

For adaptive scale-down at different sizes:

```yaml
scaleDown:
  policies:
    - type: Pods
      value: 4
      periodSeconds: 300
    - type: Percent
      value: 10
      periodSeconds: 300
  selectPolicy: Min    # Use whichever removes FEWER pods
```

| Current Pods | Pods Policy | Percent Policy (10%) | selectPolicy: Min |
|---|---|---|---|
| 48 | -4 | -5 (ceil 4.8) | **-4** |
| 20 | -4 | -2 | **-2** |
| 10 | -4 | -1 | **-1** (gentler at small scale) |

---

## 8. Layer 5 — PodDisruptionBudget

```yaml
apiVersion: policy/v1
kind: PodDisruptionBudget
metadata:
  name: payments-stream-pdb
  namespace: financial-streams
spec:
  minAvailable: 2
  selector:
    matchLabels:
      app: payments-stream
```

| Parameter | Value | Constraint |
|-----------|-------|------------|
| `minAvailable` | `2` | Must match `minReplicaCount` in ScaledObject. During voluntary disruptions (node drain, upgrade), at least 2 pods stay running. |

---

## 9. How All Layers Coordinate — Timing Chain

Every value in the five layers has dependencies. Here's the complete timing chain:

```
┌─ Circuit Breaker ─────────────────────────────────────────────────────────┐
│  max restart delay = 10 min                                               │
└───────────────────────┬───────────────────────────────────────────────────┘
                        │ must be < session-timeout-ms
                        ▼
┌─ Kafka Consumer ──────────────────────────────────────────────────────────┐
│  session-timeout-ms      = 720,000 ms (12 min)                            │
│  max-poll-interval-ms    = 720,000 ms (12 min)  ← must be >= session      │
│  heartbeat-interval-ms   = 10,000 ms  (10 sec)  ← must be < session/3    │
└───────────────────────┬───────────────────────────────────────────────────┘
                        │ must be >= cooldownPeriod
                        ▼
┌─ KEDA ScaledObject ───────────────────────────────────────────────────────┐
│  cooldownPeriod          = 300 sec  (5 min)    ← must be <= session-timeout│
│  pollingInterval         = 15 sec                                          │
│  maxReplicaCount         = 48      ← must = largest topic partition count  │
│  minReplicaCount         = 2       ← must = PDB minAvailable              │
└───────────────────────┬───────────────────────────────────────────────────┘
                        ▼
┌─ HPA Behavior ────────────────────────────────────────────────────────────┐
│  scaleUp.stabilization   = 60 sec                                          │
│  scaleUp.pods/period     = 2 pods / 60 sec                                 │
│  scaleDown.stabilization = 300 sec  ← should be <= cooldownPeriod          │
│  scaleDown.pods/period   = 4 pods / 300 sec                                │
└───────────────────────┬───────────────────────────────────────────────────┘
                        ▼
┌─ K8s Deployment ──────────────────────────────────────────────────────────┐
│  terminationGracePeriod  = 120 sec (2 min)                                 │
│  preStop sleep           = 90 sec  ← must be < terminationGracePeriod      │
│  maxSurge                = 2                                               │
│  maxUnavailable          = 0                                               │
└───────────────────────┬───────────────────────────────────────────────────┘
                        ▼
┌─ PDB ─────────────────────────────────────────────────────────────────────┐
│  minAvailable            = 2       ← must = KEDA minReplicaCount           │
└───────────────────────────────────────────────────────────────────────────┘
```

---

## 10. Tuning Formulas & Rules

### 10.1 Hard Rules (Violating These Breaks Scaling)

| Rule | Formula | Our Values | Pass? |
|------|---------|------------|-------|
| Heartbeat < Session / 3 | `10,000 < 720,000 / 3 = 240,000` | 10,000 < 240,000 | ✅ |
| Max poll interval >= Session timeout | `720,000 >= 720,000` | Equal | ✅ |
| Session timeout > Max circuit breaker delay | `720,000 > 600,000` | 720s > 600s | ✅ |
| KEDA cooldown <= Session timeout | `300,000 <= 720,000` | 300s <= 720s | ✅ |
| preStop sleep < terminationGracePeriod | `90 < 120` | 90 < 120 | ✅ |
| maxReplicaCount = largest topic partitions | `48 = 48` | payments.input = 48 | ✅ |
| PDB minAvailable = KEDA minReplicaCount | `2 = 2` | Both 2 | ✅ |
| KEDA version >= 2.8 | Required for `limitToPartitionsWithLag` | 2.15+ | ✅ |
| consumerGroup = application-id | `payments-stream-v1 = payments-stream-v1` | Match | ✅ |

### 10.2 Soft Rules (Best Practices)

| Rule | Recommendation | Rationale |
|------|---------------|-----------|
| lagThreshold | `max-poll-records × 2` | Reacts within ~2 poll cycles |
| scaleDown.stabilization | >= cooldownPeriod | Consistent behavior before shrinking |
| scaleUp.value | 2-4 pods | Fewer cooperative rebalances |
| scaleDown.value | 4-8 pods | Drain in ~1 hour from max |
| pollingInterval | 10-30 sec | Balance between speed and broker load |
| Node pool capacity | >= maxReplicaCount × pod memory | Prevent Pending pods from cluster autoscaler lag |

---

## 11. Complete Copy-Paste Configuration

### 11.1 application.properties

```properties
# ============================================================================
# KEDA-Optimized Kafka Streams Consumer Configuration
# ============================================================================

# --- Identity ---
spring.application.name=kafka-streams-cb
spring.kafka.bootstrap-servers=localhost:9092

# --- Streams Core ---
app.input.topic=payments.input
app.output.topic=payments.output
app.stream.application-id=payments-stream-v1
app.stream.processing-guarantee=exactly_once_v2
app.stream.num-stream-threads=1
app.stream.commit-interval-ms=1000
app.stream.state-dir=/tmp/kafka-streams
app.stream.replication-factor=3
app.stream.num-standby-replicas=0
app.stream.max-task-idle-ms=1000

# --- KEDA: Static Membership (prevents rebalance storms) ---
app.stream.group-instance-id=${HOSTNAME:local-dev-instance}
app.stream.internal-leave-group-on-close=false

# --- KEDA: Consumer Timeouts (dead detection window) ---
app.stream.consumer.session-timeout-ms=720000
app.stream.consumer.heartbeat-interval-ms=10000
app.stream.consumer.max-poll-interval-ms=720000

# --- Consumer Throughput ---
app.stream.consumer.auto-offset-reset=latest
app.stream.consumer.max-poll-records=250
app.stream.consumer.request-timeout-ms=30000
app.stream.consumer.retry-backoff-ms=500
app.stream.consumer.fetch-max-bytes=52428800
app.stream.consumer.max-partition-fetch-bytes=1048576

# --- Internal Producer ---
app.stream.producer.acks=all
app.stream.producer.linger-ms=5
app.stream.producer.batch-size=65536
app.stream.producer.buffer-memory=67108864

# --- Circuit Breaker (max delay must be < session-timeout-ms) ---
app.circuit-breaker.failure-rate-threshold=20
app.circuit-breaker.time-window-seconds=1800
app.circuit-breaker.minimum-number-of-calls=100
app.circuit-breaker.restart-delays[0]=1m
app.circuit-breaker.restart-delays[1]=2m
app.circuit-breaker.restart-delays[2]=3m
app.circuit-breaker.restart-delays[3]=5m
app.circuit-breaker.restart-delays[4]=7m
app.circuit-breaker.restart-delays[5]=10m

# --- Actuator (separate management port for probes + KEDA) ---
management.server.port=9090
management.endpoints.web.exposure.include=health,info,metrics,prometheus
management.endpoint.health.probes.enabled=true
management.health.livenessState.enabled=true
management.health.readinessState.enabled=true
management.health.kafka.enabled=true
```

### 11.2 KEDA ScaledObject + HPA Behavior

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

  minReplicaCount: 2           # HA baseline — must match PDB minAvailable
  maxReplicaCount: 48          # = largest topic partition count (payments.input)
  cooldownPeriod: 300          # 5 min — must be <= session-timeout-ms (720s)
  pollingInterval: 15          # 15 sec — KEDA checks Kafka lag

  advanced:
    horizontalPodAutoscalerConfig:
      behavior:
        scaleUp:
          stabilizationWindowSeconds: 60
          policies:
            - type: Pods
              value: 2
              periodSeconds: 60
        scaleDown:
          stabilizationWindowSeconds: 300
          policies:
            - type: Pods
              value: 4
              periodSeconds: 300
          selectPolicy: Min

  triggers:
    # Trigger 1: Main topic
    - type: kafka
      metadata:
        bootstrapServers: kafka-broker-0.kafka-broker:9092,kafka-broker-1.kafka-broker:9092,kafka-broker-2.kafka-broker:9092
        consumerGroup: payments-stream-v1        # = app.stream.application-id
        topic: payments.input                    # 48 partitions
        lagThreshold: "500"                      # = max-poll-records × 2
        activationLagThreshold: "10"
        limitToPartitionsWithLag: "true"          # CRITICAL — caps at partition count
        offsetResetPolicy: "latest"
        allowIdleConsumers: "false"
        excludePersistentLag: "false"

    # Trigger 2: Exception topic (if applicable)
    - type: kafka
      metadata:
        bootstrapServers: kafka-broker-0.kafka-broker:9092,kafka-broker-1.kafka-broker:9092,kafka-broker-2.kafka-broker:9092
        consumerGroup: payments-exception-v1
        topic: payments.exception                # 24 partitions
        lagThreshold: "500"
        activationLagThreshold: "10"
        limitToPartitionsWithLag: "true"
        offsetResetPolicy: "latest"
        allowIdleConsumers: "false"
        excludePersistentLag: "false"

    # Trigger 3: Cron floor during business hours
    - type: cron
      metadata:
        timezone: Europe/Zurich
        start: "0 8 * * 1-5"
        end: "30 16 * * 1-5"
        desiredReplicas: "4"
```

### 11.3 K8s Deployment (KEDA-relevant sections)

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: payments-stream
  namespace: financial-streams
spec:
  replicas: 2                      # KEDA overrides this
  strategy:
    type: RollingUpdate
    rollingUpdate:
      maxSurge: 2
      maxUnavailable: 0
  template:
    metadata:
      annotations:
        traffic.istio.io/excludeInboundPorts: "9090"
    spec:
      terminationGracePeriodSeconds: 120
      affinity:
        podAntiAffinity:
          preferredDuringSchedulingIgnoredDuringExecution:
            - weight: 100
              podAffinityTerm:
                labelSelector:
                  matchExpressions:
                    - key: app
                      operator: In
                      values: ["payments-stream"]
                topologyKey: kubernetes.io/hostname
      containers:
        - name: payments-stream
          env:
            - name: HOSTNAME
              valueFrom:
                fieldRef:
                  fieldPath: metadata.name
            - name: JAVA_TOOL_OPTIONS
              value: "-Xmx1G -Xms1G -XX:+UseG1GC -XX:MaxGCPauseMillis=200"
          resources:
            requests:
              cpu: 1000m
              memory: 1.5Gi
            limits:
              cpu: 2000m
              memory: 2Gi
          livenessProbe:
            httpGet:
              path: /actuator/health/liveness
              port: 9090
            initialDelaySeconds: 60
            periodSeconds: 10
            failureThreshold: 3
          readinessProbe:
            httpGet:
              path: /actuator/health/readiness
              port: 9090
            initialDelaySeconds: 30
            periodSeconds: 5
            failureThreshold: 2
          startupProbe:
            httpGet:
              path: /actuator/health/liveness
              port: 9090
            initialDelaySeconds: 10
            periodSeconds: 5
            failureThreshold: 20
          lifecycle:
            preStop:
              exec:
                command: ["/bin/sh", "-c", "sleep 90"]
```

### 11.4 PodDisruptionBudget

```yaml
apiVersion: policy/v1
kind: PodDisruptionBudget
metadata:
  name: payments-stream-pdb
  namespace: financial-streams
spec:
  minAvailable: 2                   # Must match KEDA minReplicaCount
  selector:
    matchLabels:
      app: payments-stream
```

---

## 12. Tuning for Different Workloads

### 12.1 Low-Volume (< 100k msgs/day)

```properties
# application.properties overrides
app.stream.consumer.max-poll-records=100
app.stream.consumer.session-timeout-ms=300000     # 5 min (reduced)
app.stream.consumer.heartbeat-interval-ms=10000
app.stream.consumer.max-poll-interval-ms=300000
```

```yaml
# ScaledObject overrides
minReplicaCount: 1
maxReplicaCount: 6           # Match partition count
lagThreshold: "200"          # max-poll-records × 2
cooldownPeriod: 600          # 10 min — less reactive
pollingInterval: 30          # 30 sec — reduce broker load
scaleUp.value: 1             # 1 pod at a time
scaleDown.value: 1
```

### 12.2 High-Volume (3M+ msgs/day — our production)

Use the values in Section 11 (the complete copy-paste configuration).

### 12.3 Burst Workload (quiet periods + sudden spikes)

```yaml
# ScaledObject overrides
cooldownPeriod: 600          # 10 min — don't rush to scale down
pollingInterval: 10          # 10 sec — react faster to spikes

scaleUp:
  stabilizationWindowSeconds: 30    # 30 sec — faster reaction
  policies:
    - type: Pods
      value: 4                       # 4 pods per step — aggressive
      periodSeconds: 30

scaleDown:
  stabilizationWindowSeconds: 600    # 10 min — very cautious
  policies:
    - type: Pods
      value: 2                       # Slow drain
      periodSeconds: 300
```

### 12.4 Cost-Sensitive (minimize idle pods)

```yaml
# ScaledObject overrides
minReplicaCount: 1           # Allow scale to 1 (sacrifice HA)
cooldownPeriod: 120          # 2 min — scale down faster

scaleDown:
  stabilizationWindowSeconds: 120    # 2 min
  policies:
    - type: Pods
      value: 8                       # Aggressive drain
      periodSeconds: 120

# Remove cron trigger (no market-hours floor)
```

---

## 13. Verification Commands

### 13.1 Check KEDA Installation

```bash
# KEDA pods running
kubectl get pods -n keda

# KEDA version (must be >= 2.8, recommended >= 2.15)
kubectl get deployment -n keda keda-operator \
  -o jsonpath='{.spec.template.spec.containers[0].image}'

# CRD installed
kubectl get crd scaledobjects.keda.sh
```

### 13.2 Check ScaledObject Status

```bash
# ScaledObject is READY
kubectl get scaledobject payments-stream-scaler -n financial-streams

# Detailed status
kubectl describe scaledobject payments-stream-scaler -n financial-streams

# HPA created by KEDA
kubectl get hpa -n financial-streams -o wide
```

### 13.3 Monitor Real-Time Scaling

```bash
# Watch pod count
kubectl get deployment payments-stream -n financial-streams --watch

# Watch scaling events
kubectl get events -n financial-streams --sort-by='.lastTimestamp' --watch

# KEDA operator logs (look for lag values)
kubectl logs -n keda deployment/keda-operator --tail=200 -f
```

### 13.4 Verify Kafka Consumer Group

```bash
# Consumer group state and lag per partition
kafka-consumer-groups.sh --bootstrap-server <broker> \
  --describe --group payments-stream-v1

# Verify static membership (group.instance.id column should show pod names)
kafka-consumer-groups.sh --bootstrap-server <broker> \
  --describe --group payments-stream-v1 --members
```

### 13.5 Verify limitToPartitionsWithLag Is Working

```bash
# Check HPA external metrics — desired should never exceed partition count
kubectl get hpa -n financial-streams -o yaml | grep -A10 "currentMetrics"

# KEDA logs should reference partition-level lag
kubectl logs -n keda deployment/keda-operator --tail=500 | grep -i "limitToPartitions"

# Confirm flag in deployed ScaledObject
kubectl get scaledobject payments-stream-scaler -n financial-streams -o yaml \
  | grep limitToPartitionsWithLag
```

### 13.6 Prometheus Metrics

```promql
# Consumer lag per topic-partition (from Micrometer)
kafka_consumer_fetch_manager_records_lag{topic="payments.input"}

# Rebalance count (should be low during scaling)
kafka_consumer_coordinator_rebalance_total

# Time since last rebalance
kafka_consumer_coordinator_last_rebalance_seconds_ago

# KEDA scaler active status
keda_scaler_is_active{scaler="kafka"}
```

---

## 14. Common Mistakes That Break Scaling

### 14.1 `consumerGroup` Doesn't Match `application-id`

```
❌ application.properties:  app.stream.application-id=payments-stream-v1
❌ KEDA trigger:            consumerGroup: payments-stream

Result: KEDA monitors wrong group → sees 0 lag → never scales.
```

**Fix:** They must be identical strings.

### 14.2 `limitToPartitionsWithLag` Silently Ignored (KEDA < 2.8)

```
KEDA < 2.8: flag is silently dropped → uses ceil(totalLag/lagThreshold)
100k lag, threshold=500 → 200 desired pods → capped at maxReplicaCount

Result: Pods far exceed partition count. Idle pods waste resources.
```

**Fix:** Upgrade KEDA to >= 2.8 (recommended 2.15+). Verify with:
```bash
kubectl get deployment -n keda keda-operator -o jsonpath='{.spec.template.spec.containers[0].image}'
```

### 14.3 PDB `minAvailable` > KEDA `minReplicaCount`

```
❌ PDB minAvailable: 3
❌ KEDA minReplicaCount: 2

Result: KEDA wants to scale to 2 but PDB prevents removing the 3rd pod → stuck at 3.
```

**Fix:** PDB `minAvailable` must equal KEDA `minReplicaCount`.

### 14.4 `session-timeout-ms` Too Short

```
❌ session-timeout-ms: 45000 (45 sec)
❌ KEDA scale-down policy: 4 pods / 5 min

Result: Pod removed → Kafka declares dead in 45s → instant rebalance of ALL partitions.
Static membership benefit wasted.
```

**Fix:** `session-timeout-ms` must be > scale-down period. Our 12 min covers everything.

### 14.5 Missing HOSTNAME Environment Variable

```
❌ No HOSTNAME env in Deployment
❌ app.stream.group-instance-id=${HOSTNAME:local-dev-instance}

Result in K8s: Uses fallback "local-dev-instance" → ALL pods share same group.instance.id
→ only 1 pod gets partitions → scaling useless.
```

**Fix:** Always inject HOSTNAME via Downward API:
```yaml
env:
  - name: HOSTNAME
    valueFrom:
      fieldRef:
        fieldPath: metadata.name
```

### 14.6 `maxReplicaCount` Not Set or Too High

```
❌ maxReplicaCount: 100 (topic has 48 partitions)

Result: If limitToPartitionsWithLag fails, KEDA ramps to 100 pods.
Pods 49-100 are completely idle — burning AKS cost with zero processing.
```

**Fix:** `maxReplicaCount` = largest topic's partition count. It's your safety ceiling.

### 14.7 Istio/Service Mesh Blocking Management Port

```
❌ No traffic.istio.io/excludeInboundPorts annotation

Result: Envoy sidecar intercepts port 9090 → requires mTLS.
Kubelet sends plain HTTP → TLS handshake fails → probe fails → pod restarts in a loop.
Pod never becomes Ready → KEDA can't scale effectively.
```

**Fix:** Add annotation to pod template:
```yaml
annotations:
  traffic.istio.io/excludeInboundPorts: "9090"
```

### 14.8 AKS Node Pool Too Small

```
❌ maxReplicaCount: 48, pod memory: 1.5Gi
❌ Node pool: 3 nodes × 8Gi = 24Gi total

Result: KEDA wants 48 pods (72Gi) but only 24Gi available.
Pods stuck in Pending. Cluster autoscaler kicks in but takes 5-10 min.
Lag keeps growing during the wait.
```

**Fix:** Ensure node pool can accommodate max pods:
$$nodePoolMemory \geq maxReplicaCount \times podMemoryRequest$$

Or enable AKS cluster autoscaler with appropriate min/max node counts.

---

## 15. Production Diagnostic: "Pods Scale But Only One Gets Messages"

> **Symptom:** KEDA scales pods correctly (e.g., 8 pods running), but all partitions are assigned to one pod. The other 7 pods are idle. If you disable KEDA and deploy 8 pods upfront, all pods get messages.

This section is a step-by-step triage guide for this exact scenario.

### 15.1 Most Likely Cause: All Pods Share the Same `group.instance.id`

If `HOSTNAME` doesn't resolve in your production deployment, every pod gets the fallback value `local-dev-instance`. Kafka sees all pods as **the same consumer** — only one can be active.

This explains both symptoms:
- **With KEDA:** Pods join one by one, all claim the same identity → only 1 is active
- **Without KEDA (8 pods at once):** Maybe your non-KEDA deployment doesn't set `group.instance.id` at all → Kafka assigns normally

### 15.2 Step-by-Step Triage (Run in Order)

#### Step 1: Check `group.instance.id` Across Pods (THE #1 CHECK)

```bash
kafka-consumer-groups.sh --bootstrap-server <your-broker> \
  --describe --group <your-application-id> --members --verbose
```

Look at the `GROUP-INSTANCE-ID` column:

```
# ❌ BAD — all the same (THIS IS YOUR BUG):
CONSUMER-ID                HOST        GROUP-INSTANCE-ID
consumer-1-xxxx            /10.0.0.1   local-dev-instance
consumer-2-xxxx            /10.0.0.2   local-dev-instance
consumer-3-xxxx            /10.0.0.3   local-dev-instance

# ✅ GOOD — each pod has unique ID:
CONSUMER-ID                HOST        GROUP-INSTANCE-ID
consumer-1-xxxx            /10.0.0.1   payments-stream-7b4f9-xk2m9
consumer-2-xxxx            /10.0.0.2   payments-stream-7b4f9-ab3c1
consumer-3-xxxx            /10.0.0.3   payments-stream-7b4f9-zz9p2
```

If all show `local-dev-instance` → skip to [Step 5 Fix](#step-5-fix).

#### Step 2: Check Partition Assignment Distribution

```bash
kafka-consumer-groups.sh --bootstrap-server <your-broker> \
  --describe --group <your-application-id>
```

```
# ❌ BAD — all partitions on one consumer:
TOPIC           PARTITION  CONSUMER-ID        HOST
payments.input  0          consumer-1-xxxx    /10.0.0.1
payments.input  1          consumer-1-xxxx    /10.0.0.1
payments.input  2          consumer-1-xxxx    /10.0.0.1
...all 48 on same host...

# ✅ GOOD — distributed:
payments.input  0          consumer-1-xxxx    /10.0.0.1
payments.input  1          consumer-2-xxxx    /10.0.0.2
payments.input  2          consumer-3-xxxx    /10.0.0.3
```

#### Step 3: Check HOSTNAME Inside a Running Pod

```bash
kubectl exec -it <pod-name> -n <namespace> -- env | grep HOSTNAME

# Expected: HOSTNAME=payments-stream-7b4f9-xk2m9
# If empty or missing → HOSTNAME env var not set
```

#### Step 4: Use /actuator/runtime to See What Java Resolved

If you have the `RuntimeDiscoveryService` (see [Section 16](#16-what-actuatorruntime-already-covers)):

```bash
# Hit the runtime discovery endpoint on EACH pod:
kubectl exec -it <pod-name> -n <namespace> -- \
  curl -s http://localhost:9090/actuator/runtime | jq
```

**Critical fields to check:**

```jsonc
{
  "pod": {
    "podName": "payments-stream-7b4f9-xk2m9",  // null = POD_NAME env not set
    "hostname": "payments-stream-7b4f9-xk2m9"   // null = HOSTNAME env not set
  },
  "kafkaStreams": {
    "state": "RUNNING",                          // NOT_STARTED = stream didn't start
    "applicationId": "payments-stream-v1",       // must match KEDA consumerGroup
    "config": {
      "group.instance.id": "payments-stream-7b4f9-xk2m9",  // ❌ "local-dev-instance" = BUG
      "internal.leave.group.on.close": "false",
      "consumer.session.timeout.ms": "720000",
      "consumer.heartbeat.interval.ms": "10000"
    },
    "threads": [
      {
        "threadState": "RUNNING",
        "activeTasks": 6,                        // 0 = this pod has no partitions
        "standbyTasks": 0
      }
    ]
  },
  "streamsInstanceAudit": {
    "instanceCount": 1,                          // > 1 = duplicate Kafka Streams instance
    "duplicateRisk": false                       // true = double-processing risk
  },
  "kafkaLag": {
    "consumerGroups": [
      {
        "consumerGroup": "payments-stream-v1",
        "totalLag": 1250
      }
    ]
  }
}
```

**Red flags in /actuator/runtime output:**

| Field | Expected | Problem If... |
|-------|----------|---------------|
| `pod.hostname` | Pod name | `null` → HOSTNAME env not set |
| `kafkaStreams.config["group.instance.id"]` | Pod name | `local-dev-instance` → all pods same ID |
| `kafkaStreams.threads[0].activeTasks` | > 0 on multiple pods | 0 on all but one pod → partition stuck |
| `streamsInstanceAudit.instanceCount` | 1 | > 1 → duplicate streams instance |
| `streamsInstanceAudit.duplicateRisk` | false | true → double consumer group collision |
| `kafkaStreams.state` | RUNNING | NOT_STARTED or ERROR → stream failed to start |

Compare the output from **multiple pods** — if `group.instance.id` is identical on all, that's your root cause.

#### Step 5: Fix {#step-5-fix}

**If `group.instance.id` is the same across pods:**

1. **Check your production `application.properties`:**
   ```bash
   kubectl exec -it <pod-name> -n <namespace> -- \
     cat /config/application.yml | grep -i "group-instance"
   ```
   Must contain: `group-instance-id: ${HOSTNAME}` (not a hardcoded value).

2. **Check your production Deployment has HOSTNAME env:**
   ```bash
   kubectl get deployment <name> -n <namespace> -o yaml | grep -A3 HOSTNAME
   ```
   The HOSTNAME env var isn't strictly required (Linux auto-sets it), but if your Docker image or entrypoint overrides it, the explicit Downward API injection guarantees the pod name.

3. **Check your production Java code has the `group.instance.id` block:**
   Your `KafkaStreamsConfig.java` must contain:
   ```java
   @Value("${app.stream.group-instance-id:#{null}}") String groupInstanceId,
   ```
   AND:
   ```java
   if (groupInstanceId != null && !groupInstanceId.isBlank()) {
       props.put("group.instance.id", groupInstanceId);
   }
   ```
   If either is missing in your production code, `group.instance.id` is never set → Kafka
   uses auto-generated IDs → static membership isn't active → different symptom though
   (would actually work, just with rebalance storms).

4. **Quickest fix — patch the Deployment to inject HOSTNAME explicitly:**
   ```bash
   kubectl patch deployment <name> -n <namespace> --type='json' -p='[
     {"op": "add", "path": "/spec/template/spec/containers/0/env/-",
      "value": {"name": "HOSTNAME", "valueFrom": {"fieldRef": {"fieldPath": "metadata.name"}}}}
   ]'
   ```

### 15.3 Other Causes (If `group.instance.id` Is Correct)

| # | Cause | How to Detect | Fix |
|---|-------|--------------|-----|
| **2** | `application-id` in production differs from KEDA `consumerGroup` | `/actuator/runtime` → `kafkaStreams.applicationId` vs KEDA trigger | Make strings identical |
| **3** | Duplicate Kafka Streams instance (missing `exclude = KafkaAutoConfiguration.class`) | `/actuator/runtime` → `streamsInstanceAudit.instanceCount > 1` | Add `@SpringBootApplication(exclude = KafkaAutoConfiguration.class)` |
| **4** | Topic has only 1 partition | `kafka-topics.sh --describe --topic <topic>` → check `PartitionCount` | Increase partitions to match expected pod count |
| **5** | Production `application.properties` has `group-instance-id` hardcoded to a fixed value | `/actuator/runtime` → `kafkaStreams.config["group.instance.id"]` same on all pods | Change to `${HOSTNAME:local-dev-instance}` |
| **6** | Session timeout (12 min) delay — new pods waiting for partition reassignment | Wait 12+ min, then check partition assignment again | This resolves itself; if too slow, reduce `session-timeout-ms` |
| **7** | Another consumer group with same `application-id` running externally | `kafka-consumer-groups.sh --describe --group <id> --members` → unexpected hosts | Stop the external consumers |
| **8** | `internal.leave.group.on.close=true` (or not set) + KEDA scale-down + scale-up within session window | KEDA operator logs + HPA events | Set `internal-leave-group-on-close=false` |

### 15.4 Quick Validation Script

Run this on every pod to compare outputs:

```bash
# Save as check-keda-scaling.sh
#!/bin/bash
NAMESPACE="financial-streams"
DEPLOYMENT="payments-stream"

echo "=== Pod Runtime Comparison ==="
for POD in $(kubectl get pods -n $NAMESPACE -l app=$DEPLOYMENT -o name); do
  echo "\n--- $POD ---"
  kubectl exec -n $NAMESPACE $POD -- curl -s http://localhost:9090/actuator/runtime 2>/dev/null | \
    jq '{
      hostname: .pod.hostname,
      groupInstanceId: .kafkaStreams.config["group.instance.id"],
      applicationId: .kafkaStreams.applicationId,
      state: .kafkaStreams.state,
      activeTasks: (.kafkaStreams.threads // [] | map(.activeTasks) | add // 0),
      streamsInstances: .streamsInstanceAudit.instanceCount,
      duplicateRisk: .streamsInstanceAudit.duplicateRisk
    }'
done

echo "\n=== Consumer Group Members ==="
kafka-consumer-groups.sh --bootstrap-server <your-broker> \
  --describe --group <your-application-id> --members --verbose
```

**Expected healthy output:**
```json
--- pod/payments-stream-7b4f9-xk2m9 ---
{
  "hostname": "payments-stream-7b4f9-xk2m9",
  "groupInstanceId": "payments-stream-7b4f9-xk2m9",
  "applicationId": "payments-stream-v1",
  "state": "RUNNING",
  "activeTasks": 6,
  "streamsInstances": 1,
  "duplicateRisk": false
}
--- pod/payments-stream-7b4f9-ab3c1 ---
{
  "hostname": "payments-stream-7b4f9-ab3c1",
  "groupInstanceId": "payments-stream-7b4f9-ab3c1",
  "applicationId": "payments-stream-v1",
  "state": "RUNNING",
  "activeTasks": 6,
  "streamsInstances": 1,
  "duplicateRisk": false
}
```

**Broken output (all pods same `groupInstanceId`):**
```json
--- pod/payments-stream-7b4f9-xk2m9 ---
{
  "hostname": null,
  "groupInstanceId": "local-dev-instance",
  "activeTasks": 48,
  ...
}
--- pod/payments-stream-7b4f9-ab3c1 ---
{
  "hostname": null,
  "groupInstanceId": "local-dev-instance",
  "activeTasks": 0,
  ...
}
```

---

## 16. What /actuator/runtime Already Covers

Your `RuntimeDiscoveryService` (exposed on management port 9090) already reports most critical KEDA-scaling diagnostics. Here's a coverage matrix:

### 16.1 Coverage Matrix

| Diagnostic Check | Covered by /actuator/runtime? | Where in Response |
|-----------------|------------------------------|-------------------|
| `group.instance.id` value per pod | **Yes** ✅ | `kafkaStreams.config["group.instance.id"]` |
| `internal.leave.group.on.close` | **Yes** ✅ | `kafkaStreams.config["internal.leave.group.on.close"]` |
| `session.timeout.ms` | **Yes** ✅ | `kafkaStreams.config["consumer.session.timeout.ms"]` |
| `heartbeat.interval.ms` | **Yes** ✅ | `kafkaStreams.config["consumer.heartbeat.interval.ms"]` |
| `max.poll.interval.ms` | **Yes** ✅ | `kafkaStreams.config["consumer.max.poll.interval.ms"]` |
| `max.poll.records` | **Yes** ✅ | `kafkaStreams.config["consumer.max.poll.records"]` |
| `application.id` (= consumer group) | **Yes** ✅ | `kafkaStreams.applicationId` |
| Kafka Streams state | **Yes** ✅ | `kafkaStreams.state` (RUNNING / NOT_STARTED / ERROR) |
| Active tasks per thread (partition count per pod) | **Yes** ✅ | `kafkaStreams.threads[].activeTasks` |
| Duplicate Kafka Streams instances | **Yes** ✅ | `streamsInstanceAudit.instanceCount`, `.duplicateRisk` |
| Pod name / HOSTNAME | **Yes** ✅ | `pod.hostname`, `pod.podName` |
| Consumer lag per partition | **Yes** ✅ | `kafkaLag.consumerGroups[].topics[].partitions[]` |
| Circuit breaker config | **Yes** ✅ | `circuitBreaker.restartDelays` (verify max < session timeout) |
| Processing guarantee | **Yes** ✅ | `kafkaStreams.config["processing.guarantee"]` |
| Bootstrap servers | **Yes** ✅ | `kafkaStreams.config["bootstrap.servers"]` (sanitised) |

### 16.2 What's NOT Covered (Requires kubectl / Kafka CLI)

| Diagnostic Check | Why Not in /actuator/runtime | How to Check |
|-----------------|------------------------------|-------------|
| **KEDA ScaledObject status** | KEDA is a K8s operator, not visible to the app | `kubectl describe scaledobject <name>` |
| **HPA desired vs actual replicas** | HPA is external to the app | `kubectl get hpa -n <ns> -o wide` |
| **KEDA operator logs** | Separate pod | `kubectl logs -n keda deployment/keda-operator` |
| **Consumer group members list** (who owns which partition) | Requires Kafka AdminClient `describeConsumerGroups` | `kafka-consumer-groups.sh --describe --group <id> --members --verbose` |
| **KEDA version** | KEDA runs in keda namespace | `kubectl get deployment -n keda keda-operator -o jsonpath='{.spec.template.spec.containers[0].image}'` |
| **`limitToPartitionsWithLag` actually working** | KEDA-internal behavior | `kubectl get hpa -o yaml \| grep currentMetrics` |
| **PDB status** | K8s resource | `kubectl get pdb -n <ns>` |
| **Node pool capacity** | AKS infrastructure | `kubectl describe nodes \| grep -A5 Allocatable` |

### 16.3 Recommended Diagnostic Flow

```
Step 1: /actuator/runtime on each pod      ← in-app (fast, complete config view)
        ├── All group.instance.id unique?   → If NO, fix HOSTNAME
        ├── activeTasks > 0 on all pods?    → If NO, partition assignment issue
        ├── instanceCount = 1?              → If NO, duplicate streams instance
        └── state = RUNNING?                → If NO, stream startup failure

Step 2: kafka-consumer-groups.sh            ← Kafka CLI (authoritative partition map)
        └── Are partitions distributed?     → If NO, static membership collision

Step 3: kubectl describe scaledobject       ← K8s CLI (KEDA health)
        ├── ScaledObject READY?             → If NO, KEDA can't reach Kafka
        └── HPA metrics reporting?          → If NO, trigger misconfiguration
```
