# Adoption Checklist — Quick Config Validation

Use this checklist when adopting the template into a new project.  
Each section lists the **required** properties and K8s settings. Verify nothing is missing.

---

## 1. Server & Spring Boot

```properties
server.port=8080
server.servlet.context-path=/api/<your-service>
spring.application.name=<your-service-name>
spring.profiles.active=dev          # prod in K8s
```

---

## 2. Kafka Streams (Core)

```properties
spring.kafka.bootstrap-servers=<broker-host>:9092
app.stream.application-id=<your-consumer-group>          # MUST match KEDA consumerGroup
app.input.topic=<input-topic>
app.output.topic=<output-topic>
app.stream.processing-guarantee=exactly_once_v2
app.stream.num-stream-threads=1                          # scale via pods, not threads
app.stream.state-dir=/tmp/kafka-streams
app.stream.replication-factor=3
```

### Consumer — Session & Heartbeat (KEDA-critical)

```properties
app.stream.consumer.session-timeout-ms=720000            # 12 min — covers max CB delay + buffer
app.stream.consumer.heartbeat-interval-ms=10000          # < session-timeout / 3
app.stream.consumer.max-poll-interval-ms=720000          # >= session-timeout
app.stream.group-instance-id=${HOSTNAME:local-dev-instance}
app.stream.internal-leave-group-on-close=false
```

### Consumer — Fetch & Batching

```properties
app.stream.consumer.auto-offset-reset=latest
app.stream.consumer.max-poll-records=250
app.stream.consumer.fetch-max-bytes=52428800
app.stream.consumer.max-partition-fetch-bytes=1048576
```

### Producer (Streams internal)

```properties
app.stream.producer.acks=all
app.stream.producer.linger-ms=5
app.stream.producer.batch-size=65536
app.stream.producer.buffer-memory=67108864
```

### Security (Confluent Cloud / SASL)

> Skip if using plain broker without auth.

```properties
app.stream.security.protocol=SASL_SSL
app.stream.sasl.mechanism=PLAIN
app.stream.sasl.jaas.config=org.apache.kafka.common.security.plain.PlainLoginModule required username="<KEY>" password="<SECRET>";
```

---

## 3. Kafka Producer (KafkaTemplate — REST endpoints)

```properties
spring.kafka.producer.acks=all
spring.kafka.producer.enable-idempotence=true
spring.kafka.producer.linger-ms=5
spring.kafka.producer.batch-size=65536
spring.kafka.producer.buffer-memory=67108864
spring.kafka.producer.compression-type=snappy
spring.kafka.producer.delivery-timeout-ms=120000
spring.kafka.producer.transactional-id-prefix=<your-service>-${random.uuid}-
```

---

## 4. Circuit Breaker

```properties
app.circuit-breaker.failure-rate-threshold=20
app.circuit-breaker.time-window-seconds=1800             # 30 min window
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

> **Constraint:** max single delay (10m) < `session-timeout-ms` (12m).

---

## 5. Management Port & Actuator

```properties
management.server.port=9090
management.endpoints.web.exposure.include=health,info,metrics,prometheus,runtime,monitoring
management.endpoint.health.probes.enabled=true
management.endpoint.health.show-details=always
management.endpoint.health.show-components=always
management.health.livenessState.enabled=true
management.health.readinessState.enabled=true
management.health.kafka.enabled=true
management.endpoint.runtime.cache.time-to-live=5s
management.metrics.distribution.percentiles.pipeline.stage.duration=0.5,0.95,0.99
management.metrics.distribution.percentiles-histogram.pipeline.stage.duration=true
```

---

## 6. Monitoring Dashboard

```properties
app.monitoring.enabled=true
app.monitoring.k8s-enabled=false                         # true in prod
app.monitoring.refresh-interval-ms=5000
app.monitoring.sse-heartbeat-ms=5000
app.monitoring.k8s-api-timeout-ms=3000
app.monitoring.namespace=${POD_NAMESPACE:financial-streams}
app.monitoring.deployment-name=<your-deployment>
app.monitoring.pod-label-selector=app=<your-app-label>
app.monitoring.kafka-consumer-groups[0]=<your-consumer-group>
app.monitoring.lag-threshold-warning=100
app.monitoring.lag-threshold-critical=500
```

---

## 7. Swagger / OpenAPI

```yaml
# application-dev.yml
springdoc.api-docs.enabled: true
springdoc.swagger-ui.enabled: true

