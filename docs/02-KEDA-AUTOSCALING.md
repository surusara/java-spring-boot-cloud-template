# 02 — KEDA Autoscaling

> **Assignee:** _______________  
> **Scope:** KEDA ScaledObject, scaling policies, Kafka lag triggers, cron market-hours floor  
> **Prereq:** Kafka Streams pod deployed (see 01-KAFKA-STREAMS-CONFIG.md)  

---

## What KEDA Does

KEDA (Kubernetes Event-Driven Autoscaling) replaces HPA for Kafka workloads. Instead of scaling on CPU/memory, it scales on **Kafka consumer lag** — the number of unprocessed messages per partition.

**Why not HPA?** CPU is a lagging indicator. By the time CPU spikes, messages are already piling up. KEDA reacts to lag directly — scale before CPU even rises.

---

## How It Works with Kafka Streams

```
KEDA checks consumer group lag every 15 seconds (pollingInterval)
  → Counts partitions with lag > lagThreshold (500)
  → Sets desired replicas = number of lagging partitions
  → K8s scheduler creates/removes pods
  → Kafka Streams rebalances partitions across pods
  → Static membership (group.instance.id) minimizes rebalance disruption
```

**Physical cap:** You can never have more *useful* pods than partitions — extra pods sit idle. `limitToPartitionsWithLag: "true"` caps each trigger's "vote" at its partition count. But `maxReplicaCount` is the **absolute safety ceiling** across ALL triggers — it MUST be set to the largest topic's partition count. See [Multi-Trigger Caveat](#multi-trigger-caveat--why-pods-can-exceed-partition-count) below.

---

## Prerequisites

1. **KEDA >= 2.10 installed on AKS cluster:**
   ```bash
   # via Helm (minimum 2.10, recommended latest stable)
   helm repo add kedacore https://kedacore.github.io/charts
   helm install keda kedacore/keda --namespace keda --create-namespace
   ```
2. **Consumer group must exist** — KEDA reads committed offsets. First pod must start and join the group.

### KEDA Version Requirements

This ScaledObject uses Kafka trigger features that were added across several KEDA releases. **Minimum required: 2.10.** Unknown metadata fields are **silently ignored** in older versions — no error, no warning, no log.

| Feature used in our config | Min KEDA Version | Behavior if KEDA is older |
|---------------------------|-----------------|--------------------------|
| `limitToPartitionsWithLag` | **2.8** | Silently ignored → uses `ceil(totalLag/lagThreshold)` → pods far exceed partition count |
| `excludePersistentLag` | **2.8** | Silently ignored → persistent lag always included (minor impact) |
| `activationLagThreshold` | **2.9** | Silently ignored → scales 0→1 on any lag (premature activation) |
| `allowIdleConsumers` | **2.10** | Silently ignored → idle consumers may be counted incorrectly |

**How to check your version:**
```bash
# Operator version
kubectl get deployment -n keda keda-operator -o jsonpath='{.spec.template.spec.containers[0].image}'

# CRD version (sometimes operator is updated but CRDs are stale)
kubectl get crd scaledobjects.keda.sh -o jsonpath='{.metadata.labels}'

# Verify KEDA sees the limitToPartitionsWithLag flag (2.8+ logs this at scaler creation)
kubectl logs -n keda deployment/keda-operator --tail=500 | grep -i "limitToPartitions"
```

**If your KEDA is < 2.10**, the `limitToPartitionsWithLag` flag is silently dropped and KEDA uses:

$$desired = \left\lceil \frac{totalLag}{lagThreshold} \right\rceil$$

For 100k lag with lagThreshold=500, that's 200 desired pods → capped at `maxReplicaCount` (48) → the ramp to 30 pods observed in the incident.

---

## Full ScaledObject Manifest (with inline explanations)

```yaml
# k8s-manifest.yaml (excerpt)
apiVersion: keda.sh/v1alpha1
kind: ScaledObject
metadata:
  name: payments-stream-scaler
  namespace: financial-streams
spec:
  scaleTargetRef:
    apiVersion: apps/v1
    kind: Deployment
    name: payments-stream         # Must match your Deployment name

  # WHAT: Minimum number of pods KEDA will maintain, even with zero lag.
  # WHY:  Never go below 2 for high availability. If one pod crashes,
  #        the other keeps processing while K8s replaces it.
  #        KEDA will never scale below this number.
  minReplicaCount: 2

  # WHAT: Time (seconds) KEDA waits after the last trigger activation before
  #        allowing scale-down to minReplicaCount.
  # WHY:  Prevents flapping during brief traffic pauses (e.g., between market batches).
  # COORDINATION: Must be <= session-timeout-ms (720000 = 12 min). cooldownPeriod (5 min)
  #        is well within the 12 min partition reservation window, so KEDA can scale down
  #        and back up before the broker gives away partitions.
  cooldownPeriod: 300             # seconds (5 min)

  # WHAT: How often (seconds) KEDA queries the Kafka broker for consumer group lag.
  # WHY:  15 sec = good balance between responsiveness and broker load.
  #        Lower = faster reaction but more AdminClient calls to broker.
  #        Don't go below 10 sec to avoid excessive broker load.
  pollingInterval: 15             # seconds

  advanced:
    horizontalPodAutoscalerConfig:
      behavior:
        # ── Scale-Up: fast but controlled ──
        scaleUp:
          # WHAT: After KEDA detects lag, K8s waits this many seconds before
          #        actually adding pods. Batches rapid signals into one decision.
          # WHY:  A burst of 1000 messages might be processed in 30 sec —
          #        don't spin up 10 pods for a blip. 60s lets you see if lag sustains.
          stabilizationWindowSeconds: 60
          policies:
            - type: Pods
              # WHAT: Maximum number of pods to ADD per periodSeconds window.
              # WHY:  Each new pod triggers a cooperative rebalance. Adding many
              #        at once = many rebalances in sequence. 2 is gentle.
              value: 2
              # WHAT: The evaluation window (seconds) for the above policy.
              #        KEDA evaluates once per this period and adds up to 'value' pods.
              periodSeconds: 60

        # ── Scale-Down: slow and cautious ──
        scaleDown:
          # WHAT: After lag drops to zero, K8s waits this many seconds of sustained
          #        zero-lag before starting scale-down.
          # WHY:  Market data comes in waves. A 3-min quiet period doesn't mean
          #        the day is over. 5 min avoids premature scale-down.
          stabilizationWindowSeconds: 300
          policies:
            - type: Pods
              # WHAT: Maximum number of pods to REMOVE per periodSeconds window.
              # WHY:  Removing too many at once causes a large rebalance.
              #        4 pods/5min = gradual, safe wind-down.
              value: 4
              # WHAT: The evaluation window (seconds) for the above policy.
              periodSeconds: 300
          # WHAT: When multiple policies exist, this decides which one wins.
          #        'Min' = pick the policy that removes the FEWEST pods.
          # WHY:  Conservative scale-down protects against sudden traffic spikes.
          selectPolicy: Min

  triggers:
    # ┌─────────────────────────────────────────────────────────────┐
    # │ Trigger 1: Main trade topic (48 partitions)                │
    # └─────────────────────────────────────────────────────────────┘
    - type: kafka
      metadata:
        # WHAT: Kafka broker addresses KEDA uses to query consumer group offsets.
        # NOTE: This is KEDA's own connection to the broker (AdminClient), completely
        #        separate from your app's bootstrap-servers. Can be the same addresses.
        bootstrapServers: kafka-broker-0.kafka-broker:9092,kafka-broker-1.kafka-broker:9092,kafka-broker-2.kafka-broker:9092

        # WHAT: The Kafka consumer group whose lag KEDA monitors.
        # MUST MATCH: app.stream.application-id in your application.properties.
        #        If these don't match, KEDA monitors the wrong group → no scaling.
        consumerGroup: payments-stream-v1

        # WHAT: The Kafka topic KEDA checks for unprocessed messages.
        # KEDA reads the difference between latest offset and committed offset per partition.
        topic: payments.input

        # WHAT: Per-partition lag threshold. A partition with more than this many
        #        unprocessed messages is considered "lagging" and counts toward scale-up.
        # WHY 500: With max.poll.records=250 and ~1s commit interval,
        #        500 = ~2 poll cycles behind. Low enough to react fast,
        #        high enough to ignore minor fluctuations.
        lagThreshold: "500"

        # WHAT: Minimum total lag required before KEDA activates scaling from
        #        idle state (0 → minReplicaCount, or minReplica → minReplica+1).
        # WHY 10: Prevents scaling on noise — a handful of messages shouldn't
        #        trigger pod creation. Only act when real traffic arrives.
        activationLagThreshold: "10"

        # WHAT: When true, desired replicas = count of partitions that have lag > lagThreshold.
        #        When false, desired replicas = total lag / lagThreshold (across all partitions).
        # WHY true: Naturally caps at partition count (48). No hardcoded maxReplicaCount needed.
        #        If 12 of 48 partitions have lag, KEDA requests 12 pods — not more.
        limitToPartitionsWithLag: "true"

        # WHAT: What offset to use if no committed offset exists for a partition
        #        (new consumer group or reset scenario).
        # 'latest' = treat all existing messages as already consumed → no false lag spike.
        # 'earliest' = treat all existing messages as unprocessed → may cause burst scale-up.
        offsetResetPolicy: "latest"

        # WHAT: When false, KEDA only counts partitions that have active consumers
        #        with lag. Idle (unassigned) partitions are excluded from the count.
        # WHY false: Only lagging partitions with assigned consumers drive scaling.
        #        Prevents ghost scaling from unassigned partitions.
        allowIdleConsumers: "false"

        # WHAT: When false, persistent lag (partitions where committed offset hasn't
        #        moved for a long time) IS included in lag calculations.
        # WHY false: If a partition is stuck, we WANT KEDA to account for it.
        #        Setting true would hide stuck partitions from scaling decisions.
        excludePersistentLag: "false"

    # ┌─────────────────────────────────────────────────────────────┐
    # │ Trigger 2: Exception topic (24 partitions)                │
    # └─────────────────────────────────────────────────────────────┘
    - type: kafka
      metadata:
        bootstrapServers: kafka-broker-0.kafka-broker:9092,kafka-broker-1.kafka-broker:9092,kafka-broker-2.kafka-broker:9092
        # Same consumer group — this deployment processes both topics
        consumerGroup: payments-stream-v1
        topic: payments.exception
        lagThreshold: "500"
        activationLagThreshold: "10"
        # Caps at 24 replicas for this trigger's "vote"
        limitToPartitionsWithLag: "true"
        offsetResetPolicy: "latest"
        allowIdleConsumers: "false"
        excludePersistentLag: "false"

    # ┌─────────────────────────────────────────────────────────────┐
    # │ Trigger 3: Cron floor during market hours                  │
    # └─────────────────────────────────────────────────────────────┘
    - type: cron
      metadata:
        # EMEA timezone — Swiss market hours (SIX Swiss Exchange)
        timezone: Europe/Zurich
        # Mon-Fri 8:00 AM — market open
        start: "0 8 * * 1-5"
        # Mon-Fri 4:30 PM — market close
        end: "30 16 * * 1-5"
        # Keep at least 4 pods during trading hours.
        # This is a FLOOR, not a ceiling. If lag-based triggers need more,
        # KEDA will scale higher. But never below 4 during market hours.
        desiredReplicas: "4"
```

---

## How KEDA Coordinates with Kafka Streams

### Static Membership (prevents rebalance storms)

When KEDA scales down, pods are terminated. Without static membership, every termination triggers a full consumer group rebalance (~30s downtime per rebalance). With static membership it avoids full rebalance.

Please note that this is not 100% full proof.
${HOSTNAME} = pod name (e.g., payments-stream-7b4f9-xk2m9). With Deployments, when KEDA scales down and back up, the new pod gets a different name (different random suffix). So the "same ID comes back" benefit only works if the exact same pod restarts (e.g., crash restart, not scale event).

The real benefit for KEDA scaling is the combination of `internal-leave-group-on-close: false` + `session-timeout-ms: 720000` (12 min): the terminated pod doesn't send LeaveGroup, and Kafka waits 12 minutes before reassigning its partitions. This 12-min window also covers the circuit breaker's max recovery delay (10 min), so partitions stay reserved on a pod whose stream is stopped by the breaker. If traffic returns within 12 min, KEDA scales back up and the new pods just get assigned the orphaned partitions in a single, clean rebalance — instead of a storm of rebalances from each pod leaving individually.

For true same-ID-comes-back behavior, you'd need a StatefulSet (where pod names are deterministic: payments-stream-0, payments-stream-1, etc.). But StatefulSet has its own tradeoffs (ordered rollout, no surge during updates), which is why Deployment + the 12-min session is best. 
Use below configuration for best result.


```yaml
# K8s Deployment env section:
env:
  - name: HOSTNAME
    valueFrom:
      fieldRef:
        fieldPath: metadata.name    # e.g., "payments-stream-7b4f9-xk2m9"

# application.yml:
app:
  stream:
    group-instance-id: ${HOSTNAME}              # = "payments-stream-7b4f9-xk2m9"
    internal-leave-group-on-close: false         # Don't send LeaveGroup on shutdown
    consumer:
      session-timeout-ms: 720000                 # 12 min — covers max circuit breaker delay (10m) + buffer
```

**Flow on scale-down:**
1. KEDA removes a pod
2. Pod shuts down but does NOT send LeaveGroup (because `internal-leave-group-on-close: false`)
3. Kafka coordinator waits `session-timeout-ms` (12 min) before reassigning partitions
4. If KEDA scales back up within 12 min and pod gets same `group.instance.id` → **no rebalance at all**
5. If 12 min expires → coordinator reassigns partitions to remaining pods (single rebalance)

### Cooperative Rebalancing

Kafka Streams 3.x uses cooperative rebalancing by default:
- Old behavior: ALL partitions revoked → reassigned (full stop-the-world)
- Cooperative: Only MOVED partitions are revoked. Everyone else keeps processing.

No config needed — it's the default in Kafka Streams 3.x.

---

## Scaling Timeline Example (Market Open Day)

```
07:55  KEDA cron trigger activates → scale to 4 pods (market floor)
08:00  Market opens → messages flow → lag builds on 12 partitions
08:01  KEDA detects lag > 500 on 12 partitions → desires 12 pods
08:02  stabilizationWindow (60s) passes → scale up 2 pods (4 → 6)
08:03  Scale up 2 more (6 → 8)
08:04  Scale up 2 more (8 → 10)
08:05  Scale up 2 more (10 → 12) → lag draining
08:30  Lag cleared on all partitions → KEDA desires minReplicaCount
08:35  stabilizationWindow (300s) starts counting
08:40  Still zero lag → scale down 4 (12 → 8)
08:41  Cron floor prevents going below 4 during market hours
...
16:30  Cron trigger deactivates → floor drops to minReplicaCount (2)
16:35  If no lag → scale down to 2 pods for overnight
```

---

## Key Numbers to Remember

| Parameter | Value | Coordinates With |
|-----------|-------|------------------|
| `pollingInterval` | 15 sec | How fast KEDA reacts to lag |
| `cooldownPeriod` | 300 sec (5 min) | Must be ≤ `session-timeout-ms` (720000 = 12 min) |
| `lagThreshold` | 500 | `max.poll.records` (250) — ~2 poll cycles |
| `minReplicaCount` | 2 | PDB `minAvailable: 2` |
| `maxReplicaCount` | 48 | **= largest topic partition count** (payments.input) |
| Scale-up rate | 2 pods/min | Gentle — fewer rebalances |
| Scale-down rate | 4 pods/5min | Conservative — ~1 hour from 48 → 2 |
| Market floor | 4 pods | Mon-Fri 08:00-16:30 Europe/Zurich |

---

## Multi-Trigger Behavior — Single Deployment, Two Kafka Streams Apps

### Architecture

This deployment runs **two separate Kafka Streams instances** in the same pod, each with its own `application.id` (= consumer group):

| Stream | application.id | Consumer Group | Topic | Partitions |
|--------|----------------|---------------|-------|------------|
| Stream 1 | payments-stream-v1 | payments-stream-v1 | payments.input | 48 |
| Stream 2 | payments-exception-v1 | payments-exception-v1 | payments.exception | 24 |

KEDA monitors each consumer group independently in separate triggers. Each trigger "votes" for a desired replica count. HPA picks the **MAX** (not SUM).

### How KEDA computes desired replicas with multiple triggers

KEDA creates **one HPA** per ScaledObject. Each trigger becomes an external metric. HPA takes the **MAX** across all triggers (not the SUM):

```
desired = min(maxReplicaCount, max(trigger_1, trigger_2, trigger_3))
```

**This means triggers never fight each other.** If one trigger wants 30 pods and another wants 2, HPA picks 30. The low-lag trigger cannot force scale-down. Scale-down only happens when ALL triggers agree.

### Scenario analysis

**Scenario A:** `payments.input` = 30 lagging partitions, `payments.exception` = 0 lag
```
Trigger 1 (input, group=payments-stream-v1):       desires 30
Trigger 2 (exception, group=payments-exception-v1): desires 0 → minReplica=2
HPA picks MAX:                                      30 pods
```
Result: 30 pods. Stream 1 uses all 30 for input. Stream 2 has 0 lag but still assigned across pods. All pods do input work.

**Scenario B:** `payments.input` = 0 lag, `payments.exception` = 24 lagging partitions
```
Trigger 1 (input, group=payments-stream-v1):       desires 0 → minReplica=2
Trigger 2 (exception, group=payments-exception-v1): desires 24
HPA picks MAX:                                      24 pods
```
Result: 24 pods. Stream 2 assigns exception partitions to all 24. Stream 1 assigns input partitions across 24 pods (no lag). Every pod runs both streams.

**Scenario C:** Both topics lag — `payments.input` = 40, `payments.exception` = 24
```
Trigger 1 (input, group=payments-stream-v1):       desires 40
Trigger 2 (exception, group=payments-exception-v1): desires 24
HPA picks MAX:                                      40 pods
```
Result: 40 pods. Stream 1 assigns 40 input partitions. Stream 2 assigns 24 exception partitions — 16 pods only run input work, but all do useful processing.

**Scale-down only happens when ALL triggers agree** (all return low/zero desired). No single trigger can pull pods away from another.

### The 30-pod incident — root cause investigation

> **Real incident:** With `payments.input` at 0 lag and `payments.exception` (24 partitions) at 100k lag, KEDA created 30 pods. Expected: 24.

With `limitToPartitionsWithLag=true` working, Trigger 2 should cap at 24 (the partition count). **30 > 24 proves the flag was NOT working.** KEDA fell back to the total-lag formula:

```
desired = ceil(totalLag / lagThreshold)
        = ceil(100,000 / 500)
        = 200  → capped by maxReplicaCount = 48
```

The scale-up policy is +2 pods/min, so the ramp was:
```
T+0min:  2 pods  (minReplica)
T+1min:  4 pods  (+2)
...
T+14min: 30 pods (+2)  ← observed here, still ramping toward 48
...
T+23min: 48 pods (maxReplicaCount cap)
```

Pods 25-48: no exception partition (only 24 exist) AND no input work (0 lag). Both Kafka Streams instances idle → pure waste + Pending state on AKS.

### Known causes for limitToPartitionsWithLag failure

| # | Cause | How to check |
|---|-------|--------------|
| 1 | **KEDA version < 2.8** — flag didn't exist | `kubectl get deployment -n keda keda-operator -o jsonpath='{.spec.template.spec.containers[0].image}'` |
| 2 | **Config drift** — deployed ScaledObject differs from git | `kubectl get scaledobject payments-stream-scaler -n financial-streams -o yaml \| grep limitToPartitionsWithLag` |
| 3 | **exactly_once_v2 transactional offsets** — Kafka Streams with EOS commits offsets via transactions. Some KEDA versions have issues reading transactional offsets via AdminClient, returning incomplete per-partition data → falls back to total-lag formula | Check KEDA operator logs for offset-related errors |
| 4 | **Consumer group in Empty/Dead state** — if all pods were at 0 or fresh deploy, the group may show `Empty` state. KEDA may not enumerate per-partition offsets for an empty group | `kafka-consumer-groups.sh --describe --group payments-exception-v1` — check State column |
| 5 | **Partition count mismatch** — KEDA caches topic metadata. If partitions were recently increased, KEDA may have stale count | Restart KEDA operator pod after partition changes |

### Diagnostic commands

```bash
# 1. KEDA version (must be >= 2.8 for limitToPartitionsWithLag)
kubectl get deployment -n keda keda-operator -o jsonpath='{.spec.template.spec.containers[0].image}'

# 2. Confirm flag is actually deployed
kubectl get scaledobject payments-stream-scaler -n financial-streams -o yaml \
  | grep -A2 limitToPartitionsWithLag

# 3. Consumer group states — look for Empty/Dead
kafka-consumer-groups.sh --bootstrap-server <broker> --describe --group payments-stream-v1
kafka-consumer-groups.sh --bootstrap-server <broker> --describe --group payments-exception-v1

# 4. KEDA operator logs — look for offset/partition errors
kubectl logs -n keda deployment/keda-operator --tail=200 \
  | grep -i "payments-exception\|partition\|offset\|error"

# 5. HPA external metrics — see raw numbers KEDA is reporting
kubectl get hpa -n financial-streams -o yaml | grep -A10 "currentMetrics"

# 6. Check for Pending pods (AKS node pool exhaustion)
kubectl get pods -n financial-streams -o wide | grep -v Running
```

---

## Files to Create

```
k8s-manifest.yaml    # ScaledObject section (shown above)
```

No Java code changes needed — KEDA operates on the K8s Deployment externally. The Kafka Streams app doesn't know about KEDA. It only sees pods joining/leaving the consumer group.

---

## Verification Checklist

- [ ] KEDA operator installed on AKS cluster
- [ ] `kubectl get scaledobject payments-stream-scaler -n financial-streams` shows READY
- [ ] `kubectl get hpa` shows KEDA-generated HPA with kafka triggers
- [ ] During market hours: pod count >= 4 (cron floor)
- [ ] Under load: pods scale up within ~2 min of sustained lag
- [ ] After load: pods scale down over ~5 min (conservative)
- [ ] No rebalance storms visible in Kafka Streams logs during scale events
