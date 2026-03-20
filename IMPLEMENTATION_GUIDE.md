# Kafka Streams Circuit Breaker - Production Implementation Guide

**Date:** March 2026  
**Status:** ✅ PRODUCTION-READY  
**Risk Level:** 🟢 LOW

---

## Table of Contents

1. [Quick Start](#quick-start)
2. [Architecture Overview](#architecture-overview)
3. [Production Deployment](#production-deployment)
4. [Operational Procedures](#operational-procedures)
5. [Troubleshooting](#troubleshooting)
6. [Monitoring & Alerts](#monitoring--alerts)
7. [Configuration Reference](#configuration-reference)

---

## Quick Start

### What This Does

Processes **3,000,000 trades/day** (104 msg/sec) with automatic circuit breaker:
- ✅ **Prevents message draining** - stops stream instead of buffering
- ✅ **Captures all failures** - deserialization, business logic, infrastructure
- ✅ **Zero duplicates** - idempotent producer safety
- ✅ **Auto-recovers** - configurable recovery backoff (1m → 10m → 20m)
- ✅ **Production-grade** - metrics, health checks, graceful shutdown

### Deployment

```bash
# Prerequisites
- Kubernetes (AKS)
- Kafka cluster (≥3 brokers)
- Database for audit logs
- Prometheus for monitoring

# Build and deploy
mvn clean package
kubectl apply -f deployment.yaml -n financial-streams

# Verify
kubectl logs -f deploy/payments-stream -n financial-streams
curl localhost:8080/actuator/health
```

---

## Architecture Overview

### Core Components

```
Kafka Input Topic
    ↓
[Deserialization] → Audit log malformed JSON
    ↓
[Circuit Breaker Gate] → Check if OK to process
    ├─ YES → [Business Logic] → [Idempotent Producer] → Output
    ├─ SOFT FAIL → [Audit log] → Continue, count toward breaker
    └─ HARD FAIL → [Fatal Handler] → Pod restart
```

### Exception Classification (3-Tier)

| Type | Handler | Action | Breaker Impact |
|------|---------|--------|---|
| **Deserialization** | `CustomDeserializationExceptionHandler` | CONTINUE | ❌ None (separate metric) |
| **Soft Business** | Log to DB, return SUCCESS_WITH_EXCEPTION_LOGGED | CONTINUE | ✅ Counted toward threshold |
| **Hard Infrastructure** | Uncaught exception → `SHUTDOWN_CLIENT` | Pod restart | ❌ None (fail-fast) |

### Circuit Breaker Configuration

```yaml
Type: TIME_BASED (sliding 30-minute window)
Failure Threshold: 20% soft failures
Minimum Calls: 100 (avoid false positives)
Recovery Strategy:
  ├─ 1st open: wait 1 minute
  ├─ 2nd open: wait 10 minutes
  └─ 3rd+ open: wait 20 minutes (stays)

Half-Open Testing: 20 messages max before decision
```

### Why Stop() Not Pause()

```
pause()  → Consumer keeps fetching → Buffers fill → 5GB RAM in 10min → OOM ❌
stop()   → Consumer offline → No buffering → Safe degradation ✅
```

---

## Production Deployment

### Pre-Deployment Checklist

#### Code & Configuration
- [ ] Load test passed at 2× expected rate
- [ ] Soft failure baseline measured
- [ ] Business processor latency validated (P50/P95/P99)
- [ ] `state-dir` configured on persistent volume
- [ ] Producer idempotence enabled: `enable-idempotence: true`
- [ ] All application.yml values reviewed

#### Infrastructure
- [ ] Kafka: 3+ brokers, RF=3
- [ ] Input partitions: ≥24 (for 6 pods × 4 threads)
- [ ] Output topic exists
- [ ] Persistent volume mounted at `/var/lib/kafka-streams/payments`
- [ ] Database connection pool: ≥(pods × threads × batch_size)

#### Kubernetes (AKS)
- [ ] Namespace created
- [ ] ServiceAccount & RBAC configured
- [ ] ConfigMap with `application.yml`
- [ ] Secret with DB credentials
- [ ] PVC for state directory (10GB)
- [ ] HPA configured (min=3, max=12, based on lag)

#### Monitoring
- [ ] Prometheus scrape targets configured
- [ ] Grafana dashboard created
- [ ] AlertManager rules defined
- [ ] Runbooks documented

### Deployment Steps (Zero-Downtime)

#### Phase 1: Canary (1 Pod, Shadow Traffic)

```bash
# Deploy shadow instance (receives copy of real traffic, no output)
kubectl apply -f deployment-canary.yaml

# Monitor for 1 hour minimum:
# - Check logs for errors
# - Verify soft failure rate matches baseline ±5%
# - Confirm circuit breaker remains CLOSED
# - Validate database audit tables growing normally

watch kubectl logs -f deploy/payments-stream-canary
```

#### Phase 2: Staged Production Rollout

```bash
# Scale gradually
kubectl scale deploy payments-stream --replicas=1  # Wait 60s
kubectl scale deploy payments-stream --replicas=3  # Wait 90s
kubectl scale deploy payments-stream --replicas=6  # Wait 90s

# Verify at each stage
kubectl get pods -n financial-streams
# All pods should be Running, Ready=1/1
```

#### Phase 3: Stabilization (2+ Hours)

```bash
# Monitor key metrics
# - Consumer lag < 10k messages
# - Soft failure rate within baseline ±5%
# - Zero unexpected pod restarts
# - Circuit breaker closed and stable
```

### Successful Deployment Indicators

✅ **Immediate (first hour)**
- 6/6 pods Running, Ready=1/1
- Consumer lag stabilized (< 10k messages)
- Circuit breaker CLOSED
- Output messages flowing to destination

✅ **Day 1**
- Soft failure rate stable
- All nodes healthy
- Dashboard metrics updating
- No alert storms

✅ **Week 1**
- Sustained performance under normal load
- Consistent soft failure ratio
- Database audit table growing predictably
- Zero hard failures (pod restarts)

---

## Operational Procedures

### Scenario 1: Circuit Breaker Opens

**Symptoms:**
- Alert: "Circuit Breaker Opened"
- Logs show: "🔴 BREAKER OPEN"
- Consumer lag grows (but doesn't spike)
- Soft failure rate > 20%

**Root Cause Analysis (5 min):**

```bash
# Check recent soft failures
kubectl logs -f deploy/payments-stream | grep "SOFT_FAILURE"

# Query audit database
SELECT error_code, COUNT(*) as cnt 
FROM soft_failures 
WHERE created_at > NOW() - INTERVAL 30 MINUTE 
GROUP BY error_code 
ORDER BY cnt DESC;

# Check dependency health (e.g., enrichment service)
kubectl get pods -n enrichment-service
curl https://enrichment-api/health
```

**Response:**

| Time | Action | Owner |
|------|--------|-------|
| Now | Page SRE | Ops |
| +2m | Investigate root cause | SRE |
| +5m | If dependency down: escalate; breaker auto-recovers in 1m | SRE |
| +10m | If data quality issue: investigate source | Data Eng |
| +20m | If code bug: review, rollback | Dev Lead |

**Auto-Recovery**
- OPEN → wait 1 minute → HALF_OPEN → test 20 messages
- If < 20% fail: CLOSED (resume)
- If ≥ 20% fail: OPEN again (wait 10 minutes next time)

---

### Scenario 2: High Consumer Lag

**Symptoms:**
- Alert: "Consumer Lag > 100k"
- Lag growing continuously
- Circuit breaker CLOSED (processing running)
- Pod CPU/memory normal

**Diagnosis:**

```bash
# Current lag
kafka-consumer-groups --bootstrap-server BROKER:9092 \
  --group payments-stream-v1 --describe

# Processing latency
kubectl logs deploy/payments-stream | grep "processing_duration"
# Or: histogram_quantile(0.95, processing_duration_seconds_bucket)

# Pod resources
kubectl top pods -n financial-streams
# CPU: should be 30-60%; if >80%: CPU-bound
```

**Fixes:**

| Issue | Solution | Time |
|-------|----------|------|
| Processing latency high | Optimize slow DB query; check index | 15 min |
| CPU maxed | Scale up: `kubectl scale deploy payments-stream --replicas=9` | 2 min |
| Dependency slow | Contact dependency team | 10-20 min |
| Network latency | Check Kafka broker health | 10 min |

---

### Scenario 3: Pod Restarts (Hard Failures)

**Symptoms:**
- Alert: "Pod Restarting Frequently"
- Logs: "❌ FATAL EXCEPTION"
- Pod restart count growing

**This is a CODE BUG - Immediate Action:**

```bash
# 1. ROLLBACK immediately
kubectl rollout undo deployment/payments-stream

# 2. Debug last logs
kubectl logs deploy/payments-stream -n financial-streams --previous

# 3. Check error
# Look for: NullPointerException, OutOfMemoryError, SQLException, etc.
```

---

## Monitoring & Alerts

### Key Metrics

**Circuit Breaker:**
```promql
# Current state (0=CLOSED, 1=OPEN, 2=HALF_OPEN)
circuit_breaker_current_state{breaker="payments-business-soft-failure"}

# When opened
circuit_breaker_open_count_total{breaker="payments-business-soft-failure"}

# Next restart delay
circuit_breaker_next_restart_delay_seconds{breaker="payments-business-soft-failure"}
```

**Consumer:**
```promql
# Lag trend
kafka_consumer_lag_sum{group="payments-stream-v1"}

# Processing rate
rate(kafka_consumer_records_consumed_total[5m])
```

**Error Rates:**
```promql
# Soft failures
(rate(soft_failures_total[5m]) / rate(messages_processed_total[5m])) * 100

# Hard failures (should be near-zero)
rate(container_last_seen{pod=~"payments-stream-.*"}[5m])
```

### Alert Rules

```yaml
# CRITICAL alerts (page on-call)
- name: CircuitBreakerOpen
  expr: circuit_breaker_current_state == 1
  for: 5m
  action: page

- name: PodRestartingFrequently
  expr: rate(container_last_seen{pod=~"payments-stream.*"}[5m]) > 0.1
  for: 2m
  action: page

- name: ConsumerLagCritical
  expr: kafka_consumer_lag_sum > 500000
  for: 10m
  action: page

# WARNING alerts (slack notify)
- name: CircuitBreakerRecovering
  expr: circuit_breaker_current_state == 2
  for: 2m
  action: slack

- name: SoftFailureRateHigh
  expr: soft_failure_rate_percent > 15
  for: 5m
  action: slack
```

### Grafana Dashboard Panels

**Panel 1: Breaker State**
```promql
circuit_breaker_current_state{breaker="payments-business-soft-failure"}
# Legend: 0=CLOSED (green), 1=OPEN (red), 2=HALF_OPEN (yellow)
```

**Panel 2: Soft Failure Rate**
```promql
(rate(soft_failures_total[5m]) / rate(messages_processed_total[5m])) * 100
# Threshold line at 20%
```

**Panel 3: Consumer Lag**
```promql
kafka_consumer_lag_sum{group="payments-stream-v1"}
# Threshold line at 100k
```

**Panel 4: Pod CPU/Memory**
```promql
rate(container_cpu_usage_seconds_total{pod=~"payments-stream.*"}[5m])
container_memory_usage_bytes{pod=~"payments-stream.*"} / 1024 / 1024
```

---

## Troubleshooting

### Issue: Breaker Opens Immediately on Startup

**Cause:** Soft failure rate already high before breaker armed

**Fix:**
```yaml
# Increase minimum calls to wait longer before evaluating
app.circuit-breaker.minimum-number-of-calls: 200
```

### Issue: Breaker Never Closes

**Cause:** Persistent issue preventing recovery

**Check:**
1. Are soft failures still high? (> 20%)
2. Is dependency still down?
3. Is database connectivity okay?

**Escalate:** Requires manual investigation + dependency fix

### Issue: Lag Keeps Growing

**Cause:** Not enough processing capacity

**Fix:**
```bash
# Option 1: Scale up
kubectl scale deploy payments-stream --replicas=9

# Option 2: Add partitions to input topic
kafka-topics --bootstrap-server BROKER:9092 --alter --topic payments.input --partitions 48
```

### Issue: Pod OOM-Killed

**Cause:** Memory limit too low OR breaker not stopping stream

**Fix:**
```yaml
# Increase memory limit
resources:
  requests:
    memory: "2Gi"
  limits:
    memory: "4Gi"

# Verify breaker working
kubectl logs deploy/payments-stream | grep "BREAKER"
```

---

## Configuration Reference

### Spring Application Properties

```yaml
spring:
  application:
    name: kafka-streams-cb
  kafka:
    bootstrap-servers: kafka-broker:9092
    producer:
      acks: all  # Wait for all replicas
      enable-idempotence: true  # ✅ CRITICAL for financial
      max-in-flight-requests-per-connection: 5  # Required with idempotence
      retries: 2147483647  # Never give up on output

app:
  input:
    topic: payments.input
  output:
    topic: payments.output
  stream:
    application-id: payments-stream-v1
    processing-guarantee: at_least_once  # Proven, safe
    num-stream-threads: 4  # 6 pods × 4 = 24 concurrent slots > 21 needed
    commit-interval-ms: 1000
    state-dir: /var/lib/kafka-streams/payments  # Must be persistent!
    consumer:
      max-poll-records: 250  # 250 × 14KB = 3.5MB per batch
      max-poll-interval-ms: 600000  # 10 minutes - long batches okay
      session-timeout-ms: 45000
      heartbeat-interval-ms: 3000
    producer:
      acks: all
  circuit-breaker:
    failure-rate-threshold: 20  # % soft failures before open
    time-window-seconds: 1800  # 30 minutes evaluation window
    minimum-number-of-calls: 100  # Need 100+ calls to evaluate
    permitted-calls-in-half-open-state: 20  # Max 20 test calls
    max-wait-in-half-open-state: 2m
    restart-delays:
      - 1m  # 1st recovery: wait 1 minute
      - 10m  # 2nd recovery: wait 10 minutes
      - 20m  # 3rd+ recovery: wait 20 minutes (stays)
    scheduler-delay-ms: 5000  # Check recovery status every 5 sec
```

### Tuning Guide

**Increase failure threshold if business naturally has 15%+ soft failures:**
```yaml
app.circuit-breaker.failure-rate-threshold: 25
```

**Decrease window if need faster detection:**
```yaml
app.circuit-breaker.time-window-seconds: 600  # 10 min instead of 30
```

**Increase minimum calls if getting false positives:**
```yaml
app.circuit-breaker.minimum-number-of-calls: 200
```

---

## Kubernetes Deployment Example

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  namespace: financial-streams
  name: payments-stream
spec:
  replicas: 6
  selector:
    matchLabels:
      app: payments-stream
  template:
    metadata:
      labels:
        app: payments-stream
    spec:
      serviceAccount: payments-stream
      terminationGracePeriodSeconds: 70

      containers:
      - name: payments-stream
        image: your-registry/kafka-streams-cb:v1.0.0
        imagePullPolicy: Always
        
        ports:
        - containerPort: 8080
          name: metrics
        
        env:
        - name: SPRING_KAFKA_BOOTSTRAP_SERVERS
          value: "kafka-broker:9092"
        - name: APP_CIRCUIT_BREAKER_SCHEDULER_DELAY_MS
          value: "5000"
        
        envFrom:
        - configMapRef:
            name: payments-stream-config
        
        resources:
          requests:
            cpu: 500m
            memory: 1Gi
          limits:
            cpu: 2000m
            memory: 4Gi
        
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
            path: /actuator/health
            port: 8080
          initialDelaySeconds: 30
          periodSeconds: 5
          failureThreshold: 2
        
        lifecycle:
          preStop:
            exec:
              command: ["/bin/sh", "-c", "sleep 60"]
        
        volumeMounts:
        - name: state-dir
          mountPath: /var/lib/kafka-streams/payments
      
      volumes:
      - name: state-dir
        persistentVolumeClaim:
          claimName: payments-stream-state

  strategy:
    type: RollingUpdate
    rollingUpdate:
      maxSurge: 1
      maxUnavailable: 0
---
apiVersion: v1
kind: PersistentVolumeClaim
metadata:
  namespace: financial-streams
  name: payments-stream-state
spec:
  accessModes:
    - ReadWriteOnce
  storageClassName: fast-ssd
  resources:
    requests:
      storage: 10Gi
---
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  namespace: financial-streams
  name: payments-stream
spec:
  scaleTargetRef:
    apiVersion: apps/v1
    kind: Deployment
    name: payments-stream
  minReplicas: 3
  maxReplicas: 12
  metrics:
  - type: Resource
    resource:
      name: cpu
      target:
        type: Utilization
        averageUtilization: 70
```

---

## Maintenance

### Weekly
- [ ] Check pod restarts (any anomalies?)
- [ ] Verify soft failure rate within baseline
- [ ] Monitor database audit table growth

### Monthly
- [ ] Review error codes (new patterns?)
- [ ] Update runbooks with lessons learned
- [ ] Validate capacity (on track for growth?)

### Quarterly
- [ ] Load test at 2× expected rate
- [ ] Review and update SLAs
- [ ] Plan next version upgrade

---

## Rollback Procedure (Emergency)

```bash
# Immediate rollback (< 2 minutes)
kubectl rollout undo deployment/payments-stream -n financial-streams

# Verify
kubectl rollout status deployment/payments-stream -n financial-streams

# Monitor for 30 minutes
watch kubectl logs -f deploy/payments-stream
watch kubectl top pods
```

---

## Support & Escalation

**For architecture questions:** See [ARCHITECTURE_REVIEW.md](ARCHITECTURE_REVIEW.md)

**For code issues:** Check logs with:
```bash
kubectl logs -f deploy/payments-stream --tail=100 | grep -i error
```

**For production incidents:**
1. Check circuit breaker state
2. Review last 100 lines of logs
3. Query audit database for soft failures
4. Contact on-call via escalation list

---

**Document Version:** 1.0  
**Last Updated:** March 2026  
**Audience:** DevOps, SRE, Platform Engineers