# application-prod.yml
springdoc.api-docs.enabled: false
springdoc.swagger-ui.enabled: false
```

---

## 8. K8s Health Probes

All probes hit **management port (9090)**. Verify these are in your Deployment/Helm:

| Probe | Path | initialDelay | period | timeout | failureThreshold |
|-------|------|-------------|--------|---------|-----------------|
| **Startup** | `/actuator/health/liveness` | 10s | 5s | 3s | 20 |
| **Liveness** | `/actuator/health/liveness` | 60s | 10s | 5s | 3 |
| **Readiness** | `/actuator/health/readiness` | 30s | 5s | 3s | 2 |

> Port name or number must reference `9090`, not `8080`.

---

## 9. KEDA ScaledObject

| Field | Required Value |
|-------|---------------|
| `minReplicaCount` | `2` (HA minimum) |
| `maxReplicaCount` | `>=` partition count |
| `cooldownPeriod` | `300` (5 min) |
| `pollingInterval` | `15` |
| `triggers[].metadata.consumerGroup` | **Must match `app.stream.application-id`** |
| `triggers[].metadata.topic` | Must match `app.input.topic` |
| `triggers[].metadata.lagThreshold` | `500` |
| `triggers[].metadata.limitToPartitionsWithLag` | `true` |
| `triggers[].metadata.excludePersistentLag` | `true` |
| `triggers[].metadata.activationLagThreshold` | `30` |
| `triggers[].metadata.allowIdleConsumers` | `true` |
| ScaleUp stabilizationWindow | `60s` |
| ScaleUp max pods per window | `2` |
| ScaleDown stabilizationWindow | `300s` |
| ScaleDown max pods per window | `4` |

---

## 10. JVM Memory Tuning

Set via `JAVA_TOOL_OPTIONS` env var (ConfigMap):

```
-Xms1G -Xmx1G -XX:+UseG1GC -XX:MaxGCPauseMillis=200
-XX:MaxMetaspaceSize=256m -XX:MaxDirectMemorySize=256m
-XX:+ExitOnOutOfMemoryError -Xlog:gc*:stdout:time,uptime,level,tags
```

### Memory Budget (2Gi pod limit)

| Component | Size |
|-----------|------|
| Heap (Xms=Xmx) | 1024 MB (50%) |
| Metaspace | 256 MB |
| Direct Memory | 256 MB |
| Thread Stacks (~50) | 50 MB |
| JVM Internal | 100 MB |
| OS / Safety | ~362 MB |

### K8s Resources

```yaml
resources:
  requests: { cpu: 1000m, memory: 1.5Gi }
  limits:   { cpu: 2000m, memory: 2Gi }
```

> **Rule:** Heap (`-Xmx`) ≤ 50% of memory limit.

---

## 11. K8s Deployment Settings

```yaml
terminationGracePeriodSeconds: 120
lifecycle:
  preStop:
    exec:
      command: ["sh", "-c", "sleep 90"]
strategy:
  type: RollingUpdate
  rollingUpdate:
    maxSurge: 2
    maxUnavailable: 0
```

### Security Context

```yaml
securityContext:
  runAsNonRoot: true
  runAsUser: 1000
  readOnlyRootFilesystem: true
  allowPrivilegeEscalation: false
  capabilities:
    drop: [ALL]
```

### Required Volumes

| Mount | Type | Purpose |
|-------|------|---------|
| `/tmp/kafka-streams` | emptyDir | Streams state (stateless topology) |

---

## 12. Downward API Env Vars

These must be in the pod spec for runtime discovery & monitoring:

```yaml
- name: HOSTNAME
  valueFrom: { fieldRef: { fieldPath: metadata.name } }
- name: POD_NAME
  valueFrom: { fieldRef: { fieldPath: metadata.name } }
- name: POD_NAMESPACE
  valueFrom: { fieldRef: { fieldPath: metadata.namespace } }
- name: NODE_NAME
  valueFrom: { fieldRef: { fieldPath: spec.nodeName } }
- name: POD_IP
  valueFrom: { fieldRef: { fieldPath: status.podIP } }
- name: K8S_ENABLED
  value: "true"
- name: SPRING_PROFILES_ACTIVE
  value: "prod"
```

---

## 13. RBAC (if `k8s-enabled=true`)

ServiceAccount needs read access to:

| Resource | API Group | Verbs |
|----------|-----------|-------|
| `pods` | `""` | get, list, watch |
| `deployments` | `apps` | get, list |
| `horizontalpodautoscalers` | `autoscaling` | get, list |
| `scaledobjects` | `keda.sh` | get, list |

---

## 14. Required Maven Dependencies

Verify these are in `pom.xml`:

| Dependency | Purpose |
|-----------|---------|
| `spring-boot-starter-web` | REST APIs |
| `spring-boot-starter-actuator` | Health probes, metrics |
| `spring-boot-starter-validation` | Bean validation |
| `spring-kafka` | Kafka client |
| `kafka-streams` | Stream processing |
| `resilience4j-circuitbreaker` (2.2.0) | Circuit breaker |
| `resilience4j-micrometer` (2.2.0) | CB metrics export |
| `micrometer-registry-prometheus` | Prometheus scrape |
| `springdoc-openapi-starter-webmvc-ui` (2.8.4) | Swagger UI |
| `client-java-spring-integration` (21.0.2) | K8s API client |

---

## Quick Cross-Check Rules

| Rule | Why it matters |
|------|---------------|
| `app.stream.application-id` == KEDA `consumerGroup` | KEDA measures lag for wrong group → no scaling |
| `app.input.topic` == KEDA trigger `topic` | KEDA watches wrong topic → no scaling |
| max CB delay (10m) < `session-timeout-ms` (12m) | Pod keeps partitions during breaker pause |
| `heartbeat-interval-ms` < `session-timeout-ms / 3` | Kafka protocol requirement |
| `max-poll-interval-ms` >= `session-timeout-ms` | Prevents consumer kick during breaker |
| KEDA `maxReplicaCount` >= partition count | Ensures each partition can get a pod |
| KEDA `cooldownPeriod` < `session-timeout-ms` | Prevents stale consumer holding partitions |
| Heap (`-Xmx`) ≤ 50% of K8s memory limit | Leaves room for metaspace, direct memory, OS |
| Probes use port `9090`, not `8080` | Management port is separate from business API |
| `preStop` sleep (90s) < `terminationGracePeriodSeconds` (120s) | Pod gets time to drain after sleep |
| Swagger **disabled** in prod profile | Security — no API docs exposed in production |
