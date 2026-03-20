# Health Probes & KEDA Autoscaling — Setup Guide

> **Service:** `payments-stream` (Kafka Streams)  
> **Namespace:** `financial-streams`  
> **Ports:** 8080 (Business APIs, mTLS) · 9090 (Management/Probes, no mTLS)

This document is divided into sequential sections. Each section marks who owns the work: **DEVELOPER** or **DEVOPS**.  
Follow sections 1 → 6 in order. Do not skip ahead.

---

## Table of Contents

1. [Architecture Overview](#1-architecture-overview) — Both
2. [Developer: application.yml — Health & Actuator Config](#2-developer-applicationyml--health--actuator-config) — Developer
3. [Developer: Java Code — LivenessState.BROKEN](#3-developer-java-code--livenessstate-broken) — Developer
4. [DevOps: K8s Deployment — Probes, Ports, Env Vars](#4-devops-k8s-deployment--probes-ports-env-vars) — DevOps
5. [DevOps: KEDA Autoscaling — ScaledObject](#5-devops-keda-autoscaling--scaledobject) — DevOps
6. [DevOps: Service, ServiceMonitor, PDB](#6-devops-service-servicemonitor-pdb) — DevOps
7. [How It All Connects — Reference Tables](#7-how-it-all-connects--reference-tables) — Both
8. [Verification Checklist](#8-verification-checklist) — Both

---

## 1. Architecture Overview

**Owner: BOTH (read and understand before starting)**

```
Port 8080 (server.port)                Port 9090 (management.server.port)
┌────────────────────────┐             ┌──────────────────────────────┐
│  Business APIs         │             │  Actuator Endpoints          │
│  - REST controllers    │             │  - /actuator/health/liveness │
│  - Swagger UI (dev)    │             │  - /actuator/health/readiness│
│  - Behind mTLS/SPIFFE  │             │  - /actuator/prometheus      │
│  - context-path:       │             │  - /actuator/info            │
│    /api/fs             │             │  - NO mTLS required          │
└────────────────────────┘             │  - NO context-path prefix    │
                                       └──────────────────────────────┘
```

### Why two ports?

| Reason | Explanation |
|--------|-------------|
| Kubelet can't do mTLS | Health probes are plain HTTP — they must hit a port without SPIFFE |
| Prometheus can't do mTLS | Scraper needs direct access without auth negotiation |
| Business APIs stay protected | Port 8080 stays behind Istio mTLS + AuthorizationPolicy |

### Three Probes — What They Do

| Probe | Endpoint | Port | Question it answers | On failure |
|-------|----------|------|---------------------|------------|
| **Startup** | `/actuator/health/liveness` | 9090 | "Has the app finished starting?" | Kill pod (if threshold exceeded) |
| **Liveness** | `/actuator/health/liveness` | 9090 | "Is the JVM/app still alive?" | Kill + restart pod |
| **Readiness** | `/actuator/health/readiness` | 9090 | "Can this pod serve traffic?" | Remove from Service (no restart) |

### What triggers liveness DOWN?

| Event | Liveness | Action |
|-------|----------|--------|
| App running normally | UP (200) | Nothing |
| Kafka broker down | **UP (200)** | Nothing — broker down ≠ pod broken |
| `StreamFatalExceptionHandler` fires `SHUTDOWN_CLIENT` | **DOWN (503)** | Kill + restart pod |
| JVM deadlock / OOM | No response (timeout) | Kill + restart pod |
| GC pause > 5s | Timeout (1 failure) | Wait — needs 3 consecutive failures |

### What triggers readiness DOWN?

| Event | Readiness | Action |
|-------|-----------|--------|
| App running, Kafka connected | UP (200) | Pod receives traffic |
| App still starting up | DOWN (503) | No traffic until ready |
| Kafka broker unreachable | DOWN (503) | Traffic rerouted to healthy pods |

**Key insight:** Kafka partition assignment is managed by the consumer group protocol, NOT by K8s readiness. Readiness only gates REST API traffic on port 8080.

---

## 2. Developer: application.yml — Health & Actuator Config

**Owner: DEVELOPER**

The health probes, management port, and KEDA-related Kafka consumer settings all live in `application.yml`. This is what the Developer must ensure is correct in the Spring Boot project.

### File: `src/main/resources/application.yml`

Below is the **exact config already in your project**. Verify each section exists and matches.

#### 2.1 — Management port (separate from business APIs)

```yaml
management:
  server:
    port: 9090                          # Actuator on 9090, isolated from 8080
```

**Why:** Kubelet and Prometheus hit 9090. mTLS on 8080 would block them.

#### 2.2 — Endpoint exposure (only what's needed)

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics,prometheus,runtime
```

**Do NOT expose:** `env`, `beans`, `configprops` — security risk.

#### 2.3 — Health endpoint + K8s probes

```yaml
management:
  endpoint:
    health:
      probes:
        enabled: true                   # REQUIRED — creates /actuator/health/liveness and /readiness
      show-details: always              # Show component-level detail in health JSON
      show-components: always           # Show individual indicator statuses
```

Without `probes.enabled: true`, the `/liveness` and `/readiness` sub-paths **do not exist** and K8s probes will 404.

#### 2.4 — Health groups (liveness + readiness + kafka)

```yaml
management:
  health:
    livenessState:
      enabled: true                     # Enables /actuator/health/liveness
    readinessState:
      enabled: true                     # Enables /actuator/health/readiness
    kafka:
      enabled: true                     # Kafka broker check in OVERALL health, NOT in liveness
```

**Critical rule:** Kafka health contributes to `/actuator/health` (overall) but NOT to `/actuator/health/liveness`. If Kafka is down, the pod is still alive — it should NOT be restarted.

#### 2.5 — Static membership + KEDA-compatible consumer settings

These settings let KEDA scale pods without causing Kafka rebalance storms:

```yaml
app:
  stream:
    # Pod hostname becomes the consumer's group.instance.id.
    # HOSTNAME env var is set in K8s Deployment (DevOps provides this).
    # Fallback "local-dev-instance" for local dev without K8s.
    group-instance-id: ${HOSTNAME:local-dev-instance}

    # Don't send LeaveGroup when pod shuts down.
    # Kafka coordinator waits session-timeout-ms before reassigning partitions.
    internal-leave-group-on-close: false

    consumer:
      # 5 min — matches KEDA cooldownPeriod (300s).
      # Kafka waits this long before assuming consumer is dead.
      session-timeout-ms: 300000

      # Must be < session-timeout-ms / 3
      heartbeat-interval-ms: 10000

      # Max time between poll() calls.
      # 5 min — same as session-timeout-ms for consistency.
      max-poll-interval-ms: 300000
```

#### 2.6 — Swagger disabled in prod/preprod

Already at the bottom of `application.yml`:

```yaml
---
spring:
  config:
    activate:
      on-profile: preprod
springdoc:
  api-docs:
    enabled: false
  swagger-ui:
    enabled: false

---
spring:
  config:
    activate:
      on-profile: prod
springdoc:
  api-docs:
    enabled: false
  swagger-ui:
    enabled: false
```

DevOps activates the profile via `SPRING_PROFILES_ACTIVE` env var in the Deployment (see Section 4).

#### 2.7 — Developer testing (local, no K8s)

After starting the app locally:

```bash
# 1. Health endpoint — overall status with all components
curl http://localhost:9090/actuator/health
# Expected: {"status":"UP","components":{...}} with kafka, liveness, readiness details

# 2. Liveness — K8s will hit this
curl http://localhost:9090/actuator/health/liveness
# Expected: {"status":"UP"}

# 3. Readiness — K8s will hit this
curl http://localhost:9090/actuator/health/readiness
# Expected: {"status":"UP"}

# 4. Prometheus metrics — KEDA/Prometheus will scrape this
curl http://localhost:9090/actuator/prometheus
# Expected: Prometheus-format text (jvm_*, kafka_*, etc.)

# 5. Business API on port 8080 (separate from actuator)
curl http://localhost:8080/api/fs/hello
# Expected: "HI World, How are you?"

# 6. Verify port isolation — actuator NOT on 8080
curl http://localhost:8080/actuator/health
# Expected: 404

# 7. Verify port isolation — business API NOT on 9090
curl http://localhost:9090/api/fs/hello
# Expected: 404
```

---

## 3. Developer: Java Code — LivenessState.BROKEN

**Owner: DEVELOPER**

When Kafka Streams hits a fatal error, the `StreamFatalExceptionHandler` must publish `LivenessState.BROKEN` so the liveness probe returns 503 and K8s restarts the pod.

### File: `StreamFatalExceptionHandler.java`

This code already exists in your project. Verify it contains:

```java
AvailabilityChangeEvent.publish(eventPublisher, this, LivenessState.BROKEN);
return StreamThreadExceptionResponse.SHUTDOWN_CLIENT;
```

### Why this is critical

Without `LivenessState.BROKEN`:
1. `SHUTDOWN_CLIENT` stops Kafka Streams threads
2. But the JVM stays running → Spring Boot keeps responding to health checks
3. Liveness returns 200 → **zombie pod** (alive but not processing anything)
4. K8s never restarts it

With `LivenessState.BROKEN`:
1. Spring Boot's `LivenessStateHealthIndicator` sees `BROKEN`
2. `/actuator/health/liveness` returns HTTP 503
3. Kubelet detects 3 consecutive 503s → kills pod → K8s restarts it

**No other Java code changes are needed for health probes.** Spring Boot Actuator handles everything else automatically.

---

## 4. DevOps: K8s Deployment — Probes, Ports, Env Vars

**Owner: DEVOPS**

### File: `k8s-manifest.yaml` — Deployment section

Below is the exact Deployment spec. Each section is annotated with what matters and why.

#### 4.1 — Container ports

```yaml
containers:
  - name: payments-stream
    image: your-registry/payments-stream:1.0.0
    ports:
      - name: http
        containerPort: 8080          # Business APIs (mTLS protected)
        protocol: TCP
      - name: management
        containerPort: 9090          # Health probes + Prometheus (no mTLS)
        protocol: TCP
```

**Rule:** Probes MUST use port `management` (9090). Never 8080.

#### 4.2 — Environment variables

```yaml
env:
  # 1. Spring profile — controls Swagger on/off
  - name: SPRING_PROFILES_ACTIVE
    value: "prod"                    # "dev", "preprod", or "prod"

  # 2. JVM tuning — from ConfigMap
  - name: JAVA_TOOL_OPTIONS
    valueFrom:
      configMapKeyRef:
        name: payments-stream-config
        key: JAVA_TOOL_OPTIONS

  # 3. HOSTNAME — used by Kafka Streams for static membership (group.instance.id)
  #    This prevents rebalance storms when KEDA scales up/down.
  - name: HOSTNAME
    valueFrom:
      fieldRef:
        fieldPath: metadata.name     # e.g., "payments-stream-7b4f9-xk2m9"

  # 4. Downward API — for /actuator/runtime discovery endpoint
  - name: POD_NAME
    valueFrom:
      fieldRef:
        fieldPath: metadata.name
  - name: POD_NAMESPACE
    valueFrom:
      fieldRef:
        fieldPath: metadata.namespace
  - name: NODE_NAME
    valueFrom:
      fieldRef:
        fieldPath: spec.nodeName
  - name: POD_IP
    valueFrom:
      fieldRef:
        fieldPath: status.podIP
```

**Important:** The `HOSTNAME` env var is what makes static membership work. The app reads it as `${HOSTNAME}` in `application.yml` for `group-instance-id`.

#### 4.3 — Startup Probe

```yaml
startupProbe:
  httpGet:
    path: /actuator/health/liveness
    port: management                 # Port 9090
  initialDelaySeconds: 10            # Wait 10s for JVM boot
  periodSeconds: 5                   # Check every 5s
  timeoutSeconds: 3                  # Fail if no response in 3s
  failureThreshold: 20              # 10 + (20 × 5) = 110s max startup time
```

**Why 110s:** Kafka Streams needs time to connect to brokers, join consumer group, complete rebalance, and initialize topology.  
**What happens:** Active only during startup. Disabled after first success. If it fails 20 times → pod is killed.

#### 4.4 — Liveness Probe

```yaml
livenessProbe:
  httpGet:
    path: /actuator/health/liveness
    port: management                 # Port 9090
  initialDelaySeconds: 60            # Extra buffer after startup probe passes
  periodSeconds: 10                  # Check every 10s
  timeoutSeconds: 5                  # Fail if no response in 5s
  failureThreshold: 3               # Kill after 3 consecutive failures (30s)
```

**What happens:** Only active AFTER startup probe passes. If liveness fails 3 times → pod is KILLED and RESTARTED.  
**Why 30s tolerance:** Fast enough to catch zombie pods, slow enough to survive GC pauses.

#### 4.5 — Readiness Probe

```yaml
readinessProbe:
  httpGet:
    path: /actuator/health/readiness
    port: management                 # Port 9090
  initialDelaySeconds: 30            # Wait for initial setup
  periodSeconds: 5                   # Check every 5s (more frequent than liveness)
  timeoutSeconds: 3                  # Fail if no response in 3s
  failureThreshold: 2               # Remove from traffic after 2 consecutive failures (10s)
```

**What happens:** If readiness fails 2 times → pod is removed from Service endpoints (NOT restarted). Traffic goes to other pods.  
**Why faster than liveness:** Readiness affects traffic routing — quick reaction prevents sending requests to unhealthy pods.

#### 4.6 — Graceful shutdown

```yaml
terminationGracePeriodSeconds: 120   # 2 min total before SIGKILL

lifecycle:
  preStop:
    exec:
      command: ["/bin/sh", "-c", "sleep 90"]  # Wait 90s for in-flight processing
```

**Why:** Kafka Streams needs time to commit offsets and close consumers cleanly. The `preStop` hook delays SIGTERM by 90s, and the pod has 120s total before forced kill.

#### 4.7 — Volumes (state dir for Kafka Streams)

```yaml
volumeMounts:
  - name: config
    mountPath: /config
  - name: state-store
    mountPath: /tmp/kafka-streams     # Required with readOnlyRootFilesystem: true

volumes:
  - name: config
    configMap:
      name: payments-stream-config
  - name: state-store
    emptyDir: {}                      # Task metadata only, no PVC needed
```

#### 4.8 — Security context

```yaml
securityContext:
  allowPrivilegeEscalation: false
  readOnlyRootFilesystem: true
  runAsNonRoot: true
  runAsUser: 1000
  capabilities:
    drop:
      - ALL
```

#### 4.9 — Resources

```yaml
resources:
  requests:
    cpu: 1000m
    memory: 1.5Gi
  limits:
    cpu: 2000m
    memory: 2Gi
```

#### 4.10 — Common mistakes to avoid

```yaml
# ❌ WRONG — probe on business port (8080 has mTLS, kubelet can't auth)
livenessProbe:
  httpGet:
    path: /api/fs/actuator/health/liveness
    port: 8080

# ✅ CORRECT — probe on management port (9090, no mTLS)
livenessProbe:
  httpGet:
    path: /actuator/health/liveness
    port: management                  # = port 9090
```

```yaml
# ❌ WRONG — using context-path prefix on management port
path: /api/fs/actuator/health/liveness

# ✅ CORRECT — no context-path on management port
path: /actuator/health/liveness
```

---

## 5. DevOps: KEDA Autoscaling — ScaledObject

**Owner: DEVOPS**

### Prerequisites

1. **KEDA must be installed on the AKS cluster:**
   ```bash
   helm repo add kedacore https://kedacore.github.io/charts
   helm install keda kedacore/keda --namespace keda --create-namespace
   ```
2. **The consumer group must exist** — KEDA reads committed offsets. The first pod must start and join the group before KEDA can read lag.

### How KEDA works with this app

```
Every 15 seconds (pollingInterval):
  KEDA queries Kafka broker for consumer group lag
    → Counts partitions with lag > 500 (lagThreshold)
    → Sets desired replicas = count of lagging partitions
    → K8s creates/removes pods
    → Kafka Streams rebalances partitions across pods (cooperative, minimal disruption)
```

### File: `k8s-manifest.yaml` — ScaledObject section

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
    name: payments-stream              # Must match Deployment name exactly
  minReplicaCount: 2                   # Never below 2 (HA — if one crashes, the other continues)
  maxReplicaCount: 48                  # Safety cap — even though KEDA auto-caps at partition count
  cooldownPeriod: 300                  # 5 min — matches session-timeout-ms in application.yml
  pollingInterval: 15                  # Check Kafka lag every 15 seconds

  advanced:
    horizontalPodAutoscalerConfig:
      behavior:
        scaleUp:
          stabilizationWindowSeconds: 60     # Wait 1 min before acting on scale-up signals
          policies:
            - type: Pods
              value: 2                       # Add max 2 pods per evaluation
              periodSeconds: 60              # Evaluate every 1 min
        scaleDown:
          stabilizationWindowSeconds: 300    # Wait 5 min of zero-lag before scale-down
          policies:
            - type: Pods
              value: 4                       # Remove max 4 pods per evaluation
              periodSeconds: 300             # Evaluate every 5 min
          selectPolicy: Min                  # If multiple policies, pick fewest removals

  triggers:
    # --- Trigger 1: Main trade topic (payments.input — 48 partitions) ---
    - type: kafka
      metadata:
        bootstrapServers: kafka-broker-0.kafka-broker:9092,kafka-broker-1.kafka-broker:9092,kafka-broker-2.kafka-broker:9092
        consumerGroup: payments-stream-v1         # Must match app.stream.application-id
        topic: payments.input                     # Must match app.input.topic
        lagThreshold: "500"                       # Scale when partition has > 500 unprocessed msgs
        activationLagThreshold: "10"              # Don't scale on noise (< 10 msgs)
        limitToPartitionsWithLag: "true"           # Replicas = count of lagging partitions
        offsetResetPolicy: "latest"
        allowIdleConsumers: "false"
        excludePersistentLag: "false"

    # --- Trigger 2: Exception topic (payments.exception — 6 partitions) ---
    - type: kafka
      metadata:
        bootstrapServers: kafka-broker-0.kafka-broker:9092,kafka-broker-1.kafka-broker:9092,kafka-broker-2.kafka-broker:9092
        consumerGroup: payments-exception-v1
        topic: payments.exception
        lagThreshold: "500"
        activationLagThreshold: "10"
        limitToPartitionsWithLag: "true"
        offsetResetPolicy: "latest"
        allowIdleConsumers: "false"
        excludePersistentLag: "false"

    # --- Trigger 3: Cron floor during Swiss market hours ---
    - type: cron
      metadata:
        timezone: Europe/Zurich
        start: "0 8 * * 1-5"                     # Mon-Fri 08:00
        end: "30 16 * * 1-5"                      # Mon-Fri 16:30
        desiredReplicas: "4"                      # Floor, not ceiling — lag triggers can go higher
```

### About `maxReplicaCount: 48`

KEDA with `limitToPartitionsWithLag: "true"` already caps replicas at the partition count (48 for `payments.input`). However, adding an explicit `maxReplicaCount: 48` is a **safety net**:
- Protects against edge cases or KEDA bugs
- Makes the upper bound visible to anyone reading the manifest
- No downside — KEDA would never request more than 48 anyway

### Key values that must match between Developer and DevOps

| application.yml (Developer) | k8s-manifest.yaml (DevOps) | Must match? |
|-----------------------------|---------------------------|-------------|
| `app.stream.application-id: payments-stream-v1` | `consumerGroup: payments-stream-v1` | **YES — exact** |
| `app.input.topic: payments.input` | `topic: payments.input` | **YES — exact** |
| `app.stream.consumer.session-timeout-ms: 300000` | `cooldownPeriod: 300` | **YES — both 5 min** |
| `management.server.port: 9090` | `containerPort: 9090` + probe `port: management` | **YES** |

### How KEDA scaling works with Kafka Streams (no rebalance storms)

The combination of these settings prevents rebalance storms during KEDA scale events:

```
Developer sets in application.yml:
  group-instance-id: ${HOSTNAME}            → each pod has a unique consumer ID
  internal-leave-group-on-close: false      → pod doesn't send LeaveGroup on shutdown
  session-timeout-ms: 300000                → Kafka waits 5 min before reassigning partitions

DevOps sets in k8s-manifest.yaml:
  cooldownPeriod: 300                       → KEDA waits 5 min before scaling to min
  env HOSTNAME from metadata.name           → pod name becomes consumer identity
```

**Scale-down flow:**
1. KEDA removes a pod
2. Pod shuts down but does NOT send LeaveGroup
3. Kafka coordinator waits 5 min before reassigning that pod's partitions
4. If KEDA scales back up within 5 min → new pod picks up orphaned partitions in one clean rebalance
5. If 5 min expires → coordinator reassigns partitions to remaining pods (single rebalance, not a storm)

**Scale-up flow:**
1. KEDA detects lag → requests more pods (2 at a time)
2. New pods start, join consumer group
3. Kafka Streams uses cooperative rebalancing (default in Kafka 3.x) — only moved partitions are revoked, everyone else keeps processing

### Scaling timeline example (market open day)

```
07:55  KEDA cron trigger activates → scale to 4 pods (market floor)
08:00  Market opens → messages flow → lag builds on 12 partitions
08:01  KEDA detects lag > 500 on 12 partitions → desires 12 pods
08:02  stabilizationWindow (60s) passes → scale up 2 pods (4 → 6)
08:03  Scale up 2 more (6 → 8)
08:04  Scale up 2 more (8 → 10)
08:05  Scale up 2 more (10 → 12) → lag draining
08:30  Lag cleared → KEDA desires minReplicaCount
08:35  stabilizationWindow (300s) starts counting
08:40  Still zero lag → scale down 4 pods (12 → 8)
08:41  Cron floor prevents going below 4 during market hours
...
16:30  Cron trigger deactivates → floor drops to minReplicaCount (2)
16:35  If no lag → scale down to 2 pods for overnight
```

---

## 6. DevOps: Service, ServiceMonitor, PDB

**Owner: DEVOPS**

### 6.1 — Service (dual port)

```yaml
apiVersion: v1
kind: Service
metadata:
  name: payments-stream
  namespace: financial-streams
  labels:
    app: payments-stream
spec:
  type: ClusterIP
  ports:
    - name: http
      port: 8080
      targetPort: http               # Business APIs (mTLS protected)
      protocol: TCP
    - name: management
      port: 9090
      targetPort: management         # Probes + Prometheus (no mTLS)
      protocol: TCP
  selector:
    app: payments-stream
```

### 6.2 — ServiceMonitor (Prometheus scraping)

```yaml
apiVersion: monitoring.coreos.com/v1
kind: ServiceMonitor
metadata:
  name: payments-stream
  namespace: financial-streams
  labels:
    app: payments-stream             # Must match Prometheus serviceMonitorSelector
spec:
  selector:
    matchLabels:
      app: payments-stream
  endpoints:
    - port: management               # Scrape from port 9090
      path: /actuator/prometheus     # Prometheus-format metrics endpoint
      interval: 30s                  # Scrape every 30 seconds
      scrapeTimeout: 10s
```

**What gets scraped automatically:** JVM memory, GC, threads, Kafka consumer lag, poll latency, commit latency, Spring request counts, and any custom Micrometer gauges/counters.

### 6.3 — PodDisruptionBudget

```yaml
apiVersion: policy/v1
kind: PodDisruptionBudget
metadata:
  name: payments-stream-pdb
  namespace: financial-streams
spec:
  minAvailable: 2                    # At least 2 pods always running during voluntary disruptions
  selector:
    matchLabels:
      app: payments-stream
```

### 6.4 — ServiceAccount

```yaml
apiVersion: v1
kind: ServiceAccount
metadata:
  name: payments-stream
  namespace: financial-streams
```

### 6.5 — ConfigMap

```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: payments-stream-config
  namespace: financial-streams
data:
  JAVA_TOOL_OPTIONS: "-Xmx1G -Xms1G -XX:+UseG1GC -XX:MaxGCPauseMillis=200"
```

---

## 7. How It All Connects — Reference Tables

### Probe summary

| Probe | Path | Port | Check interval | Timeout | Failures to act | Max wait | Action on failure |
|-------|------|------|---------------|---------|-----------------|----------|-------------------|
| Startup | `/actuator/health/liveness` | 9090 | 5s | 3s | 20 | 110s | Kill pod |
| Liveness | `/actuator/health/liveness` | 9090 | 10s | 5s | 3 | 30s | Kill + restart pod |
| Readiness | `/actuator/health/readiness` | 9090 | 5s | 3s | 2 | 10s | Remove from traffic |

### KEDA numbers

| Parameter | Value | Coordinates with |
|-----------|-------|-----------------|
| `pollingInterval` | 15s | How fast KEDA reacts to lag |
| `cooldownPeriod` | 300s (5 min) | `session-timeout-ms` (also 5 min) |
| `lagThreshold` | 500 | `max.poll.records` (250) — ~2 poll cycles |
| `minReplicaCount` | 2 | PDB `minAvailable: 2` |
| `maxReplicaCount` | 48 | Safety cap = partition count of payments.input |
| Scale-up rate | 2 pods/min | Gentle — fewer rebalances |
| Scale-down rate | 4 pods/5min | Conservative — ~1 hour from 48 → 2 |
| Market floor (cron) | 4 pods | Mon-Fri 08:00-16:30 Europe/Zurich |

### Port mapping

| Port | What's on it | Who hits it | mTLS? |
|------|-------------|------------|-------|
| 8080 | Business APIs (`/api/fs/*`), Swagger UI | Other services (coreui, admin-dashboard) | Yes |
| 9090 | Actuator (`/actuator/*`) | Kubelet probes, Prometheus | No |

### Critical values that must stay in sync

| Value | Developer file | DevOps file |
|-------|---------------|-------------|
| Consumer group ID | `app.stream.application-id: payments-stream-v1` | ScaledObject `consumerGroup: payments-stream-v1` |
| Input topic | `app.input.topic: payments.input` | ScaledObject `topic: payments.input` |
| Session timeout = cooldown | `session-timeout-ms: 300000` | `cooldownPeriod: 300` |
| Management port | `management.server.port: 9090` | `containerPort: 9090`, probe port: `management` |
| Static membership ID | `group-instance-id: ${HOSTNAME}` | `env HOSTNAME` from `metadata.name` |

---

## 8. Verification Checklist

### Developer — Local Testing (before handing to DevOps)

```bash
# Start app locally (Kafka must be running on localhost:9092)

# 1. Liveness endpoint exists and returns UP
curl http://localhost:9090/actuator/health/liveness
# Expected: {"status":"UP"}

# 2. Readiness endpoint exists and returns UP
curl http://localhost:9090/actuator/health/readiness
# Expected: {"status":"UP"}

# 3. Full health with component details
curl http://localhost:9090/actuator/health
# Expected: {"status":"UP","components":{"livenessState":...,"readinessState":...,"kafka":...}}

# 4. Prometheus metrics work
curl http://localhost:9090/actuator/prometheus
# Expected: lines starting with jvm_, kafka_, etc.

# 5. Business API works on 8080
curl http://localhost:8080/api/fs/hello
# Expected: "HI World, How are you?"

# 6. Port isolation — actuator NOT on 8080
curl http://localhost:8080/actuator/health
# Expected: 404

# 7. Port isolation — business API NOT on 9090
curl http://localhost:9090/api/fs/hello
# Expected: 404

# 8. Swagger disabled in prod profile
# (restart with SPRING_PROFILES_ACTIVE=prod)
curl http://localhost:8080/api/fs/swagger-ui.html
# Expected: 404
```

### DevOps — After Deploying to AKS

```bash
# 1. Pod is running and all probes pass
kubectl get pods -n financial-streams -l app=payments-stream
# Expected: STATUS=Running, READY=1/1

# 2. Liveness probe works from inside pod
kubectl exec <pod-name> -n financial-streams -- curl -s http://localhost:9090/actuator/health/liveness
# Expected: {"status":"UP"}

# 3. Readiness probe works from inside pod
kubectl exec <pod-name> -n financial-streams -- curl -s http://localhost:9090/actuator/health/readiness
# Expected: {"status":"UP"}

# 4. Startup probe config is correct
kubectl get pod <pod-name> -n financial-streams -o jsonpath='{.spec.containers[0].startupProbe}' | jq
# Expected: port: 9090, path: /actuator/health/liveness

# 5. KEDA ScaledObject is ready
kubectl get scaledobject payments-stream-scaler -n financial-streams
# Expected: READY=True

# 6. KEDA-generated HPA exists
kubectl get hpa -n financial-streams
# Expected: HPA with kafka trigger, TARGETS showing current lag

# 7. During market hours: cron floor is active
kubectl get pods -n financial-streams -l app=payments-stream --no-headers | wc -l
# Expected: >= 4 during Mon-Fri 08:00-16:30 Europe/Zurich

# 8. Prometheus scraping works
kubectl port-forward svc/payments-stream -n financial-streams 9090:9090
curl http://localhost:9090/actuator/prometheus
# Expected: Prometheus-format metrics

# 9. ServiceMonitor is detected by Prometheus
# Check Prometheus UI → Targets → payments-stream should show UP

# 10. Pod does NOT restart when Kafka broker is temporarily down
# (Simulate by scaling Kafka broker statefulset to 0, wait, scale back)
kubectl get pods -n financial-streams -w
# Expected: Pod stays Running, RESTARTS stays at 0

# 11. Pod DOES restart after StreamFatalExceptionHandler fires
# (Trigger a fatal error, check restart count increases)
kubectl get pods -n financial-streams -w
# Expected: RESTARTS increments by 1

# 12. PDB is in place
kubectl get pdb payments-stream-pdb -n financial-streams
# Expected: MIN AVAILABLE=2

# 13. Verify HOSTNAME env var is set (needed for static membership)
kubectl exec <pod-name> -n financial-streams -- printenv HOSTNAME
# Expected: pod name like "payments-stream-7b4f9-xk2m9"
```

### Troubleshooting

| Symptom | Cause | Fix |
|---------|-------|-----|
| Pod in `CrashLoopBackOff` | Liveness probe on port 8080 (mTLS blocks kubelet) | Change probe port to `management` (9090) |
| `0/1 Ready` but pod running | Readiness probe failing | Check Kafka connectivity, verify probe path has no `/api/fs` prefix |
| Probes return 404 | `management.endpoint.health.probes.enabled` not set to `true` | Developer: add `probes.enabled: true` in application.yml |
| Probes return 404 | Wrong path — using `/api/fs/actuator/...` | Remove context-path prefix — management port has none |
| KEDA not scaling | `consumerGroup` in ScaledObject doesn't match `application-id` | Align both to `payments-stream-v1` |
| KEDA not scaling | Consumer group doesn't exist yet | Deploy at least one pod first, let it join the group |
| Rebalance storms during scaling | `internal-leave-group-on-close` not set to `false` | Developer: add to application.yml |
| Rebalance storms during scaling | `HOSTNAME` env var not set in Deployment | DevOps: add `fieldRef: metadata.name` |
| Prometheus targets DOWN | Scraping port 8080 instead of 9090 | Update ServiceMonitor to `port: management` |
| Zombie pod (alive but not processing) | `LivenessState.BROKEN` not published | Developer: add to `StreamFatalExceptionHandler` |
