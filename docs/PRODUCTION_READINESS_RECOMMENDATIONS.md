# Production Readiness Recommendations

**Service:** `kafka-streams-cb` (Payments Stream)
**Stack:** Java 21, Spring Boot 3.5.9, Kafka Streams 3.9.x, PostgreSQL, Redis, AKS, Helm, Confluent Cloud
**Target Load:** 3M trades/day
**Date:** March 2026

> This document is a **recommendation-only** guide. No code or configuration changes are included.
> Items are prioritized into two sections: Day 1 config-only changes and Day 1 must-have implementation work, scoped to a 1-week timeline.

---

## Table of Contents

- [Section 1 — Configuration-Only (Day 1 Prod Proving)](#section-1--configuration-only-day-1-prod-proving)
  - [1.1 JVM Flags](#11-jvm-flags)
  - [1.2 HikariCP Connection Pool](#12-hikaricp-connection-pool)
  - [1.3 Redis Client Settings](#13-redis-client-settings)
  - [1.4 Kubernetes Security Context](#14-kubernetes-security-context)
  - [1.5 Kubernetes Network Policy](#15-kubernetes-network-policy)
  - [1.6 Secrets Management](#16-secrets-management)
  - [1.7 Spring Profiles — Prod Hardening](#17-spring-profiles--prod-hardening)
  - [1.8 Prometheus Scrape Endpoint](#18-prometheus-scrape-endpoint)
- [Section 2 — Must-Have Implementation (Day 1 Business Go-Live)](#section-2--must-have-implementation-day-1-business-go-live)
  - [2.1 Dead-Letter Queue (DLQ)](#21-dead-letter-queue-dlq)
  - [2.2 Idempotent Consumer](#22-idempotent-consumer)
  - [2.3 Structured Logging](#23-structured-logging)
  - [2.4 Grafana Alerts — Golden Signals](#24-grafana-alerts--golden-signals)
  - [2.5 Smoke Test Script](#25-smoke-test-script)
  - [2.6 Database Migration via CI/CD](#26-database-migration-via-cicd)
- [Section 3 — Post Go-Live / Sprint 2](#section-3--post-go-live--sprint-2)
- [Recommended 7-Day Timeline](#recommended-7-day-timeline)
- [What to Skip for Day 1](#what-to-skip-for-day-1)

---

## Section 1 — Configuration-Only (Day 1 Prod Proving)

These items require **zero code changes** — only ConfigMap, Helm values, or `application-prod.yml` updates.

---

### 1.1 JVM Flags

**Current state in `k8s-manifest.yaml` ConfigMap:**

```
JAVA_TOOL_OPTIONS: "-Xmx1G -Xms1G -XX:+UseG1GC -XX:MaxGCPauseMillis=200"
```

**Problem:**
- `-XX:+UseG1GC` is unnecessary — G1 is the default GC in Java 21.
- `-XX:MaxGCPauseMillis=200` is also the default — explicitly setting it adds no value and can confuse future tuners.
- Missing `-XX:+AlwaysPreTouch` — without it, first-touch page faults cause latency spikes during live traffic.
- Missing `-XX:+UseStringDeduplication` — Kafka + JSON workloads have many duplicate strings (keys, headers, codes). This saves ~10-15% heap on string-heavy workloads.

**Recommendation:**

```
JAVA_TOOL_OPTIONS: "-Xmx1G -Xms1G -XX:+UseStringDeduplication -XX:+AlwaysPreTouch"
```

**Why Xms = Xmx:**
Already correctly set. In Kubernetes with hard memory limits, dynamic heap growth (Xms < Xmx) causes sudden memory spikes and OOMKilled events. Fixed heap keeps usage predictable.

**Heap sizing rule:** ~70-75% of pod memory limit. Current: 1G heap / 2G limit = 50% — this is conservative. If memory pressure is observed, consider increasing to 1500m.

**Reference:** [Java21_AKS_JVM_Settings_Reference](Java21_AKS_JVM_Settings_Reference%20(2).txt), Sections 2.2 and 2.3.

---

### 1.2 HikariCP Connection Pool

**Current state:** No explicit HikariCP settings in `application.yml` or `application.properties`.

**Problem:** Spring Boot defaults (`maximumPoolSize=10`, no leak detection, no connection timeout tuning) may not be appropriate for a high-throughput financial service.

**Recommendation — add to `application-prod.yml`:**

```yaml
spring:
  datasource:
    hikari:
      maximum-pool-size: 10            # Match to PostgreSQL max_connections / number_of_pods
      minimum-idle: 10                  # Fixed pool size (minimum-idle = maximum-pool-size)
      connection-timeout: 5000          # 5 sec — fail fast rather than queue requests
      leak-detection-threshold: 30000   # 30 sec — log warning if connection not returned
      idle-timeout: 600000              # 10 min — close idle connections
      max-lifetime: 1800000             # 30 min — recycle connections before DB-side timeout
      validation-timeout: 3000          # 3 sec — connection validation timeout
```

**Sizing guide:**
- Total DB connections = `maximum-pool-size` x `number_of_pods`
- For 3-5 pods with pool size 10 = 30-50 connections. Confirm this is within PostgreSQL `max_connections`.
- Set `minimum-idle = maximum-pool-size` for a fixed pool (no dynamic resizing, same principle as JVM heap).

---

### 1.3 Redis Client Settings

**Current state:** No Redis configuration in `application.yml`.

**Problem:** When Redis is added, defaults may lack timeouts and TLS. Without explicit timeouts, a Redis outage can block threads indefinitely.

**Recommendation — add when Redis is integrated:**

```yaml
spring:
  data:
    redis:
      host: ${REDIS_HOST}
      port: 6380                        # Azure Redis TLS port
      password: ${REDIS_PASSWORD}       # From Key Vault
      ssl:
        enabled: true                   # Require TLS
      timeout: 3000                     # 3 sec command timeout
      lettuce:
        pool:
          max-active: 16                # Max concurrent connections
          max-idle: 8
          min-idle: 4
          max-wait: 2000                # 2 sec max wait when pool exhausted
        shutdown-timeout: 5000          # 5 sec graceful close
```

**Redis server-side recommendation:**
- Eviction policy: `allkeys-lru` (for cache-only use)
- `maxmemory`: set to 80% of Redis node memory
- Monitor: hit/miss ratio, evictions, memory usage, latency

---

### 1.4 Kubernetes Security Context

**Current state:** Security context is already well-configured in `k8s-manifest.yaml`:

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

**Assessment:** This is already production-grade. No changes needed.

**Optional pod-level addition (defense in depth):**

```yaml
spec:
  securityContext:
    runAsNonRoot: true
    seccompProfile:
      type: RuntimeDefault
```

Adding `seccompProfile: RuntimeDefault` at pod level enables the default Linux seccomp filter, blocking ~300+ unnecessary syscalls.

---

### 1.5 Kubernetes Network Policy

**Current state:** No NetworkPolicy defined in `k8s-manifest.yaml`.

**Problem:** Without a NetworkPolicy, any pod in the cluster can reach `payments-stream` on any port. This violates the principle of least privilege.

**Recommendation — add to `k8s-manifest.yaml` or Helm chart:**

```yaml
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: payments-stream-netpol
  namespace: financial-streams
spec:
  podSelector:
    matchLabels:
      app: payments-stream
  policyTypes:
    - Ingress
    - Egress
  ingress:
    # Allow health probes from kubelet (any pod in namespace)
    - ports:
        - port: 9090
          protocol: TCP
    # Allow business traffic from known services only
    - from:
        - podSelector:
            matchLabels:
              app: api-gateway        # Replace with actual upstream service label
      ports:
        - port: 8080
          protocol: TCP
    # Allow Prometheus scraping
    - from:
        - namespaceSelector:
            matchLabels:
              name: monitoring        # Replace with your monitoring namespace label
      ports:
        - port: 9090
          protocol: TCP
  egress:
    # Allow Kafka
    - to:
        - ipBlock:
            cidr: 0.0.0.0/0          # Replace with Confluent Cloud IP ranges
      ports:
        - port: 9092
          protocol: TCP
    # Allow PostgreSQL
    - to:
        - podSelector:
            matchLabels:
              app: postgresql         # Or use ipBlock for managed DB
      ports:
        - port: 5432
          protocol: TCP
    # Allow Redis
    - to:
        - podSelector:
            matchLabels:
              app: redis
      ports:
        - port: 6380
          protocol: TCP
    # Allow DNS
    - to:
        - namespaceSelector: {}
      ports:
        - port: 53
          protocol: UDP
        - port: 53
          protocol: TCP
```

**Note:** Replace placeholder labels and CIDRs with actual values from your environment.

---

### 1.6 Secrets Management

**Current state:** Kafka bootstrap servers are hardcoded in the ConfigMap. No Key Vault integration visible in the manifest.

**Recommendation:**

1. **Azure Key Vault CSI Driver** — mount secrets as volumes or inject as environment variables:
   - Kafka SASL credentials (API key + secret)
   - PostgreSQL password
   - Redis password
   - Any API keys

2. **Never store secrets in:**
   - ConfigMap (plaintext, visible via `kubectl get configmap -o yaml`)
   - Docker image layers
   - Git repository

3. **Implementation approach:**

```yaml
# In Deployment spec
volumes:
  - name: secrets
    csi:
      driver: secrets-store.csi.k8s.io
      readOnly: true
      volumeAttributes:
        secretProviderClass: payments-stream-secrets
```

```yaml
# SecretProviderClass
apiVersion: secrets-store.csi.x-k8s.io/v1
kind: SecretProviderClass
metadata:
  name: payments-stream-secrets
  namespace: financial-streams
spec:
  provider: azure
  parameters:
    keyvaultName: "your-keyvault-name"
    objects: |
      array:
        - |
          objectName: kafka-api-key
          objectType: secret
        - |
          objectName: kafka-api-secret
          objectType: secret
        - |
          objectName: postgres-password
          objectType: secret
        - |
          objectName: redis-password
          objectType: secret
    tenantId: "your-tenant-id"
  secretObjects:
    - secretName: payments-stream-secrets
      type: Opaque
      data:
        - objectName: kafka-api-key
          key: KAFKA_API_KEY
        - objectName: kafka-api-secret
          key: KAFKA_API_SECRET
        - objectName: postgres-password
          key: POSTGRES_PASSWORD
        - objectName: redis-password
          key: REDIS_PASSWORD
```

---

### 1.7 Spring Profiles — Prod Hardening

**Current state:** `SPRING_PROFILES_ACTIVE=prod` is set in the Deployment, but no `application-prod.yml` is visible in the project.

**Recommendation — create `application-prod.yml` with these overrides:**

| Property | Value | Reason |
|----------|-------|--------|
| `springdoc.api-docs.enabled` | `false` | Disable OpenAPI spec in prod |
| `springdoc.swagger-ui.enabled` | `false` | Disable Swagger UI in prod |
| `management.endpoint.health.show-details` | `never` | Don't expose component details externally |
| `management.endpoint.health.show-components` | `never` | Don't expose component names externally |
| `logging.level.root` | `WARN` | Reduce log noise |
| `logging.level.com.example.financialstream` | `INFO` | Keep app logs at INFO |

**Note on health details:** Currently `show-details: always` and `show-components: always`. This is useful in dev/staging but exposes internal infrastructure info in prod. Since management port 9090 is internal-only (not exposed via Ingress), the risk is low — but "never" is the defense-in-depth choice. If operational teams need health details for debugging, keep `always` but ensure port 9090 is never exposed externally.

---

### 1.8 Prometheus Scrape Endpoint

**Current state:** Actuator exposes `health,info,metrics,prometheus,runtime` on port 9090. However, the `micrometer-registry-prometheus` dependency is not in `pom.xml`.

**Problem:** Without the Prometheus registry, the `/actuator/prometheus` endpoint returns 404. Metrics are collected by Micrometer but not exposed in Prometheus format.

**Recommendation:**

1. Add to `pom.xml`:

```xml
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-registry-prometheus</artifactId>
</dependency>
```

2. Add Prometheus scrape annotations to the pod template in `k8s-manifest.yaml`:

```yaml
template:
  metadata:
    annotations:
      prometheus.io/scrape: "true"
      prometheus.io/port: "9090"
      prometheus.io/path: "/actuator/prometheus"
```

3. Verify after deployment: `curl http://localhost:9090/actuator/prometheus` should return Prometheus text format metrics.

**Note:** This is listed in Section 1 because it's a dependency + annotation change, not business logic code.

---

## Section 2 — Must-Have Implementation (Day 1 Business Go-Live)

These items require **code changes** and are essential for a financial processing system.

---

### 2.1 Dead-Letter Queue (DLQ)

**Current state:** No DLQ topic or error handler configured. Poison messages that fail all retries will block the stream or be silently dropped (depending on the deserialization exception handler behavior).

**Why this is Day 1:** In a financial system processing 3M trades/day, even 0.01% poison messages = 300 messages/day that need investigation. Without a DLQ, these are lost.

**Recommendation:**

1. **Create a DLQ topic** in Confluent Cloud:
   - Topic name: `payments.input.dlq`
   - Partitions: same as `payments.input`
   - Retention: 7 days (or per compliance requirements)

2. **Implement an error handler** that:
   - Catches messages that fail processing after retries
   - Publishes the original message + error metadata (exception class, message, timestamp, original topic/partition/offset) to the DLQ
   - Logs the DLQ publication at WARN level

3. **DLQ message format** should include:
   - Original message key and value (raw bytes)
   - Original topic, partition, offset
   - Exception class and message
   - Timestamp of failure
   - Pod name (from `POD_NAME` env var)

4. **Operational process:**
   - Set up a Grafana alert on DLQ topic lag > 0
   - Document a runbook for investigating and replaying DLQ messages
   - Consider a simple DLQ viewer UI or CLI tool

---

### 2.2 Idempotent Consumer

**Current state:** `exactly_once_v2` is configured for Kafka Streams, which handles produce-consume cycles atomically. However, if the service writes to PostgreSQL, the DB write is **outside** the Kafka transaction boundary.

**Why this is Day 1:** Exactly-once in Kafka does not extend to external systems. If the pod crashes after writing to PostgreSQL but before committing the Kafka offset, the message will be reprocessed and the DB row may be duplicated.

**Recommendation:**

1. **For each trade/payment written to PostgreSQL:**
   - Store a unique message identifier (e.g., Kafka `topic-partition-offset` or a business key like `tradeId`)
   - Use a `UNIQUE` constraint or `INSERT ... ON CONFLICT DO NOTHING` to prevent duplicates
   - Example: `processed_messages` table with columns `(message_id VARCHAR PRIMARY KEY, processed_at TIMESTAMP)`

2. **Processing flow:**
   ```
   receive message -> check if message_id exists in DB -> if yes, skip -> if no, process + insert message_id
   ```

3. **Cleanup:** Schedule a daily job to delete entries older than 7 days from `processed_messages` (retention aligned with Kafka topic retention).

4. **Alternative — outbox pattern:**
   - Write the Kafka produce and DB update in a single DB transaction
   - A separate publisher reads the outbox table and sends to Kafka
   - More complex but provides stronger consistency guarantees

**Recommendation:** Start with the deduplication table approach (simpler), upgrade to outbox pattern post go-live if needed.

---

### 2.3 Structured Logging

**Current state:** No explicit logging configuration for production. Default Spring Boot logging is plaintext.

**Why this is Day 1:** Without structured (JSON) logging, log aggregation (ELK/Loki) parsing is fragile and searching by traceId or correlationId is impossible.

**Recommendation:**

1. **Add Logback JSON encoder** — dependency:

```xml
<dependency>
    <groupId>ch.qos.logback.contrib</groupId>
    <artifactId>logback-jackson</artifactId>
    <version>0.1.5</version>
</dependency>
<dependency>
    <groupId>ch.qos.logback.contrib</groupId>
    <artifactId>logback-json-classic</artifactId>
    <version>0.1.5</version>
</dependency>
```

Or use Spring Boot 3.4+ built-in structured logging:

```yaml
# application-prod.yml
logging:
  structured:
    format:
      console: ecs    # Elastic Common Schema format
```

2. **Minimum fields per log line:**
   - `timestamp` (ISO 8601)
   - `level` (INFO, WARN, ERROR)
   - `logger` (class name)
   - `message`
   - `traceId` / `spanId` (if OpenTelemetry is configured)
   - `pod_name` (from env var)
   - `thread`

3. **Sensitive data:** Ensure PII (account numbers, names) is never logged. Review all `log.info()` / `log.warn()` / `log.error()` statements.

---

### 2.4 Grafana Alerts — Golden Signals

**Current state:** Prometheus metrics are planned (Section 1.8) but no alert rules are defined.

**Why this is Day 1:** Without alerts, the team discovers production issues from user complaints, not monitoring. For a financial system, this is unacceptable.

**Recommendation — define these 6 essential Prometheus alert rules:**

| Alert | PromQL (example) | Threshold | Severity |
|-------|-------------------|-----------|----------|
| **High Error Rate** | `rate(http_server_requests_seconds_count{status=~"5.."}[5m]) / rate(http_server_requests_seconds_count[5m]) > 0.01` | > 1% errors for 5 min | Critical |
| **High p95 Latency** | `histogram_quantile(0.95, rate(http_server_requests_seconds_bucket[5m])) > 2` | > 2 sec p95 for 5 min | Warning |
| **Kafka Consumer Lag** | `kafka_consumer_group_lag > 10000` | > 10K messages for 10 min | Critical |
| **Circuit Breaker Open** | `circuit_breaker_state == 1` (open) | Any pod for 5 min | Critical |
| **Pod Restarts** | `increase(kube_pod_container_status_restarts_total{container="payments-stream"}[1h]) > 3` | > 3 restarts/hour | Critical |
| **DLQ Messages** | `kafka_consumer_group_lag{topic="payments.input.dlq"} > 0` | Any messages | Warning |

**Notification channels:** PagerDuty or Teams webhook for Critical, email for Warning.

---

### 2.5 Smoke Test Script

**Current state:** No automated post-deployment validation.

**Why this is Day 1:** After every deployment, someone must manually verify the service is working. This is error-prone and slow. A 30-second smoke test catches 80% of deployment failures.

**Recommendation — create a shell script that runs post-Helm-install:**

```bash
#!/bin/bash
# smoke-test.sh — run after deployment
set -e

BASE_URL="http://payments-stream.financial-streams.svc.cluster.local"
MGMT_URL="http://payments-stream.financial-streams.svc.cluster.local:9090"

# 1. Health check
echo "Checking health..."
STATUS=$(curl -s -o /dev/null -w "%{http_code}" "$MGMT_URL/actuator/health/readiness")
[ "$STATUS" -eq 200 ] || { echo "FAIL: readiness probe returned $STATUS"; exit 1; }

# 2. Liveness check
echo "Checking liveness..."
STATUS=$(curl -s -o /dev/null -w "%{http_code}" "$MGMT_URL/actuator/health/liveness")
[ "$STATUS" -eq 200 ] || { echo "FAIL: liveness probe returned $STATUS"; exit 1; }

# 3. Prometheus endpoint
echo "Checking metrics..."
STATUS=$(curl -s -o /dev/null -w "%{http_code}" "$MGMT_URL/actuator/prometheus")
[ "$STATUS" -eq 200 ] || { echo "FAIL: prometheus endpoint returned $STATUS"; exit 1; }

# 4. Runtime discovery (verify config loaded)
echo "Checking runtime config..."
RUNTIME=$(curl -s "$MGMT_URL/actuator/runtime")
echo "$RUNTIME" | grep -q "kafka-streams-cb" || { echo "FAIL: app name not in runtime"; exit 1; }

echo "ALL SMOKE TESTS PASSED"
```

**Integration:** Add this as a post-install Helm hook or a CI/CD pipeline step after `helm upgrade`.

---

### 2.6 Database Migration via CI/CD

**Current state:** No Flyway or Liquibase migration setup visible.

**Why this is Day 1:** Running schema migrations on application startup in prod is dangerous — a failed migration can block all pods from starting (all pods retry the same migration simultaneously).

**Recommendation:**

1. **Add Flyway** dependency to `pom.xml`:

```xml
<dependency>
    <groupId>org.flywaydb</groupId>
    <artifactId>flyway-core</artifactId>
</dependency>
<dependency>
    <groupId>org.flywaydb</groupId>
    <artifactId>flyway-database-postgresql</artifactId>
</dependency>
```

2. **Disable auto-migration in prod:**

```yaml
# application-prod.yml
spring:
  flyway:
    enabled: false    # Migrations run in CI/CD, not on app startup
```

3. **Run migrations in CI/CD** — a dedicated pipeline step before `helm upgrade`:

```bash
# CI/CD step (before deployment)
flyway -url=jdbc:postgresql://$DB_HOST:5432/$DB_NAME \
       -user=$DB_USER -password=$DB_PASSWORD \
       migrate
```

4. **Migration naming convention:** `V001__create_trades_table.sql`, `V002__add_status_column.sql`

5. **Dev/staging:** Keep `spring.flyway.enabled=true` for convenience. Only disable in prod.

---

## Section 3 — Post Go-Live / Sprint 2

These items are important but can wait until after initial go-live:

| Item | Why It Can Wait | Effort |
|------|-----------------|--------|
| **Istio mTLS (STRICT)** | Internal traffic is already in a private VNet. mTLS adds defense-in-depth but is not a blocking requirement. | 2-3 days |
| **Chaos testing** (kill pods, network partitions) | Circuit breaker + graceful shutdown already implemented. Chaos testing validates but doesn't add new capability. | 2 days |
| **Read replicas** for PostgreSQL | Not needed unless read traffic is high. 3M trades/day is primarily write-heavy. | 1 day config |
| **OpenTelemetry tracing** | Very valuable but not a Day 1 blocker. Logging + metrics cover 80% of observability. | 2-3 days |
| **SBOM generation + image signing** | Security best practice but does not affect runtime behavior. | 1 day CI/CD |
| **Load testing** (k6 / JMeter) | Should be done in staging, but don't block go-live for it. | 2-3 days |
| **PII audit + column encryption** | Important for compliance. Schedule within Sprint 2 if handling PII data. | 3-5 days |
| **Feature flags** (LaunchDarkly / ConfigCat) | Useful for gradual rollout. Not needed if deploying circuit breaker to all traffic. | 2 days |

---

## Recommended 7-Day Timeline

| Day | Focus | Items |
|-----|-------|-------|
| **Day 1** | Config hardening | JVM flags (1.1), HikariCP (1.2), Spring prod profile (1.7), Prometheus dep (1.8) |
| **Day 2** | K8s + secrets | Network Policy (1.5), Key Vault CSI (1.6), Prometheus annotations (1.8), Redis config (1.3) |
| **Day 3** | DLQ + dedup | DLQ topic + handler (2.1), idempotent consumer table (2.2) |
| **Day 4** | Observability | Structured logging (2.3), Grafana alert rules (2.4) |
| **Day 5** | Automation | Smoke test script (2.5), Flyway CI/CD (2.6) |
| **Day 6** | Staging deploy | Full deployment to staging with all changes, run smoke tests, monitor for 4+ hours |
| **Day 7** | Canary go-live | Deploy to prod with 1 pod (canary), monitor golden signals for 2+ hours, then scale up |

---

## What to Skip for Day 1

The following items from the Production Checklist are **intentionally excluded** from the Day 1 scope. They are valid best practices but would extend the timeline beyond 1 week without adding proportional risk reduction:

| Skipped Item | Reason |
|--------------|--------|
| Istio service mesh | VNet provides network isolation. Add mTLS in Sprint 2. |
| ZGC tuning | G1 with defaults is correct for 1-2 GB pods (per JVM reference doc). |
| OWASP ZAP scan | Important but non-blocking. Schedule in Sprint 2. |
| React UI hardening | Out of scope — this document covers the backend service only. |
| Backup/restore drill | Critical but a team exercise, not a config change. Schedule separately. |
| Velero for PVC backup | Service is stateless (emptyDir for task metadata). No PVCs to back up. |
| SAST/SCA pipeline | DevSecOps maturity item. Add to CI/CD in Sprint 2. |
| Advanced GC tuning | Java 21 defaults are well-calibrated. Only tune after GC log analysis in prod. |
| Manual `VACUUM FULL` scheduling | PostgreSQL autovacuum handles this. Only intervene after observing table bloat. |
| Blue/green deployments | RollingUpdate with `maxUnavailable: 0` already provides zero-downtime deploys. |

---

## Appendix: Current State Assessment

A quick summary of what is **already production-ready** in the current codebase:

| Area | Status | Details |
|------|--------|---------|
| Health probes | Done | Liveness, readiness, startup probes on port 9090 |
| Graceful shutdown | Done | `terminationGracePeriodSeconds: 120`, `preStop: sleep 90` |
| Circuit breaker | Done | Resilience4j with 20% threshold, 30 min window, exponential backoff |
| Security context | Done | `runAsNonRoot`, `readOnlyRootFilesystem`, drop ALL capabilities |
| Pod anti-affinity | Done | Spread across nodes via `preferredDuringSchedulingIgnoredDuringExecution` |
| PDB | Done | `minAvailable: 2` |
| KEDA autoscaling | Done | Kafka lag-based scaling with cooldown and stabilization |
| Static group membership | Done | `group.instance.id = ${HOSTNAME}`, prevents rebalance storms |
| Separate management port | Done | Port 9090 isolated from business API on 8080 |
| Exactly-once semantics | Done | `exactly_once_v2`, idempotent producer, `acks=all` |
| Transactional producer | Done | UUID-based transactional ID prefix |
| Compression | Done | LZ4 for throughput |
| Configuration validation | Done | `BreakerConfigurationValidator` for fail-fast startup |
| Runtime discovery | Done | `/actuator/runtime` with credential sanitization |
